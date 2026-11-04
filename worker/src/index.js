/**
 * Cloudflare Worker for Re:Focus Android Application
 * 
 * Endpoints:
 * - POST /api/ensure-subscription (Trial Initialization - 7-Day Server Trial)
 * - POST /api/create-subscription (Razorpay Recurring Subscriptions API - ₹79/month autopay)
 * - POST /api/verify-subscription (Server Verification of Razorpay Recurring Subscription Mandate)
 * - POST /api/cancel-subscription (Authoritative End-of-Cycle Subscription Cancellation)
 * - POST /api/razorpay-webhook (Authoritative Webhook & Lifecycle Reconciliation)
 * - POST /api/account/request-deletion (Start 7-Day Soft-Delete Grace Period)
 * - POST /api/account/cancel-deletion (Restore Account if within Grace Period)
 * - GET  /api/account/status (Authoritative Account Status & Lazy Purge)
 * - GET  /health (Service Healthcheck)
 * 
 * Security Capabilities:
 * - Multi-tier Rate Limiting (Bounded in-memory sliding window per-UID & per-IP, with Retry-After)
 * - Firebase App Check verification (Play Integrity / Debug attestation with configurable mode: disabled, audit, enforced)
 * - Edge RS256 JWT ID token verification via Google JWKS
 * - Strict CORS allowlist with Vary: Origin (No wildcard *)
 * - Comprehensive Security Response Headers (nosniff, HSTS, Referrer-Policy, X-Frame-Options)
 * - Sanitized Production Error Responses (Zero internal secret leakages)
 * - Razorpay Webhook HMAC-SHA256 signature verification & exact-once idempotency
 * - Server-Authoritative Account Deletion lifecycle (ACTIVE, PENDING_DELETION, DELETED)
 * - Permanent purge of Firestore user data and Identity Toolkit user records
 */

// ---------------------------------------------------------------------------
// CONFIGURATION & ALLOWED ORIGINS
// ---------------------------------------------------------------------------

const ALLOWED_ORIGINS = [
  "https://refocus-app.firebaseapp.com",
  "https://refocus-504119.web.app"
];

// Cache for Google JWKS public certificates
let jwksCache = null;
let jwksCacheTime = 0;
const JWKS_CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour

// Cache for Firebase App Check JWKS
let appCheckJwksCache = null;
let appCheckJwksCacheTime = 0;

// Cache for Google OAuth2 Access Token (Service Account)
let serviceAccountTokenCache = null;
let serviceAccountTokenExpiry = 0;

// Backup 24-Hour Restriction Window (Server-Authoritative)
const BACKUP_COOLDOWN_MS = 24 * 60 * 60 * 1000; // 24 hours in milliseconds

// Authoritative Razorpay Plan Pricing & Currency Constants (Monthly Autopay: ₹79/month)
const EXPECTED_PLAN_AMOUNT_PAISE = 7900;
const EXPECTED_PLAN_CURRENCY = "INR";

// ---------------------------------------------------------------------------
// 1. SECURITY HEADERS & CORS HELPERS
// ---------------------------------------------------------------------------

const GLOBAL_SECURITY_HEADERS = {
  "X-Content-Type-Options": "nosniff",
  "Strict-Transport-Security": "max-age=31536000; includeSubDomains; preload",
  "Referrer-Policy": "no-referrer",
  "X-Frame-Options": "DENY",
  "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'; sandbox"
};

function getCorsHeaders(request) {
  const origin = request.headers.get("Origin");
  const headers = { ...GLOBAL_SECURITY_HEADERS };

  if (origin && ALLOWED_ORIGINS.includes(origin)) {
    headers["Access-Control-Allow-Origin"] = origin;
    headers["Access-Control-Allow-Methods"] = "POST, GET, OPTIONS";
    headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization, X-Razorpay-Signature, X-Firebase-AppCheck, X-Client-Timestamp";
    headers["Access-Control-Max-Age"] = "86400";
    headers["Vary"] = "Origin";
  }

  return headers;
}

function jsonResponse(data, status = 200, request = null, additionalHeaders = {}) {
  const corsAndSecurity = request ? getCorsHeaders(request) : { ...GLOBAL_SECURITY_HEADERS };
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...corsAndSecurity,
      ...additionalHeaders
    }
  });
}

function base64UrlDecode(str) {
  let base64 = str.replace(/-/g, "+").replace(/_/g, "/");
  while (base64.length % 4) {
    base64 += "=";
  }
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function base64UrlEncode(buffer) {
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// ---------------------------------------------------------------------------
// 2. RATE LIMITER (BOUNDED SLIDING-WINDOW IN-MEMORY ENGINE)
// ---------------------------------------------------------------------------

const rateLimitStore = new Map();
const MAX_RATE_LIMIT_KEYS = 10000;

async function checkRateLimit(key, maxRequests, windowMs, options = {}, env = null) {
  const record = typeof options === "boolean" ? options : (options.record !== undefined ? options.record : true);
  const failClosed = typeof options === "object" && options.failClosed !== undefined ? options.failClosed : true;
  const workerEnv = env || (typeof options === "object" ? options.env : null);

  const requireShared = workerEnv?.REQUIRE_SHARED_RATE_LIMITER === "true";
  const hasSharedLimiter = Boolean(workerEnv?.RATE_LIMITER && typeof workerEnv.RATE_LIMITER.limit === "function");

  // 1. Strict shared enforcement: If shared rate limiting is required by policy, fail closed when missing
  if (failClosed && requireShared && !hasSharedLimiter) {
    console.error("Shared rate limiter required but binding is unavailable in environment");
    return {
      allowed: false,
      unavailable: true,
      retryAfterSec: 60
    };
  }

  // 2. Cloudflare Native RateLimiter Binding (Atomic / Shared across edge if provisioned)
  if (hasSharedLimiter && record) {
    try {
      const bindingResult = await workerEnv.RATE_LIMITER.limit({ key });
      if (bindingResult && bindingResult.success === false) {
        return {
          allowed: false,
          unavailable: false,
          retryAfterSec: 60
        };
      }
    } catch (bindingErr) {
      console.error("Rate limiter binding error:", bindingErr?.name || "Error");
      if (failClosed) {
        return {
          allowed: false,
          unavailable: true,
          retryAfterSec: 60
        };
      }
    }
  }

  // 2. Sliding-Window In-Memory Limiter Engine (Per-Isolate)
  try {
    const now = Date.now();
    let entry = rateLimitStore.get(key);

    if (rateLimitStore.size >= MAX_RATE_LIMIT_KEYS) {
      for (const [k, storedEntry] of rateLimitStore.entries()) {
        const storedWindowMs = storedEntry.windowMs || windowMs;
        const lastTimestamp = storedEntry.timestamps[storedEntry.timestamps.length - 1];
        if (lastTimestamp === undefined || lastTimestamp <= now - storedWindowMs) {
          rateLimitStore.delete(k);
        }
      }

      entry = rateLimitStore.get(key);
      if (record && !entry && rateLimitStore.size >= MAX_RATE_LIMIT_KEYS) {
        let earliestExpiryMs = now + windowMs;
        for (const storedEntry of rateLimitStore.values()) {
          const lastTimestamp = storedEntry.timestamps[storedEntry.timestamps.length - 1];
          if (lastTimestamp !== undefined) {
            earliestExpiryMs = Math.min(earliestExpiryMs, lastTimestamp + (storedEntry.windowMs || windowMs));
          }
        }
        return {
          allowed: false,
          unavailable: false,
          retryAfterSec: Math.max(1, Math.ceil((earliestExpiryMs - now) / 1000))
        };
      }
    }

    if (!entry) entry = { timestamps: [], windowMs };
    entry.windowMs = Math.max(entry.windowMs || 0, windowMs);
    const windowStart = now - windowMs;
    let timestamps = entry.timestamps.filter(ts => ts > windowStart);

    if (timestamps.length >= maxRequests) {
      const earliestTimestamp = timestamps[0];
      const retryAfterSec = Math.max(1, Math.ceil((earliestTimestamp + windowMs - now) / 1000));
      rateLimitStore.delete(key);
      entry.timestamps = timestamps;
      rateLimitStore.set(key, entry);
      return {
        allowed: false,
        unavailable: false,
        retryAfterSec: retryAfterSec
      };
    }

    if (record) {
      timestamps.push(now);
      rateLimitStore.delete(key);
      entry.timestamps = timestamps;
      rateLimitStore.set(key, entry);
    } else if (timestamps.length > 0) {
      rateLimitStore.delete(key);
      entry.timestamps = timestamps;
      rateLimitStore.set(key, entry);
    }

    return {
      allowed: true,
      unavailable: false,
      retryAfterSec: 0
    };
  } catch (err) {
    console.error("Rate limiter storage error:", err?.name || "Error");
    if (failClosed) {
      return {
        allowed: false,
        unavailable: true,
        retryAfterSec: 60
      };
    }
    return {
      allowed: true,
      unavailable: false,
      retryAfterSec: 0
    };
  }
}

function getClientIp(request) {
  // Trust CF-Connecting-IP provided directly by Cloudflare edge runtime.
  // In Cloudflare Workers, CF-Connecting-IP is guaranteed to be set by Cloudflare.
  // In local test environments without Cloudflare edge proxy, allow fallback.
  return request.headers.get("CF-Connecting-IP") ||
         (request.headers.get("X-Forwarded-For") ? request.headers.get("X-Forwarded-For").split(",")[0].trim() : "127.0.0.1");
}

// ---------------------------------------------------------------------------
// 3. FIREBASE ID TOKEN & APP CHECK TOKEN VERIFICATION
// ---------------------------------------------------------------------------

async function getGooglePublicKeys() {
  const now = Date.now();
  if (jwksCache && now - jwksCacheTime < JWKS_CACHE_TTL_MS) {
    return jwksCache;
  }

  const res = await fetch("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com");
  if (!res.ok) {
    throw new Error(`Failed to fetch Google JWKS: ${res.status}`);
  }

  const data = await res.json();
  jwksCache = data.keys;
  jwksCacheTime = now;
  return jwksCache;
}

function decodeJwtPayload(b64url) {
  let base64 = b64url.replace(/-/g, "+").replace(/_/g, "/");
  while (base64.length % 4) {
    base64 += "=";
  }
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return JSON.parse(new TextDecoder().decode(bytes));
}

async function verifyFirebaseIdToken(token, projectId) {
  if (!token || typeof token !== "string") {
    throw new Error("Unauthorized: Missing or invalid token");
  }

  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new Error("Unauthorized: Malformed JWT token");
  }

  const header = decodeJwtPayload(parts[0]);
  const payload = decodeJwtPayload(parts[1]);

  if (header.alg !== "RS256") {
    throw new Error(`Unauthorized: Unsupported JWT algorithm: ${header.alg}`);
  }

  const keys = await getGooglePublicKeys();
  const keyObj = keys.find(k => k.kid === header.kid);
  if (!keyObj) {
    throw new Error("Unauthorized: Signing key not found in Google JWKS");
  }

  const cryptoKey = await crypto.subtle.importKey(
    "jwk",
    keyObj,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"]
  );

  const signedData = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const signature = base64UrlDecode(parts[2]);

  const isValid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    signature,
    signedData
  );

  if (!isValid) {
    throw new Error("Unauthorized: Invalid token signature");
  }

  const nowSec = Math.floor(Date.now() / 1000);
  if (payload.exp && payload.exp < nowSec) {
    throw new Error("Unauthorized: Firebase ID token has expired");
  }

  const expectedIssuer = `https://securetoken.google.com/${projectId}`;
  if (payload.iss !== expectedIssuer) {
    throw new Error(`Unauthorized: Invalid token issuer: expected ${expectedIssuer}, got ${payload.iss}`);
  }

  if (payload.aud !== projectId) {
    throw new Error(`Unauthorized: Invalid token audience: expected ${projectId}, got ${payload.aud}`);
  }

  const uid = payload.user_id || payload.sub || payload.uid;
  if (!uid || typeof uid !== "string") {
    throw new Error("Unauthorized: Invalid token subject (UID)");
  }

  payload.uid = uid;
  return payload;
}

async function authenticateRequest(request, env) {
  const authHeader = request.headers.get("Authorization");
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    throw new Error("Unauthorized: Missing or invalid Bearer token");
  }
  const token = authHeader.split("Bearer ")[1].trim();
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  return await verifyFirebaseIdToken(token, projectId);
}

async function getFirebaseAppCheckKeys() {
  const now = Date.now();
  if (appCheckJwksCache && now - appCheckJwksCacheTime < JWKS_CACHE_TTL_MS) {
    return appCheckJwksCache;
  }

  const res = await fetch("https://firebaseappcheck.googleapis.com/v1/jwks");
  if (!res.ok) {
    throw new Error(`Failed to fetch Firebase App Check JWKS: ${res.status}`);
  }

  const data = await res.json();
  appCheckJwksCache = data.keys;
  appCheckJwksCacheTime = now;
  return appCheckJwksCache;
}

async function verifyFirebaseAppCheckToken(appCheckToken, projectNumber, projectId) {
  if (!appCheckToken || typeof appCheckToken !== "string") {
    return false;
  }

  if (!projectNumber || typeof projectNumber !== "string") {
    return false;
  }

  const parts = appCheckToken.split(".");
  if (parts.length !== 3) return false;

  try {
    const header = decodeJwtPayload(parts[0]);
    const payload = decodeJwtPayload(parts[1]);

    if (header.alg !== "RS256") return false;

    const keys = await getFirebaseAppCheckKeys();
    const keyObj = keys.find(k => k.kid === header.kid);
    if (!keyObj) return false;

    const cryptoKey = await crypto.subtle.importKey(
      "jwk",
      keyObj,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"]
    );

    const signedData = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
    const signature = base64UrlDecode(parts[2]);

    const isValid = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      cryptoKey,
      signature,
      signedData
    );

    if (!isValid) return false;

    const nowSec = Math.floor(Date.now() / 1000);
    if (payload.exp && payload.exp < nowSec) return false;

    const expectedIssuer = `https://firebaseappcheck.googleapis.com/${projectNumber}`;
    if (payload.iss !== expectedIssuer) {
      return false;
    }

    const validAudiences = [
      `projects/${projectNumber}`,
      projectId ? `projects/${projectId}` : null
    ].filter(Boolean);

    const audList = Array.isArray(payload.aud) ? payload.aud : (payload.aud ? [payload.aud] : []);
    const hasValidAud = audList.some(a => validAudiences.includes(a));
    const isSubValid = payload.sub ? validAudiences.includes(payload.sub) : true;

    if (!hasValidAud && !isSubValid) {
      return false;
    }

    return true;
  } catch (err) {
    return false;
  }
}

async function validateAppCheck(request, env, options = {}) {
  const mode = options.mode || env.APP_CHECK_ENFORCEMENT_MODE || "audit";
  if (mode === "disabled") return true;

  const appCheckToken = request.headers.get("X-Firebase-AppCheck");
  const projectNumber = env.FIREBASE_PROJECT_NUMBER;
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";

  if (!projectNumber) {
    if (mode === "enforced") {
      throw new Error("AppCheckUnauthorized: Server configuration missing FIREBASE_PROJECT_NUMBER");
    }
    console.warn("[AppCheck Audit] Server configuration missing FIREBASE_PROJECT_NUMBER; skipping token verification");
    return true;
  }

  const isValid = await verifyFirebaseAppCheckToken(appCheckToken, projectNumber, projectId);

  if (mode === "enforced") {
    if (!isValid) {
      throw new Error("AppCheckUnauthorized: Invalid or missing App Check token");
    }
    return true;
  }

  if (!isValid && appCheckToken) {
    console.warn(`[AppCheck Audit] Token present but verification failed for endpoint: ${new URL(request.url).pathname}`);
  }
  return true;
}

// ---------------------------------------------------------------------------
// 4. GOOGLE SERVICE ACCOUNT & FIRESTORE / IDENTITY TOOLKIT REST API
// ---------------------------------------------------------------------------

function pemToArrayBuffer(pem) {
  const b64 = pem
    .replace(/-----BEGIN[ A-Z0-9_-]+-----/g, "")
    .replace(/-----END[ A-Z0-9_-]+-----/g, "")
    .replace(/[\r\n\s]/g, "");
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

async function getGoogleServiceAccountAccessToken(serviceAccountJson) {
  const nowSec = Math.floor(Date.now() / 1000);
  if (serviceAccountTokenCache && serviceAccountTokenExpiry > nowSec + 60) {
    return serviceAccountTokenCache;
  }

  const sa = typeof serviceAccountJson === "string" ? JSON.parse(serviceAccountJson) : serviceAccountJson;
  const privateKeyPem = sa.private_key;
  const clientEmail = sa.client_email;

  const jwtHeader = { alg: "RS256", typ: "JWT" };
  const jwtClaimSet = {
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/identitytoolkit https://www.googleapis.com/auth/firebase",
    aud: "https://oauth2.googleapis.com/token",
    exp: nowSec + 3600,
    iat: nowSec
  };

  const encHeader = base64UrlEncode(new TextEncoder().encode(JSON.stringify(jwtHeader)));
  const encClaims = base64UrlEncode(new TextEncoder().encode(JSON.stringify(jwtClaimSet)));
  const unsignedAssertion = `${encHeader}.${encClaims}`;

  const privateKeyBuffer = pemToArrayBuffer(privateKeyPem);
  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    privateKeyBuffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    new TextEncoder().encode(unsignedAssertion)
  );

  const signedAssertion = `${unsignedAssertion}.${base64UrlEncode(signature)}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: signedAssertion
    })
  });

  if (!res.ok) {
    const errorText = await res.text();
    throw new Error(`Failed to obtain Google OAuth access token: ${errorText}`);
  }

  const tokenData = await res.json();
  serviceAccountTokenCache = tokenData.access_token;
  serviceAccountTokenExpiry = nowSec + (tokenData.expires_in || 3600);
  return serviceAccountTokenCache;
}

// Read subscription document from Firestore REST API
async function getFirestoreSubscription(uid, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { exists: false, error: true, status: 500, message: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${uid}/subscription/status`;
    const res = await fetch(docUrl, {
      method: "GET",
      headers: { "Authorization": `Bearer ${accessToken}` }
    });

    if (res.status === 200) {
      const doc = await res.json();
      const fields = doc.fields || {};
      return { ...fields, exists: true, error: false, fields, updateTime: doc.updateTime };
    }

    if (res.status === 404) {
      return { exists: false, error: false };
    }

    console.warn(`Firestore GET subscription status returned ${res.status}`);
    return { exists: false, error: true, status: res.status };
  } catch (err) {
    console.error("Error reading Firestore subscription status:", err?.name || "Error");
    return { exists: false, error: true, status: 503, errorMsg: err?.name || "Network error" };
  }
}

// Write subscription document to Firestore REST API (using PATCH for merging)
async function updateFirestoreSubscription(uid, fields, env, expectedUpdateTime = null) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY secret not configured in Cloudflare Worker.");
  }

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);

  const fieldNames = Object.keys(fields);
  const updateMaskParams = fieldNames.map(f => `updateMask.fieldPaths=${f}`).join("&");

  let docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${uid}/subscription/status?${updateMaskParams}`;
  if (expectedUpdateTime) {
    docUrl += `&currentDocument.updateTime=${encodeURIComponent(expectedUpdateTime)}`;
  }

  const res = await fetch(docUrl, {
    method: "PATCH",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ fields })
  });

  if (!res.ok) {
    const errorBody = await res.text();
    const error = new Error(`Firestore REST PATCH failed (${res.status}): ${errorBody}`);
    error.status = res.status;
    throw error;
  }

  return await res.json();
}

// Atomically creates trial subscription document in Firestore REST API only if it does not already exist
async function createTrialSubscriptionAtomic(uid, fields, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY secret not configured in Cloudflare Worker.");
  }

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const fieldNames = Object.keys(fields);
  const updateMaskParams = fieldNames.map(f => `updateMask.fieldPaths=${f}`).join("&");

  // Precondition: currentDocument.exists=false enforces atomic creation preventing race conditions
  const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${uid}/subscription/status?${updateMaskParams}&currentDocument.exists=false`;

  const res = await fetch(docUrl, {
    method: "PATCH",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ fields })
  });

  if (res.status === 409 || res.status === 412) {
    // Document already exists (race lost or existing subscription)
    return { created: false, reason: "ALREADY_EXISTS" };
  }

  if (!res.ok) {
    const errorBody = await res.text();
    throw new Error(`Firestore REST PATCH failed (${res.status}): ${errorBody}`);
  }

  return { created: true, doc: await res.json() };
}

// Permanent server-side trial registry to prevent multi-trial abuse via account recreate/purge
async function getTrialRegistry(uid, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { exists: false, error: true, status: 500, message: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/trial_registry/${uid}`;
    const res = await fetch(docUrl, {
      method: "GET",
      headers: { "Authorization": `Bearer ${accessToken}` }
    });

    if (res.status === 200) {
      const doc = await res.json();
      const fields = doc.fields || {};
      return { ...fields, exists: true, error: false, fields };
    }

    if (res.status === 404) {
      return { exists: false, error: false };
    }

    console.warn(`Firestore GET trial_registry returned ${res.status}`);
    return { exists: false, error: true, status: res.status };
  } catch (err) {
    console.error("Error reading trial_registry:", err?.name || "Error");
    return { exists: false, error: true, status: 503, errorMsg: err?.name || "Network error" };
  }
}

async function recordTrialRegistry(uid, trialEndIso, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return;

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/trial_registry/${uid}`;
    const now = new Date().toISOString();

    await fetch(docUrl, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        fields: {
          claimedAt: { timestampValue: now },
          trialEnd: { timestampValue: trialEndIso },
          uid: { stringValue: uid }
        }
      })
    });
  } catch (err) {
    console.error("Error writing trial_registry:", err?.message || err);
  }
}

// ---------------------------------------------------------------------------
// STABLE IDENTITY FREE-TRIAL ANTI-ABUSE ENGINE (Step 5B)
// ---------------------------------------------------------------------------

function getGoogleSubjectIdFromToken(payload) {
  if (!payload || typeof payload !== "object") return null;
  const firebaseClaim = payload.firebase;
  if (!firebaseClaim || typeof firebaseClaim !== "object") return null;

  const identities = firebaseClaim.identities;
  if (!identities || typeof identities !== "object") return null;

  const googleIdentities = identities["google.com"];
  if (Array.isArray(googleIdentities) && googleIdentities.length > 0) {
    const sub = googleIdentities[0];
    if (typeof sub === "string" && sub.trim().length > 0) {
      return sub.trim();
    }
  }

  return null;
}

async function computeTrialIdentityHash(googleSub, secretKey) {
  if (!googleSub || typeof googleSub !== "string" || !secretKey || typeof secretKey !== "string") {
    throw new Error("Invalid parameters for computeTrialIdentityHash");
  }
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secretKey),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    enc.encode(googleSub)
  );
  const hashArray = Array.from(new Uint8Array(signature));
  return hashArray.map(b => b.toString(16).padStart(2, "0")).join("");
}

function extractFieldTimestampMs(field) {
  if (!field) return 0;
  const raw = field.timestampValue || field.stringValue || (typeof field === "string" || typeof field === "number" ? field : null);
  if (typeof raw === "string") {
    const t = new Date(raw).getTime();
    return isNaN(t) ? 0 : t;
  }
  if (typeof raw === "number") {
    return raw < 1e12 ? raw * 1000 : raw;
  }
  return 0;
}

async function getTrialIdentityRecord(identityKey, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { exists: false, error: true, status: 500, message: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/trial_identities/${identityKey}`;
    const res = await fetch(docUrl, {
      method: "GET",
      headers: { "Authorization": `Bearer ${accessToken}` }
    });

    if (res.status === 200) {
      const doc = await res.json();
      return { exists: true, error: false, fields: doc.fields || {} };
    }

    if (res.status === 404) {
      return { exists: false, error: false };
    }

    console.warn(`Firestore GET trial_identities returned ${res.status}`);
    return { exists: false, error: true, status: res.status };
  } catch (err) {
    console.error("Error reading trial_identities:", err?.name || "Error");
    return { exists: false, error: true, errorMsg: err?.name || "Network error" };
  }
}

async function reserveTrialIdentityAtomic(identityKey, fields, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY secret not configured in Cloudflare Worker.");
  }

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const fieldNames = Object.keys(fields);
  const updateMaskParams = fieldNames.map(f => `updateMask.fieldPaths=${f}`).join("&");

  // Precondition: currentDocument.exists=false enforces atomic creation preventing race conditions
  const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/trial_identities/${identityKey}?${updateMaskParams}&currentDocument.exists=false`;

  const res = await fetch(docUrl, {
    method: "PATCH",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ fields })
  });

  if (res.status === 409 || res.status === 412) {
    // Identity already reserved (concurrent race or pre-existing trial)
    return { created: false, reason: "ALREADY_EXISTS" };
  }

  if (!res.ok) {
    const errorBody = await res.text().catch(() => "");
    throw new Error(`Firestore REST PATCH trial_identities failed (${res.status}): ${errorBody}`);
  }

  return { created: true, doc: await res.json().catch(() => ({})) };
}

async function updateTrialIdentityRecord(identityKey, fields, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { success: false, error: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const fieldNames = Object.keys(fields);
    const updateMaskParams = fieldNames.map(f => `updateMask.fieldPaths=${f}`).join("&");
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/trial_identities/${identityKey}?${updateMaskParams}`;

    const res = await fetch(docUrl, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ fields })
    });

    if (!res.ok) {
      console.warn(`Firestore PATCH trial_identities failed: HTTP ${res.status}`);
      return { success: false, status: res.status };
    }

    return { success: true, status: res.status };
  } catch (err) {
    console.error("Error writing trial_identities:", err?.name || "Error");
    return { success: false, error: err?.name || "Network error" };
  }
}

// ---------------------------------------------------------------------------
// CONCURRENT SUBSCRIPTION CREATION COORDINATION & DUPLICATE PREVENTION
// ---------------------------------------------------------------------------

async function getSubscriptionCreationLock(uid, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return null;

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/subscription_creation_locks/${uid}`;
    const res = await fetch(docUrl, {
      method: "GET",
      headers: { "Authorization": `Bearer ${accessToken}` }
    });

    if (res.status === 404) return { exists: false, fields: {}, updateTime: null };
    if (!res.ok) return null;

    const doc = await res.json();
    return { exists: true, fields: doc.fields || {}, updateTime: doc.updateTime || null };
  } catch (err) {
    console.warn("Error reading subscription creation lock:", err.message || err);
    return null;
  }
}

async function claimSubscriptionCreationLockAtomic(uid, nowMs, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY not configured");

  const currentDoc = await getSubscriptionCreationLock(uid, env);
  const LOCK_TTL_MS = 60000; // 60 seconds in-flight lock window

  if (currentDoc && currentDoc.exists) {
    const lockStatus = currentDoc.fields?.status?.stringValue;
    const expiresAtMs = currentDoc.fields?.expiresAtMs?.integerValue ? Number(currentDoc.fields.expiresAtMs.integerValue) : 0;
    const existingSubId = currentDoc.fields?.subscriptionId?.stringValue;

    // Reuse recent uncompleted subscription mandate (e.g. within 5 minutes) to prevent duplicate gateway mandates
    if (lockStatus === "COMPLETED" && existingSubId && nowMs < expiresAtMs) {
      return { claimed: false, canReuse: true, subscriptionId: existingSubId };
    }

    // An in-flight creation request is actively holding the lock
    if (lockStatus === "IN_PROGRESS" && nowMs < expiresAtMs) {
      return { claimed: false, canReuse: false, reason: "CREATION_IN_PROGRESS" };
    }
  }

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const fields = {
    status: { stringValue: "IN_PROGRESS" },
    uid: { stringValue: uid },
    lockedAtMs: { integerValue: nowMs.toString() },
    expiresAtMs: { integerValue: (nowMs + LOCK_TTL_MS).toString() },
    updatedAt: { timestampValue: new Date(nowMs).toISOString() }
  };

  const fieldPaths = ["status", "uid", "lockedAtMs", "expiresAtMs", "updatedAt"];
  const updateMask = fieldPaths.map(p => `updateMask.fieldPaths=${p}`).join("&");

  let docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/subscription_creation_locks/${uid}?${updateMask}`;
  if (!currentDoc || !currentDoc.exists) {
    docUrl += `&currentDocument.exists=false`;
  } else if (currentDoc.updateTime) {
    docUrl += `&currentDocument.updateTime=${encodeURIComponent(currentDoc.updateTime)}`;
  }

  const res = await fetch(docUrl, {
    method: "PATCH",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ fields })
  });

  if (res.status === 409 || res.status === 412) {
    return { claimed: false, canReuse: false, reason: "CONCURRENT_CLAIM_CONFLICT" };
  }

  if (!res.ok) {
    const errBody = await res.text();
    throw new Error(`Failed to claim creation lock (${res.status}): ${errBody}`);
  }

  const resData = await res.json().catch(() => ({}));
  const acquiredUpdateTime = resData.updateTime;
  if (!acquiredUpdateTime) {
    throw new Error("Firestore PATCH succeeded but returned no updateTime revision.");
  }

  return { claimed: true, canReuse: false, updateTime: acquiredUpdateTime };
}

async function finalizeSubscriptionCreationLock(uid, subscriptionId, nowMs, expectedUpdateTime, env) {
  if (!uid || !expectedUpdateTime) {
    console.warn("Cannot finalize subscription creation lock: missing expected updateTime revision.");
    return { success: false, superseded: false, reason: "MISSING_REVISION" };
  }

  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { success: false, reason: "NO_SA_KEY" };

  const HOLD_TIME_MS = 5 * 60 * 1000; // Hold completed reservation for 5 minutes
  const fields = {
    status: { stringValue: "COMPLETED" },
    subscriptionId: { stringValue: subscriptionId },
    expiresAtMs: { integerValue: (nowMs + HOLD_TIME_MS).toString() },
    updatedAt: { timestampValue: new Date(nowMs).toISOString() }
  };

  const fieldPaths = ["status", "subscriptionId", "expiresAtMs", "updatedAt"];
  const updateMask = fieldPaths.map(p => `updateMask.fieldPaths=${p}`).join("&");
  const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/subscription_creation_locks/${encodeURIComponent(uid)}?${updateMask}&currentDocument.updateTime=${encodeURIComponent(expectedUpdateTime)}`;

  const maxAttempts = 2; // Bounded retry for transient failures
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const accessToken = await getGoogleServiceAccountAccessToken(saKey);
      const res = await fetch(docUrl, {
        method: "PATCH",
        headers: {
          "Authorization": `Bearer ${accessToken}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ fields })
      });

      if (res.status === 409 || res.status === 412) {
        if (attempt > 1) {
          // If a prior attempt encountered a network drop or transient error,
          // check if our own write actually committed remotely before marking as superseded.
          try {
            const checkDoc = await getSubscriptionCreationLock(uid, env);
            if (
              checkDoc &&
              checkDoc.exists &&
              checkDoc.fields?.status?.stringValue === "COMPLETED" &&
              checkDoc.fields?.subscriptionId?.stringValue === subscriptionId &&
              checkDoc.fields?.uid?.stringValue === uid
            ) {
              console.log("Lock finalization verified via read-only check after lost response.");
              return { success: true, updateTime: checkDoc.updateTime || null, recoveredFromLostResponse: true };
            }
          } catch (checkErr) {
            console.warn("Error verifying lock document on precondition conflict:", checkErr.message || checkErr);
          }
        }
        console.warn("Lock finalization superseded. Precondition failed.");
        return { success: false, superseded: true, reason: "SUPERSEDED" };
      }

      if (res.ok) {
        const resData = await res.json().catch(() => ({}));
        return { success: true, updateTime: resData.updateTime || null };
      }

      // If transient 5xx, retry once
      if (res.status >= 500 && attempt < maxAttempts) {
        console.warn(`Transient Firestore error (${res.status}) on lock finalization, retrying...`);
        continue;
      }

      console.warn(`Failed to finalize creation lock: HTTP ${res.status}`);
      return { success: false, reason: `HTTP_${res.status}` };
    } catch (err) {
      if (attempt < maxAttempts) {
        console.warn("Network error on lock finalization, retrying...");
        continue;
      }
      console.warn("Error finalizing creation lock:", err?.name || "Error");
      return { success: false, reason: "NETWORK_ERROR" };
    }
  }

  return { success: false, reason: "MAX_RETRIES_EXCEEDED" };
}

async function releaseSubscriptionCreationLockOnError(uid, expectedUpdateTime, env) {
  if (!uid || !expectedUpdateTime) {
    console.warn("Cannot release subscription creation lock: missing expected updateTime revision.");
    return { released: false, superseded: false, reason: "MISSING_REVISION" };
  }

  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { released: false, reason: "NO_SA_KEY" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const fields = {
      status: { stringValue: "FAILED" },
      expiresAtMs: { integerValue: "0" },
      updatedAt: { timestampValue: new Date().toISOString() }
    };

    const fieldPaths = ["status", "expiresAtMs", "updatedAt"];
    const updateMask = fieldPaths.map(p => `updateMask.fieldPaths=${p}`).join("&");
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/subscription_creation_locks/${encodeURIComponent(uid)}?${updateMask}&currentDocument.updateTime=${encodeURIComponent(expectedUpdateTime)}`;

    const res = await fetch(docUrl, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ fields })
    });

    if (res.status === 409 || res.status === 412) {
      console.warn("Lock release superseded. Precondition failed.");
      return { released: false, superseded: true, reason: "SUPERSEDED" };
    }

    if (!res.ok) {
      console.warn(`Failed to release creation lock: HTTP ${res.status}`);
      return { released: false, reason: `HTTP_${res.status}` };
    }

    return { released: true };
  } catch (err) {
    console.warn("Error releasing creation lock:", err?.name || "Error");
    return { released: false, reason: "NETWORK_ERROR" };
  }
}

// ---------------------------------------------------------------------------
// SERVER-AUTHORITATIVE BACKUP & DAILY LOCK ARCHITECTURE
// ---------------------------------------------------------------------------

const IST_OFFSET_MS = (5 * 60 + 30) * 60 * 1000; // 5 hours 30 mins (19,800,000 ms)

function getIstDateString(timestampMs = Date.now()) {
  const istDate = new Date(timestampMs + IST_OFFSET_MS);
  return istDate.toISOString().slice(0, 10);
}

function getNextIstMidnightMs(timestampMs = Date.now()) {
  const istMs = timestampMs + IST_OFFSET_MS;
  const currentIstDayStr = new Date(istMs).toISOString().slice(0, 10);
  const nextDay = new Date(new Date(currentIstDayStr + "T00:00:00.000Z").getTime() + 86400000);
  return nextDay.getTime() - IST_OFFSET_MS;
}

async function getFirestoreDocument(docPath, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return null;

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/${docPath}`;
    const res = await fetch(docUrl, {
      method: "GET",
      headers: { "Authorization": `Bearer ${accessToken}` }
    });

    if (res.status === 404) {
      return { exists: false, fields: {}, updateTime: null };
    }
    if (!res.ok) {
      console.warn(`Firestore GET ${docPath} returned ${res.status}`);
      return null;
    }

    const doc = await res.json();
    return {
      exists: true,
      name: doc.name,
      fields: doc.fields || {},
      updateTime: doc.updateTime || null
    };
  } catch (err) {
    console.error(`Error reading Firestore doc ${docPath}:`, err?.name || "Error");
    return null;
  }
}

async function listFirestoreDocuments(collectionPath, env, maxRecords = 5000) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return [];

  const allDocuments = [];
  let pageToken = null;
  const pageSize = Math.min(300, maxRecords);

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    do {
      const listUrl = new URL(`https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/${collectionPath}`);
      listUrl.searchParams.set("pageSize", pageSize.toString());
      if (pageToken) {
        listUrl.searchParams.set("pageToken", pageToken);
      }
      const res = await fetch(listUrl.toString(), {
        method: "GET",
        headers: { "Authorization": `Bearer ${accessToken}` }
      });

      if (res.status === 404) {
        // Parent document does not exist or collection not found
        break;
      }
      if (!res.ok) {
        console.warn(`Firestore list ${collectionPath} returned ${res.status}`);
        break;
      }

      const data = await res.json();
      const docs = (data.documents || []).map(doc => ({
        name: doc.name,
        fields: doc.fields || {},
        updateTime: doc.updateTime || null
      }));

      allDocuments.push(...docs);
      pageToken = data.nextPageToken || null;
    } while (pageToken && allDocuments.length < maxRecords);

    return allDocuments;
  } catch (err) {
    console.error(`Error listing Firestore ${collectionPath}:`, err?.name || "Error");
    return allDocuments;
  }
}

async function commitFirestoreBatch(writes, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY not configured");

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const commitUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:commit`;

  const res = await fetch(commitUrl, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ writes })
  });

  if (!res.ok) {
    const errorBody = await res.text();
    const err = new Error(`Firestore commit failed (${res.status}): ${errorBody}`);
    err.status = res.status;
    err.errorBody = errorBody;
    throw err;
  }

  return await res.json();
}

function parseQuotaClaimDoc(doc) {
  if (!doc || doc.exists === false || !doc.fields || Object.keys(doc.fields).length === 0) {
    return { exists: false, updateTime: null };
  }
  const f = doc.fields;
  return {
    exists: true,
    updateTime: doc.updateTime || null,
    status: f.status?.stringValue || null,
    idempotencyKey: f.idempotencyKey?.stringValue || null,
    backupId: f.backupId?.stringValue || null,
    pinnedSnapshotId: f.pinnedSnapshotId?.stringValue || null,
    pinStatus: f.pinStatus?.stringValue || null,
    fencingToken: f.fencingToken?.integerValue ? Number(f.fencingToken.integerValue) : 1,
    attemptCount: f.attemptCount?.integerValue ? Number(f.attemptCount.integerValue) : 1,
    claimDate: f.claimDate?.stringValue || null,
    claimTimeMs: f.claimTimeMs?.integerValue ? Number(f.claimTimeMs.integerValue) : 0,
    expiresAtMs: f.expiresAtMs?.integerValue ? Number(f.expiresAtMs.integerValue) : 0,
    pinLeaseExpiresAtMs: f.pinLeaseExpiresAtMs?.integerValue ? Number(f.pinLeaseExpiresAtMs.integerValue) : 0,
    offlineGraceExpiresAtMs: f.offlineGraceExpiresAtMs?.integerValue ? Number(f.offlineGraceExpiresAtMs.integerValue) : 0,
    dispatchedAtMs: f.dispatchedAtMs?.integerValue ? Number(f.dispatchedAtMs.integerValue) : 0,
    acknowledgedAtMs: f.acknowledgedAtMs?.integerValue ? Number(f.acknowledgedAtMs.integerValue) : 0,
    committedAtMs: f.committedAtMs?.integerValue ? Number(f.committedAtMs.integerValue) : 0,
    rePinCount: f.rePinCount?.integerValue ? Number(f.rePinCount.integerValue) : 0,
    rePinnedAtMs: f.rePinnedAtMs?.integerValue ? Number(f.rePinnedAtMs.integerValue) : 0,
    failureReason: f.failureReason?.stringValue || null,
    abortedAtMs: f.abortedAtMs?.integerValue ? Number(f.abortedAtMs.integerValue) : 0,
    expiredAtMs: f.expiredAtMs?.integerValue ? Number(f.expiredAtMs.integerValue) : 0,
    updatedAtMs: f.updatedAtMs?.integerValue ? Number(f.updatedAtMs.integerValue) : 0
  };
}

function parseManifestDoc(doc) {
  if (!doc || doc.exists === false || !doc.fields || Object.keys(doc.fields).length === 0) {
    return { exists: false, updateTime: null };
  }
  const f = doc.fields;
  return {
    exists: true,
    updateTime: doc.updateTime || null,
    backupId: f.backupId?.stringValue || null,
    status: f.status?.stringValue || null,
    recordCount: f.recordCount?.integerValue ? Number(f.recordCount.integerValue) : 0,
    defaultDailyLimit: f.defaultDailyLimit?.integerValue ? Number(f.defaultDailyLimit.integerValue) : 0,
    committedAtMs: f.committedAtMs?.integerValue ? Number(f.committedAtMs.integerValue) : 0,
    uploadInitiatedAtMs: f.uploadInitiatedAtMs?.integerValue ? Number(f.uploadInitiatedAtMs.integerValue) : 0,
    activeRestorePinCount: f.activeRestorePinCount?.integerValue ? Number(f.activeRestorePinCount.integerValue) : 0,
    lastPinnedAtMs: f.lastPinnedAtMs?.integerValue ? Number(f.lastPinnedAtMs.integerValue) : 0,
    quarantineStatus: f.quarantineStatus?.stringValue || null,
    quarantineReason: f.quarantineReason?.stringValue || null,
    quarantineInitiatedAtMs: f.quarantineInitiatedAtMs?.integerValue ? Number(f.quarantineInitiatedAtMs.integerValue) : null,
    quarantineObservationExpiresAtMs: f.quarantineObservationExpiresAtMs?.integerValue ? Number(f.quarantineObservationExpiresAtMs.integerValue) : null,
    lastQuarantineClearedAtMs: f.lastQuarantineClearedAtMs?.integerValue ? Number(f.lastQuarantineClearedAtMs.integerValue) : null,
    chunks: f.chunks?.arrayValue?.values || []
  };
}

function parseSnapshotDocToRecord(d) {
  const rawTotal = d.fields.totalReelsCount?.integerValue !== undefined ? Number(d.fields.totalReelsCount.integerValue) : null;
  const rawCount = d.fields.count?.integerValue !== undefined ? Number(d.fields.count.integerValue) : null;

  let countVal = 0;
  if (rawTotal !== null && !isNaN(rawTotal) && rawTotal > 0) {
    countVal = rawTotal;
  } else if (rawCount !== null && !isNaN(rawCount) && rawCount > 0) {
    countVal = rawCount;
  } else if (rawTotal === 0 || rawCount === 0) {
    countVal = 0;
  }

  return {
    dateString: d.fields.dateString?.stringValue || d.name.split("/").pop(),
    totalReelsCount: countVal,
    count: countVal,
    dailyGoal: Number(d.fields.dailyGoal?.integerValue || 0),
    isSkullDay: Boolean(d.fields.isSkullDay?.booleanValue),
    isStrictModeOn: d.fields.isStrictModeOn?.booleanValue !== undefined ? Boolean(d.fields.isStrictModeOn.booleanValue) : true,
    isStrictModeLocked: Boolean(d.fields.isStrictModeLocked?.booleanValue),
    isLimitLocked: Boolean(d.fields.isLimitLocked?.booleanValue),
    totalTimeSpentMillis: Number(d.fields.totalTimeSpentMillis?.integerValue || 0)
  };
}

async function fetchSnapshotRecords(uid, backupId, env) {
  const isLegacy = !backupId || backupId === "legacy" || backupId.startsWith("legacy_");

  if (isLegacy) {
    // Explicit legacy restore from root daily_data
    const legacyDocs = await listFirestoreDocuments(`users/${uid}/daily_data`, env, 1000);
    return legacyDocs.map(parseSnapshotDocToRecord);
  }

  // Snapshot-based restore from users/${uid}/backup_snapshots/${backupId}/daily_data
  const snapshotParentPath = `users/${uid}/backup_snapshots/${backupId}`;
  const parentDoc = await getFirestoreDocument(snapshotParentPath, env);

  if (!parentDoc || !parentDoc.exists) {
    // Check if manifest exists to self-heal missing parent doc on previously committed backups
    const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${backupId}`, env);
    if (manifestDoc && manifestDoc.exists) {
      const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
      await commitFirestoreBatch([{
        update: {
          name: `projects/${projectId}/databases/(default)/documents/${snapshotParentPath}`,
          fields: {
            backupId: { stringValue: backupId },
            selfHealedAtMs: { integerValue: Date.now().toString() },
            status: { stringValue: "COMMITTED" }
          }
        }
      }], env).catch(err => console.warn("Failed to self-heal parent snapshot doc:", err?.name || "Error"));
    }
  }

  const docs = await listFirestoreDocuments(`users/${uid}/backup_snapshots/${backupId}/daily_data`, env, 5000);
  return docs.map(parseSnapshotDocToRecord);
}

async function getFirestoreBackupStatus(uid, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return null;

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${uid}/backup/status`;
    const res = await fetch(docUrl, {
      method: "GET",
      headers: { "Authorization": `Bearer ${accessToken}` }
    });

    if (res.status === 404) {
      return { exists: false, fields: {}, updateTime: null };
    }
    if (!res.ok) {
      console.warn(`Firestore GET backup/status returned ${res.status}`);
      return null;
    }

    const doc = await res.json();
    return {
      exists: true,
      fields: doc.fields || {},
      updateTime: doc.updateTime || null
    };
  } catch (err) {
    console.error("Error reading Firestore backup/status:", err?.name || "Error");
    return null;
  }
}

function parseBackupStatusDoc(doc) {
  if (!doc || !doc.fields) {
    return {
      status: null,
      lastBackupDate: null,
      lastBackupTimeMs: 0,
      lastRequestId: null,
      claimDate: null,
      claimRequestId: null,
      claimTimeMs: 0,
      activeBackupId: null,
      recordCount: 0
    };
  }
  const f = doc.fields;
  const countVal = f.recordCount?.integerValue ? Number(f.recordCount.integerValue) :
                   (f.recordCount?.stringValue ? Number(f.recordCount.stringValue) :
                   (typeof f.recordCount === "number" ? f.recordCount : 0));
  return {
    status: f.status?.stringValue || null,
    lastBackupDate: f.lastBackupDate?.stringValue || null,
    lastBackupTimeMs: f.lastBackupTimeMs?.integerValue ? Number(f.lastBackupTimeMs.integerValue) : 0,
    lastRequestId: f.lastRequestId?.stringValue || null,
    claimDate: f.claimDate?.stringValue || null,
    claimRequestId: f.claimRequestId?.stringValue || null,
    claimTimeMs: f.claimTimeMs?.integerValue ? Number(f.claimTimeMs.integerValue) : 0,
    activeBackupId: f.activeBackupId?.stringValue || null,
    recordCount: isNaN(countVal) ? 0 : countVal
  };
}

async function claimBackupSlotAtomic(uid, todayUtc, requestId, nowMs, currentDoc, env, fencingToken = 1) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY not configured");

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const fields = {
    status: { stringValue: "IN_PROGRESS" },
    claimDate: { stringValue: todayUtc },
    claimRequestId: { stringValue: requestId },
    claimTimeMs: { integerValue: nowMs.toString() },
    updatedAt: { timestampValue: new Date(nowMs).toISOString() }
  };

  const fieldPaths = ["status", "claimDate", "claimRequestId", "claimTimeMs", "updatedAt"];
  const updateMask = fieldPaths.map(p => `updateMask.fieldPaths=${p}`).join("&");
  
  let docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${uid}/backup/status?${updateMask}`;
  if (!currentDoc || !currentDoc.exists) {
    docUrl += `&currentDocument.exists=false`;
  } else if (currentDoc.updateTime) {
    docUrl += `&currentDocument.updateTime=${encodeURIComponent(currentDoc.updateTime)}`;
  }

  const res = await fetch(docUrl, {
    method: "PATCH",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ fields })
  });

  if (res.status === 409 || res.status === 412) {
    return { claimed: false, reason: "CONCURRENT_CLAIM_CONFLICT" };
  }

  if (!res.ok) {
    const errorBody = await res.text();
    throw new Error(`Firestore REST claim backup PATCH failed (${res.status}): ${errorBody}`);
  }

  const newDoc = await res.json();
  const backupId = `${todayUtc}_${fencingToken}_${requestId}`;

  // Also write daily_quota_claims/${uid}_${todayUtc}_BACKUP
  const claimFields = {
    uid: { stringValue: uid },
    status: { stringValue: "IN_PROGRESS" },
    fencingToken: { integerValue: fencingToken.toString() },
    backupId: { stringValue: backupId },
    idempotencyKey: { stringValue: requestId },
    claimDate: { stringValue: todayUtc },
    claimTimeMs: { integerValue: nowMs.toString() },
    expiresAtMs: { integerValue: (nowMs + 60000).toString() },
    updatedAtMs: { integerValue: nowMs.toString() }
  };
  const claimMask = Object.keys(claimFields).map(p => `updateMask.fieldPaths=${p}`).join("&");
  const claimUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${todayUtc}_BACKUP?${claimMask}`;
  try {
    await fetch(claimUrl, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ fields: claimFields })
    });
  } catch (err) {
    console.warn("Non-fatal: Failed to write initial daily_quota_claim for backup:", err?.message);
  }

  return { claimed: true, updateTime: newDoc.updateTime, fencingToken, backupId };
}

async function commitFirestoreBackup(uid, todayUtc, requestId, nowMs, dailyRecords, defaultDailyLimit, env, backupIdOverride, fencingTokenOverride) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY not configured");

  const token = fencingTokenOverride || 1;
  const backupId = backupIdOverride || `${todayUtc}_${token}_${requestId}`;

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const dailyWrites = [];

  // 1. Daily tracking records (both immutable snapshot path and legacy path for Stage 1 compatibility)
  if (Array.isArray(dailyRecords)) {
    for (const rec of dailyRecords) {
      if (!rec || typeof rec.dateString !== "string" || !rec.dateString.match(/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/)) {
        continue;
      }
      const totalVal = rec.totalReelsCount !== undefined && rec.totalReelsCount !== null ? Number(rec.totalReelsCount) : null;
      const countValRaw = rec.count !== undefined && rec.count !== null ? Number(rec.count) : null;
      let resolvedCount = 0;
      if (totalVal !== null && !isNaN(totalVal) && totalVal > 0) {
        resolvedCount = totalVal;
      } else if (countValRaw !== null && !isNaN(countValRaw) && countValRaw > 0) {
        resolvedCount = countValRaw;
      } else if (totalVal === 0 || countValRaw === 0) {
        resolvedCount = 0;
      }
      const countVal = Math.max(0, Math.min(100000, resolvedCount));
      const recordFields = {
        dateString: { stringValue: rec.dateString },
        totalReelsCount: { integerValue: countVal.toString() },
        count: { integerValue: countVal.toString() },
        dailyGoal: { integerValue: (Math.max(0, Math.min(100000, Number(rec.dailyGoal) || 0))).toString() },
        isSkullDay: { booleanValue: Boolean(rec.isSkullDay) },
        isStrictModeOn: rec.isStrictModeOn !== undefined ? { booleanValue: Boolean(rec.isStrictModeOn) } : { booleanValue: true },
        isStrictModeLocked: { booleanValue: Boolean(rec.isStrictModeLocked) },
        isLimitLocked: { booleanValue: Boolean(rec.isLimitLocked) },
        totalTimeSpentMillis: { integerValue: (Math.max(0, Number(rec.totalTimeSpentMillis) || 0)).toString() }
      };

      // Immutable snapshot record
      dailyWrites.push({
        update: {
          name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_snapshots/${backupId}/daily_data/${rec.dateString}`,
          fields: recordFields
        }
      });

      // Legacy direct daily_data record for Stage 1 backward compatibility
      dailyWrites.push({
        update: {
          name: `projects/${projectId}/databases/(default)/documents/users/${uid}/daily_data/${rec.dateString}`,
          fields: recordFields
        }
      });
    }
  }

  // Final batch writes (preferences + Manifest + backup/status + daily quota claim)
  const finalWrites = [];

  // 2. Default daily limit (if positive)
  if (typeof defaultDailyLimit === "number" && defaultDailyLimit > 0) {
    const cappedLimit = Math.max(0, Math.min(100000, defaultDailyLimit));
    finalWrites.push({
      update: {
        name: `projects/${projectId}/databases/(default)/documents/users/${uid}/settings/preferences`,
        fields: {
          defaultDailyLimit: { integerValue: cappedLimit.toString() },
          dailyLimit: { integerValue: cappedLimit.toString() },
          lastUpdatedEpoch: { integerValue: nowMs.toString() },
          updatedAt: { timestampValue: new Date(nowMs).toISOString() }
        }
      }
    });
  }

  // 3. Manifest document
  finalWrites.push({
    update: {
      name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
      fields: {
        backupId: { stringValue: backupId },
        status: { stringValue: "COMMITTED" },
        recordCount: { integerValue: (Array.isArray(dailyRecords) ? dailyRecords.length : 0).toString() },
        defaultDailyLimit: { integerValue: (defaultDailyLimit || 0).toString() },
        committedAtMs: { integerValue: nowMs.toString() },
        activeRestorePinCount: { integerValue: "0" },
        lastPinnedAtMs: { integerValue: "0" },
        quarantineStatus: { nullValue: null },
        quarantineReason: { nullValue: null },
        quarantineInitiatedAtMs: { nullValue: null },
        quarantineObservationExpiresAtMs: { nullValue: null },
        lastQuarantineClearedAtMs: { nullValue: null },
        updatedAt: { timestampValue: new Date(nowMs).toISOString() }
      }
    }
  });

  // 3b. Parent snapshot document (enables Firestore REST subcollection enumeration)
  finalWrites.push({
    update: {
      name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_snapshots/${backupId}`,
      fields: {
        backupId: { stringValue: backupId },
        status: { stringValue: "COMMITTED" },
        recordCount: { integerValue: (Array.isArray(dailyRecords) ? dailyRecords.length : 0).toString() },
        createdAt: { stringValue: new Date(nowMs).toISOString() },
        createdAtMs: { integerValue: nowMs.toString() }
      }
    }
  });

  // 4. Status record marking SUCCESS
  finalWrites.push({
    update: {
      name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup/status`,
      fields: {
        status: { stringValue: "SUCCESS" },
        activeBackupId: { stringValue: backupId },
        lastBackupDate: { stringValue: todayUtc },
        lastBackupTimeMs: { integerValue: nowMs.toString() },
        lastRequestId: { stringValue: requestId },
        claimDate: { nullValue: null },
        claimRequestId: { nullValue: null },
        claimTimeMs: { nullValue: null },
        recordCount: { integerValue: (Array.isArray(dailyRecords) ? dailyRecords.length : 0).toString() },
        updatedAt: { timestampValue: new Date(nowMs).toISOString() }
      }
    }
  });

  // 5. Daily quota claim document marking COMPLETED
  finalWrites.push({
    update: {
      name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${todayUtc}_BACKUP`,
      fields: {
        uid: { stringValue: uid },
        status: { stringValue: "COMPLETED" },
        fencingToken: { integerValue: token.toString() },
        backupId: { stringValue: backupId },
        idempotencyKey: { stringValue: requestId },
        claimDate: { stringValue: todayUtc },
        committedAtMs: { integerValue: nowMs.toString() },
        updatedAtMs: { integerValue: nowMs.toString() },
        updatedAt: { timestampValue: new Date(nowMs).toISOString() }
      }
    }
  });

  const commitUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:commit`;

  // If total writes <= 450, execute in a single atomic transaction
  if (dailyWrites.length + finalWrites.length <= 450) {
    const allWrites = [...dailyWrites, ...finalWrites];
    const res = await fetch(commitUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ writes: allWrites })
    });

    if (!res.ok) {
      const errorBody = await res.text();
      throw new Error(`Firestore commit failed (${res.status}): ${errorBody}`);
    }

    return await res.json();
  }

  // If exceeding Firestore 500-write limit, chunk preceding daily records in batches of 400
  const CHUNK_SIZE = 400;
  let offset = 0;
  while (dailyWrites.length - offset > CHUNK_SIZE) {
    const slice = dailyWrites.slice(offset, offset + CHUNK_SIZE);
    offset += CHUNK_SIZE;
    const res = await fetch(commitUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ writes: slice })
    });

    if (!res.ok) {
      const errorBody = await res.text();
      throw new Error(`Firestore chunked commit failed (${res.status}): ${errorBody}`);
    }
  }

  // Final chunk commits remaining daily writes + preferences + Manifest + SUCCESS status + quota claim atomically
  const remainingDaily = dailyWrites.slice(offset);
  const lastChunkWrites = [...remainingDaily, ...finalWrites];
  const lastRes = await fetch(commitUrl, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ writes: lastChunkWrites })
  });

  if (!lastRes.ok) {
    const errorBody = await lastRes.text();
    throw new Error(`Firestore final commit failed (${lastRes.status}): ${errorBody}`);
  }

  return await lastRes.json();
}

async function releaseBackupSlotOnError(uid, errorMsg, env, istDate, expectedUpdateTime) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return;

  const dateStr = istDate || getIstDateString();

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const fields = {
      status: { stringValue: "FAILED" },
      claimDate: { nullValue: null },
      claimRequestId: { nullValue: null },
      claimTimeMs: { nullValue: null },
      failureReason: { stringValue: (errorMsg || "Unknown error").slice(0, 200) },
      updatedAt: { timestampValue: new Date().toISOString() }
    };

    const fieldPaths = ["status", "claimDate", "claimRequestId", "claimTimeMs", "failureReason", "updatedAt"];
    let updateMask = fieldPaths.map(p => `updateMask.fieldPaths=${p}`).join("&");
    if (expectedUpdateTime) {
      updateMask += `&currentDocument.updateTime=${encodeURIComponent(expectedUpdateTime)}`;
    }
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${uid}/backup/status?${updateMask}`;

    const res = await fetch(docUrl, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ fields })
    });

    if (res.status === 412 || res.status === 409) {
      // Stale claim release: slot was updated or claimed concurrently; do not clobber
      return;
    }

    // Also update daily_quota_claims to FAILED
    const claimFields = {
      status: { stringValue: "FAILED" },
      failureReason: { stringValue: (errorMsg || "Unknown error").slice(0, 200) },
      updatedAtMs: { integerValue: Date.now().toString() }
    };
    const claimMask = ["status", "failureReason", "updatedAtMs"].map(p => `updateMask.fieldPaths=${p}`).join("&");
    const claimUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${dateStr}_BACKUP?${claimMask}`;
    await fetch(claimUrl, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ fields: claimFields })
    });
  } catch (err) {
    console.error("Non-fatal: Failed to release backup slot on error:", err?.name || "Error");
  }
}

async function reconcileExpiredPins(env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { reconciledCount: 0 };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const queryUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:runQuery`;
    
    const structuredQuery = {
      from: [{ collectionId: "daily_quota_claims" }],
      where: {
        fieldFilter: {
          field: { fieldPath: "pinStatus" },
          op: "EQUAL",
          value: { stringValue: "PINNED" }
        }
      },
      limit: 100
    };

    const res = await fetch(queryUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ structuredQuery })
    });

    if (!res.ok) return { reconciledCount: 0 };
    const queryResults = await res.json();
    const nowMs = Date.now();
    let reconciledCount = 0;

    for (const item of queryResults) {
      if (!item.document) continue;
      const doc = item.document;
      const claimPath = doc.name.split("/documents/")[1];
      const parsed = parseQuotaClaimDoc({ fields: doc.fields, updateTime: doc.updateTime });
      
      if (parsed.offlineGraceExpiresAtMs && nowMs > parsed.offlineGraceExpiresAtMs && parsed.pinStatus === "PINNED") {
        const snapshotId = parsed.pinnedSnapshotId;
        const rawClaimId = claimPath.replace("daily_quota_claims/", "");
        const lastUnderscore = rawClaimId.lastIndexOf("_");
        const secondLastUnderscore = rawClaimId.lastIndexOf("_", lastUnderscore - 1);
        const uid = doc.fields?.uid?.stringValue || (secondLastUnderscore > 0 ? rawClaimId.slice(0, secondLastUnderscore) : rawClaimId.split("_")[0]);

        const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${snapshotId}`, env);
        const parsedManifest = parseManifestDoc(manifestDoc);

        const writes = [
          {
            update: {
              name: `projects/${projectId}/databases/(default)/documents/${claimPath}`,
              fields: {
                ...doc.fields,
                status: { stringValue: "EXPIRED_UNACKNOWLEDGED" },
                pinStatus: { stringValue: "EXPIRED" },
                expiredAtMs: { integerValue: nowMs.toString() },
                updatedAtMs: { integerValue: nowMs.toString() }
              }
            },
            currentDocument: { updateTime: doc.updateTime }
          }
        ];

        if (parsedManifest.exists) {
          writes.push({
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${snapshotId}`,
              fields: {
                ...manifestDoc.fields,
                activeRestorePinCount: { integerValue: Math.max(0, parsedManifest.activeRestorePinCount - 1).toString() }
              }
            },
            currentDocument: { updateTime: manifestDoc.updateTime }
          });
        }

        try {
          await commitFirestoreBatch(writes, env);
          reconciledCount++;
        } catch (err) {
          console.warn("Expired pin reconciliation race (412/conflict), skipped:", err?.status || err?.message);
        }
      }
    }

    return { reconciledCount };
  } catch (err) {
    console.error("Error in reconcileExpiredPins:", err?.name || "Error");
    return { reconciledCount: 0, error: err?.message };
  }
}

async function auditSnapshotPinConsistency(env, uid, backupId) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { discrepancy: false };

  try {
    const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${backupId}`, env);
    const parsedManifest = parseManifestDoc(manifestDoc);
    if (!parsedManifest.exists) return { skipped: true };

    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const queryUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:runQuery`;
    
    const structuredQuery = {
      from: [{ collectionId: "daily_quota_claims" }],
      where: {
        compositeFilter: {
          op: "AND",
          filters: [
            {
              fieldFilter: {
                field: { fieldPath: "pinnedSnapshotId" },
                op: "EQUAL",
                value: { stringValue: backupId }
              }
            },
            {
              fieldFilter: {
                field: { fieldPath: "pinStatus" },
                op: "EQUAL",
                value: { stringValue: "PINNED" }
              }
            }
          ]
        }
      }
    };

    const res = await fetch(queryUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ structuredQuery })
    });

    let activeClaimsCount = 0;
    if (res.ok) {
      const results = await res.json();
      activeClaimsCount = (results || []).filter(r => r.document).length;
    }

    const nowMs = Date.now();
    const manifestCount = parsedManifest.activeRestorePinCount;

    // Case 1: Healthy snapshot with discrepancy detected
    if (parsedManifest.quarantineStatus === null) {
      if (manifestCount !== activeClaimsCount) {
        if (manifestCount < activeClaimsCount) {
          // Undercount
          const writes = [{
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
              fields: {
                ...manifestDoc.fields,
                activeRestorePinCount: { integerValue: activeClaimsCount.toString() },
                quarantineStatus: { stringValue: "RESOLVED_UNDERCOUNT_OBSERVATION" },
                quarantineReason: { stringValue: `MANIFEST_${manifestCount}_VS_CLAIMS_${activeClaimsCount}` },
                quarantineInitiatedAtMs: { integerValue: nowMs.toString() },
                quarantineObservationExpiresAtMs: { integerValue: (nowMs + 24 * 3600 * 1000).toString() }
              }
            },
            currentDocument: { updateTime: manifestDoc.updateTime }
          }];
          await commitFirestoreBatch(writes, env);
          return { discrepancy: true, type: "UNDERCOUNT", latched: true };
        } else {
          // Overcount
          const writes = [{
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
              fields: {
                ...manifestDoc.fields,
                quarantineStatus: { stringValue: "RESOLVED_OVERCOUNT_OBSERVATION" },
                quarantineReason: { stringValue: `MANIFEST_${manifestCount}_VS_CLAIMS_${activeClaimsCount}` },
                quarantineInitiatedAtMs: { integerValue: nowMs.toString() },
                quarantineObservationExpiresAtMs: { integerValue: (nowMs + 48 * 3600 * 1000).toString() }
              }
            },
            currentDocument: { updateTime: manifestDoc.updateTime }
          }];
          await commitFirestoreBatch(writes, env);
          return { discrepancy: true, type: "OVERCOUNT", latched: true };
        }
      }
      return { discrepancy: false, healthy: true };
    }

    // Case 2: Snapshot already in quarantine observation
    if (parsedManifest.quarantineStatus !== null) {
      // Check clearance first if observation timer has elapsed
      if (parsedManifest.quarantineObservationExpiresAtMs && nowMs >= parsedManifest.quarantineObservationExpiresAtMs) {
        const canClear = (parsedManifest.quarantineStatus === "RESOLVED_UNDERCOUNT_OBSERVATION" && manifestCount === activeClaimsCount) ||
                         (parsedManifest.quarantineStatus === "RESOLVED_OVERCOUNT_OBSERVATION" && activeClaimsCount === 0);
        if (canClear) {
          const updatedFields = { ...manifestDoc.fields };
          updatedFields.quarantineStatus = { nullValue: null };
          updatedFields.quarantineReason = { nullValue: null };
          updatedFields.quarantineObservationExpiresAtMs = { nullValue: null };
          updatedFields.lastQuarantineClearedAtMs = { integerValue: nowMs.toString() };
          if (parsedManifest.quarantineStatus === "RESOLVED_OVERCOUNT_OBSERVATION") {
            updatedFields.activeRestorePinCount = { integerValue: "0" };
          }
          const writes = [{
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
              fields: updatedFields
            },
            currentDocument: { updateTime: manifestDoc.updateTime }
          }];
          await commitFirestoreBatch(writes, env);
          return { discrepancy: false, cleared: true };
        }
      }

      // Check if new discrepancy occurred
      const newDiscrepancy = (parsedManifest.quarantineStatus === "RESOLVED_UNDERCOUNT_OBSERVATION" && manifestCount !== activeClaimsCount) ||
                             (parsedManifest.quarantineStatus === "RESOLVED_OVERCOUNT_OBSERVATION" && activeClaimsCount > manifestCount);
      if (newDiscrepancy) {
        const writes = [{
          update: {
            name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
            fields: {
              ...manifestDoc.fields,
              activeRestorePinCount: { integerValue: Math.max(manifestCount, activeClaimsCount).toString() },
              quarantineStatus: { stringValue: "ACTIVE_DISCREPANCY_HOLD" },
              quarantineObservationExpiresAtMs: { integerValue: (nowMs + 24 * 3600 * 1000).toString() }
            }
          },
          currentDocument: { updateTime: manifestDoc.updateTime }
        }];
        await commitFirestoreBatch(writes, env);
        return { discrepancy: true, timerReset: true };
      }

      return { discrepancy: true, inObservation: true };
    }

    return { discrepancy: false };
  } catch (err) {
    console.error("Error in auditSnapshotPinConsistency:", err?.name || "Error");
    return { error: err?.message };
  }
}

async function pruneHistoricalCommittedBackups(env, uid, candidateBackupId) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { pruned: false, reason: "NO_KEY" };

  try {
    const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${candidateBackupId}`, env);
    const parsedManifest = parseManifestDoc(manifestDoc);
    if (!parsedManifest.exists) return { pruned: false, reason: "NOT_FOUND" };

    const nowMs = Date.now();
    if (parsedManifest.status !== "COMMITTED") return { pruned: false, reason: "NOT_COMMITTED" };
    if (parsedManifest.activeRestorePinCount !== 0) return { pruned: false, reason: "ACTIVE_PINS" };
    if (parsedManifest.quarantineStatus !== null) return { pruned: false, reason: "QUARANTINED" };
    if (parsedManifest.lastPinnedAtMs && parsedManifest.lastPinnedAtMs > (nowMs - 24 * 3600 * 1000)) {
      return { pruned: false, reason: "PIN_TTL_BUFFER" };
    }
    if (parsedManifest.committedAtMs && parsedManifest.committedAtMs > (nowMs - 48 * 3600 * 1000)) {
      return { pruned: false, reason: "COMMITTED_BUFFER" };
    }

    // Atomic OCC CAS: latch status to PURGING_RETENTION
    const latchWrites = [{
      update: {
        name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${candidateBackupId}`,
        fields: {
          ...manifestDoc.fields,
          status: { stringValue: "PURGING_RETENTION" },
          purgingStartedAtMs: { integerValue: nowMs.toString() }
        }
      },
      currentDocument: { updateTime: manifestDoc.updateTime }
    }];

    try {
      await commitFirestoreBatch(latchWrites, env);
    } catch (latchErr) {
      return { pruned: false, reason: "LATCH_OCC_FAILED", error: latchErr?.message };
    }

    // Batched deletion of child records in backup_snapshots/${candidateBackupId}/daily_data
    const children = await listFirestoreDocuments(`users/${uid}/backup_snapshots/${candidateBackupId}/daily_data`, env, 200);
    if (children.length > 0) {
      const deleteWrites = children.map(c => ({
        delete: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_snapshots/${candidateBackupId}/daily_data/${c.name.split("/").pop()}`
      }));
      await commitFirestoreBatch(deleteWrites, env);
    }

    // Delete manifest document and parent snapshot document
    await Promise.all([
      deleteFirestoreDocument(`users/${uid}/backup_manifests/${candidateBackupId}`, env),
      deleteFirestoreDocument(`users/${uid}/backup_snapshots/${candidateBackupId}`, env)
    ]);

    return { pruned: true, backupId: candidateBackupId };
  } catch (err) {
    console.error("Error in pruneHistoricalCommittedBackups:", err?.name || "Error");
    return { pruned: false, error: err?.message };
  }
}

async function cleanupAbandonedUploads(env, uid, candidateBackupId) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { cleaned: false, reason: "NO_KEY" };

  try {
    const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${candidateBackupId}`, env);
    const parsedManifest = parseManifestDoc(manifestDoc);
    if (!parsedManifest.exists) return { cleaned: false, reason: "NOT_FOUND" };

    const nowMs = Date.now();
    if (parsedManifest.status !== "UPLOADING") return { cleaned: false, reason: "NOT_UPLOADING" };
    if (parsedManifest.uploadInitiatedAtMs && parsedManifest.uploadInitiatedAtMs > (nowMs - 24 * 3600 * 1000)) {
      return { cleaned: false, reason: "AGE_LESS_THAN_24H" };
    }

    // Check backup/status activeBackupId
    const statusDoc = await getFirestoreBackupStatus(uid, env);
    const parsedStatus = parseBackupStatusDoc(statusDoc);
    if (parsedStatus.activeBackupId === candidateBackupId) {
      return { cleaned: false, reason: "IS_ACTIVE_BACKUP" };
    }

    // Atomic latch: status -> PURGING_ABANDONED
    const latchWrites = [{
      update: {
        name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${candidateBackupId}`,
        fields: {
          ...manifestDoc.fields,
          status: { stringValue: "PURGING_ABANDONED" },
          purgingStartedAtMs: { integerValue: nowMs.toString() }
        }
      },
      currentDocument: { updateTime: manifestDoc.updateTime }
    }];

    try {
      await commitFirestoreBatch(latchWrites, env);
    } catch (latchErr) {
      return { cleaned: false, reason: "LATCH_OCC_FAILED", error: latchErr?.message };
    }

    // Delete child records
    const children = await listFirestoreDocuments(`users/${uid}/backup_snapshots/${candidateBackupId}/daily_data`, env, 200);
    if (children.length > 0) {
      const deleteWrites = children.map(c => ({
        delete: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_snapshots/${candidateBackupId}/daily_data/${c.name.split("/").pop()}`
      }));
      await commitFirestoreBatch(deleteWrites, env);
    }

    // Delete manifest and parent snapshot document
    await Promise.all([
      deleteFirestoreDocument(`users/${uid}/backup_manifests/${candidateBackupId}`, env),
      deleteFirestoreDocument(`users/${uid}/backup_snapshots/${candidateBackupId}`, env)
    ]);

    return { cleaned: true, backupId: candidateBackupId };
  } catch (err) {
    console.error("Error in cleanupAbandonedUploads:", err?.name || "Error");
    return { cleaned: false, error: err?.message };
  }
}

async function verifyUserSubscriptionEntitlement(uid, env) {
  const accountDoc = await getFirestoreAccountStatus(uid, env);
  if (accountDoc?.error) {
    return { entitled: false, reason: "Account status verification unavailable. Please try again." };
  }
  const accountStatus = accountDoc?.accountStatus?.stringValue || "ACTIVE";
  if (accountStatus === "PENDING_DELETION" || accountStatus === "DELETED" || accountStatus === "RESTRICTED") {
    return { entitled: false, reason: `Account status is ${accountStatus}. Access locked.` };
  }

  const subFields = await getFirestoreSubscription(uid, env);
  if (subFields?.error) {
    return { entitled: false, reason: "Subscription status verification unavailable. Please try again." };
  }
  if (!subFields || !subFields.exists) {
    return { entitled: false, reason: "No active subscription found. Trial or Premium entitlement required." };
  }

  const status = subFields.subscriptionStatus?.stringValue || subFields.status?.stringValue || "NONE";
  const nowMs = Date.now();

  let currentPeriodEndMs = 0;
  const rawEnd = subFields.currentPeriodEnd || subFields.currentPeriodEndDate || subFields.expiryDate || subFields.expiresAt || subFields.expirationDate || subFields.premiumExpiry || subFields.validUntil || subFields.current_period_end;
  if (rawEnd?.timestampValue) {
    currentPeriodEndMs = new Date(rawEnd.timestampValue).getTime();
  } else if (rawEnd?.integerValue) {
    const num = parseInt(rawEnd.integerValue, 10);
    currentPeriodEndMs = num < 1e12 ? num * 1000 : num;
  } else if (rawEnd?.stringValue) {
    currentPeriodEndMs = new Date(rawEnd.stringValue).getTime();
  }

  let trialEndMs = 0;
  const rawTrial = subFields.trialEnd || subFields.trialEndDate || subFields.trialExpiresAt || subFields.trialExpiry || subFields.trial_end;
  if (rawTrial?.timestampValue) {
    trialEndMs = new Date(rawTrial.timestampValue).getTime();
  } else if (rawTrial?.integerValue) {
    const num = parseInt(rawTrial.integerValue, 10);
    trialEndMs = num < 1e12 ? num * 1000 : num;
  } else if (rawTrial?.stringValue) {
    trialEndMs = new Date(rawTrial.stringValue).getTime();
  }

  const isPaidActive = status !== "HALTED" && currentPeriodEndMs > 0 && nowMs < currentPeriodEndMs;

  const isTrialActive = !isPaidActive && status !== "HALTED" && trialEndMs > 0 && nowMs < trialEndMs;

  if (isPaidActive) {
    return { entitled: true, mode: "ACTIVE" };
  } else if (isTrialActive) {
    return { entitled: true, mode: "TRIAL" };
  } else {
    return { entitled: false, reason: "Backup requires an active Trial or Premium entitlement." };
  }
}

// ---------------------------------------------------------------------------
// ASYMMETRIC SERVER-SIGNED LEASE CREDENTIAL (ECDSA P-256 / SHA-256)
// ---------------------------------------------------------------------------

let cachedLeaseSigningKey = null;
let cachedPkcs8Str = null;

async function getLeaseSigningKey(env) {
  const pkcs8Base64 = env?.LEASE_PRIVATE_KEY_PKCS8;
  if (!pkcs8Base64 || typeof pkcs8Base64 !== "string" || !pkcs8Base64.trim()) {
    console.error("LEASE_PRIVATE_KEY_PKCS8 is not configured in Worker environment.");
    throw new Error("ServerConfigurationError: LEASE_PRIVATE_KEY_PKCS8 secret is missing.");
  }
  const cleanBase64 = pkcs8Base64.trim();
  if (cachedLeaseSigningKey && cachedPkcs8Str === cleanBase64) {
    return cachedLeaseSigningKey;
  }
  const binary = atob(cleanBase64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  cachedLeaseSigningKey = await crypto.subtle.importKey(
    "pkcs8",
    bytes.buffer,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"]
  );
  cachedPkcs8Str = cleanBase64;
  return cachedLeaseSigningKey;
}

function p1363ToDer(p1363Bytes) {
  const r = p1363Bytes.slice(0, 32);
  const s = p1363Bytes.slice(32, 64);
  function formatInteger(bytes) {
    let start = 0;
    while (start < bytes.length - 1 && bytes[start] === 0) start++;
    let slice = bytes.slice(start);
    if (slice[0] >= 0x80) {
      const padded = new Uint8Array(slice.length + 1);
      padded[0] = 0x00;
      padded.set(slice, 1);
      return padded;
    }
    return slice;
  }
  const rFormatted = formatInteger(r);
  const sFormatted = formatInteger(s);
  const totalLen = 2 + rFormatted.length + 2 + sFormatted.length;
  const der = new Uint8Array(2 + totalLen);
  der[0] = 0x30; // SEQUENCE
  der[1] = totalLen;
  der[2] = 0x02; // INTEGER
  der[3] = rFormatted.length;
  der.set(rFormatted, 4);
  const sOffset = 4 + rFormatted.length;
  der[sOffset] = 0x02; // INTEGER
  der[sOffset + 1] = sFormatted.length;
  der.set(sFormatted, sOffset + 2);
  return der;
}

async function signLeasePayload(payloadStr, env) {
  const key = await getLeaseSigningKey(env);
  const encoder = new TextEncoder();
  const data = encoder.encode(payloadStr);
  const p1363Sig = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    data
  ));
  const derSig = p1363ToDer(p1363Sig);
  let binary = "";
  for (let i = 0; i < derSig.length; i++) {
    binary += String.fromCharCode(derSig[i]);
  }
  return btoa(binary);
}

// Lookup Firebase UID by subscription ID from the subscription_map collection
async function lookupSubscriptionUid(subscriptionId, env) {
  if (!subscriptionId) return null;
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY missing for subscription owner lookup.");

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/subscription_map/${encodeURIComponent(subscriptionId)}`;
  const res = await fetch(docUrl, {
    method: "GET",
    headers: { "Authorization": `Bearer ${accessToken}` }
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`Subscription owner lookup temporarily unavailable (${res.status}).`);
  const doc = await res.json();
  return doc.fields?.firebaseUid?.stringValue || null;
}

// Record subscription ID -> Firebase UID mapping in Firestore for robust webhook resolution
async function recordSubscriptionUidMap(subscriptionId, uid, planId, env) {
  if (!subscriptionId || !uid) return { success: false, reason: "MISSING_PARAMS" };
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { success: false, reason: "NO_SA_KEY" };

  const fields = {
    firebaseUid: { stringValue: uid },
    planId: { stringValue: planId || "" },
    createdAt: { timestampValue: new Date().toISOString() }
  };
  const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/subscription_map/${encodeURIComponent(subscriptionId)}`;

  const maxAttempts = 2;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const accessToken = await getGoogleServiceAccountAccessToken(saKey);
      const res = await fetch(docUrl, {
        method: "PATCH",
        headers: {
          "Authorization": `Bearer ${accessToken}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ fields })
      });

      if (res.ok) {
        return { success: true };
      }

      if (res.status >= 500 && attempt < maxAttempts) {
        console.warn(`Transient error (${res.status}) writing subscription map, retrying...`);
        continue;
      }

      console.warn(`Failed writing subscription map: HTTP ${res.status}`);
      return { success: false, reason: `HTTP_${res.status}` };
    } catch (err) {
      if (attempt < maxAttempts) {
        console.warn("Network error writing subscription map, retrying...");
        continue;
      }
      console.warn("Error writing subscription map:", err?.name || "Error");
      return { success: false, reason: "NETWORK_ERROR" };
    }
  }

  return { success: false, reason: "MAX_RETRIES_EXCEEDED" };
}

// Validate Razorpay credentials against environment and key mode requirements
function validateRazorpayCredentials(env) {
  const rawKeyId = env.RAZORPAY_KEY_ID?.trim();
  const rawKeySecret = env.RAZORPAY_KEY_SECRET?.trim();

  const environment = env.ENVIRONMENT;
  if (!['test', 'staging', 'production'].includes(environment)) {
    return { valid: false, error: "Worker ENVIRONMENT must be explicitly set to test, staging, or production." };
  }

  if (!rawKeyId || !rawKeySecret) {
    return { valid: false, error: "Razorpay credentials not configured in Worker environment." };
  }

  const isStaging = environment === "staging";
  const isProduction = environment === "production";

  if (environment === "test" || isStaging) {
    if (!rawKeyId.startsWith("rzp_test_")) {
      return {
        valid: false,
        error: `${environment === "staging" ? "Staging" : "Test"} environment requires Razorpay test key (rzp_test_*). Live key rejected.`
      };
    }
  } else if (isProduction) {
    if (rawKeyId.startsWith("rzp_test_")) {
      return {
        valid: false,
        error: "Production environment rejects Razorpay test key (rzp_test_*). Live key required."
      };
    }
    if (!rawKeyId.startsWith("rzp_live_")) {
      return {
        valid: false,
        error: "Production environment requires valid Razorpay live key (rzp_live_*)."
      };
    }
  }

  return { valid: true, keyId: rawKeyId, keySecret: rawKeySecret };
}

// Validate Razorpay plan ID against environment and isolation requirements
function validateRazorpayPlanId(env) {
  const planId = env.RAZORPAY_PLAN_ID?.trim();
  const environment = env.ENVIRONMENT;

  if (!planId) {
    return { valid: false, error: "Server payment configuration missing plan ID." };
  }

  if (planId.toUpperCase().includes("PLACEHOLDER")) {
    return { valid: false, error: "Server payment configuration incomplete: Razorpay plan ID contains placeholder." };
  }

  if (environment === "staging") {
    if (planId === "plan_TdtgehXIhzQ8U9") {
      return {
        valid: false,
        error: "CRITICAL ISOLATION FAULT: Staging environment cannot use production Razorpay plan ID ('plan_TdtgehXIhzQ8U9')."
      };
    }
  }

  if (environment === "production") {
    if (planId !== "plan_TdtgehXIhzQ8U9") {
      return {
        valid: false,
        error: "Production environment requires authoritative production Razorpay plan ID ('plan_TdtgehXIhzQ8U9')."
      };
    }
  }

  return { valid: true, planId };
}

// Query subscription_map for all subscription IDs associated with a Firebase UID
async function findSubscriptionsByUid(uid, env) {
  if (!uid) return [];
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return [];

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const queryUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:runQuery`;

    const structuredQuery = {
      from: [{ collectionId: "subscription_map" }],
      where: {
        fieldFilter: {
          field: { fieldPath: "firebaseUid" },
          op: "EQUAL",
          value: { stringValue: uid }
        }
      },
      limit: 10
    };

    const res = await fetch(queryUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ structuredQuery })
    });

    if (!res.ok) {
      console.warn(`Firestore runQuery for subscription_map failed (${res.status})`);
      return [];
    }

    const queryResults = await res.json();
    const subIds = [];
    for (const item of queryResults) {
      if (!item.document || !item.document.name) continue;
      const parts = item.document.name.split("/");
      const subId = parts[parts.length - 1];
      if (subId && subId.startsWith("sub_")) {
        subIds.push(subId);
      }
    }
    return subIds;
  } catch (err) {
    console.warn("Error querying subscription_map by UID:", err?.name || "Error");
    return [];
  }
}

// Query daily_quota_claims for all claim documents associated with a Firebase UID
async function findDailyQuotaClaimsByUid(uid, env) {
  if (!uid || typeof uid !== "string") return [];
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return [];

  const foundClaims = new Set();
  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const queryUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:runQuery`;

    const structuredQuery = {
      from: [{ collectionId: "daily_quota_claims" }],
      where: {
        fieldFilter: {
          field: { fieldPath: "uid" },
          op: "EQUAL",
          value: { stringValue: uid }
        }
      },
      limit: 500
    };

    const res = await fetch(queryUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ structuredQuery })
    });

    if (res.ok) {
      const results = await res.json();
      if (Array.isArray(results)) {
        for (const item of results) {
          if (!item.document || !item.document.name) continue;
          const parts = item.document.name.split("/documents/");
          const docPath = parts[1];
          if (docPath && docPath.startsWith(`daily_quota_claims/${uid}_`)) {
            foundClaims.add(docPath);
          }
        }
      }
    }
  } catch (err) {
    console.warn("Error querying daily_quota_claims by UID:", err?.name || "Error");
  }

  return Array.from(foundClaims);
}

// Fetch a subscription entity from Razorpay and verify ownership against the calling UID
async function fetchAndVerifyRazorpaySubscription(subscriptionId, uid, env) {
  if (!subscriptionId || !uid) return null;
  const creds = validateRazorpayCredentials(env);
  if (!creds.valid) throw new Error("Subscription recovery credentials are unavailable.");

  let res;
  try {
    const authHeaderValue = "Basic " + btoa(`${creds.keyId}:${creds.keySecret}`);
    res = await fetch(`https://api.razorpay.com/v1/subscriptions/${encodeURIComponent(subscriptionId)}`, {
      headers: { "Authorization": authHeaderValue }
    });
  } catch (err) {
    throw new Error("Razorpay subscription recovery lookup failed temporarily.");
  }

  if (res.status === 404) return null;
  if (res.status === 429 || res.status >= 500 || res.status === 401 || res.status === 403) {
    throw new Error(`Razorpay subscription recovery is temporarily unavailable (${res.status}).`);
  }
  if (!res.ok) return null;

  let subscription;
  try {
    subscription = await res.json();
  } catch (err) {
    throw new Error("Razorpay subscription recovery returned malformed data.");
  }
  if (!subscription || typeof subscription !== "object" || Array.isArray(subscription) || subscription.id !== subscriptionId) {
    throw new Error("Razorpay subscription recovery returned an invalid entity.");
  }

  const noteUid = subscription.notes?.firebase_uid;
  const mappedUid = await lookupSubscriptionUid(subscription.id, env);
  if (noteUid && mappedUid && noteUid !== mappedUid) {
    console.warn("fetchAndVerifyRazorpaySubscription rejected: ownership conflict.");
    return null;
  }
  const ownerUid = noteUid || mappedUid;
  if (ownerUid === uid) return subscription;

  console.warn("fetchAndVerifyRazorpaySubscription rejected: ownership mismatch.");
  return null;
}

// Read master account status from Firestore REST API
async function getFirestoreAccountTombstone(uid, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { exists: false, error: true, status: 500, message: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/account_tombstones/${uid}`;
    const res = await fetch(docUrl, {
      method: "GET",
      headers: { "Authorization": `Bearer ${accessToken}` }
    });

    if (res.status === 200) {
      const doc = await res.json();
      return { exists: true, error: false, fields: doc.fields || {}, updateTime: doc.updateTime };
    }

    if (res.status === 404) {
      return { exists: false, error: false };
    }

    console.warn(`Firestore GET account_tombstones returned ${res.status}`);
    return { exists: false, error: true, status: res.status };
  } catch (err) {
    console.error("Error reading account_tombstones:", err?.name || "Error");
    return { exists: false, error: true, errorMsg: err?.name || "Network error" };
  }
}

async function recordFirestoreAccountTombstone(uid, fields, env, options = {}) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { success: false, error: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const fieldNames = Object.keys(fields);
    const updateMaskParams = fieldNames.map(f => `updateMask.fieldPaths=${f}`).join("&");
    let docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/account_tombstones/${uid}?${updateMaskParams}`;

    if (options.expectedUpdateTime) {
      docUrl += `&currentDocument.updateTime=${encodeURIComponent(options.expectedUpdateTime)}`;
    } else if (options.exists !== undefined) {
      docUrl += `&currentDocument.exists=${options.exists ? "true" : "false"}`;
    }

    const res = await fetch(docUrl, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ fields })
    });

    if (res.status === 409 || res.status === 412) {
      return { success: false, status: res.status, conflict: true, error: "PRECONDITION_FAILED" };
    }

    if (!res.ok) {
      console.warn(`Firestore PATCH account_tombstones failed: HTTP ${res.status}`);
      return { success: false, status: res.status, error: `HTTP_${res.status}` };
    }

    const resJson = await res.json().catch(() => ({}));
    return { success: true, status: res.status, updateTime: resJson.updateTime };
  } catch (err) {
    console.error("Error writing account_tombstones:", err?.name || "Error");
    return { success: false, error: err?.name || "Network error" };
  }
}

const PURGE_LEASE_TTL_MS = 60000; // 60 seconds bounded lease for in-flight account purges

async function claimPurgeLockAtomic(uid, nowMs, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY not configured");

  const currentDoc = await getFirestoreAccountTombstone(uid, env);
  if (currentDoc.error) {
    return { claimed: false, error: true, status: currentDoc.status, reason: "FIRESTORE_READ_ERROR" };
  }

  let existingPendingSubId = null;
  let originalDeletedAt = null;

  if (currentDoc.exists && currentDoc.fields) {
    const purgeState = currentDoc.fields.purgeState?.stringValue;
    const accountStatus = currentDoc.fields.accountStatus?.stringValue;
    if (purgeState === "CANCELLED" || accountStatus === "ACTIVE") {
      return { claimed: false, cancelled: true, reason: "ACCOUNT_ACTIVE_OR_CANCELLED" };
    }
    if (purgeState === "COMPLETED") {
      return { claimed: false, alreadyCompleted: true, purgeState: "COMPLETED" };
    }

    const leaseExpiresAtMs = currentDoc.fields.leaseExpiresAtMs?.integerValue
      ? Number(currentDoc.fields.leaseExpiresAtMs.integerValue)
      : (currentDoc.fields.expiresAtMs?.integerValue ? Number(currentDoc.fields.expiresAtMs.integerValue) : 0);

    const safeUpdateTimeMs = currentDoc.fields.updatedAt?.timestampValue
      ? new Date(currentDoc.fields.updatedAt.timestampValue).getTime()
      : (currentDoc.updateTime ? new Date(currentDoc.updateTime).getTime() : 0);
    const isPastUpdatedThreshold = Number.isFinite(safeUpdateTimeMs) && (nowMs - safeUpdateTimeMs) >= (15 * 60 * 1000);

    // Active lease held by another in-flight worker:
    // Must be within 60s lease window AND within 15-minute updated threshold
    if (purgeState === "IN_PROGRESS" && (nowMs < leaseExpiresAtMs) && !isPastUpdatedThreshold) {
      return { claimed: false, inProgress: true, reason: "CONCURRENT_PURGE_IN_PROGRESS" };
    }

    existingPendingSubId = currentDoc.fields.pendingSubscriptionId?.stringValue || null;
    originalDeletedAt = currentDoc.fields.deletedAt?.timestampValue || null;
  }

  const claimId = crypto.randomUUID ? crypto.randomUUID() : (Math.random().toString(36).substring(2) + Date.now().toString(36));
  const leaseExpiresAtMs = nowMs + PURGE_LEASE_TTL_MS;
  const nowIso = new Date(nowMs).toISOString();
  const deletedAtIso = originalDeletedAt || nowIso;

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const fields = {
    uid: { stringValue: uid },
    accountStatus: { stringValue: "DELETED" },
    purgeState: { stringValue: "IN_PROGRESS" },
    claimId: { stringValue: claimId },
    lockedAtMs: { integerValue: nowMs.toString() },
    leaseExpiresAtMs: { integerValue: leaseExpiresAtMs.toString() },
    updatedAt: { timestampValue: nowIso },
    deletedAt: { timestampValue: deletedAtIso }
  };
  if (existingPendingSubId) {
    fields.pendingSubscriptionId = { stringValue: existingPendingSubId };
  }

  const fieldPaths = Object.keys(fields);
  const updateMask = fieldPaths.map(p => `updateMask.fieldPaths=${p}`).join("&");
  let docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/account_tombstones/${uid}?${updateMask}`;

  if (!currentDoc.exists) {
    docUrl += "&currentDocument.exists=false";
  } else if (currentDoc.updateTime) {
    docUrl += `&currentDocument.updateTime=${encodeURIComponent(currentDoc.updateTime)}`;
  }

  const res = await fetch(docUrl, {
    method: "PATCH",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ fields })
  });

  if (res.status === 409 || res.status === 412) {
    return { claimed: false, inProgress: true, reason: "CONCURRENT_CLAIM_CONFLICT" };
  }

  if (!res.ok) {
    console.warn(`Firestore claim purge lock failed: HTTP ${res.status}`);
    return { claimed: false, error: true, status: res.status };
  }

  const resData = await res.json().catch(() => ({}));
  const acquiredUpdateTime = resData.updateTime;
  if (!acquiredUpdateTime) {
    throw new Error("Firestore PATCH succeeded but returned no updateTime revision.");
  }

  return {
    claimed: true,
    claimId,
    updateTime: acquiredUpdateTime,
    existingPendingSubId,
    originalDeletedAt: deletedAtIso
  };
}

async function finalizePurgeTombstoneAtomic(uid, fields, expectedUpdateTime, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { success: false, error: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const fieldNames = Object.keys(fields);
    const updateMaskParams = fieldNames.map(f => `updateMask.fieldPaths=${f}`).join("&");
    let docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/account_tombstones/${uid}?${updateMaskParams}`;

    if (expectedUpdateTime) {
      docUrl += `&currentDocument.updateTime=${encodeURIComponent(expectedUpdateTime)}`;
    }

    const res = await fetch(docUrl, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ fields })
    });

    if (res.status === 409 || res.status === 412) {
      console.warn("Purge finalization superseded by another revision or expired lease.");
      // Check current doc to verify if newer worker already completed it
      const currentDoc = await getFirestoreAccountTombstone(uid, env);
      if (currentDoc.exists && currentDoc.fields?.purgeState?.stringValue === "COMPLETED") {
        return { success: true, superseded: true, status: 200 };
      }
      return { success: false, superseded: true, status: res.status };
    }

    if (!res.ok) {
      console.warn(`Firestore PATCH finalize tombstone failed: HTTP ${res.status}`);
      return { success: false, status: res.status, error: `HTTP_${res.status}` };
    }

    return { success: true, status: res.status };
  } catch (err) {
    console.error("Error finalizing account tombstone:", err?.name || "Error");
    return { success: false, error: err?.name || "Network error" };
  }
}

async function getFirestoreAccountStatus(uid, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { exists: false, error: true, status: 500, message: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${uid}/account/status`;
    const res = await fetch(docUrl, {
      method: "GET",
      headers: { "Authorization": `Bearer ${accessToken}` }
    });

    if (res.status === 200) {
      const doc = await res.json();
      const fields = doc.fields || {};
      return { ...fields, exists: true, error: false, fields, updateTime: doc.updateTime };
    }

    if (res.status !== 404) {
      // 5xx, 401, 403, 429, etc. -> FAIL CLOSED!
      console.warn(`Firestore GET account status returned ${res.status}`);
      return { exists: false, error: true, status: res.status };
    }

    // users/${uid}/account/status returned 404.
    // Check durable tombstone collection to distinguish fresh active accounts from deleted accounts.
    const tombstoneResult = await getFirestoreAccountTombstone(uid, env);
    if (tombstoneResult.error) {
      // Tombstone check failed (e.g. 503, 500, or network error).
      // DO NOT treat as confirmed absence! FAIL CLOSED!
      console.warn("Tombstone lookup failed during account status check; failing closed.");
      return { exists: false, error: true, status: tombstoneResult.status || 503 };
    }

    if (tombstoneResult.exists) {
      // Tombstone found! Self-heal status doc and return DELETED tombstone
      const tombstoneFields = tombstoneResult.fields || {};
      updateFirestoreAccountStatus(uid, tombstoneFields, env).catch(() => {});
      return { ...tombstoneFields, exists: true, error: false, fields: tombstoneFields, updateTime: tombstoneResult.updateTime };
    }

    // Confirmed absence: both status doc and tombstone doc returned 404
    return { exists: false, error: false };
  } catch (err) {
    console.error("Error reading Firestore account status:", err?.name || "Error");
    return { exists: false, error: true, errorMsg: err?.name || "Network error" };
  }
}

async function updateFirestoreAccountStatus(uid, fields, env, options = {}) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY secret not configured in Cloudflare Worker.");
  }

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const fieldNames = Object.keys(fields);
  const updateMaskParams = fieldNames.map(f => `updateMask.fieldPaths=${f}`).join("&");

  let docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${uid}/account/status?${updateMaskParams}`;

  if (options.expectedUpdateTime) {
    docUrl += `&currentDocument.updateTime=${encodeURIComponent(options.expectedUpdateTime)}`;
  } else if (options.exists !== undefined) {
    docUrl += `&currentDocument.exists=${options.exists ? "true" : "false"}`;
  }

  const res = await fetch(docUrl, {
    method: "PATCH",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ fields })
  });

  if (res.status === 409 || res.status === 412) {
    const err = new Error(`Firestore REST PATCH account status precondition failed: HTTP ${res.status}`);
    err.status = res.status;
    err.conflict = true;
    throw err;
  }

  if (!res.ok) {
    throw new Error(`Firestore REST PATCH account status failed: HTTP ${res.status}`);
  }

  return await res.json();
}

async function deleteFirestoreDocument(docPath, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { success: false, error: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/${docPath}`;

    const res = await fetch(docUrl, {
      method: "DELETE",
      headers: { "Authorization": `Bearer ${accessToken}` }
    });

    if (res.ok || res.status === 404) {
      return { success: true, status: res.status };
    }

    console.warn(`Failed to delete Firestore document: HTTP ${res.status}`);
    return { success: false, status: res.status };
  } catch (err) {
    console.warn("Error deleting Firestore document:", err?.name || "Error");
    return { success: false, error: err?.name || "Network error" };
  }
}

async function deleteFirestoreSubcollection(parentPath, collectionId, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { success: false, error: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    let pageToken = "";
    let hadError = false;
    do {
      const listUrl = new URL(`https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/${parentPath}/${collectionId}`);
      listUrl.searchParams.set("pageSize", "100");
      listUrl.searchParams.set("showMissing", "true");
      if (pageToken) listUrl.searchParams.set("pageToken", pageToken);

      const res = await fetch(listUrl.toString(), {
        headers: { "Authorization": `Bearer ${accessToken}` }
      });

      if (res.status === 404) break;
      if (!res.ok) {
        console.warn(`Failed to list collection ${collectionId}: HTTP ${res.status}`);
        hadError = true;
        break;
      }

      const data = await res.json();
      const documents = data.documents || [];
      for (const doc of documents) {
        const fullDocName = doc.name;
        const relativePath = fullDocName.split("/databases/(default)/documents/")[1];
        if (relativePath) {
          const delRes = await deleteFirestoreDocument(relativePath, env);
          if (!delRes?.success) {
            hadError = true;
          }
        }
      }

      pageToken = data.nextPageToken || "";
    } while (pageToken);

    return { success: !hadError };
  } catch (err) {
    console.warn(`Error during collection purge of ${collectionId}:`, err?.name || "Error");
    return { success: false, error: err?.name || "Network error" };
  }
}

async function purgeAccountData(uid, env) {
  console.log("Executing permanent purge of account data.");
  const errors = [];
  let gatewaySuccess = true;
  let firestoreSuccess = true;
  let statusWriteSuccess = false;
  let tombstoneWriteSuccess = false;

  const nowMs = Date.now();
  const now = new Date(nowMs);
  const nowIso = now.toISOString();

  // 0. Acquire atomic claim & bounded lease on the account tombstone
  const claimResult = await claimPurgeLockAtomic(uid, nowMs, env);
  if (!claimResult.claimed) {
    if (claimResult.alreadyCompleted) {
      console.log("Permanent purge already completed by prior execution.");
      return {
        success: true,
        purgeState: "COMPLETED",
        alreadyCompleted: true
      };
    }
    if (claimResult.cancelled) {
      console.log("Permanent purge skipped: account is ACTIVE or deletion was CANCELLED.");
      return {
        success: true,
        cancelled: true,
        purgeState: "CANCELLED"
      };
    }
    if (claimResult.inProgress) {
      console.warn("Purge currently in progress by another worker; skipping duplicate concurrent execution.");
      return {
        success: false,
        purgeState: "IN_PROGRESS",
        inProgress: true,
        reason: claimResult.reason || "CONCURRENT_PURGE_IN_PROGRESS",
        errors: ["Concurrent purge in progress"]
      };
    }
    // Claim failed due to Firestore error. Attempt to write status document so DELETED state is durable
    let fallbackStatusSuccess = false;
    try {
      await updateFirestoreAccountStatus(uid, {
        accountStatus: { stringValue: "DELETED" },
        deletedAt: { timestampValue: nowIso },
        updatedAt: { timestampValue: nowIso },
        purgeState: { stringValue: "PENDING_RETRY" }
      }, env);
      fallbackStatusSuccess = true;
    } catch (e) {
      errors.push("Failed updating fallback account status");
    }

    return {
      success: false,
      purgeState: "PENDING_RETRY",
      tombstoneWritten: fallbackStatusSuccess,
      errors: ["Failed to acquire purge lock"]
    };
  }

  const { claimId, updateTime: claimUpdateTime, existingPendingSubId, originalDeletedAt } = claimResult;
  tombstoneWriteSuccess = true;

  // Also sync users/{uid}/account/status
  const statusDocFields = {
    accountStatus: { stringValue: "DELETED" },
    deletedAt: { timestampValue: originalDeletedAt },
    updatedAt: { timestampValue: nowIso },
    purgeState: { stringValue: "IN_PROGRESS" }
  };
  if (existingPendingSubId) {
    statusDocFields.pendingSubscriptionId = { stringValue: existingPendingSubId };
  }

  try {
    await updateFirestoreAccountStatus(uid, statusDocFields, env);
    statusWriteSuccess = true;
  } catch (err) {
    errors.push("Failed writing account status DELETED");
  }

  // 1. If active Razorpay recurring subscriptions exist, cancel mandates at gateway
  const candidateSubIds = new Set();
  if (existingPendingSubId && existingPendingSubId.startsWith("sub_")) {
    candidateSubIds.add(existingPendingSubId);
  }
  let activeSubId = existingPendingSubId;
  try {
    const subDoc = await getFirestoreSubscription(uid, env);
    if (subDoc?.error) {
      firestoreSuccess = false;
      errors.push("Failed reading subscription status during purge: service unavailable");
    } else if (subDoc?.exists && subDoc?.subscriptionId?.stringValue) {
      const sId = subDoc.subscriptionId.stringValue;
      if (sId.startsWith("sub_")) {
        candidateSubIds.add(sId);
        activeSubId = sId;
      }
    }
  } catch (subErr) {
    firestoreSuccess = false;
    errors.push(`Error checking subscription for purge: ${subErr?.name || "Error"}`);
    console.warn("Non-fatal: Error checking subscription for purge:", subErr?.name || "Error");
  }

  try {
    const mappedSubIds = await findSubscriptionsByUid(uid, env);
    for (const mId of mappedSubIds) {
      if (mId.startsWith("sub_")) candidateSubIds.add(mId);
    }
  } catch (mapErr) {
    firestoreSuccess = false;
    errors.push(`Error querying subscription_map for candidate subscriptions: ${mapErr?.name || "Error"}`);
    console.warn("Non-fatal: Error querying subscription_map for candidate subscriptions:", mapErr?.name || "Error");
  }

  const successfullyCancelledSubIds = new Set();
  const failedSubIds = new Set();

  for (const sId of candidateSubIds) {
    if (sId && sId.startsWith("sub_")) {
      const credValidation = validateRazorpayCredentials(env);
      if (!credValidation.valid) {
        failedSubIds.add(sId);
        errors.push(`Gateway credentials invalid or missing for subscription ${sId}: ${credValidation.error}`);
        console.warn(`Cannot cancel gateway subscription ${sId}: ${credValidation.error}`);
        continue;
      }
      const authHeaderValue = "Basic " + btoa(`${credValidation.keyId}:${credValidation.keySecret}`);
      const cancelUrl = `https://api.razorpay.com/v1/subscriptions/${sId}/cancel`;
      try {
        const rzpRes = await fetch(cancelUrl, {
          method: "POST",
          headers: { "Authorization": authHeaderValue, "Content-Type": "application/json" },
          body: JSON.stringify({ cancel_at_cycle_end: 0 }) // Immediate mandate cancellation on permanent purge
        });

        if (rzpRes.ok || rzpRes.status === 404) {
          successfullyCancelledSubIds.add(sId);
        } else if (rzpRes.status === 400) {
          let errDesc = "";
          try {
            const errData = await rzpRes.json();
            errDesc = (errData?.error?.description || "").toLowerCase();
          } catch (_) {
            errDesc = (await rzpRes.text().catch(() => "")).toLowerCase();
          }

          const isExplicitTerminal = errDesc.includes("already cancelled") ||
                                     errDesc.includes("in cancelled state") ||
                                     errDesc.includes("already completed") ||
                                     errDesc.includes("in completed state") ||
                                     errDesc.includes("already expired") ||
                                     errDesc.includes("in expired state");

          if (isExplicitTerminal) {
            successfullyCancelledSubIds.add(sId);
          } else {
            // Ambiguous HTTP 400 (e.g. generic "cannot be cancelled"): verify authoritative state via GET
            console.warn("Ambiguous gateway cancellation response; verifying authoritative status via GET");
            try {
              const getRes = await fetch(`https://api.razorpay.com/v1/subscriptions/${sId}`, {
                method: "GET",
                headers: { "Authorization": authHeaderValue }
              });
              if (getRes.ok) {
                const subData = await getRes.json();
                const subStatus = (subData?.status || "").toLowerCase();
                if (subStatus === "cancelled" || subStatus === "completed" || subStatus === "expired") {
                  console.log(`Authoritative subscription check confirmed terminal status: ${subStatus}`);
                  successfullyCancelledSubIds.add(sId);
                } else {
                  failedSubIds.add(sId);
                  errors.push(`Gateway cancellation rejected for ${sId}: status is ${subStatus}`);
                  console.warn(`Authoritative subscription status is ${subStatus}; not terminal.`);
                }
              } else {
                failedSubIds.add(sId);
                errors.push(`Gateway status verification failed for ${sId}: HTTP ${getRes.status}`);
              }
            } catch (getErr) {
              failedSubIds.add(sId);
              errors.push(`Gateway status verification network error for ${sId}`);
            }
          }
        } else {
          failedSubIds.add(sId);
          errors.push(`Gateway cancel failed for ${sId}: HTTP ${rzpRes.status}`);
          console.warn(`Gateway subscription cancel failed during purge for ${sId}: HTTP ${rzpRes.status}`);
        }
      } catch (cancelErr) {
        failedSubIds.add(sId);
        errors.push(`Gateway cancel network error for ${sId}`);
        console.warn(`Gateway cancel exception for ${sId}:`, cancelErr?.name || "Error");
      }
    }
  }

  if (failedSubIds.size > 0) {
    gatewaySuccess = false;
  } else {
    gatewaySuccess = true;
  }

  // 2. Delete all Firestore user subcollections & documents independently
  const subcollections = ["daily_data", "onboarding", "settings", "backup"];
  for (const coll of subcollections) {
    const delCollRes = await deleteFirestoreSubcollection(`users/${uid}`, coll, env);
    if (!delCollRes?.success) {
      firestoreSuccess = false;
      errors.push(`Subcollection purge failed for ${coll}`);
    }
  }

  // Handle subscription subcollection / doc & subscription_map:
  // If gateway cancellation failed, DO NOT delete subscription status document yet!
  if (gatewaySuccess) {
    await deleteFirestoreSubcollection(`users/${uid}`, "subscription", env);
    await deleteFirestoreDocument(`users/${uid}/subscription/status`, env);
  } else {
    firestoreSuccess = false;
    errors.push("Subscription status document preserved for gateway cancellation retry");
  }

  // Delete subscription_map entries ONLY for subscriptions with confirmed terminal gateway cancellation
  for (const sId of successfullyCancelledSubIds) {
    try {
      const delMapRes = await deleteFirestoreDocument(`subscription_map/${sId}`, env);
      if (!delMapRes?.success) {
        firestoreSuccess = false;
        errors.push(`Failed to delete subscription_map for ${sId}`);
      }
    } catch (delMapErr) {
      firestoreSuccess = false;
      errors.push(`Error purging subscription_map for ${sId}: ${delMapErr?.name || "Error"}`);
    }
  }

  // Handle backup snapshots and backup manifests (including nested snapshot daily_data records)
  try {
    const snapshotDocs = await listFirestoreDocuments(`users/${uid}/backup_snapshots`, env, 5000);
    const manifestDocs = await listFirestoreDocuments(`users/${uid}/backup_manifests`, env, 5000);
    const allBackupIds = new Set();
    if (Array.isArray(snapshotDocs)) {
      for (const d of snapshotDocs) {
        const bId = d?.name ? d.name.split("/").pop() : null;
        if (bId) allBackupIds.add(bId);
      }
    }
    if (Array.isArray(manifestDocs)) {
      for (const d of manifestDocs) {
        const bId = d?.name ? d.name.split("/").pop() : null;
        if (bId) allBackupIds.add(bId);
      }
    }

    for (const backupId of allBackupIds) {
      const delSnapshotDailyRes = await deleteFirestoreSubcollection(`users/${uid}/backup_snapshots/${backupId}`, "daily_data", env);
      if (!delSnapshotDailyRes?.success) {
        firestoreSuccess = false;
        errors.push(`Backup snapshot daily_data purge failed for ${backupId}`);
      }
      const delSnapDocRes = await deleteFirestoreDocument(`users/${uid}/backup_snapshots/${backupId}`, env);
      if (!delSnapDocRes?.success) {
        firestoreSuccess = false;
        errors.push(`Backup snapshot document purge failed for ${backupId}`);
      }
      const delManifestDocRes = await deleteFirestoreDocument(`users/${uid}/backup_manifests/${backupId}`, env);
      if (!delManifestDocRes?.success) {
        firestoreSuccess = false;
        errors.push(`Backup manifest document purge failed for ${backupId}`);
      }
    }

    const delSnapCollRes = await deleteFirestoreSubcollection(`users/${uid}`, "backup_snapshots", env);
    if (!delSnapCollRes?.success) {
      firestoreSuccess = false;
      errors.push("Subcollection purge failed for backup_snapshots");
    }
    const delManifestCollRes = await deleteFirestoreSubcollection(`users/${uid}`, "backup_manifests", env);
    if (!delManifestCollRes?.success) {
      firestoreSuccess = false;
      errors.push("Subcollection purge failed for backup_manifests");
    }
  } catch (backupPurgeErr) {
    firestoreSuccess = false;
    errors.push(`Backup purge encountered error: ${backupPurgeErr?.name || "Error"}`);
  }

  const todayIstStr = getIstDateString(nowMs);
  const yesterdayIstStr = getIstDateString(nowMs - 86400000);
  const quotaClaimPaths = new Set([
    `daily_quota_claims/${uid}_${todayIstStr}_BACKUP`,
    `daily_quota_claims/${uid}_${todayIstStr}_RESTORE`,
    `daily_quota_claims/${uid}_${yesterdayIstStr}_BACKUP`,
    `daily_quota_claims/${uid}_${yesterdayIstStr}_RESTORE`
  ]);

  try {
    const historicalClaims = await findDailyQuotaClaimsByUid(uid, env);
    for (const cPath of historicalClaims) {
      if (cPath && cPath.startsWith(`daily_quota_claims/${uid}_`)) {
        quotaClaimPaths.add(cPath);
      }
    }
  } catch (claimQueryErr) {
    console.warn("Non-fatal: Error querying historical daily_quota_claims for purge:", claimQueryErr?.name || "Error");
  }

  for (const claimPath of quotaClaimPaths) {
    const delClaimRes = await deleteFirestoreDocument(claimPath, env);
    if (!delClaimRes?.success) {
      firestoreSuccess = false;
      errors.push(`Document purge failed for ${claimPath}`);
    }
  }

  const individualDocs = [
    `users/${uid}/onboarding/tour`,
    `users/${uid}/settings/preferences`,
    `users/${uid}/backup/status`,
    `users/${uid}`,
    `trial_registry/${uid}`,
    `subscription_creation_locks/${uid}`
  ];
  for (const docP of individualDocs) {
    const delDocRes = await deleteFirestoreDocument(docP, env);
    if (!delDocRes?.success) {
      firestoreSuccess = false;
      errors.push(`Document purge failed for ${docP}`);
    }
  }

  // 3. Delete Firebase Authentication user identity
  const authResult = await deleteFirebaseAuthUser(uid, env);
  const authSuccess = authResult?.success === true;
  if (!authSuccess) {
    errors.push(`Auth delete failed: ${authResult?.error || authResult?.status || "Unknown"}`);
  }

  // 4. Update tombstone state: COMPLETED if all steps succeeded, else PENDING_RETRY
  const operationsSucceeded = statusWriteSuccess && tombstoneWriteSuccess && gatewaySuccess && firestoreSuccess && authSuccess;
  const targetPurgeState = operationsSucceeded ? "COMPLETED" : "PENDING_RETRY";

  const updatedTombstoneFields = {
    accountStatus: { stringValue: "DELETED" },
    deletedAt: { timestampValue: originalDeletedAt },
    updatedAt: { timestampValue: new Date().toISOString() },
    purgeState: { stringValue: targetPurgeState },
    claimId: { stringValue: claimId },
    leaseExpiresAtMs: { integerValue: "0" } // Clear active lease
  };
  const targetSubIdToPreserve = failedSubIds.size > 0 ? Array.from(failedSubIds)[0] : activeSubId;
  if (!gatewaySuccess && targetSubIdToPreserve) {
    updatedTombstoneFields.pendingSubscriptionId = { stringValue: targetSubIdToPreserve };
  }

  let finalStatusSuccess = false;
  try {
    await updateFirestoreAccountStatus(uid, updatedTombstoneFields, env);
    finalStatusSuccess = true;
  } catch (err) {
    errors.push("Failed updating final account status");
  }

  const finalTombstoneRes = await finalizePurgeTombstoneAtomic(uid, {
    uid: { stringValue: uid },
    ...updatedTombstoneFields
  }, claimUpdateTime, env);
  let finalTombstoneSuccess = finalTombstoneRes?.success === true;
  if (!finalTombstoneSuccess && finalTombstoneRes?.superseded) {
    // If superseded, check if another worker completed it
    const currentTomb = await getFirestoreAccountTombstone(uid, env);
    if (currentTomb?.exists && currentTomb?.fields?.purgeState?.stringValue === "COMPLETED") {
      finalTombstoneSuccess = true;
    }
  }
  if (!finalTombstoneSuccess) {
    errors.push("Failed updating final account tombstone");
  }

  // Durable completion check:
  // A purge is truly completed ONLY if all operational steps succeeded AND both final persistence writes succeeded!
  const trulyCompleted = operationsSucceeded && finalStatusSuccess && finalTombstoneSuccess;
  const finalPurgeState = trulyCompleted ? "COMPLETED" : "PENDING_RETRY";

  if (trulyCompleted) {
    console.log("Permanent purge completed successfully.");
  } else {
    console.warn("Permanent purge incomplete. Retries scheduled.");
  }

  return {
    success: trulyCompleted,
    purgeState: finalPurgeState,
    tombstoneWritten: statusWriteSuccess || tombstoneWriteSuccess,
    gatewaySuccess,
    firestoreSuccess,
    authSuccess,
    finalPersistenceSuccess: finalStatusSuccess && finalTombstoneSuccess,
    errors
  };
}

async function deleteFirebaseAuthUser(uid, env) {
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) return { success: false, error: "Missing service account key" };

  try {
    const accessToken = await getGoogleServiceAccountAccessToken(saKey);
    const deleteUrl = "https://identitytoolkit.googleapis.com/v1/accounts:delete";
    const res = await fetch(deleteUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ localId: uid })
    });

    if (res.ok) {
      console.log(`Firebase Auth account delete response status: HTTP ${res.status}`);
      return { success: true, status: res.status };
    }

    if (res.status === 404) {
      console.log(`Firebase Auth account delete response status: HTTP ${res.status}`);
      return { success: true, status: res.status, alreadyDeleted: true };
    }

    if (res.status === 400) {
      const errBody = await res.text().catch(() => "");
      if (errBody.includes("USER_NOT_FOUND")) {
        console.log(`Firebase Auth account delete response status: HTTP 400 (USER_NOT_FOUND)`);
        return { success: true, status: 400, alreadyDeleted: true };
      }
    }

    console.warn(`Firebase Auth delete returned status: HTTP ${res.status}`);
    return { success: false, status: res.status };
  } catch (err) {
    console.error("Error deleting Firebase Auth user:", err?.name || "Error");
    return { success: false, error: err?.name || "Network error" };
  }
}

/**
 * Enumerate account tombstones in PENDING_RETRY state and stale IN_PROGRESS state via Firestore REST runQuery.
 * Strictly bounded by limit (default 25, maximum 25) to prevent edge timeouts.
 * Orders by updatedAt ASC to guarantee fair FIFO scheduling and prevent starvation.
 * Distinguishes authentication / query errors from empty result sets.
 */
async function listPendingAccountTombstones(env, limit = 25) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) {
    console.error("Firestore scheduled recovery: missing FIREBASE_SERVICE_ACCOUNT_KEY");
    const errList = [];
    errList.success = false;
    errList.error = "MISSING_SERVICE_ACCOUNT_KEY";
    errList.status = 500;
    errList.pending = [];
    errList.uids = [];
    return errList;
  }

  const boundedLimit = Math.min(Math.max(1, limit), 25);

  let accessToken;
  try {
    accessToken = await getGoogleServiceAccountAccessToken(saKey);
  } catch (authErr) {
    console.error("Firestore scheduled recovery: failed to obtain OAuth access token:", authErr?.name || "Error");
    const errList = [];
    errList.success = false;
    errList.error = "AUTH_TOKEN_GENERATION_FAILED";
    errList.status = 401;
    errList.pending = [];
    errList.uids = [];
    return errList;
  }

  const queryUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:runQuery`;

  function getSafeTimeMs(val, defaultVal = 0) {
    if (!val) return defaultVal;
    const t = new Date(val).getTime();
    return Number.isFinite(t) ? t : defaultVal;
  }

  async function queryTombstonesByState(targetState, queryLimit) {
    async function sendQuery(withOrderBy, countLimit) {
      const structuredQuery = {
        from: [{ collectionId: "account_tombstones" }],
        where: {
          fieldFilter: {
            field: { fieldPath: "purgeState" },
            op: "EQUAL",
            value: { stringValue: targetState }
          }
        },
        limit: countLimit
      };

      if (withOrderBy) {
        structuredQuery.orderBy = [
          { field: { fieldPath: "updatedAt" }, direction: "ASCENDING" }
        ];
      }

      return await fetch(queryUrl, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${accessToken}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ structuredQuery })
      });
    }

    // Try query with orderBy updatedAt ASC for starvation-free FIFO processing
    let res = await sendQuery(true, queryLimit);

    // If Firestore returns 400 (FAILED_PRECONDITION: missing composite index), fallback without orderBy
    if (res.status === 400) {
      console.warn(`Firestore index missing for account_tombstones (purgeState, updatedAt); falling back to unordered query for ${targetState}`);
      const fallbackLimit = Math.min(100, Math.max(queryLimit * 4, 50));
      res = await sendQuery(false, fallbackLimit);
    }

    if (res.status === 401 || res.status === 403) {
      return { ok: false, status: res.status, error: `AUTH_REJECTED_${res.status}` };
    }

    if (res.status >= 500) {
      return { ok: false, status: res.status, error: `FIRESTORE_5XX_${res.status}` };
    }

    if (!res.ok) {
      return { ok: false, status: res.status, error: `QUERY_FAILED_${res.status}` };
    }

    let rows;
    try {
      rows = await res.json();
    } catch (parseErr) {
      return { ok: false, status: 502, error: "MALFORMED_QUERY_RESPONSE" };
    }

    if (!Array.isArray(rows)) {
      return { ok: false, status: 502, error: "MALFORMED_QUERY_RESPONSE" };
    }

    const items = [];
    for (const row of rows) {
      if (!row.document || !row.document.name) continue;
      const parts = row.document.name.split("/documents/account_tombstones/");
      const docUid = (parts.length > 1 && parts[1]) ? parts[1] : row.document.fields?.uid?.stringValue;
      if (!docUid) continue;

      const fields = row.document.fields || {};
      const pendingSubId = fields.pendingSubscriptionId?.stringValue || null;
      const updatedAtStr = fields.updatedAt?.timestampValue || fields.updatedAt?.stringValue || row.document.updateTime || null;
      const leaseExpiresAt = fields.leaseExpiresAtMs?.integerValue || fields.leaseExpiresAtMs?.stringValue || null;
      const deletionScheduledFor = fields.deletionScheduledFor?.timestampValue || fields.deletionScheduledFor?.stringValue || null;

      items.push({
        uid: docUid,
        pendingSubscriptionId: pendingSubId,
        purgeState: targetState,
        updatedAt: updatedAtStr,
        leaseExpiresAtMs: leaseExpiresAt,
        deletionScheduledFor
      });
    }

    return { ok: true, items };
  }

  try {
    // 1. Discover PENDING_RETRY records
    const retryRes = await queryTombstonesByState("PENDING_RETRY", boundedLimit);
    if (!retryRes.ok) {
      console.warn(`Firestore runQuery for PENDING_RETRY tombstones failed: HTTP ${retryRes.status}`);
      const errList = [];
      errList.success = false;
      errList.error = retryRes.error;
      errList.status = retryRes.status;
      errList.pending = [];
      errList.uids = [];
      return errList;
    }

    // 2. Discover IN_PROGRESS records concurrently so stale IN_PROGRESS is never hidden behind 25 PENDING_RETRY records
    const inProgressRes = await queryTombstonesByState("IN_PROGRESS", boundedLimit);
    if (!inProgressRes.ok) {
      console.warn(`Firestore runQuery for IN_PROGRESS tombstones failed: HTTP ${inProgressRes.status}`);
      const errList = [];
      errList.success = false;
      errList.error = inProgressRes.error;
      errList.status = inProgressRes.status;
      errList.pending = [];
      errList.uids = [];
      return errList;
    }

    // 3. Discover PENDING_DELETION records whose 7-day grace period has expired
    const pendingDelRes = await queryTombstonesByState("PENDING_DELETION", boundedLimit);
    if (!pendingDelRes.ok) {
      console.warn(`Firestore runQuery for PENDING_DELETION tombstones failed: HTTP ${pendingDelRes.status}`);
      const errList = [];
      errList.success = false;
      errList.error = pendingDelRes.error;
      errList.status = pendingDelRes.status;
      errList.pending = [];
      errList.uids = [];
      return errList;
    }

    const candidates = new Map();

    for (const item of retryRes.items) {
      candidates.set(item.uid, item);
    }

    const nowMs = Date.now();
    for (const item of pendingDelRes.items) {
      if (candidates.has(item.uid)) continue;
      const scheduledForMs = getSafeTimeMs(item.deletionScheduledFor, Infinity);
      if (nowMs >= scheduledForMs) {
        candidates.set(item.uid, item);
      }
    }

    // Stale IN_PROGRESS evaluation:
    // Stale if lease has expired (nowMs >= leaseExpiresAtMs) or if unleased, older than 15 min fallback.
    // Malformed or missing updatedAt evaluates to safeTimeMs = 0, so it is immediately considered stale and healed.
    for (const item of inProgressRes.items) {
      if (candidates.has(item.uid)) continue;

      const leaseExpires = Number(item.leaseExpiresAtMs || 0);
      const itemTimeMs = getSafeTimeMs(item.updatedAt, 0);
      let isStale = false;
      if (leaseExpires > 0 && nowMs >= leaseExpires) {
        isStale = true;
      } else if ((nowMs - itemTimeMs) >= (15 * 60 * 1000)) {
        isStale = true;
      }

      if (isStale) {
        candidates.set(item.uid, item);
      }
    }

    // Sort all candidates by safe updatedAt ASC:
    // Records with malformed or missing updatedAt evaluate to 0 and sort to the front (healed first).
    // Fair FIFO ordering prevents starvation.
    const sortedCandidates = Array.from(candidates.values()).sort((a, b) => {
      const timeA = getSafeTimeMs(a.updatedAt, 0);
      const timeB = getSafeTimeMs(b.updatedAt, 0);
      return timeA - timeB;
    });

    const pendingItems = sortedCandidates.slice(0, boundedLimit);
    const resultList = pendingItems.map(p => p.uid);
    resultList.success = true;
    resultList.pending = pendingItems;
    resultList.uids = [...resultList];
    resultList.count = pendingItems.length;
    return resultList;
  } catch (err) {
    console.error("Error querying pending account tombstones:", err?.name || "Error");
    const errList = [];
    errList.success = false;
    errList.error = "NETWORK_ERROR";
    errList.status = 500;
    errList.pending = [];
    errList.uids = [];
    return errList;
  }
}

/**
 * Scheduled handler helper: Process pending account purges with strict error isolation.
 * Guarantees that failure on one account does NOT abort or block processing remaining accounts.
 * Distinguishes discovery/auth failures from successful zero-pending sweeps.
 */
async function processPendingAccountPurges(env, limit = 25) {
  const boundedLimit = Math.min(Math.max(1, limit), 25);
  const discoveryResult = await listPendingAccountTombstones(env, boundedLimit);

  if (!discoveryResult.success) {
    console.error("Scheduled purge discovery failed:", discoveryResult.error || "Query error");
    return {
      success: false,
      error: discoveryResult.error,
      status: discoveryResult.status || 500,
      total: 0,
      succeeded: 0,
      failed: 0,
      errors: [discoveryResult.error]
    };
  }

  const pendingItems = discoveryResult.pending || [];
  const results = {
    success: true,
    total: pendingItems.length,
    succeeded: 0,
    failed: 0,
    errors: []
  };

  for (const item of pendingItems) {
    const uid = typeof item === "string" ? item : item.uid;
    if (!uid) continue;
    try {
      const purgeResult = await purgeAccountData(uid, env);
      if (purgeResult.success && purgeResult.purgeState === "COMPLETED") {
        results.succeeded++;
      } else {
        results.failed++;
        results.errors.push({ uid, status: "FAILED_RETRY" });
      }
    } catch (err) {
      results.failed++;
      console.error("Scheduled purge execution error:", err?.name || "Error");
      results.errors.push({ uid, status: "EXCEPTION" });
    }
  }

  console.log(`Scheduled purge sweep finished: processed ${results.total}, succeeded ${results.succeeded}, failed ${results.failed}`);
  return results;
}

const WEBHOOK_EVENT_LEASE_MS = 2 * 60 * 1000;

async function readWebhookEvent(eventId, env) {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY missing.");

  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/razorpay_events/${encodeURIComponent(eventId)}`;
  const res = await fetch(docUrl, { headers: { "Authorization": `Bearer ${accessToken}` } });
  if (res.status === 404) return { exists: false, docUrl, accessToken };
  if (!res.ok) throw new Error(`Failed to read webhook event state (${res.status}).`);
  const document = await res.json();
  return { exists: true, docUrl, accessToken, fields: document.fields || {}, updateTime: document.updateTime };
}

async function claimProcessedEventAtomic(eventId, eventData, env, nowMs = Date.now()) {
  const leaseOwner = crypto.randomUUID();
  const metadata = {
    eventId: { stringValue: eventId },
    eventType: { stringValue: eventData.eventType || "unknown" },
    paymentId: { stringValue: eventData.paymentId || "" },
    subscriptionId: { stringValue: eventData.subscriptionId || "" },
    orderId: { stringValue: eventData.orderId || "" },
    firebaseUid: { stringValue: eventData.firebaseUid || "" }
  };

  for (let attempt = 0; attempt < 4; attempt++) {
    const current = await readWebhookEvent(eventId, env);
    if (current.exists) {
      const state = current.fields.processingStatus?.stringValue;
      // Preserve exact-once behavior for event records written by the previous
      // schema, which used processedAt without an explicit processingStatus.
      if (state === "COMPLETED" || (!state && current.fields.processedAt?.timestampValue)) {
        return { completed: true };
      }

      const leaseExpiresAtMs = Number(current.fields.leaseExpiresAtMs?.integerValue || 0);
      if (state === "PROCESSING" && leaseExpiresAtMs > nowMs) {
        return { inProgress: true };
      }
    }

    const fields = {
      ...metadata,
      processingStatus: { stringValue: "PROCESSING" },
      leaseOwner: { stringValue: leaseOwner },
      leaseExpiresAtMs: { integerValue: String(nowMs + WEBHOOK_EVENT_LEASE_MS) },
      attemptCount: {
        integerValue: String(Number(current.fields?.attemptCount?.integerValue || 0) + 1)
      },
      lastError: { stringValue: "" },
      updatedAt: { timestampValue: new Date(nowMs).toISOString() }
    };
    const precondition = current.exists
      ? `currentDocument.updateTime=${encodeURIComponent(current.updateTime)}`
      : "currentDocument.exists=false";
    const url = `${current.docUrl}?${precondition}`;
    const res = await fetch(url, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${current.accessToken}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ fields })
    });

    if (res.status === 409 || res.status === 412) continue;
    if (!res.ok) throw new Error(`Failed to claim webhook event (${res.status}).`);
    const document = await res.json();
    if (!document.updateTime) throw new Error("Webhook event claim returned no update time.");
    return { claimed: true, leaseOwner, updateTime: document.updateTime };
  }

  return { inProgress: true };
}

async function setWebhookEventState(eventId, claim, state, env, errorName = "") {
  const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";
  const saKey = env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!saKey) throw new Error("FIREBASE_SERVICE_ACCOUNT_KEY missing.");
  const accessToken = await getGoogleServiceAccountAccessToken(saKey);
  const docUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/razorpay_events/${encodeURIComponent(eventId)}`;
  const nowMs = Date.now();
  const fields = state === "COMPLETED"
    ? {
        processingStatus: { stringValue: "COMPLETED" },
        processedAt: { timestampValue: new Date(nowMs).toISOString() },
        leaseExpiresAtMs: { integerValue: "0" },
        updatedAt: { timestampValue: new Date(nowMs).toISOString() }
      }
    : {
        processingStatus: { stringValue: "FAILED" },
        lastError: { stringValue: errorName || "ProcessingError" },
        leaseExpiresAtMs: { integerValue: "0" },
        updatedAt: { timestampValue: new Date(nowMs).toISOString() }
      };
  const url = `${docUrl}?currentDocument.updateTime=${encodeURIComponent(claim.updateTime)}`;

  const res = await fetch(url, {
    method: "PATCH",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ fields })
  });

  // Include the compare-and-set precondition in the URL. If ownership changed,
  // a later attempt must not overwrite the newer processor's state.
  if (res.status === 409 || res.status === 412) {
    const current = await readWebhookEvent(eventId, env);
    if (state === "COMPLETED" && current.fields?.processingStatus?.stringValue === "COMPLETED") return true;
    throw new Error("Webhook event claim changed before state could be recorded.");
  }
  if (!res.ok) throw new Error(`Failed to record webhook event state (${res.status}).`);
  return true;
}

// ---------------------------------------------------------------------------
// 5. RAZORPAY SIGNATURE & WEBHOOK VERIFICATION
// ---------------------------------------------------------------------------

async function verifyHmacSignature(message, signatureHex, secret) {
  if (!secret || typeof signatureHex !== "string") return false;
  const cleanSig = signatureHex.trim().toLowerCase();
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const calculatedSigBuffer = await crypto.subtle.sign("HMAC", key, enc.encode(message));
  const calculatedSigBytes = new Uint8Array(calculatedSigBuffer);
  
  let calculatedHex = "";
  for (let i = 0; i < calculatedSigBytes.length; i++) {
    calculatedHex += calculatedSigBytes[i].toString(16).padStart(2, "0");
  }

  if (calculatedHex.length !== cleanSig.length) {
    return false;
  }

  let result = 0;
  for (let i = 0; i < calculatedHex.length; i++) {
    result |= calculatedHex.charCodeAt(i) ^ cleanSig.charCodeAt(i);
  }
  return result === 0;
}

// ---------------------------------------------------------------------------
// 6. RECURRING AUTOPAY SUBSCRIPTION RECONCILIATION ENGINE
// ---------------------------------------------------------------------------

/**
 * Extract authoritative epoch millisecond timestamp from Razorpay event/entity payload.
 * Validates candidate timestamps:
 * 1. Must be finite positive numbers.
 * 2. Rejects <= 0, NaN, non-numeric strings, Infinity.
 * 3. Enforces a maximum future clock-skew limit of 5 minutes relative to Worker Date.now().
 * 4. Inspects subscription entity, payment entity, refund entity, and top-level event timestamps uniformly.
 */
function extractAuthoritativeEventTimeMs(entity, eventPayload = null) {
  const MAX_FUTURE_SKEW_MS = 5 * 60 * 1000; // 5 minutes
  const maxAllowedTimeMs = Date.now() + MAX_FUTURE_SKEW_MS;

  function parseCandidate(val) {
    if (typeof val !== "number" || !Number.isFinite(val) || isNaN(val) || val <= 0) {
      return null;
    }
    // Razorpay timestamps are standard Unix epoch seconds (e.g. ~1.7e9).
    // If value is in seconds (< 1e12), convert to ms.
    const ms = val < 1e12 ? Math.round(val * 1000) : Math.round(val);
    if (ms > maxAllowedTimeMs) {
      console.warn(`Webhook timestamp ${ms} exceeds maximum allowed future skew (${maxAllowedTimeMs}). Discarded as unverifiable.`);
      return null;
    }
    return ms;
  }

  // Precedence 1: Explicit entity passed to function
  if (entity) {
    const t1 = parseCandidate(entity.updated_at);
    if (t1 !== null) return t1;
    const t2 = parseCandidate(entity.created_at);
    if (t2 !== null) return t2;
    const t3 = parseCandidate(entity.current_start);
    if (t3 !== null) return t3;
  }

  // Precedence 2: Inspect entity structures inside eventPayload
  if (eventPayload) {
    const subEntity = eventPayload.payload?.subscription?.entity;
    if (subEntity) {
      const tsSubUpdated = parseCandidate(subEntity.updated_at);
      if (tsSubUpdated !== null) return tsSubUpdated;
      const tsSubCreated = parseCandidate(subEntity.created_at);
      if (tsSubCreated !== null) return tsSubCreated;
      const tsSubStart = parseCandidate(subEntity.current_start);
      if (tsSubStart !== null) return tsSubStart;
    }

    const payEntity = eventPayload.payload?.payment?.entity;
    if (payEntity) {
      const tsPayUpdated = parseCandidate(payEntity.updated_at);
      if (tsPayUpdated !== null) return tsPayUpdated;
      const tsPayCreated = parseCandidate(payEntity.created_at);
      if (tsPayCreated !== null) return tsPayCreated;
    }

    const refEntity = eventPayload.payload?.refund?.entity;
    if (refEntity) {
      const tsRefCreated = parseCandidate(refEntity.created_at);
      if (tsRefCreated !== null) return tsRefCreated;
    }

    // Precedence 3: Top-level event timestamps
    const tsEventCreated = parseCandidate(eventPayload.created_at);
    if (tsEventCreated !== null) return tsEventCreated;
    const tsEventCreatedAt = parseCandidate(eventPayload.event_created_at);
    if (tsEventCreatedAt !== null) return tsEventCreatedAt;
  }

  return null;
}

/**
 * Helper to check if incoming event is stale or unverifiable compared to stored Firestore subscription state.
 * Fails closed if incomingEventTimeMs is missing/unverifiable and existing state exists.
 * Enforces strict monotonic ordering: incoming event must be strictly newer than stored timestamps.
 */
function isEventStale(existingFields, incomingEventTimeMs) {
  if (!existingFields) return false;

  // Fail closed: If incoming timestamp is missing/unverifiable on an existing subscription, treat as stale
  if (!incomingEventTimeMs) {
    return true;
  }

  // 1. Primary Check: Monotonic event ordering against explicitly recorded event timestamp
  const storedEventTimeStr = existingFields.lastEventTimestamp?.timestampValue;
  if (storedEventTimeStr) {
    const storedEventTimeMs = new Date(storedEventTimeStr).getTime();
    // Strict monotonic ordering: incoming event must be strictly newer (> storedEventTimeMs)
    if (incomingEventTimeMs <= storedEventTimeMs) {
      return true;
    }
  }

  // 2. Secondary Guard: Strict ordering against updatedAt timestamp (no negative tolerance offset)
  const storedUpdatedAtStr = existingFields.updatedAt?.timestampValue || existingFields.updateTime;
  if (storedUpdatedAtStr) {
    const storedUpdatedAtMs = new Date(storedUpdatedAtStr).getTime();
    if (incomingEventTimeMs < storedUpdatedAtMs) {
      return true;
    }
  }

  return false;
}

async function readBoundedRequestBody(request, maxBytes) {
  if (!request.body) return new Uint8Array(0);

  const reader = request.body.getReader();
  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel("Request body exceeds configured limit.").catch(() => {});
        const error = new Error("PayloadTooLarge");
        error.code = "PAYLOAD_TOO_LARGE";
        throw error;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bodyBytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bodyBytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bodyBytes;
}

/**
 * Reconcile subscription activation or recurring charge (subscription.activated, subscription.charged, verify-subscription)
 */
async function reconcileSubscriptionActivation(uid, subscriptionData, env, eventId = null, eventPayload = null) {
  const subId = subscriptionData.id || subscriptionData.subscriptionId;
  const planId = subscriptionData.plan_id || subscriptionData.planId;
  const latestPaymentId = subscriptionData.latestPaymentId || subscriptionData.payment_id || "";
  const incomingEventTimeMs = extractAuthoritativeEventTimeMs(subscriptionData, eventPayload);

  // Guard 0: Erased / Deleted Account Guard
  const accountDoc = await getFirestoreAccountStatus(uid, env);
  const isDeleted = accountDoc?.accountStatus?.stringValue === "DELETED" || accountDoc?.purgeState?.stringValue === "COMPLETED";

  if (isDeleted) {
    console.warn(`Webhook subscription activation rejected: account is permanently deleted for UID ${uid}.`);
    if (subId && subId.startsWith("sub_") && env.RAZORPAY_KEY_ID && env.RAZORPAY_KEY_SECRET) {
      try {
        const creds = validateRazorpayCredentials(env);
        if (creds.valid) {
          const authHeaderValue = "Basic " + btoa(`${creds.keyId}:${creds.keySecret}`);
          await fetch(`https://api.razorpay.com/v1/subscriptions/${encodeURIComponent(subId)}/cancel`, {
            method: "POST",
            headers: { "Authorization": authHeaderValue, "Content-Type": "application/json" },
            body: JSON.stringify({ cancel_at_cycle_end: 0 })
          });
        }
      } catch (cancelErr) {
        console.warn("Failed to cancel mandate on deleted account:", cancelErr?.name || "Error");
      }
    }
    if (subId) {
      await deleteFirestoreDocument(`subscription_map/${subId}`, env).catch(() => {});
    }
    return {
      success: true,
      message: "Account is permanently deleted. Webhook rejected and gateway mandate cancelled.",
      status: "CANCELLED"
    };
  }

  const existingFields = await getFirestoreSubscription(uid, env);

  // Anti-stale / Anti-regression guards
  if (existingFields && existingFields.exists) {
    const existingStatus = existingFields.subscriptionStatus?.stringValue;
    const existingSubId = existingFields.subscriptionId?.stringValue;
    
    // Guard 1: Anti-resurrection after refund/cancellation
    if (existingSubId === subId && existingStatus === "CANCELLED" && existingFields.refundedAt) {
      console.warn("Reactivation rejected: subscription was refunded and cancelled.");
      return {
        success: true,
        message: "Subscription previously refunded/cancelled. Maintained cancelled state.",
        status: "CANCELLED"
      };
    }

    // Guard 2: Stale/out-of-order/unverifiable event check
    if (isEventStale(existingFields, incomingEventTimeMs)) {
      console.warn(`Stale or unverifiable activation/charged event ignored (event time: ${incomingEventTimeMs}).`);
      return {
        success: true,
        message: "Ignored stale or out-of-order webhook event.",
        status: existingStatus || "ACTIVE"
      };
    }
  }

  // Authoritative billing period extraction: DO NOT manufacture 30-day periods from Date.now()
  let startMs = null;
  let endMs = null;

  if (typeof subscriptionData.current_start === "number" && subscriptionData.current_start > 0) {
    startMs = subscriptionData.current_start < 1e12 ? subscriptionData.current_start * 1000 : subscriptionData.current_start;
  }
  if (typeof subscriptionData.current_end === "number" && subscriptionData.current_end > 0) {
    endMs = subscriptionData.current_end < 1e12 ? subscriptionData.current_end * 1000 : subscriptionData.current_end;
  }

  // If Razorpay omitted current_start/current_end, fall back to existing valid stored periods only if for the same subscription
  if (existingFields?.subscriptionId?.stringValue === subId) {
    if (!startMs && existingFields?.currentPeriodStart?.timestampValue) {
      startMs = new Date(existingFields.currentPeriodStart.timestampValue).getTime();
    }
    if (!endMs && existingFields?.currentPeriodEnd?.timestampValue) {
      endMs = new Date(existingFields.currentPeriodEnd.timestampValue).getTime();
    }
  }

  // Fail closed if authoritative billing period cannot be established
  if (!startMs || !endMs) {
    console.warn("Activation rejected: Missing authoritative current_start/current_end from Razorpay.");
    return {
      success: false,
      message: "Missing authoritative billing period from Razorpay.",
      status: existingFields?.subscriptionStatus?.stringValue || "INCOMPLETE"
    };
  }

  if (endMs <= startMs) {
    console.warn(`Activation rejected: Billing period end timestamp must be greater than start timestamp (startMs=${startMs}, endMs=${endMs}).`);
    return {
      success: false,
      message: "Invalid billing period: period end must be after period start.",
      status: existingFields?.subscriptionStatus?.stringValue || "INCOMPLETE"
    };
  }

  const durationDays = (endMs - startMs) / (24 * 60 * 60 * 1000);
  if (durationDays < 26 || durationDays > 65) {
    console.warn(`Activation rejected: Non-monthly billing cycle duration detected (${durationDays} days).`);
    return {
      success: false,
      message: "Subscription billing period is not a valid monthly interval.",
      status: existingFields?.subscriptionStatus?.stringValue || "INCOMPLETE"
    };
  }

  const now = new Date();
  let nextBillingMs = null;
  if (typeof subscriptionData.charge_at === "number" && subscriptionData.charge_at > 0) {
    nextBillingMs = subscriptionData.charge_at < 1e12 ? subscriptionData.charge_at * 1000 : subscriptionData.charge_at;
  } else if (!subscriptionData.ended_at) {
    nextBillingMs = endMs;
  }

  const isPendingDeletion = accountDoc?.accountStatus?.stringValue === "PENDING_DELETION";
  const cancelAtPeriodEnd = isPendingDeletion ? true : (subscriptionData.cancelAtPeriodEnd || false);

  const fields = {
    subscriptionStatus: { stringValue: "ACTIVE" },
    mandateStatus: { stringValue: "ACTIVE" },
    planId: { stringValue: planId || "plan_monthly" },
    subscriptionId: { stringValue: subId },
    currentPeriodStart: { timestampValue: new Date(startMs).toISOString() },
    currentPeriodEnd: { timestampValue: new Date(endMs).toISOString() },
    premium: { booleanValue: true },
    cancelAtPeriodEnd: { booleanValue: cancelAtPeriodEnd },
    updatedAt: { timestampValue: now.toISOString() }
  };

  if (latestPaymentId) {
    fields.latestPaymentId = { stringValue: latestPaymentId };
  }
  if (nextBillingMs) {
    fields.nextBillingDate = { timestampValue: new Date(nextBillingMs).toISOString() };
  }
  if (eventId) {
    fields.lastWebhookEventId = { stringValue: eventId };
  }
  if (incomingEventTimeMs) {
    fields.lastEventTimestamp = { timestampValue: new Date(incomingEventTimeMs).toISOString() };
  }

  await updateFirestoreSubscription(uid, fields, env);
  await recordSubscriptionUidMap(subId, uid, planId, env);

  return {
    success: true,
    message: "Recurring subscription active.",
    subscriptionId: subId,
    currentPeriodStart: startMs,
    currentPeriodEnd: endMs,
    status: "ACTIVE"
  };
}

/**
 * Reconcile payment failure or retry in progress (subscription.pending)
 * Puts subscription in PENDING status, preserving access during grace period.
 */
async function reconcileSubscriptionPending(uid, subId, env, eventId = null, eventPayload = null) {
  const accountDoc = await getFirestoreAccountStatus(uid, env);
  if (accountDoc?.accountStatus?.stringValue === "DELETED" || accountDoc?.purgeState?.stringValue === "COMPLETED") {
    return { success: true, message: "Account is permanently deleted. Pending event ignored.", status: "CANCELLED" };
  }

  const existingFields = await getFirestoreSubscription(uid, env);
  const incomingEventTimeMs = extractAuthoritativeEventTimeMs(null, eventPayload);

  if (existingFields && existingFields.exists) {
    const existingStatus = existingFields.subscriptionStatus?.stringValue;
    if (existingStatus === "CANCELLED" || existingStatus === "HALTED") {
      return { success: true, message: "Subscription already terminal.", status: existingStatus };
    }
    if (isEventStale(existingFields, incomingEventTimeMs)) {
      console.warn("Stale or unverifiable pending event ignored.");
      return { success: true, message: "Ignored stale pending event.", status: existingStatus };
    }
  }

  const now = new Date();
  const fields = {
    subscriptionStatus: { stringValue: "PENDING" },
    updatedAt: { timestampValue: now.toISOString() }
  };
  if (eventId) {
    fields.lastWebhookEventId = { stringValue: eventId };
  }
  if (incomingEventTimeMs) {
    fields.lastEventTimestamp = { timestampValue: new Date(incomingEventTimeMs).toISOString() };
  }

  await updateFirestoreSubscription(uid, fields, env);
  return { success: true, message: "Subscription marked PENDING (in grace period).", status: "PENDING" };
}

/**
 * Reconcile subscription halt after retries exhausted (subscription.halted)
 * Revokes premium entitlement immediately.
 */
async function reconcileSubscriptionHalted(uid, subId, env, eventId = null, eventPayload = null) {
  const accountDoc = await getFirestoreAccountStatus(uid, env);
  if (accountDoc?.accountStatus?.stringValue === "DELETED" || accountDoc?.purgeState?.stringValue === "COMPLETED") {
    return { success: true, message: "Account is permanently deleted. Halted event ignored.", status: "CANCELLED" };
  }

  const existingFields = await getFirestoreSubscription(uid, env);
  const incomingEventTimeMs = extractAuthoritativeEventTimeMs(null, eventPayload);

  if (existingFields && existingFields.exists) {
    const existingStatus = existingFields.subscriptionStatus?.stringValue;
    if (existingStatus === "CANCELLED") {
      return { success: true, message: "Subscription already cancelled.", status: "CANCELLED" };
    }
    if (isEventStale(existingFields, incomingEventTimeMs)) {
      console.warn("Stale or unverifiable halted event ignored.");
      return { success: true, message: "Ignored stale halted event.", status: existingStatus };
    }
  }

  const now = new Date();
  const fields = {
    subscriptionStatus: { stringValue: "HALTED" },
    premium: { booleanValue: false },
    updatedAt: { timestampValue: now.toISOString() }
  };
  if (eventId) {
    fields.lastWebhookEventId = { stringValue: eventId };
  }
  if (incomingEventTimeMs) {
    fields.lastEventTimestamp = { timestampValue: new Date(incomingEventTimeMs).toISOString() };
  }

  await updateFirestoreSubscription(uid, fields, env);
  return { success: true, message: "Subscription HALTED. Access revoked.", status: "HALTED" };
}

/**
 * Reconcile subscription cancellation (subscription.cancelled)
 * If cancelled at cycle end, preserves access until currentPeriodEnd.
 */
async function reconcileSubscriptionCancelled(uid, subId, cancelAtCycleEnd, currentEndSec, env, eventId = null, eventPayload = null) {
  const accountDoc = await getFirestoreAccountStatus(uid, env);
  if (accountDoc?.accountStatus?.stringValue === "DELETED" || accountDoc?.purgeState?.stringValue === "COMPLETED") {
    return { success: true, message: "Account is permanently deleted. Cancellation event ignored.", status: "CANCELLED" };
  }

  const existingFields = await getFirestoreSubscription(uid, env);
  const incomingEventTimeMs = extractAuthoritativeEventTimeMs(null, eventPayload);

  if (existingFields && existingFields.exists) {
    const existingStatus = existingFields.subscriptionStatus?.stringValue;
    if (existingStatus === "CANCELLED" && existingFields.refundedAt) {
      console.warn("Cancellation ignored: subscription was previously refunded and cancelled.");
      return { success: true, message: "Subscription previously refunded and cancelled.", status: "CANCELLED" };
    }
    if (isEventStale(existingFields, incomingEventTimeMs)) {
      console.warn("Stale or unverifiable cancellation event ignored.");
      return { success: true, message: "Ignored stale cancellation event.", status: existingStatus };
    }
  }

  const isCancelAtCycleEnd = cancelAtCycleEnd === true || cancelAtCycleEnd === 1;

  const now = new Date();
  let currentEndMs = null;
  if (typeof currentEndSec === "number" && currentEndSec > 0) {
    currentEndMs = currentEndSec < 1e12 ? currentEndSec * 1000 : currentEndSec;
  } else if (existingFields && existingFields.exists && existingFields.currentPeriodEnd?.timestampValue) {
    currentEndMs = new Date(existingFields.currentPeriodEnd.timestampValue).getTime();
  }

  const isStillInPeriod = currentEndMs ? (now.getTime() < currentEndMs) : false;
  const shouldPreserveAccess = isCancelAtCycleEnd && isStillInPeriod;

  const fields = {
    cancelAtPeriodEnd: { booleanValue: isCancelAtCycleEnd },
    updatedAt: { timestampValue: now.toISOString() }
  };

  if (shouldPreserveAccess) {
    fields.subscriptionStatus = { stringValue: "ACTIVE" };
    fields.premium = { booleanValue: true };
    if (currentEndMs) {
      fields.currentPeriodEnd = { timestampValue: new Date(currentEndMs).toISOString() };
    }
  } else {
    fields.subscriptionStatus = { stringValue: "CANCELLED" };
    fields.premium = { booleanValue: false };
    if (!isCancelAtCycleEnd) {
      fields.currentPeriodEnd = { timestampValue: now.toISOString() };
    } else if (currentEndMs) {
      fields.currentPeriodEnd = { timestampValue: new Date(currentEndMs).toISOString() };
    }
  }

  if (eventId) {
    fields.lastWebhookEventId = { stringValue: eventId };
  }
  if (incomingEventTimeMs) {
    fields.lastEventTimestamp = { timestampValue: new Date(incomingEventTimeMs).toISOString() };
  }

  await updateFirestoreSubscription(uid, fields, env);
  return {
    success: true,
    message: shouldPreserveAccess
      ? "Subscription cancelled at period end. Access remains active."
      : (isCancelAtCycleEnd ? "Subscription cancelled." : "Subscription cancelled immediately. Access revoked."),
    status: shouldPreserveAccess ? "ACTIVE" : "CANCELLED"
  };
}

/**
 * Reconcile completed subscription (120 cycles reached)
 */
async function reconcileSubscriptionCompleted(uid, subId, currentEndSec, env, eventId = null, eventPayload = null) {
  const accountDoc = await getFirestoreAccountStatus(uid, env);
  if (accountDoc?.accountStatus?.stringValue === "DELETED" || accountDoc?.purgeState?.stringValue === "COMPLETED") {
    return { success: true, message: "Account is permanently deleted. Completed event ignored.", status: "CANCELLED" };
  }

  const existingFields = await getFirestoreSubscription(uid, env);
  const incomingEventTimeMs = extractAuthoritativeEventTimeMs(null, eventPayload);

  if (existingFields && existingFields.exists && isEventStale(existingFields, incomingEventTimeMs)) {
    console.warn("Stale or unverifiable completed event ignored.");
    return { success: true, message: "Ignored stale completed event.", status: existingFields.subscriptionStatus?.stringValue || "EXPIRED" };
  }

  const now = new Date();
  let currentEndMs = null;
  if (typeof currentEndSec === "number" && currentEndSec > 0) {
    currentEndMs = currentEndSec < 1e12 ? currentEndSec * 1000 : currentEndSec;
  } else if (existingFields && existingFields.exists && existingFields.currentPeriodEnd?.timestampValue) {
    currentEndMs = new Date(existingFields.currentPeriodEnd.timestampValue).getTime();
  }

  const isStillInPeriod = currentEndMs ? (now.getTime() < currentEndMs) : false;

  const fields = {
    subscriptionStatus: { stringValue: isStillInPeriod ? "ACTIVE" : "EXPIRED" },
    premium: { booleanValue: isStillInPeriod },
    cancelAtPeriodEnd: { booleanValue: true },
    updatedAt: { timestampValue: now.toISOString() }
  };
  if (eventId) {
    fields.lastWebhookEventId = { stringValue: eventId };
  }
  if (incomingEventTimeMs) {
    fields.lastEventTimestamp = { timestampValue: new Date(incomingEventTimeMs).toISOString() };
  }

  await updateFirestoreSubscription(uid, fields, env);
  return { success: true, message: "Subscription completed cycle count.", status: fields.subscriptionStatus.stringValue };
}

/**
 * Reconcile refund on recurring subscription
 */
async function reconcileSubscriptionRefund(uid, paymentId, refundId, env, eventId = null, eventPayload = null, expectedSubId = "") {
  const accountDoc = await getFirestoreAccountStatus(uid, env);
  if (accountDoc?.accountStatus?.stringValue === "DELETED" || accountDoc?.purgeState?.stringValue === "COMPLETED") {
    return { success: true, message: "Account is permanently deleted. Refund event ignored.", status: "CANCELLED" };
  }

  const existingFields = await getFirestoreSubscription(uid, env);
  const incomingEventTimeMs = extractAuthoritativeEventTimeMs(null, eventPayload);

  if (!existingFields?.exists) {
    return { success: true, message: "Refund does not match an existing subscription.", status: "IGNORED" };
  }

  const currentSubId = existingFields.subscriptionId?.stringValue || "";
  const currentPaymentId = existingFields.latestPaymentId?.stringValue || "";
  if (!expectedSubId || expectedSubId !== currentSubId) {
    return { success: true, message: "Refund does not match the current subscription.", status: existingFields.subscriptionStatus?.stringValue || "UNKNOWN" };
  }
  if (currentPaymentId && paymentId && currentPaymentId !== paymentId) {
    return { success: true, message: "Refund does not match the current subscription payment.", status: existingFields.subscriptionStatus?.stringValue || "UNKNOWN" };
  }

  if (isEventStale(existingFields, incomingEventTimeMs)) {
    return { success: true, message: "Ignored stale or out-of-order refund event.", status: existingFields.subscriptionStatus?.stringValue || "UNKNOWN" };
  }

  if (existingFields && existingFields.exists) {
    const subId = existingFields.subscriptionId?.stringValue;
    // Cancel subscription on Razorpay if active
    if (subId && subId.startsWith("sub_")) {
      const credValidation = validateRazorpayCredentials(env);
      if (credValidation.valid) {
        try {
          const authHeaderValue = "Basic " + btoa(`${credValidation.keyId}:${credValidation.keySecret}`);
          await fetch(`https://api.razorpay.com/v1/subscriptions/${encodeURIComponent(subId)}/cancel`, {
            method: "POST",
            headers: { "Authorization": authHeaderValue, "Content-Type": "application/json" },
            body: JSON.stringify({ cancel_at_cycle_end: 0 })
          });
        } catch (err) {
          console.warn("Failed to auto-cancel Razorpay subscription upon refund:", err?.message || err);
        }
      }
    }
  }

  const now = new Date();
  const fields = {
    subscriptionStatus: { stringValue: "CANCELLED" },
    premium: { booleanValue: false },
    currentPeriodEnd: { timestampValue: now.toISOString() },
    refundedAt: { timestampValue: now.toISOString() },
    refundId: { stringValue: refundId },
    updatedAt: { timestampValue: now.toISOString() }
  };
  if (eventId) {
    fields.lastWebhookEventId = { stringValue: eventId };
  }
  if (incomingEventTimeMs) {
    fields.lastEventTimestamp = { timestampValue: new Date(incomingEventTimeMs).toISOString() };
  }

  await updateFirestoreSubscription(uid, fields, env);
  return { success: true, message: "Subscription revoked due to refund.", status: "CANCELLED" };
}

// ---------------------------------------------------------------------------
// 7. MAIN WORKER FETCH HANDLER
// ---------------------------------------------------------------------------

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") {
      const corsHeaders = getCorsHeaders(request);
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    const url = new URL(request.url);
    const path = url.pathname;
    const clientIp = getClientIp(request);

    // Global Request Hardening: Limit supported HTTP methods
    const ALLOWED_METHODS = ["GET", "POST", "OPTIONS"];
    if (!ALLOWED_METHODS.includes(request.method)) {
      return jsonResponse({ error: "Method Not Allowed" }, 405, request);
    }

    // Global Request Hardening: Payload size enforcement (max 256KB for all endpoints)
    const contentLength = request.headers.get("Content-Length");
    if (contentLength && parseInt(contentLength, 10) > 256 * 1024) {
      return jsonResponse({ error: "Payload Too Large. Request exceeds 256KB limit." }, 413, request);
    }

    if (request.method === "POST" && request.body) {
      try {
        const boundedBody = await readBoundedRequestBody(request, 256 * 1024);
        const boundedHeaders = new Headers(request.headers);
        boundedHeaders.delete("Content-Length");
        request = new Request(request, { headers: boundedHeaders, body: boundedBody });
      } catch (bodyError) {
        if (bodyError?.code === "PAYLOAD_TOO_LARGE") {
          return jsonResponse({ error: "Payload Too Large. Request exceeds 256KB limit." }, 413, request);
        }
        return jsonResponse({ error: "Unable to read request body." }, 400, request);
      }
    }

    // Global Request Hardening: Enforce JSON content-type on mutation requests that send a request body
    if (request.method === "POST") {
      const contentType = request.headers.get("Content-Type") || "";
      const contentLengthHeader = request.headers.get("Content-Length");
      const hasBody = (contentLengthHeader && parseInt(contentLengthHeader, 10) > 0) || contentType.length > 0;
      
      // Endpoints requiring JSON body payloads:
      const bodyEndpoints = [
        "/api/razorpay-webhook",
        "/api/create-subscription",
        "/api/verify-subscription",
        "/api/cancel-subscription",
        "/api/restore-subscription",
        "/api/backup",
        "/api/backup/init",
        "/api/backup/chunk",
        "/api/backup/finalize"
      ];
      
      if (bodyEndpoints.includes(path) || (hasBody && contentType.length > 0)) {
        if (!contentType.toLowerCase().includes("application/json")) {
          return jsonResponse({ error: "Unsupported Media Type. Content-Type must be application/json." }, 415, request);
        }
      }
    }

    try {
      // ---------------------------------------------------------------------
      // Root / Health Check (Public Rate Limit: 60 req / 60s per IP)
      // ---------------------------------------------------------------------
      if (path === "/" || path === "/health") {
        if (request.method !== "GET") {
          return jsonResponse({ error: "Method Not Allowed. Use GET." }, 405, request);
        }

        const rateCheck = await checkRateLimit(`ip_health_${clientIp}`, 60, 60000, { failClosed: false }, env);
        if (!rateCheck.allowed) {
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: rateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": rateCheck.retryAfterSec.toString() }
          );
        }
        return jsonResponse({
          status: "healthy",
          service: "Re:Focus Edge Worker",
          model: "recurring_autopay_v2"
        }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Server-Authoritative Time: GET /api/time
      // Rate Limit: 30 req / 60s per IP
      // Protected by: Firebase App Check (configurable mode) & IP Rate Limiting
      // Supports pre-login time synchronization without leaking sensitive data
      // ---------------------------------------------------------------------
      if (path === "/api/time") {
        if (request.method !== "GET") {
          return jsonResponse({ error: "Method Not Allowed. Use GET." }, 405, request);
        }

        const rateCheck = await checkRateLimit(`ip_time_${clientIp}`, 30, 60000, { failClosed: false }, env);
        if (!rateCheck.allowed) {
          if (rateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (rateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: rateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": rateCheck.retryAfterSec.toString(), "Cache-Control": "no-store" }
          );
        }

        // Validate App Check attestation
        await validateAppCheck(request, env, { mode: "audit" });

        const serverTime = Date.now();
        const clientEchoHeader = request.headers.get("X-Client-Timestamp");
        const clientEcho = clientEchoHeader ? (isNaN(Number(clientEchoHeader)) ? clientEchoHeader : Number(clientEchoHeader)) : null;

        return jsonResponse({
          serverTime: serverTime,
          iso: new Date(serverTime).toISOString(),
          clientEcho: clientEcho
        }, 200, request, {
          "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
          "Pragma": "no-cache"
        });
      }

      // ---------------------------------------------------------------------
      // Webhook: POST /api/razorpay-webhook (Server-to-Server)
      // Rate Limit: 120 req / 60s per IP
      // ---------------------------------------------------------------------
      if (path === "/api/razorpay-webhook") {
        if (request.method !== "POST") {
          return jsonResponse({ error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const rateCheck = await checkRateLimit(`ip_webhook_${clientIp}`, 120, 60000, { failClosed: true }, env);
        if (!rateCheck.allowed) {
          if (rateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (rateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: rateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": rateCheck.retryAfterSec.toString() }
          );
        }

        const signature = request.headers.get("X-Razorpay-Signature");
        if (!signature) {
          return jsonResponse({ error: "Missing X-Razorpay-Signature header" }, 400, request);
        }

        const webhookSecret = env.RAZORPAY_WEBHOOK_SECRET;
        if (!webhookSecret) {
          console.error("RAZORPAY_WEBHOOK_SECRET is not configured in Worker environment.");
          return jsonResponse({ error: "Internal Server Error" }, 500, request);
        }

        const rawBody = await request.text();
        const isSignatureValid = await verifyHmacSignature(rawBody, signature.trim(), webhookSecret.trim());
        
        if (!isSignatureValid) {
          console.error("Razorpay webhook signature verification failed.");
          return jsonResponse({ error: "Invalid webhook signature" }, 400, request);
        }

        let eventPayload;
        try {
          eventPayload = JSON.parse(rawBody);
        } catch (e) {
          return jsonResponse({ error: "Invalid JSON payload" }, 400, request);
        }

        const rawHeaderEventId = request.headers.get("x-razorpay-event-id") || request.headers.get("X-Razorpay-Event-Id");
        const headerEventId = typeof rawHeaderEventId === "string" ? rawHeaderEventId.trim() : "";
        const rawBodyEventId = (typeof eventPayload?.event_id === "string" && eventPayload.event_id.trim()) ||
                               (typeof eventPayload?.id === "string" && eventPayload.id.trim()) ||
                               "";
        if (headerEventId && rawBodyEventId && headerEventId !== rawBodyEventId) {
          console.warn("Razorpay webhook event ID discrepancy: header and body differ; prioritizing header event ID.");
        }
        const eventId = headerEventId || rawBodyEventId;
        const eventType = typeof eventPayload?.event === "string" ? eventPayload.event.trim() : "";

        if (!eventId || !eventType) {
          return jsonResponse({ error: "Missing event identifier or event type" }, 400, request);
        }

        if (eventId.length > 255 || eventId.includes("/")) {
          return jsonResponse({ error: "Invalid event identifier" }, 400, request);
        }

        console.log(`Processing Razorpay Webhook Event: ${eventType} (ID: ${eventId})`);

        const subscriptionEntity = eventPayload.payload?.subscription?.entity;
        let paymentEntity = eventPayload.payload?.payment?.entity;
        const orderEntity = eventPayload.payload?.order?.entity;
        const refundEntity = eventPayload.payload?.refund?.entity;

        const paymentId = paymentEntity?.id || refundEntity?.payment_id || "";
        const isRefundEvent = eventType === "refund.processed" || eventType === "refund.created" || eventType === "refund.failed";
        if (isRefundEvent && !paymentEntity && paymentId) {
          const credentials = validateRazorpayCredentials(env);
          if (!credentials.valid) throw new Error("Refund payment lookup configuration is unavailable.");
          const authHeaderValue = "Basic " + btoa(`${credentials.keyId}:${credentials.keySecret}`);
          const paymentResponse = await fetch(`https://api.razorpay.com/v1/payments/${encodeURIComponent(paymentId)}`, {
            headers: { "Authorization": authHeaderValue }
          });
          if (!paymentResponse.ok) throw new Error("Refund payment lookup failed.");
          const fetchedPayment = await paymentResponse.json();
          if (fetchedPayment?.id !== paymentId) throw new Error("Refund payment lookup returned a mismatched payment.");
          paymentEntity = fetchedPayment;
        }

        const subId = subscriptionEntity?.id || paymentEntity?.subscription_id || "";
        const orderId = orderEntity?.id || paymentEntity?.order_id || "";

        // Resolve Firebase UID via notes or subscription mapping
        const noteUid = subscriptionEntity?.notes?.firebase_uid ||
                        paymentEntity?.notes?.firebase_uid ||
                        orderEntity?.notes?.firebase_uid;
        const mappedUid = subId ? await lookupSubscriptionUid(subId, env) : "";
        let firebaseUid = noteUid;

        if (isRefundEvent && noteUid && mappedUid && noteUid !== mappedUid) {
          console.warn("Refund webhook ownership conflict between Razorpay notes and subscription mapping.");
          firebaseUid = "";
        } else if (!firebaseUid && mappedUid) {
          firebaseUid = mappedUid;
        }

        // Claim with a Firestore compare-and-set. A duplicate active attempt is
        // retryable, while a completed event is safe to acknowledge.
        const claim = await claimProcessedEventAtomic(eventId, {
          eventType,
          paymentId,
          subscriptionId: subId,
          orderId,
          firebaseUid: firebaseUid || ""
        }, env);

        if (claim.completed) {
          return jsonResponse({ status: "already_processed", event_id: eventId }, 200, request);
        }
        if (claim.inProgress) {
          return jsonResponse(
            { status: "processing", event_id: eventId },
            503,
            request,
            { "Retry-After": "5" }
          );
        }

        let outcome;
        try {
          switch (eventType) {
          // 1. Mandate Authenticated
          case "subscription.authenticated": {
            if (firebaseUid && subId) {
              const mappingResult = await recordSubscriptionUidMap(subId, firebaseUid, subscriptionEntity?.plan_id, env);
              if (!mappingResult.success) throw new Error("Failed to persist subscription ownership mapping.");
            }
            outcome = { status: "processed_authenticated", subscription_id: subId };
            break;
          }

          // 2. Initial Activation & 3. Recurring Charged
          case "subscription.activated":
          case "subscription.charged": {
            if (!firebaseUid) {
              outcome = { status: "ignored_missing_uid", subscription_id: subId };
              break;
            }

            const configuredPlanId = env.RAZORPAY_PLAN_ID?.trim();
            if (configuredPlanId && subscriptionEntity?.plan_id && subscriptionEntity.plan_id !== configuredPlanId) {
              console.warn(`Webhook plan mismatch: expected ${configuredPlanId}, got ${subscriptionEntity.plan_id}`);
              outcome = { status: "ignored_plan_mismatch" };
              break;
            }

            if (paymentEntity) {
              if (paymentEntity.currency && paymentEntity.currency !== EXPECTED_PLAN_CURRENCY) {
                console.warn(`Webhook payment currency mismatch: expected ${EXPECTED_PLAN_CURRENCY}, got ${paymentEntity.currency}`);
                outcome = { status: "ignored_currency_mismatch" };
                break;
              }
              if (typeof paymentEntity.amount === "number" && paymentEntity.amount !== EXPECTED_PLAN_AMOUNT_PAISE) {
                console.warn(`Webhook payment amount mismatch: expected ${EXPECTED_PLAN_AMOUNT_PAISE}, got ${paymentEntity.amount}`);
                outcome = { status: "ignored_amount_mismatch" };
                break;
              }
            }

            const result = await reconcileSubscriptionActivation(firebaseUid, {
              id: subId,
              plan_id: subscriptionEntity?.plan_id || "plan_monthly",
              latestPaymentId: paymentId,
              current_start: subscriptionEntity?.current_start,
              current_end: subscriptionEntity?.current_end,
              charge_at: subscriptionEntity?.charge_at,
              ended_at: subscriptionEntity?.ended_at,
              updated_at: subscriptionEntity?.updated_at || paymentEntity?.created_at
            }, env, eventId, eventPayload);

            if (!result?.success) throw new Error("Subscription activation reconciliation did not complete.");
            outcome = { status: "processed", result };
            break;
          }

          // 4. Payment Failure / Retry Pending (Grace Period)
          case "subscription.pending": {
            if (!firebaseUid) {
              outcome = { status: "ignored_missing_uid" };
              break;
            }
            const result = await reconcileSubscriptionPending(firebaseUid, subId, env, eventId, eventPayload);
            if (!result?.success) throw new Error("Subscription pending reconciliation did not complete.");
            outcome = { status: "processed", result };
            break;
          }

          // 5. Subscription Halted (Retries Exhausted)
          case "subscription.halted": {
            if (!firebaseUid) {
              outcome = { status: "ignored_missing_uid" };
              break;
            }
            const result = await reconcileSubscriptionHalted(firebaseUid, subId, env, eventId, eventPayload);
            if (!result?.success) throw new Error("Subscription halted reconciliation did not complete.");
            outcome = { status: "processed", result };
            break;
          }

          // 6. Subscription Cancelled
          case "subscription.cancelled": {
            if (!firebaseUid) {
              outcome = { status: "ignored_missing_uid" };
              break;
            }
            const cancelAtCycleEnd = subscriptionEntity?.cancel_at_cycle_end === 1 || subscriptionEntity?.cancel_at_cycle_end === true;
            const currentEndSec = subscriptionEntity?.current_end;
            const result = await reconcileSubscriptionCancelled(firebaseUid, subId, cancelAtCycleEnd, currentEndSec, env, eventId, eventPayload);
            if (!result?.success) throw new Error("Subscription cancellation reconciliation did not complete.");
            outcome = { status: "processed", result };
            break;
          }

          // 7. Subscription Completed (120 cycles reached)
          case "subscription.completed": {
            if (!firebaseUid) {
              outcome = { status: "ignored_missing_uid" };
              break;
            }
            const result = await reconcileSubscriptionCompleted(firebaseUid, subId, subscriptionEntity?.current_end, env, eventId, eventPayload);
            if (!result?.success) throw new Error("Subscription completion reconciliation did not complete.");
            outcome = { status: "processed", result };
            break;
          }

          // 8. Payment Failed Notification
          case "payment.failed": {
            outcome = { status: "processed_payment_failed", payment_id: paymentId };
            break;
          }

          // Refund creation is pending; only a successful refund changes entitlement.
          case "refund.created": {
            outcome = { status: "refund_pending", refund_id: refundEntity?.id || null };
            break;
          }

          // Failed refunds leave the current entitlement unchanged.
          case "refund.failed": {
            outcome = { status: "refund_failed_no_entitlement_change", refund_id: refundEntity?.id || null };
            break;
          }

          // A processed refund is the only refund event that revokes entitlement.
          case "refund.processed": {
            if (!firebaseUid) {
              outcome = { status: "ignored_missing_uid" };
              break;
            }
            const result = await reconcileSubscriptionRefund(firebaseUid, paymentId, refundEntity?.id || "refund_auto", env, eventId, eventPayload, subId);
            if (!result?.success) throw new Error("Subscription refund reconciliation did not complete.");
            outcome = { status: "processed", result };
            break;
          }

          default: {
            outcome = { status: "ignored_unhandled_type" };
            break;
          }
          }
        } catch (processingError) {
          try {
            await setWebhookEventState(eventId, claim, "FAILED", env, processingError?.name || "ProcessingError");
          } catch (stateError) {
            console.error("Failed to release Razorpay webhook event claim:", stateError?.name || "Error");
          }
          throw processingError;
        }

        await setWebhookEventState(eventId, claim, "COMPLETED", env);
        return jsonResponse(outcome, 200, request);
      }

      // ---------------------------------------------------------------------
      // UNKNOWN ENDPOINT CHECK: Fast 404 & Probe Abuse Protection
      // ---------------------------------------------------------------------
      const KNOWN_AUTH_PATHS = [
        "/api/account/status",
        "/api/account/request-deletion",
        "/api/account/cancel-deletion",
        "/api/ensure-subscription",
        "/api/subscription/lease",
        "/api/create-subscription",
        "/api/verify-subscription",
        "/api/cancel-subscription",
        "/api/restore-subscription",
        "/api/backup/status",
        "/api/backup",
        "/api/backup/init",
        "/api/backup/chunk",
        "/api/backup/finalize",
        "/api/backup/abort",
        "/api/restore",
        "/api/restore/ack",
        "/api/restore/abort",
        "/api/restore/heartbeat",
        "/api/restore/chunk"
      ];

      if (!KNOWN_AUTH_PATHS.includes(path)) {
        const notFoundRateCheck = await checkRateLimit(`ip_404_${clientIp}`, 30, 60000, { failClosed: true }, env);
        if (!notFoundRateCheck.allowed) {
          if (notFoundRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (notFoundRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: notFoundRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": notFoundRateCheck.retryAfterSec.toString() }
          );
        }
        return jsonResponse({ error: "Not Found" }, 404, request);
      }

      // ---------------------------------------------------------------------
      // AUTHENTICATED ENDPOINTS PRE-CHECKS: Rate Limiting, App Check & JWT Auth
      // ---------------------------------------------------------------------

      // 1. Global Pre-Auth IP Ceiling (120 req / 60s per IP across all protected routes)
      // Enforced BEFORE expensive JWT verification to prevent unauthenticated / invalid-token DoS exhaustion
      const globalAuthRateCheck = await checkRateLimit(`ip_auth_global_${clientIp}`, 120, 60000, { failClosed: true }, env);
      if (!globalAuthRateCheck.allowed) {
          if (globalAuthRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (globalAuthRateCheck.retryAfterSec || 60).toString() }
            );
          }
        return jsonResponse(
          { error: "Too many requests. Please try again later.", retryAfter: globalAuthRateCheck.retryAfterSec },
          429,
          request,
          { "Retry-After": globalAuthRateCheck.retryAfterSec.toString() }
        );
      }

      // 2. IP Authentication Failure Ceiling (20 failed attempts / 60s per IP)
      // Throttles credential stuffing, token fuzzing, and unauthenticated brute force before crypto verification
      const authFailCheck = await checkRateLimit(`ip_auth_fail_${clientIp}`, 20, 60000, false, env);
      if (!authFailCheck.allowed) {
          if (authFailCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (authFailCheck.retryAfterSec || 60).toString() }
            );
          }
        return jsonResponse(
          { error: "Too many authentication failures. Please try again later.", retryAfter: authFailCheck.retryAfterSec },
          429,
          request,
          { "Retry-After": authFailCheck.retryAfterSec.toString() }
        );
      }

      // 3. Validate Firebase App Check & Authenticate Request via Firebase ID Token
      let user;
      try {
        await validateAppCheck(request, env);
        user = await authenticateRequest(request, env);
      } catch (authErr) {
        // Record failed attestation/authentication attempt against this IP
        await checkRateLimit(`ip_auth_fail_${clientIp}`, 20, 60000, { record: true, failClosed: false }, env);
        throw authErr;
      }
      const uid = user.uid;

      // Zero-Trust Authorization: Reject client-supplied UID mismatches across all authenticated POST endpoints
      if (request.method === "POST") {
        try {
          const bodyClone = await request.clone().json().catch(() => null);
          if (bodyClone && typeof bodyClone === "object" && bodyClone.uid !== undefined && bodyClone.uid !== uid) {
            return jsonResponse({
              success: false,
              code: "UID_MISMATCH",
              error: "Forbidden: Client-supplied UID does not match authenticated token owner."
            }, 403, request);
          }
        } catch (_ignored) {}
      }

      // ---------------------------------------------------------------------
      // Account Lifecycle: GET /api/account/status
      // Rate Limit: 30 req / 60s per UID
      // ---------------------------------------------------------------------
      if (path === "/api/account/status") {
        if (request.method !== "GET") {
          return jsonResponse({ error: "Method Not Allowed. Use GET." }, 405, request);
        }

        const rateCheck = await checkRateLimit(`uid_status_${uid}`, 30, 60000, { failClosed: true }, env);
        if (!rateCheck.allowed) {
          if (rateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (rateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: rateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": rateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_status_${clientIp}`, 180, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const accountDoc = await getFirestoreAccountStatus(uid, env);
        if (accountDoc?.error) {
          return jsonResponse({ error: "Account status verification unavailable. Please try again." }, 503, request);
        }
        const currentStatus = accountDoc?.accountStatus?.stringValue || "ACTIVE";
        const scheduledForStr = accountDoc?.deletionScheduledFor?.timestampValue || null;
        const scheduledForMs = scheduledForStr ? new Date(scheduledForStr).getTime() : null;
        const requestedAtStr = accountDoc?.deletionRequestedAt?.timestampValue || null;
        const accountType = accountDoc?.accountType?.stringValue || "NORMAL";
        const purgeState = accountDoc?.purgeState?.stringValue || null;

        if (currentStatus === "PENDING_DELETION" && scheduledForMs && Date.now() > scheduledForMs) {
          console.log("Account PENDING_DELETION has expired. Triggering permanent purge.");
          const purgeResult = await purgeAccountData(uid, env);
          if (!purgeResult.success) {
            return jsonResponse({
              accountStatus: "DELETED",
              purgeState: "PENDING_RETRY",
              error: "Account purge in progress. Required cleanup encountered an error and will be retried."
            }, 500, request);
          }
          return jsonResponse({
            accountStatus: "DELETED",
            purgeState: "COMPLETED",
            message: "Account deletion window expired. Data permanently purged."
          }, 410, request);
        }

        if (currentStatus === "DELETED") {
          if (purgeState === "PENDING_RETRY" || purgeState === "IN_PROGRESS") {
            const retryResult = await purgeAccountData(uid, env);
            if (!retryResult.success) {
              return jsonResponse({
                accountStatus: "DELETED",
                purgeState: "PENDING_RETRY",
                error: "Account purge in progress. Required cleanup encountered an error and will be retried."
              }, 500, request);
            }
          }
          return jsonResponse({
            accountStatus: "DELETED",
            purgeState: "COMPLETED",
            message: "This account has been permanently deleted."
          }, 410, request);
        }

        return jsonResponse({
          accountStatus: currentStatus,
          accountType: accountType,
          deletionScheduledFor: scheduledForStr,
          deletionScheduledForMs: scheduledForMs,
          deletionRequestedAt: requestedAtStr
        }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Account Lifecycle: POST /api/account/request-deletion
      // Rate Limit: 5 req / 60s per UID
      // ---------------------------------------------------------------------
      if (path === "/api/account/request-deletion") {
        if (request.method !== "POST") {
          return jsonResponse({ error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const rateCheck = await checkRateLimit(`uid_req_del_${uid}`, 5, 60000, { failClosed: true }, env);
        if (!rateCheck.allowed) {
          if (rateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (rateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: rateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": rateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_req_del_${clientIp}`, 15, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const [accountDoc, tombstoneDoc] = await Promise.all([
          getFirestoreAccountStatus(uid, env),
          getFirestoreAccountTombstone(uid, env)
        ]);
        if (accountDoc?.error || tombstoneDoc?.error) {
          return jsonResponse({ error: "Account status verification unavailable. Please try again." }, 503, request);
        }
        const currentStatus = accountDoc?.accountStatus?.stringValue || "ACTIVE";
        const tombstoneStatus = tombstoneDoc.fields?.accountStatus?.stringValue;
        const tombstonePurgeState = tombstoneDoc.fields?.purgeState?.stringValue;

        if (currentStatus === "DELETED" || tombstoneStatus === "DELETED" || tombstonePurgeState === "COMPLETED" || tombstonePurgeState === "PENDING_RETRY") {
          return jsonResponse({ error: "Account is already deleted." }, 410, request);
        }

        if (tombstonePurgeState === "IN_PROGRESS") {
          return jsonResponse({
            error: "Account purge is currently in progress.",
            code: "PURGE_IN_PROGRESS"
          }, 409, request);
        }

        if (currentStatus === "PENDING_DELETION" && accountDoc?.deletionScheduledFor?.timestampValue) {
          const existingScheduledFor = accountDoc.deletionScheduledFor.timestampValue;
          const existingScheduledForMs = new Date(existingScheduledFor).getTime();

          if (Date.now() > existingScheduledForMs) {
            const purgeResult = await purgeAccountData(uid, env);
            if (!purgeResult.success) {
              return jsonResponse({
                accountStatus: "DELETED",
                purgeState: "PENDING_RETRY",
                error: "Account purge in progress. Required cleanup encountered an error and will be retried."
              }, 500, request);
            }
            return jsonResponse({
              accountStatus: "DELETED",
              purgeState: "COMPLETED",
              message: "Account deletion window expired. Data permanently purged."
            }, 410, request);
          }

          // Ensure durable tombstone exists in account_tombstones for scheduled discovery
          await recordFirestoreAccountTombstone(uid, {
            uid: { stringValue: uid },
            accountStatus: { stringValue: "PENDING_DELETION" },
            purgeState: { stringValue: "PENDING_DELETION" },
            deletionScheduledFor: { timestampValue: existingScheduledFor },
            updatedAt: { timestampValue: new Date().toISOString() }
          }, env).catch(() => {});

          return jsonResponse({
            message: "Account deletion request already pending.",
            accountStatus: "PENDING_DELETION",
            scheduledFor: existingScheduledFor,
            scheduledForMs: existingScheduledForMs
          }, 200, request);
        }

        const now = Date.now();
        const scheduledForMs = now + (7 * 24 * 60 * 60 * 1000);
        const requestedAtIso = new Date(now).toISOString();
        const scheduledForIso = new Date(scheduledForMs).toISOString();

        // Proactively cancel any active recurring mandate at Razorpay gateway so no renewal charges occur
        try {
          const subDoc = await getFirestoreSubscription(uid, env);
          const subId = subDoc?.exists ? subDoc?.subscriptionId?.stringValue : null;
          if (subId && subId.startsWith("sub_") && env.RAZORPAY_KEY_ID && env.RAZORPAY_KEY_SECRET) {
            const authHeaderValue = "Basic " + btoa(`${env.RAZORPAY_KEY_ID.trim()}:${env.RAZORPAY_KEY_SECRET.trim()}`);
            await fetch(`https://api.razorpay.com/v1/subscriptions/${subId}/cancel`, {
              method: "POST",
              headers: { "Authorization": authHeaderValue, "Content-Type": "application/json" },
              body: JSON.stringify({ cancel_at_cycle_end: 1 }) // Stop renewals at period end
            }).catch(e => console.warn("Non-fatal: Gateway subscription cancel failed during deletion request:", e?.name || "Error"));
          }
        } catch (subErr) {
          console.warn("Non-fatal: Error checking subscription for deletion request:", subErr?.name || "Error");
        }

        await updateFirestoreAccountStatus(uid, {
          accountStatus: { stringValue: "PENDING_DELETION" },
          deletionRequestedAt: { timestampValue: requestedAtIso },
          deletionScheduledFor: { timestampValue: scheduledForIso },
          updatedAt: { timestampValue: requestedAtIso }
        }, env);

        // Record durable tombstone in account_tombstones with OCC precondition if tombstone exists
        const tombstonePrecondition = (tombstoneDoc.exists && tombstoneDoc.updateTime)
          ? { expectedUpdateTime: tombstoneDoc.updateTime }
          : {};
        await recordFirestoreAccountTombstone(uid, {
          uid: { stringValue: uid },
          accountStatus: { stringValue: "PENDING_DELETION" },
          purgeState: { stringValue: "PENDING_DELETION" },
          deletionRequestedAt: { timestampValue: requestedAtIso },
          deletionScheduledFor: { timestampValue: scheduledForIso },
          updatedAt: { timestampValue: requestedAtIso }
        }, env, tombstonePrecondition).catch(err => console.warn("Failed recording account tombstone on request-deletion:", err?.name || "Error"));

        console.log(`Account deletion requested. Scheduled for ${scheduledForIso}`);

        return jsonResponse({
          message: "Account scheduled for deletion with 7-day grace period.",
          accountStatus: "PENDING_DELETION",
          scheduledFor: scheduledForIso,
          scheduledForMs: scheduledForMs
        }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Account Lifecycle: POST /api/account/cancel-deletion
      // Rate Limit: 5 req / 60s per UID
      // ---------------------------------------------------------------------
      if (path === "/api/account/cancel-deletion") {
        if (request.method !== "POST") {
          return jsonResponse({ error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const rateCheck = await checkRateLimit(`uid_cancel_del_${uid}`, 5, 60000, { failClosed: true }, env);
        if (!rateCheck.allowed) {
          if (rateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (rateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: rateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": rateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_cancel_del_${clientIp}`, 15, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const [accountDoc, tombstoneDoc] = await Promise.all([
          getFirestoreAccountStatus(uid, env),
          getFirestoreAccountTombstone(uid, env)
        ]);

        if (accountDoc?.error || tombstoneDoc?.error) {
          return jsonResponse({ error: "Account status verification unavailable. Please try again." }, 503, request);
        }

        const currentStatus = accountDoc?.accountStatus?.stringValue || "ACTIVE";
        const tombstoneStatus = tombstoneDoc.fields?.accountStatus?.stringValue;
        const tombstonePurgeState = tombstoneDoc.fields?.purgeState?.stringValue;
        const nowMs = Date.now();

        // 1. Active purge check:
        if (tombstonePurgeState === "IN_PROGRESS") {
          const leaseExpiresAtMs = Number(tombstoneDoc.fields?.leaseExpiresAtMs?.integerValue || 0);
          const safeUpdateTimeMs = tombstoneDoc.fields?.updatedAt?.timestampValue
            ? new Date(tombstoneDoc.fields.updatedAt.timestampValue).getTime()
            : (tombstoneDoc.updateTime ? new Date(tombstoneDoc.updateTime).getTime() : 0);
          const isLeaseActive = (nowMs < leaseExpiresAtMs) && ((nowMs - safeUpdateTimeMs) < (15 * 60 * 1000));
          if (isLeaseActive) {
            return jsonResponse({
              error: "Account purge is currently in progress and cannot be cancelled.",
              code: "PURGE_IN_PROGRESS"
            }, 409, request);
          }
          // Lease expired while IN_PROGRESS: partial deletion may have already occurred
          return jsonResponse({
            error: "Account has been permanently deleted or is undergoing irreversible purge.",
            code: "ACCOUNT_ALREADY_PURGED",
            accountStatus: "DELETED",
            purgeState: "PENDING_RETRY"
          }, 410, request);
        }

        // 2. Partial deletion / awaiting retry check:
        if (tombstonePurgeState === "PENDING_RETRY" || accountDoc?.purgeState?.stringValue === "PENDING_RETRY") {
          return jsonResponse({
            error: "Account has been permanently deleted or is undergoing irreversible purge.",
            code: "ACCOUNT_ALREADY_PURGED",
            accountStatus: "DELETED",
            purgeState: "PENDING_RETRY"
          }, 410, request);
        }

        // 3. Permanent/terminal deletion check:
        if (currentStatus === "DELETED" || tombstoneStatus === "DELETED" || tombstonePurgeState === "COMPLETED") {
          return jsonResponse({
            error: "Account is permanently deleted and cannot be restored.",
            code: "ACCOUNT_ALREADY_DELETED",
            accountStatus: "DELETED",
            purgeState: "COMPLETED"
          }, 410, request);
        }

        // 4. Pending deletion check:
        if (currentStatus !== "PENDING_DELETION" && tombstoneStatus !== "PENDING_DELETION") {
          return jsonResponse({
            message: "Account is not pending deletion.",
            accountStatus: currentStatus
          }, 200, request);
        }

        // 5. Grace period expiration check:
        const scheduledForStr = accountDoc?.deletionScheduledFor?.timestampValue || tombstoneDoc.fields?.deletionScheduledFor?.timestampValue;
        const scheduledForMs = scheduledForStr ? new Date(scheduledForStr).getTime() : 0;

        if (nowMs > scheduledForMs) {
          console.log("Cancel deletion rejected: Grace period expired. Purging.");
          const purgeResult = await purgeAccountData(uid, env);
          if (!purgeResult.success) {
            return jsonResponse({
              error: "Grace period has expired. Permanent purge in progress.",
              accountStatus: "DELETED",
              purgeState: "PENDING_RETRY"
            }, 500, request);
          }
          return jsonResponse({
            error: "Grace period has expired. Account has been permanently deleted.",
            accountStatus: "DELETED",
            purgeState: "COMPLETED"
          }, 410, request);
        }

        // 6. Atomic transition:
        // Update account_tombstones with OCC precondition
        const nowIso = new Date(nowMs).toISOString();
        const tombstoneOptions = (tombstoneDoc.exists && tombstoneDoc.updateTime)
          ? { expectedUpdateTime: tombstoneDoc.updateTime }
          : {};

        const tombstoneCancelRes = await recordFirestoreAccountTombstone(uid, {
          uid: { stringValue: uid },
          accountStatus: { stringValue: "ACTIVE" },
          purgeState: { stringValue: "CANCELLED" },
          restoredAt: { timestampValue: nowIso },
          updatedAt: { timestampValue: nowIso }
        }, env, tombstoneOptions);

        if (!tombstoneCancelRes.success) {
          if (tombstoneCancelRes.conflict) {
            // Concurrent race: another worker claimed purge lock or updated tombstone!
            const freshTomb = await getFirestoreAccountTombstone(uid, env);
            const freshPurgeState = freshTomb.fields?.purgeState?.stringValue;
            if (freshPurgeState === "IN_PROGRESS") {
              return jsonResponse({
                error: "Account purge is currently in progress and cannot be cancelled.",
                code: "PURGE_IN_PROGRESS"
              }, 409, request);
            }
            return jsonResponse({
              error: "Account has been permanently deleted or is undergoing irreversible purge.",
              code: "ACCOUNT_ALREADY_PURGED",
              accountStatus: "DELETED",
              purgeState: freshPurgeState || "DELETED"
            }, 410, request);
          }
          return jsonResponse({ error: "Failed to cancel account deletion. Please try again." }, 500, request);
        }

        // Update users/${uid}/account/status
        try {
          const statusOptions = (accountDoc.exists && accountDoc.updateTime)
            ? { expectedUpdateTime: accountDoc.updateTime }
            : {};
          await updateFirestoreAccountStatus(uid, {
            accountStatus: { stringValue: "ACTIVE" },
            restoredAt: { timestampValue: nowIso },
            updatedAt: { timestampValue: nowIso }
          }, env, statusOptions);
        } catch (err) {
          if (err.conflict) {
            console.warn("Conflict updating account status to ACTIVE; re-verifying current status.");
            const recheck = await getFirestoreAccountStatus(uid, env);
            if (recheck?.accountStatus?.stringValue === "DELETED") {
              return jsonResponse({
                error: "Account has been permanently deleted and cannot be restored.",
                code: "ACCOUNT_ALREADY_DELETED",
                accountStatus: "DELETED"
              }, 410, request);
            }
          }
          await updateFirestoreAccountStatus(uid, {
            accountStatus: { stringValue: "ACTIVE" },
            restoredAt: { timestampValue: nowIso },
            updatedAt: { timestampValue: nowIso }
          }, env).catch(e => console.warn("Non-fatal: fallback status doc update on cancel:", e?.name || "Error"));
        }

        console.log("Account deletion cancelled. Restored to ACTIVE.");

        return jsonResponse({
          message: "Account deletion cancelled successfully. Account restored to ACTIVE.",
          accountStatus: "ACTIVE"
        }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Endpoint 0: POST /api/ensure-subscription (Abuse-Resistant Free Trial)
      // Rate Limit: 10 req / 60s per UID
      // ---------------------------------------------------------------------
      if (path === "/api/ensure-subscription") {
        if (request.method !== "POST") {
          return jsonResponse({ error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_ensure_sub_${uid}`, 10, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_ensure_sub_${clientIp}`, 30, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const accountDoc = await getFirestoreAccountStatus(uid, env);
        if (accountDoc?.error) {
          return jsonResponse({ error: "Account status verification unavailable. Please try again." }, 503, request);
        }
        const accountStatus = accountDoc?.accountStatus?.stringValue || "ACTIVE";
        if (accountStatus === "PENDING_DELETION" || accountStatus === "DELETED" || accountStatus === "RESTRICTED") {
          return jsonResponse({ error: `Account status is ${accountStatus}. Subscription initialization blocked.` }, 403, request);
        }

        // 1. Stable Identity Verification: Must be authenticated via Google provider
        const googleSub = getGoogleSubjectIdFromToken(user);
        if (!googleSub) {
          return jsonResponse({ error: "Free trial requires a verified Google account. Please sign in with Google." }, 403, request);
        }

        // 2. Secret Verification: TRIAL_SECRET_KEY is mandatory
        const trialSecret = env.TRIAL_SECRET_KEY;
        if (!trialSecret || typeof trialSecret !== "string" || trialSecret.trim().length === 0) {
          console.error("TRIAL_SECRET_KEY is not configured in Worker environment.");
          return jsonResponse({ error: "Trial service unavailable: configuration error." }, 503, request);
        }

        // 3. Compute deterministic opaque registry key (HMAC-SHA256)
        let identityKey;
        try {
          identityKey = await computeTrialIdentityHash(googleSub, trialSecret.trim());
        } catch (hashErr) {
          console.error("Failed to compute trial identity hash:", hashErr?.name || "Error");
          return jsonResponse({ error: "Trial service unavailable: internal crypto error." }, 500, request);
        }

        // 4. Check stable identity registry (and previous secret rotation fallback)
        let identityDoc = await getTrialIdentityRecord(identityKey, env);
        if (identityDoc.error) {
          return jsonResponse({ error: "Trial verification unavailable. Please try again." }, 503, request);
        }

        // Check previous secret if configured
        if (env.TRIAL_PREVIOUS_SECRET_KEY && typeof env.TRIAL_PREVIOUS_SECRET_KEY === "string") {
          try {
            const prevKey = await computeTrialIdentityHash(googleSub, env.TRIAL_PREVIOUS_SECRET_KEY.trim());
            const prevDoc = await getTrialIdentityRecord(prevKey, env);
            if (prevDoc.error) {
              return jsonResponse({ error: "Trial verification unavailable. Please try again." }, 503, request);
            }
            if (prevDoc.exists && prevDoc.fields) {
              console.log("Previous-key trial identity match discovered; queued backfill.");
              if (!identityDoc.exists) {
                identityDoc = prevDoc;
                // Migrate/backfill to current secret key asynchronously via ctx.waitUntil
                const migratePromise = updateTrialIdentityRecord(identityKey, prevDoc.fields, env).then(res => {
                  if (!res || !res.success) {
                    console.warn("Non-fatal: Failed to migrate trial identity to current secret:", res?.error || res?.status || "Unknown error");
                  }
                }).catch(e => {
                  console.warn("Non-fatal: Failed to migrate trial identity to current secret:", e?.name || "Error");
                });
                if (ctx && typeof ctx.waitUntil === "function") {
                  ctx.waitUntil(migratePromise);
                }
              } else {
                // Both records exist (e.g. dual reservations across skewed nodes during rotation).
                // Reconcile by strictly preserving the earliest trial expiration.
                const currEnd = extractFieldTimestampMs(identityDoc.fields.trialEnd);
                const prevEnd = extractFieldTimestampMs(prevDoc.fields.trialEnd);
                if (prevEnd > 0 && (currEnd === 0 || prevEnd < currEnd)) {
                  identityDoc = prevDoc;
                  const reconcileRes = await updateTrialIdentityRecord(identityKey, prevDoc.fields, env);
                  if (!reconcileRes || !reconcileRes.success) {
                    console.warn("Failed to reconcile earliest trial identity to current secret:", reconcileRes?.error || reconcileRes?.status || "Unknown error");
                    return jsonResponse({ error: "Trial verification unavailable. Please try again." }, 503, request);
                  }
                }
              }
            }
          } catch (prevErr) {
            console.error("Error checking or reconciling previous trial secret key:", prevErr?.name || "Error");
            return jsonResponse({ error: "Trial verification unavailable. Please try again." }, 503, request);
          }
        }

        // 5. Check legacy trial_registry by UID for backward compatibility
        const legacyRegistryDoc = await getTrialRegistry(uid, env);
        if (legacyRegistryDoc?.error) {
          return jsonResponse({ error: "Trial verification unavailable. Please try again." }, 503, request);
        }
        if (!identityDoc.exists && legacyRegistryDoc?.exists && legacyRegistryDoc.fields) {
          identityDoc = { exists: true, error: false, fields: legacyRegistryDoc.fields };
          // Backfill legacy registry entry to stable identity
          const backfillFields = {
            claimedAt: legacyRegistryDoc.fields.claimedAt || { timestampValue: new Date().toISOString() },
            trialEnd: legacyRegistryDoc.fields.trialEnd || { timestampValue: new Date().toISOString() }
          };
          const legacyBackfillPromise = updateTrialIdentityRecord(identityKey, backfillFields, env).catch(e => {
            console.warn("Non-fatal: Failed to backfill legacy trial registry to stable identity:", e?.name || "Error");
          });
          if (ctx && typeof ctx.waitUntil === "function") {
            ctx.waitUntil(legacyBackfillPromise);
          }
        }

        // 6. Check existing subscription document on user record
        let existingFields = await getFirestoreSubscription(uid, env);
        if (existingFields?.error) {
          return jsonResponse({ error: "Subscription status verification unavailable. Please try again." }, 503, request);
        }

        // If subscription document already has trialEnd, backfill identityDoc if missing
        if (!identityDoc.exists && existingFields?.exists && existingFields.fields) {
          const rawTrialEnd = existingFields.fields.trialEnd?.timestampValue || existingFields.fields.trialEndDate?.timestampValue || existingFields.fields.trialEnd?.stringValue;
          if (rawTrialEnd) {
            const rawClaimedAt = existingFields.fields.trialStart?.timestampValue || existingFields.fields.trialStartDate?.timestampValue || existingFields.fields.trialStart?.stringValue || new Date().toISOString();
            const backfillFields = {
              claimedAt: { timestampValue: typeof rawClaimedAt === "string" ? rawClaimedAt : new Date().toISOString() },
              trialEnd: { timestampValue: typeof rawTrialEnd === "string" ? rawTrialEnd : new Date().toISOString() }
            };
            identityDoc = { exists: true, error: false, fields: backfillFields };
            const subBackfillPromise = updateTrialIdentityRecord(identityKey, backfillFields, env).catch(e => {
              console.warn("Non-fatal: Failed to backfill subscription document to stable identity:", e?.name || "Error");
            });
            if (ctx && typeof ctx.waitUntil === "function") {
              ctx.waitUntil(subBackfillPromise);
            }
            const recordRegistryPromise = recordTrialRegistry(uid, typeof rawTrialEnd === "string" ? rawTrialEnd : new Date().toISOString(), env).catch(() => {});
            if (ctx && typeof ctx.waitUntil === "function") {
              ctx.waitUntil(recordRegistryPromise);
            }
          }
        }

        // 7. Handle already claimed trial (either on this account, on a recreated account, or in legacy registry)
        if (identityDoc.exists && identityDoc.fields) {
          const rawTrialEnd = identityDoc.fields.trialEnd?.timestampValue || identityDoc.fields.trialEnd?.stringValue || identityDoc.fields.trialEnd;
          const claimedAtField = identityDoc.fields.claimedAt || { timestampValue: new Date().toISOString() };
          let trialEndMs = 0;
          if (typeof rawTrialEnd === "string") {
            trialEndMs = new Date(rawTrialEnd).getTime();
          } else if (typeof rawTrialEnd === "number") {
            trialEndMs = rawTrialEnd < 1e12 ? rawTrialEnd * 1000 : rawTrialEnd;
          }

          const now = new Date();
          const nowMs = now.getTime();
          const isTrialActive = trialEndMs > 0 && nowMs < trialEndMs;
          const correctTrialStatus = isTrialActive ? "TRIAL" : "EXPIRED";

          let restoredPaidSub = false;
          let currentStatus = "NONE";
          let isPremium = false;
          let gatewayCheckFailed = false;
          let currentPeriodStartMs = 0;
          let currentPeriodEndMs = 0;
          let activeSubId = null;

          if (!existingFields || !existingFields.exists) {
            // Check if user has an existing paid subscription in subscription_map before reverting to trial
            try {
              const mappedSubs = await findSubscriptionsByUid(uid, env);
              if (mappedSubs && mappedSubs.length > 0) {
                for (const candidateSubId of mappedSubs) {
                  const subEntity = await fetchAndVerifyRazorpaySubscription(candidateSubId, uid, env);
                  if (subEntity && subEntity.status === "active") {
                    const actRes = await reconcileSubscriptionActivation(uid, subEntity, env);
                    if (actRes && actRes.success) {
                      restoredPaidSub = true;
                      isPremium = true;
                      currentStatus = "ACTIVE";
                      break;
                    }
                  }
                }
              }
            } catch (checkErr) {
              console.warn("Non-fatal: error checking subscription_map on ensure-subscription:", checkErr?.name || "Error");
            }

            if (!restoredPaidSub) {
              // Recreated account after deletion or fresh install missing status doc:
              // Restore document preserving original trial duration (never grant a fresh 7 days)
              const restoredFields = {
                subscriptionStatus: { stringValue: correctTrialStatus },
                trialStart: claimedAtField,
                trialEnd: { timestampValue: new Date(trialEndMs).toISOString() },
                trialStartDate: claimedAtField,
                trialEndDate: { timestampValue: new Date(trialEndMs).toISOString() },
                updatedAt: { timestampValue: now.toISOString() },
                premium: { booleanValue: false }
              };
              await updateFirestoreSubscription(uid, restoredFields, env);
            }
          } else {
            currentStatus = existingFields.subscriptionStatus?.stringValue || existingFields.status?.stringValue || "NONE";

            currentPeriodStartMs = extractFieldTimestampMs(existingFields.currentPeriodStart || existingFields.currentPeriodStartDate);
            currentPeriodEndMs = extractFieldTimestampMs(existingFields.currentPeriodEnd || existingFields.currentPeriodEndDate);
            activeSubId = existingFields.subscriptionId?.stringValue || null;

            const isPaidPeriodExpired = currentPeriodEndMs > 0 && nowMs >= currentPeriodEndMs;

            if (isPaidPeriodExpired && currentStatus === "ACTIVE") {
              let renewed = false;
              const subId = existingFields.subscriptionId?.stringValue;
              if (subId && subId.startsWith("sub_")) {
                try {
                  const subEntity = await fetchAndVerifyRazorpaySubscription(subId, uid, env);
                  if (subEntity && subEntity.status === "active") {
                    const rzpEndMs = typeof subEntity.current_end === "number"
                      ? (subEntity.current_end < 1e12 ? subEntity.current_end * 1000 : subEntity.current_end)
                      : 0;
                    if (rzpEndMs > nowMs) {
                      const actRes = await reconcileSubscriptionActivation(uid, subEntity, env);
                      if (actRes && actRes.success) {
                        renewed = true;
                        currentStatus = "ACTIVE";
                        isPremium = true;
                        currentPeriodEndMs = actRes.currentPeriodEnd || rzpEndMs;
                        currentPeriodStartMs = actRes.currentPeriodStart || currentPeriodStartMs;
                        activeSubId = subId;
                      }
                    }
                  }
                } catch (checkErr) {
                  gatewayCheckFailed = true;
                  console.warn("Non-fatal: error checking Razorpay renewal on ensure-subscription:", checkErr?.name || "Error");
                }
              }

              if (!renewed && !gatewayCheckFailed) {
                currentStatus = "EXPIRED";
                isPremium = false;
                const expireFields = {
                  subscriptionStatus: { stringValue: "EXPIRED" },
                  premium: { booleanValue: false },
                  updatedAt: { timestampValue: now.toISOString() }
                };
                let updateTargetTime = existingFields.updateTime;
                let attempts = 0;
                const maxAttempts = 2;
                while (attempts < maxAttempts) {
                  attempts++;
                  try {
                    await updateFirestoreSubscription(uid, expireFields, env, updateTargetTime);
                    currentStatus = "EXPIRED";
                    isPremium = false;
                    break;
                  } catch (updateErr) {
                    if (updateErr?.status === 412 || updateErr?.name === "PreconditionFailed") {
                      console.warn(`Precondition failed during expiration reconciliation (attempt ${attempts}); fetching latest committed document:`, updateErr?.status || updateErr?.name);
                      const refreshed = await getFirestoreSubscription(uid, env);
                      if (refreshed && refreshed.exists) {
                        currentStatus = refreshed.subscriptionStatus?.stringValue || refreshed.status?.stringValue || "NONE";
                        const refEndMs = extractFieldTimestampMs(refreshed.currentPeriodEnd || refreshed.currentPeriodEndDate);
                        const refPaidActive = refEndMs > nowMs;
                        if (refPaidActive && (refreshed.premium?.booleanValue === true || currentStatus === "ACTIVE")) {
                          isPremium = true;
                          currentStatus = "ACTIVE";
                          currentPeriodEndMs = refEndMs;
                          currentPeriodStartMs = extractFieldTimestampMs(refreshed.currentPeriodStart || refreshed.currentPeriodStartDate);
                          activeSubId = refreshed.subscriptionId?.stringValue || null;
                          break;
                        } else {
                          updateTargetTime = refreshed.updateTime;
                          currentStatus = "EXPIRED";
                          isPremium = false;
                        }
                      } else {
                        break;
                      }
                    } else {
                      console.warn("Non-fatal error updating expiration in Firestore:", updateErr?.status || updateErr?.name);
                      break;
                    }
                  }
                }
              } else if (gatewayCheckFailed) {
                isPremium = false;
                currentStatus = "EXPIRED";
              }
            } else {
              isPremium = !isPaidPeriodExpired && (existingFields.premium?.booleanValue === true || currentStatus === "ACTIVE");
            }

            if (!isPremium && !gatewayCheckFailed) {
              if (isTrialActive && currentStatus !== "TRIAL") {
                const reconcileFields = {
                  subscriptionStatus: { stringValue: "TRIAL" },
                  trialStart: claimedAtField,
                  trialEnd: { timestampValue: new Date(trialEndMs).toISOString() },
                  trialStartDate: claimedAtField,
                  trialEndDate: { timestampValue: new Date(trialEndMs).toISOString() },
                  updatedAt: { timestampValue: now.toISOString() },
                  premium: { booleanValue: false }
                };
                await updateFirestoreSubscription(uid, reconcileFields, env);
              } else if (!isTrialActive && currentStatus === "TRIAL") {
                const expireFields = {
                  subscriptionStatus: { stringValue: "EXPIRED" },
                  updatedAt: { timestampValue: now.toISOString() }
                };
                await updateFirestoreSubscription(uid, expireFields, env);
              }
            }
          }

          // Ensure legacy registry is also synchronized
          recordTrialRegistry(uid, new Date(trialEndMs).toISOString(), env).catch(() => {});

          const finalStatus = isPremium ? currentStatus : correctTrialStatus;
          return jsonResponse({
            message: isPremium ? "Active premium subscription" : (isTrialActive ? "Trial active" : "Trial already claimed for this account"),
            trialAlreadyClaimed: true,
            trialActive: isTrialActive,
            subscriptionStatus: finalStatus,
            premium: isPremium,
            trialEnd: trialEndMs,
            subscriptionId: isPremium ? activeSubId : null,
            currentPeriodStart: isPremium && currentPeriodStartMs > 0 ? new Date(currentPeriodStartMs).toISOString() : null,
            currentPeriodEnd: isPremium && currentPeriodEndMs > 0 ? new Date(currentPeriodEndMs).toISOString() : null
          }, 200, request);
        }

        // 8. Brand new eligible user: Atomically reserve trial_identities FIRST
        const now = new Date();
        const trialEnd = new Date(now.getTime() + (7 * 24 * 60 * 60 * 1000));
        const nowIso = now.toISOString();
        const trialEndIso = trialEnd.toISOString();

        const reserveFields = {
          claimedAt: { timestampValue: nowIso },
          trialEnd: { timestampValue: trialEndIso }
        };

        let reserveResult;
        try {
          reserveResult = await reserveTrialIdentityAtomic(identityKey, reserveFields, env);
        } catch (reserveErr) {
          console.error("Failed to reserve trial identity atomic:", reserveErr?.name || "Error");
          return jsonResponse({ error: "Trial initialization unavailable. Please try again." }, 503, request);
        }

        if (!reserveResult.created) {
          // Race lost: another concurrent request created the document first.
          // Re-fetch the newly created identity record and reconcile
          const winnerDoc = await getTrialIdentityRecord(identityKey, env);
          if (winnerDoc.error || !winnerDoc.exists) {
            return jsonResponse({ error: "Trial verification unavailable. Please try again." }, 503, request);
          }

          const rawTrialEnd = winnerDoc.fields?.trialEnd?.timestampValue || winnerDoc.fields?.trialEnd?.stringValue || winnerDoc.fields?.trialEnd;
          let trialEndMs = 0;
          if (typeof rawTrialEnd === "string") {
            trialEndMs = new Date(rawTrialEnd).getTime();
          } else if (typeof rawTrialEnd === "number") {
            trialEndMs = rawTrialEnd < 1e12 ? rawTrialEnd * 1000 : rawTrialEnd;
          }

          const isTrialActive = trialEndMs > 0 && Date.now() < trialEndMs;
          const correctTrialStatus = isTrialActive ? "TRIAL" : "EXPIRED";

          const restoredFields = {
            subscriptionStatus: { stringValue: correctTrialStatus },
            trialStart: winnerDoc.fields?.claimedAt || { timestampValue: nowIso },
            trialEnd: { timestampValue: new Date(trialEndMs).toISOString() },
            trialStartDate: winnerDoc.fields?.claimedAt || { timestampValue: nowIso },
            trialEndDate: { timestampValue: new Date(trialEndMs).toISOString() },
            updatedAt: { timestampValue: nowIso },
            premium: { booleanValue: false }
          };
          await updateFirestoreSubscription(uid, restoredFields, env);
          recordTrialRegistry(uid, new Date(trialEndMs).toISOString(), env).catch(() => {});

          return jsonResponse({
            message: isTrialActive ? "Trial active" : "Trial already claimed for this account",
            trialAlreadyClaimed: true,
            trialActive: isTrialActive,
            subscriptionStatus: correctTrialStatus,
            trialEnd: trialEndMs
          }, 200, request);
        }

        // 9. Atomic reservation succeeded: Write users/{uid}/subscription/status
        const subFields = {
          subscriptionStatus: { stringValue: "TRIAL" },
          trialStart: { timestampValue: nowIso },
          trialEnd: { timestampValue: trialEndIso },
          trialStartDate: { timestampValue: nowIso },
          trialEndDate: { timestampValue: trialEndIso },
          updatedAt: { timestampValue: nowIso },
          premium: { booleanValue: false }
        };

        try {
          await updateFirestoreSubscription(uid, subFields, env);
        } catch (subWriteErr) {
          console.error("Failed to persist subscription status after trial reservation:", subWriteErr?.name || "Error");
          return jsonResponse({ error: "Failed to persist subscription status. Please retry." }, 500, request);
        }

        // Record legacy trial_registry entry asynchronously
        recordTrialRegistry(uid, trialEndIso, env).catch(e => {
          console.warn("Non-fatal: Failed writing legacy trial registry:", e?.name || "Error");
        });

        return jsonResponse({
          message: "Trial initialized",
          trialEnd: trialEnd.getTime(),
          subscriptionStatus: "TRIAL",
          trialActive: true
        }, 201, request);
      }

      // ---------------------------------------------------------------------
      // Endpoint 0B: POST /api/subscription/lease (Server-Signed ECDSA P-256 Lease)
      // Rate Limit: 60 req / 60s per UID
      // Protected by: Firebase App Check & Authenticated Firebase UID
      // Validates subscription entitlement server-side from Firestore.
      // Signs ECDSA P-256 lease credential.
      // ---------------------------------------------------------------------
      if (path === "/api/subscription/lease") {
        if (request.method !== "POST") {
          return jsonResponse({ error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_lease_${uid}`, 60, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_lease_${clientIp}`, 60, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        // Check account deletion status
        const accountDoc = await getFirestoreAccountStatus(uid, env);
        if (accountDoc?.error) {
          return jsonResponse({ error: "Account status verification unavailable. Please try again." }, 503, request);
        }
        const accountStatus = accountDoc?.accountStatus?.stringValue || "ACTIVE";
        if (accountStatus === "PENDING_DELETION" || accountStatus === "DELETED" || accountStatus === "RESTRICTED") {
          return jsonResponse({ error: `Account status is ${accountStatus}. Access locked.` }, 403, request);
        }

        // Fetch subscription document
        let subFields = await getFirestoreSubscription(uid, env);
        const nowMs = Date.now();

        if (subFields?.error) {
          return jsonResponse({ error: "Subscription status verification unavailable. Please try again." }, 503, request);
        }

        if (!subFields || !subFields.exists) {
          return jsonResponse({ error: "No subscription document found. Ensure subscription first." }, 404, request);
        }

        const status = subFields.subscriptionStatus?.stringValue || subFields.status?.stringValue || "NONE";
        const planId = subFields.planId?.stringValue || "reeltrak_pro_monthly";
        const subscriptionId = subFields.subscriptionId?.stringValue || null;

        let currentPeriodEndMs = 0;
        const rawEnd = subFields.currentPeriodEnd || subFields.currentPeriodEndDate || subFields.expiryDate || subFields.expiresAt || subFields.expirationDate || subFields.premiumExpiry || subFields.validUntil || subFields.current_period_end;
        if (rawEnd?.timestampValue) {
          currentPeriodEndMs = new Date(rawEnd.timestampValue).getTime();
        } else if (rawEnd?.integerValue) {
          const num = parseInt(rawEnd.integerValue, 10);
          currentPeriodEndMs = num < 1e12 ? num * 1000 : num;
        } else if (rawEnd?.stringValue) {
          currentPeriodEndMs = new Date(rawEnd.stringValue).getTime();
        }

        let trialEndMs = 0;
        const rawTrial = subFields.trialEnd || subFields.trialEndDate || subFields.trialExpiresAt || subFields.trialExpiry || subFields.trial_end;
        if (rawTrial?.timestampValue) {
          trialEndMs = new Date(rawTrial.timestampValue).getTime();
        } else if (rawTrial?.integerValue) {
          const num = parseInt(rawTrial.integerValue, 10);
          trialEndMs = num < 1e12 ? num * 1000 : num;
        } else if (rawTrial?.stringValue) {
          trialEndMs = new Date(rawTrial.stringValue).getTime();
        }

        let accessMode = null;
        let expiresAtMs = 0;

        const isPaidActive = status !== "HALTED" && currentPeriodEndMs > 0 && nowMs < currentPeriodEndMs;

        // Trial is active if paid is not active and trialEnd is in the future.
        const isTrialActive = !isPaidActive && status !== "HALTED" && trialEndMs > 0 && nowMs < trialEndMs;

        if (isPaidActive) {
          accessMode = "ACTIVE";
          expiresAtMs = currentPeriodEndMs;
          if (status !== "ACTIVE" && status !== "CANCELLED") {
            try {
              await updateFirestoreSubscription(uid, {
                subscriptionStatus: { stringValue: "ACTIVE" },
                updatedAt: { timestampValue: new Date().toISOString() }
              }, env);
            } catch (reconcileErr) {
              console.warn("Non-fatal: Failed to auto-reconcile paid subscription status during lease issuance:", reconcileErr?.name || "Error");
            }
          }
        } else if (isTrialActive) {
          accessMode = "TRIAL";
          expiresAtMs = trialEndMs;
          // If status was stored as EXPIRED or NONE, auto-reconcile Firestore to TRIAL
          if (status !== "TRIAL") {
            try {
              await updateFirestoreSubscription(uid, {
                subscriptionStatus: { stringValue: "TRIAL" },
                updatedAt: { timestampValue: new Date().toISOString() }
              }, env);
            } catch (reconcileErr) {
              console.warn("Non-fatal: Failed to auto-reconcile subscription status during lease issuance:", reconcileErr?.name || "Error");
            }
          }
        } else {
          return jsonResponse({
            error: "Subscription is not active or has expired.",
            status: status,
            accessMode: null
          }, 403, request);
        }

        // Set token lease validity to the actual authoritative subscription expiration.
        // Entitlement is validated locally against this signed lease until expiration without routine polling.
        const leaseValidUntilMs = expiresAtMs;
        const integrityDigest = "com.reeltrak.app:df485c640bd322464a72171a516cadf3a3cbb217d0a52111f608a0300c374b66";
        const leaseVersion = 1;
        const nonce = crypto.randomUUID();

        // Canonical payload string matching Android computePayload()
        const payloadStr = `${uid}:${planId}:${accessMode}:${nowMs}:${expiresAtMs}:${leaseValidUntilMs}:${integrityDigest}:${leaseVersion}:${nonce}`;
        const signature = await signLeasePayload(payloadStr, env);

        return jsonResponse({
          uid: uid,
          planId: planId,
          accessMode: accessMode,
          issuedAtMs: nowMs,
          expiresAtMs: expiresAtMs,
          leaseValidUntilMs: leaseValidUntilMs,
          integrityDigest: integrityDigest,
          leaseVersion: leaseVersion,
          nonce: nonce,
          subscriptionId: subscriptionId,
          signature: signature
        }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Endpoint 1: POST /api/create-subscription (Razorpay Recurring Autopay)
      // Rate Limit: 5 req / 60s per UID & 20 req / 3600s per IP
      // ---------------------------------------------------------------------
      if (path === "/api/create-subscription") {
        if (request.method !== "POST") {
          return jsonResponse({ error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_create_sub_${uid}`, 5, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_create_sub_${clientIp}`, 20, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const accountDoc = await getFirestoreAccountStatus(uid, env);
        if (accountDoc?.error) {
          return jsonResponse({ error: "Account status verification unavailable. Please try again." }, 503, request);
        }
        const accountStatus = accountDoc?.accountStatus?.stringValue || "ACTIVE";
        if (accountStatus === "PENDING_DELETION" || accountStatus === "DELETED" || accountStatus === "RESTRICTED") {
          return jsonResponse({ error: `Account status is ${accountStatus}. Subscriptions cannot be created.` }, 403, request);
        }

        const body = await request.json().catch(() => ({}));
        const reqPlanId = body.planId;
        const planValidation = validateRazorpayPlanId(env);
        if (!planValidation.valid) {
          console.error("Razorpay plan validation failed:", planValidation.error);
          return jsonResponse({ error: planValidation.error }, 500, request);
        }
        const configuredPlanId = planValidation.planId;

        if (reqPlanId && reqPlanId !== configuredPlanId && reqPlanId !== "plan_monthly") {
          return jsonResponse({ error: "Invalid planId requested." }, 400, request);
        }

        // 1. Guard against duplicate recurring mandates for already-active or in-progress accounts
        const existingSub = await getFirestoreSubscription(uid, env);
        if (existingSub?.error) {
          return jsonResponse({ error: "Subscription verification unavailable. Please try again." }, 503, request);
        }
        if (existingSub && existingSub.exists) {
          const currentStatus = existingSub.subscriptionStatus?.stringValue || existingSub.status?.stringValue || "NONE";
          let currentPeriodEndMs = 0;
          const rawEnd = existingSub.currentPeriodEnd || existingSub.currentPeriodEndDate;
          if (rawEnd?.timestampValue) {
            currentPeriodEndMs = new Date(rawEnd.timestampValue).getTime();
          } else if (rawEnd?.integerValue) {
            const num = parseInt(rawEnd.integerValue, 10);
            currentPeriodEndMs = num < 1e12 ? num * 1000 : num;
          } else if (rawEnd?.stringValue) {
            currentPeriodEndMs = new Date(rawEnd.stringValue).getTime();
          }

          const now = Date.now();
          if (currentStatus === "ACTIVE" && currentPeriodEndMs > now) {
            return jsonResponse({
              error: "Account already has an active recurring subscription.",
              code: "SUBSCRIPTION_ALREADY_ACTIVE",
              subscriptionId: existingSub.subscriptionId?.stringValue || null,
              currentPeriodEnd: currentPeriodEndMs
            }, 409, request);
          }

          if (existingSub.mandateStatus?.stringValue === "AUTHENTICATED" && currentStatus !== "ACTIVE") {
            return jsonResponse({
              error: "A subscription mandate is already pending activation.",
              code: "MANDATE_PENDING_ACTIVATION",
              subscriptionId: existingSub.subscriptionId?.stringValue || null
            }, 409, request);
          }
        }

        const credValidation = validateRazorpayCredentials(env);
        if (!credValidation.valid) {
          console.error("Razorpay credential validation failed:", credValidation.error);
          return jsonResponse({ error: credValidation.error || "Internal Server Error" }, 500, request);
        }

        const keyId = credValidation.keyId;
        const keySecret = credValidation.keySecret;

        // 2. Distributed atomic reservation & lock coordination across Worker isolates
        const nowMs = Date.now();
        const lockResult = await claimSubscriptionCreationLockAtomic(uid, nowMs, env);
        if (!lockResult.claimed) {
          if (lockResult.canReuse && lockResult.subscriptionId) {
            return jsonResponse({
              subscriptionId: lockResult.subscriptionId,
              keyId: keyId,
              amount: 7900,
              currency: "INR"
            }, 200, request);
          }
          return jsonResponse({
            error: "Subscription creation is already in progress. Please wait a moment."
          }, 409, request);
        }

        const acquiredRevision = lockResult.updateTime;
        const authHeaderValue = "Basic " + btoa(`${keyId}:${keySecret}`);
        let rzpRes;
        try {
          rzpRes = await fetch("https://api.razorpay.com/v1/subscriptions", {
            method: "POST",
            headers: {
              "Authorization": authHeaderValue,
              "Content-Type": "application/json"
            },
            body: JSON.stringify({
              plan_id: configuredPlanId,
              total_count: 120, // 120 monthly cycles (10 years recurring)
              quantity: 1,
              customer_notify: 1,
              notes: {
                firebase_uid: uid,
                plan_id: configuredPlanId
              }
            })
          });
        } catch (fetchErr) {
          console.warn("Network error during Razorpay subscription creation:", fetchErr?.name || "Error");
          await releaseSubscriptionCreationLockOnError(uid, acquiredRevision, env);
          return jsonResponse({
            error: "Payment gateway connection timeout. Please try again."
          }, 504, request);
        }

        if (!rzpRes.ok) {
          await releaseSubscriptionCreationLockOnError(uid, acquiredRevision, env);
          const errorCategory = rzpRes.status === 400 ? "BAD_REQUEST" :
                                rzpRes.status === 401 ? "UNAUTHORIZED" :
                                rzpRes.status === 403 ? "FORBIDDEN" :
                                rzpRes.status === 404 ? "NOT_FOUND" :
                                rzpRes.status === 422 ? "UNPROCESSABLE_ENTITY" :
                                rzpRes.status === 429 ? "RATE_LIMITED" :
                                rzpRes.status >= 500 ? "GATEWAY_ERROR" : "GATEWAY_REJECTED";
          console.error(`Razorpay subscription creation failed: HTTP ${rzpRes.status} (${errorCategory})`);
          return jsonResponse({ error: "Razorpay subscription creation failed" }, 500, request);
        }

        const subData = await rzpRes.json();
        
        // Record subscription ownership mapping and finalize lock reservation
        // If Firestore mapping or lock write encounters transient or permanent errors,
        // we do NOT discard or re-create the subscription at Razorpay; we log and return the valid ID.
        // Webhook processing and /api/verify-subscription resolve and backfill ownership from notes.
        const mapResult = await recordSubscriptionUidMap(subData.id, uid, configuredPlanId, env);
        if (!mapResult?.success) {
          console.warn("Non-fatal: initial subscription mapping persistence encountered an issue. Will self-heal on verification.");
        }

        const finalizeResult = await finalizeSubscriptionCreationLock(uid, subData.id, nowMs, acquiredRevision, env);
        if (finalizeResult?.superseded) {
          console.warn("Lock reservation was superseded by another request during checkout initialization.");
        }

        return jsonResponse({
          subscriptionId: subData.id,
          keyId: keyId,
          amount: 7900,
          currency: "INR"
        }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Endpoint 2: POST /api/verify-subscription (Server Validation of Mandate)
      // Rate Limit: 5 req / 60s per UID & 20 req / 3600s per IP
      // ---------------------------------------------------------------------
      if (path === "/api/verify-subscription") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_verify_sub_${uid}`, 5, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_verify_sub_${clientIp}`, 20, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const accountDoc = await getFirestoreAccountStatus(uid, env);
        if (accountDoc?.error) {
          return jsonResponse({ success: false, error: "Account status verification unavailable. Please try again." }, 503, request);
        }
        const accountStatus = accountDoc?.accountStatus?.stringValue || "ACTIVE";
        if (accountStatus === "PENDING_DELETION" || accountStatus === "DELETED" || accountStatus === "RESTRICTED") {
          return jsonResponse({ success: false, error: `Account status is ${accountStatus}. Subscription verification rejected.` }, 403, request);
        }

        const body = await request.json().catch(() => ({}));
        const { razorpay_subscription_id, subscription_id, razorpay_payment_id, payment_id, razorpay_signature, signature } = body;

        const subId = typeof (razorpay_subscription_id || subscription_id) === "string" ? (razorpay_subscription_id || subscription_id).trim() : "";
        const payId = typeof (razorpay_payment_id || payment_id) === "string" ? (razorpay_payment_id || payment_id).trim() : "";
        const sig = typeof (razorpay_signature || signature) === "string" ? (razorpay_signature || signature).trim() : "";

        if (!subId || !payId || !sig) {
          return jsonResponse({ success: false, error: "Missing required subscription verification fields" }, 400, request);
        }

        const credValidation = validateRazorpayCredentials(env);
        if (!credValidation.valid) {
          console.error("Razorpay credential validation failed:", credValidation.error);
          return jsonResponse({ success: false, error: credValidation.error || "Internal Server Error" }, 500, request);
        }

        const keyId = credValidation.keyId;
        const keySecret = credValidation.keySecret;

        // Razorpay Subscriptions Signature format: razorpay_payment_id + "|" + razorpay_subscription_id
        const isSigValid = await verifyHmacSignature(`${payId}|${subId}`, sig, keySecret);
        if (!isSigValid) {
          console.error("Subscription verification failed: signature mismatch.");
          return jsonResponse({ success: false, error: "Signature verification failed: Invalid signature" }, 400, request);
        }

        const authHeaderValue = "Basic " + btoa(`${keyId}:${keySecret}`);
        let rzpRes;
        try {
          rzpRes = await fetch(`https://api.razorpay.com/v1/subscriptions/${encodeURIComponent(subId)}`, {
            headers: { "Authorization": authHeaderValue }
          });
        } catch (lookupError) {
          console.warn("Razorpay subscription lookup failed temporarily:", lookupError?.name || "Error");
          return jsonResponse({ success: false, error: "Subscription verification is temporarily unavailable. Please retry." }, 503, request, { "Retry-After": "5" });
        }

        if (rzpRes.status === 429) {
          const upstreamRetryAfter = rzpRes.headers.get("Retry-After");
          const retryAfter = /^\d{1,3}$/.test(upstreamRetryAfter || "") ? upstreamRetryAfter : "5";
          return jsonResponse({ success: false, error: "Razorpay is rate limiting verification. Please retry." }, 429, request, { "Retry-After": retryAfter });
        }
        if (rzpRes.status >= 500) {
          return jsonResponse({ success: false, error: "Subscription verification is temporarily unavailable. Please retry." }, 503, request, { "Retry-After": "5" });
        }
        if (!rzpRes.ok) {
          const status = rzpRes.status === 401 || rzpRes.status === 403 ? 502 : 400;
          const error = status === 502
            ? "Payment gateway authorization is unavailable. Please retry later."
            : "Razorpay subscription was not found or could not be verified.";
          return jsonResponse({ success: false, error }, status, request, status === 502 ? { "Retry-After": "5" } : {});
        }

        let subscription;
        try {
          subscription = await rzpRes.json();
        } catch (malformedResponseError) {
          console.warn("Razorpay subscription lookup returned malformed JSON.");
          return jsonResponse({ success: false, error: "Payment gateway returned an invalid verification response. Please retry." }, 503, request, { "Retry-After": "5" });
        }
        if (!subscription || typeof subscription !== "object" || Array.isArray(subscription) || subscription.id !== subId) {
          console.warn("Razorpay subscription lookup returned an invalid entity.");
          return jsonResponse({ success: false, error: "Payment gateway returned an invalid verification response. Please retry." }, 503, request, { "Retry-After": "5" });
        }
        
        // Confirm subscription matches configured Plan ID
        const configuredPlanId = env.RAZORPAY_PLAN_ID?.trim();
        if (configuredPlanId && subscription.plan_id !== configuredPlanId) {
          return jsonResponse({ success: false, error: "Subscription plan does not match configured plan." }, 400, request);
        }

        // Confirm subscription UID ownership (Fail-closed & consistency check)
        const noteUid = subscription.notes?.firebase_uid;
        const mapUid = await lookupSubscriptionUid(subId, env);

        // Consistency check: If both Razorpay notes and server mapping exist, reject conflicting ownership
        if (noteUid && mapUid && noteUid !== mapUid) {
          console.warn("Subscription verification rejected: conflicting ownership between notes and server map.");
          return jsonResponse({ success: false, error: "Subscription ownership verification conflict." }, 403, request);
        }

        const trustedOwnerUid = noteUid || mapUid;
        if (!trustedOwnerUid) {
          console.warn("Subscription verification rejected: no verifiable owner in notes or server map.");
          return jsonResponse({ success: false, error: "Subscription has no verifiable owner." }, 403, request);
        }

        if (trustedOwnerUid !== uid) {
          return jsonResponse({ success: false, error: "Subscription belongs to a different account." }, 403, request);
        }

        // Ensure mapping is stored if notes had UID but mapping was not yet recorded
        if (noteUid && !mapUid) {
          await recordSubscriptionUidMap(subId, uid, subscription.plan_id, env);
        }

        // Verify status is active / authenticated
        if (subscription.status !== "active" && subscription.status !== "authenticated") {
          return jsonResponse({ success: false, error: `Subscription is in ${subscription.status} state.` }, 400, request);
        }

        const hasAuthoritativePeriod = typeof subscription.current_start === "number" && subscription.current_start > 0 &&
                                       typeof subscription.current_end === "number" && subscription.current_end > 0;

        let capturedPayment = null;
        if (payId) {
          try {
            const payRes = await fetch(`https://api.razorpay.com/v1/payments/${payId}`, {
              headers: { "Authorization": authHeaderValue }
            });
            if (payRes.ok) {
              const paymentData = await payRes.json();
              if (paymentData && typeof paymentData === "object") {
                if (paymentData.currency && paymentData.currency !== EXPECTED_PLAN_CURRENCY) {
                  console.warn(`Payment currency mismatch: expected ${EXPECTED_PLAN_CURRENCY}, got ${paymentData.currency}`);
                  return jsonResponse({ success: false, error: "Payment currency does not match configured plan." }, 400, request);
                }

                if (paymentData.status === "captured") {
                  if (typeof paymentData.amount === "number" && paymentData.amount !== EXPECTED_PLAN_AMOUNT_PAISE) {
                    console.warn(`Payment amount mismatch: expected ${EXPECTED_PLAN_AMOUNT_PAISE}, got ${paymentData.amount}`);
                    return jsonResponse({ success: false, error: "Payment amount does not match configured plan." }, 400, request);
                  }
                  const payNoteUid = paymentData.notes?.firebase_uid;
                  if (!payNoteUid || payNoteUid === uid) {
                    capturedPayment = paymentData;
                  }
                } else if (paymentData.status === "authorized") {
                  console.log(`Payment ${payId} is authorized (mandate authorization), awaiting capture.`);
                }
              }
            }
          } catch (payErr) {
            console.warn("Non-fatal: could not fetch payment entity:", payErr?.name || "Error");
          }
        }

        const isMandateOnly = (subscription.status === "authenticated" || !hasAuthoritativePeriod) && !capturedPayment && subscription.status !== "active";

        if (isMandateOnly) {
          const now = new Date();
          const fields = {
            subscriptionId: { stringValue: subId },
            planId: { stringValue: subscription.plan_id || configuredPlanId || "plan_monthly" },
            mandateStatus: { stringValue: "AUTHENTICATED" },
            updatedAt: { timestampValue: now.toISOString() }
          };
          if (payId) {
            fields.latestPaymentId = { stringValue: payId };
          }
          await updateFirestoreSubscription(uid, fields, env);
          await recordSubscriptionUidMap(subId, uid, subscription.plan_id || configuredPlanId, env);

          return jsonResponse({
            success: true,
            status: "AUTHENTICATED",
            mandatePending: true,
            message: "Mandate authorized successfully. Activation pending initial payment confirmation.",
            subscriptionId: subId
          }, 200, request);
        }

        if (!hasAuthoritativePeriod) {
          const existingSubscription = await getFirestoreSubscription(uid, env);
          const existingStartMs = Date.parse(existingSubscription?.currentPeriodStart?.timestampValue || "");
          const existingEndMs = Date.parse(existingSubscription?.currentPeriodEnd?.timestampValue || "");
          const hasCurrentVerifiedEntitlement = existingSubscription?.exists &&
            existingSubscription.subscriptionId?.stringValue === subId &&
            existingSubscription.subscriptionStatus?.stringValue === "ACTIVE" &&
            existingSubscription.premium?.booleanValue === true &&
            Number.isFinite(existingStartMs) && Number.isFinite(existingEndMs) && existingEndMs > Date.now();

          if (hasCurrentVerifiedEntitlement) {
            return jsonResponse({
              success: true,
              status: "ACTIVE",
              subscriptionId: subId,
              message: "Existing verified subscription period preserved; Razorpay did not provide authoritative period dates."
            }, 200, request);
          }

          return jsonResponse({
            success: false,
            error: "Razorpay did not provide authoritative billing-period dates. Please retry verification."
          }, 503, request);
        }

        const startMs = subscription.current_start < 1e12 ? subscription.current_start * 1000 : subscription.current_start;
        const endMs = subscription.current_end < 1e12 ? subscription.current_end * 1000 : subscription.current_end;

        if (endMs <= startMs) {
          console.warn(`Invalid billing period order: startMs=${startMs}, endMs=${endMs}`);
          return jsonResponse({
            success: false,
            error: "Invalid billing period: period end must be after period start."
          }, 400, request);
        }

        const durationDays = (endMs - startMs) / (24 * 60 * 60 * 1000);
        if (durationDays < 26 || durationDays > 35) {
          console.warn(`Non-monthly billing cycle duration detected: ${durationDays} days`);
          return jsonResponse({
            success: false,
            error: "Subscription billing period is not a valid monthly interval."
          }, 400, request);
        }

        const result = await reconcileSubscriptionActivation(uid, {
          id: subscription.id,
          plan_id: subscription.plan_id,
          latestPaymentId: payId,
          current_start: startMs,
          current_end: endMs,
          charge_at: subscription.charge_at,
          ended_at: subscription.ended_at
        }, env);

        if (!result?.success) {
          return jsonResponse(result, 400, request);
        }

        return jsonResponse(result, 200, request);
      }

      // ---------------------------------------------------------------------
      // Endpoint 3: POST /api/cancel-subscription (End-of-Cycle Cancellation)
      // Rate Limit: 5 req / 60s per UID & 20 req / 3600s per IP
      // ---------------------------------------------------------------------
      if (path === "/api/cancel-subscription") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_cancel_sub_${uid}`, 5, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_cancel_sub_${clientIp}`, 20, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const subDoc = await getFirestoreSubscription(uid, env);
        if (subDoc?.error) {
          return jsonResponse({ success: false, error: "Subscription verification unavailable. Please try again." }, 503, request);
        }
        if (!subDoc || !subDoc.exists) {
          return jsonResponse({ success: false, error: "No active recurring subscription found for this account." }, 400, request);
        }
        const subId = subDoc.subscriptionId?.stringValue;

        if (!subId || !subId.startsWith("sub_")) {
          return jsonResponse({ success: false, error: "No active recurring subscription found for this account." }, 400, request);
        }

        const credValidation = validateRazorpayCredentials(env);
        if (!credValidation.valid) {
          console.error("Razorpay credential validation failed:", credValidation.error);
          return jsonResponse({ success: false, error: credValidation.error || "Internal Server Error" }, 500, request);
        }

        const keyId = credValidation.keyId;
        const keySecret = credValidation.keySecret;
        const authHeaderValue = "Basic " + btoa(`${keyId}:${keySecret}`);

        // Cancel on Razorpay at cycle end (cancel_at_cycle_end: 1)
        const rzpRes = await fetch(`https://api.razorpay.com/v1/subscriptions/${subId}/cancel`, {
          method: "POST",
          headers: {
            "Authorization": authHeaderValue,
            "Content-Type": "application/json"
          },
          body: JSON.stringify({ cancel_at_cycle_end: 1 })
        });

        if (!rzpRes.ok) {
          const cancelCategory = rzpRes.status === 400 ? "BAD_REQUEST" :
                                 rzpRes.status === 401 ? "UNAUTHORIZED" :
                                 rzpRes.status === 404 ? "NOT_FOUND" :
                                 rzpRes.status >= 500 ? "GATEWAY_ERROR" : "GATEWAY_REJECTED";
          console.error(`Razorpay subscription cancel failed: HTTP ${rzpRes.status} (${cancelCategory})`);
          return jsonResponse({ success: false, error: "Failed to cancel subscription with payment gateway." }, 500, request);
        }

        const cancelledSub = await rzpRes.json();
        const result = await reconcileSubscriptionCancelled(
          uid,
          subId,
          true,
          cancelledSub.current_end,
          env,
          null,
          { created_at: Math.floor(Date.now() / 1000), payload: { subscription: { entity: cancelledSub } } }
        );

        let finalPeriodEnd = subDoc?.currentPeriodEnd?.timestampValue || null;
        if (typeof cancelledSub.current_end === "number" && cancelledSub.current_end > 0) {
          const endMs = cancelledSub.current_end < 1e12 ? cancelledSub.current_end * 1000 : cancelledSub.current_end;
          finalPeriodEnd = new Date(endMs).toISOString();
        }

        return jsonResponse({
          success: true,
          message: "Subscription cancelled successfully. Access remains active until the end of the billing period.",
          cancelAtPeriodEnd: true,
          currentPeriodEnd: finalPeriodEnd
        }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Endpoint: POST /api/restore-subscription (Restore & Reconcile Purchases)
      // Rate Limit: 5 req / 60s per UID & 20 req / 3600s per IP
      // ---------------------------------------------------------------------
      if (path === "/api/restore-subscription") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_restore_sub_${uid}`, 5, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_restore_sub_${clientIp}`, 20, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const accountDoc = await getFirestoreAccountStatus(uid, env);
        if (accountDoc?.error) {
          return jsonResponse({ success: false, error: "Account status verification unavailable. Please try again." }, 503, request);
        }
        const accountStatus = accountDoc?.accountStatus?.stringValue || "ACTIVE";
        if (accountStatus === "PENDING_DELETION" || accountStatus === "DELETED" || accountStatus === "RESTRICTED") {
          return jsonResponse({ success: false, error: `Account status is ${accountStatus}. Subscription restore rejected.` }, 403, request);
        }

        const body = await request.json().catch(() => ({}));
        const requestedSubId = typeof (body.subscription_id || body.subscriptionId || body.razorpay_subscription_id) === "string"
          ? (body.subscription_id || body.subscriptionId || body.razorpay_subscription_id).trim()
          : "";

        const candidateSubIds = new Set();
        if (requestedSubId && requestedSubId.startsWith("sub_")) {
          candidateSubIds.add(requestedSubId);
        }

        // 1. Check existing user subscription status doc
        const existingDoc = await getFirestoreSubscription(uid, env);
        if (existingDoc && existingDoc.exists && existingDoc.fields) {
          const currentSubId = existingDoc.fields.subscriptionId?.stringValue;
          if (currentSubId && currentSubId.startsWith("sub_")) {
            candidateSubIds.add(currentSubId);
          }
        }

        // 2. Query subscription_map for this UID
        const mappedSubIds = await findSubscriptionsByUid(uid, env);
        for (const id of mappedSubIds) {
          candidateSubIds.add(id);
        }

        // If no candidate subscriptions found:
        if (candidateSubIds.size === 0) {
          const currentPeriodEndMs = Date.parse(existingDoc?.fields?.currentPeriodEnd?.timestampValue || "");
          const isPeriodActive = Number.isFinite(currentPeriodEndMs) ? (Date.now() < currentPeriodEndMs) : true;
          const isCurrentlyActive = (existingDoc?.fields?.premium?.booleanValue === true ||
            existingDoc?.fields?.subscriptionStatus?.stringValue === "ACTIVE") && isPeriodActive;
          return jsonResponse({
            success: true,
            restored: false,
            message: isCurrentlyActive ? "Active subscription already present on account." : "No active subscription found to restore.",
            status: isCurrentlyActive ? "ACTIVE" : (existingDoc?.fields?.subscriptionStatus?.stringValue || "NONE")
          }, 200, request);
        }

        // 3. Inspect and reconcile candidates with Razorpay
        let restoredResult = null;
        for (const candidateId of candidateSubIds) {
          const subscription = await fetchAndVerifyRazorpaySubscription(candidateId, uid, env);
          if (!subscription) continue;

          if (subscription.status === "created") {
            const credentials = validateRazorpayCredentials(env);
            if (!credentials.valid) {
              return jsonResponse({ success: false, error: "Subscription recovery is temporarily unavailable." }, 503, request, { "Retry-After": "5" });
            }
            if (subscription.plan_id !== env.RAZORPAY_PLAN_ID?.trim()) continue;
            restoredResult = {
              success: true,
              restored: false,
              status: "CREATED",
              subscriptionId: candidateId,
              keyId: credentials.keyId,
              amount: 7900,
              currency: "INR",
              message: "Existing checkout can be resumed."
            };
            break;
          } else if (subscription.status === "active") {
            const activationResult = await reconcileSubscriptionActivation(uid, subscription, env);
            if (activationResult && activationResult.success) {
              restoredResult = {
                success: true,
                restored: true,
                status: "ACTIVE",
                subscriptionId: candidateId,
                currentPeriodStart: activationResult.currentPeriodStart,
                currentPeriodEnd: activationResult.currentPeriodEnd,
                message: "Subscription restored successfully."
              };
              break;
            }
          } else if (subscription.status === "authenticated") {
            restoredResult = {
              success: true,
              restored: false,
              mandatePending: true,
              status: "AUTHENTICATED",
              subscriptionId: candidateId,
              message: "Subscription mandate is authenticated but awaiting initial charge confirmation."
            };
            break;
          } else if (subscription.status === "pending") {
            await reconcileSubscriptionPending(uid, candidateId, env);
            restoredResult = {
              success: true,
              restored: true,
              status: "PENDING",
              subscriptionId: candidateId,
              message: "Subscription is pending payment verification."
            };
            break;
          } else if (subscription.status === "completed") {
            await reconcileSubscriptionCompleted(uid, candidateId, subscription.current_end, env);
            const isStillInPeriod = subscription.current_end ? (Date.now() < (subscription.current_end < 1e12 ? subscription.current_end * 1000 : subscription.current_end)) : false;
            restoredResult = {
              success: true,
              restored: isStillInPeriod,
              status: isStillInPeriod ? "ACTIVE" : "EXPIRED",
              subscriptionId: candidateId,
              message: isStillInPeriod ? "Subscription completed all cycles. Access remains active until period end." : "Subscription completed all cycles and has expired."
            };
            if (isStillInPeriod) break;
          } else if (subscription.status === "cancelled") {
            const isCancelAtCycleEnd = subscription.cancel_at_cycle_end === 1 || subscription.cancel_at_cycle_end === true;
            await reconcileSubscriptionCancelled(uid, candidateId, isCancelAtCycleEnd, subscription.current_end, env);
            const isStillInPeriod = subscription.current_end ? (Date.now() < (subscription.current_end < 1e12 ? subscription.current_end * 1000 : subscription.current_end)) : false;
            if (isCancelAtCycleEnd && isStillInPeriod) {
              restoredResult = {
                success: true,
                restored: true,
                status: "ACTIVE",
                cancelAtPeriodEnd: true,
                subscriptionId: candidateId,
                currentPeriodEnd: subscription.current_end ? new Date(subscription.current_end < 1e12 ? subscription.current_end * 1000 : subscription.current_end).toISOString() : null,
                message: "Subscription cancelled at period end. Access remains active until billing period ends."
              };
              break;
            } else {
              restoredResult = {
                success: true,
                restored: false,
                status: "CANCELLED",
                subscriptionId: candidateId,
                message: "Subscription is cancelled."
              };
            }
          } else if (subscription.status === "halted" || subscription.status === "expired") {
            await reconcileSubscriptionHalted(uid, candidateId, env);
            restoredResult = {
              success: true,
              restored: false,
              status: "EXPIRED",
              subscriptionId: candidateId,
              message: "Subscription has expired."
            };
          }
        }

        if (restoredResult) {
          return jsonResponse(restoredResult, 200, request);
        }

        return jsonResponse({
          success: true,
          restored: false,
          message: "No active subscription found to restore.",
          status: existingDoc?.fields?.subscriptionStatus?.stringValue || "NONE"
        }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Authoritative Cloud Data Backup: GET /api/backup/status
      // Rate Limit: 30 req / 60s per UID
      // ---------------------------------------------------------------------
      if (path === "/api/backup/status") {
        if (request.method !== "GET") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use GET." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_backup_status_${uid}`, 30, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_backup_status_${clientIp}`, 60, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const entitlement = await verifyUserSubscriptionEntitlement(uid, env);
        if (!entitlement.entitled) {
          return jsonResponse({ success: false, error: entitlement.reason, code: "ENTITLEMENT_REQUIRED" }, 403, request);
        }

        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        const nextEligibleTimeMs = getNextIstMidnightMs(nowMs);
        const remainingCooldownMs = Math.max(0, nextEligibleTimeMs - nowMs);

        // Parallel fetch of backup status doc, today's backup quota claim, and today's restore quota claim
        const [doc, todayClaimDoc, todayRestoreClaimDoc] = await Promise.all([
          getFirestoreBackupStatus(uid, env),
          getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_BACKUP`, env),
          getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_RESTORE`, env)
        ]);

        const parsed = parseBackupStatusDoc(doc);
        const parsedClaim = parseQuotaClaimDoc(todayClaimDoc);
        const parsedRestoreClaim = parseQuotaClaimDoc(todayRestoreClaimDoc);

        const lastBackupTimeMs = parsed.lastBackupTimeMs || parsedClaim.committedAtMs || 0;
        const backedUpToday = parsedClaim.status === "COMPLETED" || (parsed.status === "SUCCESS" && parsed.lastBackupDate === todayIstDate);
        const isRestricted = backedUpToday;

        const restoredToday = parsedRestoreClaim.status === "ACKNOWLEDGED";
        const canRestoreNow = !restoredToday && parsedRestoreClaim.status !== "DISPATCHED" && parsedRestoreClaim.status !== "EXHAUSTED_ATTEMPTS";

        return jsonResponse({
          success: true,
          isRestricted,
          canBackupNow: !isRestricted && parsed.status !== "IN_PROGRESS",
          backedUpToday,
          lastBackupDate: parsed.lastBackupDate || (parsedClaim.status === "COMPLETED" ? todayIstDate : null),
          lastBackupTimeMs,
          nextEligibleTimeMs: isRestricted ? nextEligibleTimeMs : nowMs,
          nextIstMidnightMs: nextEligibleTimeMs,
          remainingCooldownMs: isRestricted ? remainingCooldownMs : 0,
          serverTimeMs: nowMs,
          serverDate: todayIstDate,
          istDate: todayIstDate,
          activeBackupId: parsed.activeBackupId || parsedClaim.backupId || null,
          restoredToday,
          canRestoreNow,
          isRestoreRestricted: restoredToday
        }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Authoritative Cloud Data Backup: POST /api/backup (Strict 1/24h/UID Limit)
      // Rate Limit: 10 req / 60s per UID & 30 req / 3600s per IP
      // ---------------------------------------------------------------------
      if (path === "/api/backup") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_backup_${uid}`, 10, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_backup_${clientIp}`, 30, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        // 1. Verify authoritative Trial or Premium entitlement
        const entitlement = await verifyUserSubscriptionEntitlement(uid, env);
        if (!entitlement.entitled) {
          return jsonResponse({ success: false, error: entitlement.reason, code: "ENTITLEMENT_REQUIRED" }, 403, request);
        }

        // 2. Parse and validate request body
        const body = await request.json().catch(() => ({}));
        const rawRequestId = (typeof body.requestId === "string" && body.requestId.trim()) ||
                             (typeof body.idempotencyKey === "string" && body.idempotencyKey.trim()) || null;
        if (!rawRequestId) {
          return jsonResponse({ success: false, error: "Missing or invalid requestId" }, 400, request);
        }
        const requestId = rawRequestId;

        const rawDailyRecords = Array.isArray(body.dailyRecords) ? body.dailyRecords : (Array.isArray(body.records) ? body.records : []);
        if (rawDailyRecords.length > 365) {
          return jsonResponse({ success: false, error: "dailyRecords exceeds maximum allowed limit of 365 days." }, 400, request);
        }
        const dailyRecords = rawDailyRecords;
        const defaultDailyLimit = typeof body.defaultDailyLimit === "number" ? body.defaultDailyLimit : 0;

        // 3. Determine authoritative server dates
        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        const yesterdayIstDate = getIstDateString(nowMs - 86400000);
        const nextEligibleTimeMs = getNextIstMidnightMs(nowMs);
        const remainingCooldownMs = Math.max(0, nextEligibleTimeMs - nowMs);

        // 4. Parallel fetch of today claim, yesterday claim, and backup status pointer
        const [todayClaimDoc, yesterdayClaimDoc, currentDoc] = await Promise.all([
          getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_BACKUP`, env),
          getFirestoreDocument(`daily_quota_claims/${uid}_${yesterdayIstDate}_BACKUP`, env),
          getFirestoreBackupStatus(uid, env)
        ]);

        const todayClaim = parseQuotaClaimDoc(todayClaimDoc);
        const yesterdayClaim = parseQuotaClaimDoc(yesterdayClaimDoc);
        const parsed = parseBackupStatusDoc(currentDoc);

        // 5. Cross-Midnight resolution (Section 6.4)
        if (yesterdayClaim.exists && yesterdayClaim.idempotencyKey === requestId) {
          if (yesterdayClaim.status === "COMPLETED") {
            return jsonResponse({
              success: true,
              idempotent: true,
              message: "Backup already completed within the last 24 hours",
              backupDate: yesterdayClaim.claimDate || yesterdayIstDate,
              istDate: yesterdayIstDate,
              lastBackupTimeMs: yesterdayClaim.committedAtMs || nowMs,
              nextEligibleTimeMs,
              remainingCooldownMs,
              serverTimeMs: nowMs,
              activeBackupId: yesterdayClaim.backupId
            }, 200, request);
          }
          if (yesterdayClaim.status === "IN_PROGRESS") {
            if (nowMs >= yesterdayClaim.expiresAtMs) {
              return jsonResponse({
                success: false,
                code: "CALENDAR_DAY_EXPIRED",
                error: "Backup lease expired across calendar day boundary. Please retry with a new request."
              }, 409, request);
            }
          }
        }

        // 6. Same-day completion check
        const lastBackupTimeMs = parsed.lastBackupTimeMs || todayClaim.committedAtMs || 0;
        const isRestrictedToday = todayClaim.status === "COMPLETED" ||
          (parsed.status === "SUCCESS" && parsed.lastBackupDate === todayIstDate);

        if (isRestrictedToday) {
          // Idempotent retry with matching requestId
          if ((todayClaim.idempotencyKey && todayClaim.idempotencyKey === requestId) ||
              (parsed.lastRequestId && parsed.lastRequestId === requestId)) {
            return jsonResponse({
              success: true,
              idempotent: true,
              message: "Backup already completed within the last 24 hours",
              backupDate: parsed.lastBackupDate || todayIstDate,
              istDate: todayIstDate,
              lastBackupTimeMs: lastBackupTimeMs || nowMs,
              nextEligibleTimeMs,
              remainingCooldownMs,
              serverTimeMs: nowMs,
              activeBackupId: parsed.activeBackupId || todayClaim.backupId || null
            }, 200, request);
          }

          // Distinct request within restriction window
          return jsonResponse({
            success: false,
            error: "You can back up your data once every 24 hours.",
            code: "BACKUP_RESTRICTED_24_HOURS",
            backupDate: parsed.lastBackupDate || todayIstDate,
            istDate: todayIstDate,
            lastBackupTimeMs: lastBackupTimeMs || nowMs,
            nextEligibleTimeMs,
            remainingCooldownMs,
            serverTimeMs: nowMs
          }, 409, request);
        }

        // Anti-Data-Loss Safety Gate:
        // Prevent an empty local database or default values from ever overwriting a non-empty remote backup
        const incomingRecordCount = dailyRecords.length;
        let incomingTotalReels = 0;
        for (const rec of dailyRecords) {
          const c = Number(rec?.totalReelsCount ?? rec?.count ?? 0);
          if (!isNaN(c) && c > 0) incomingTotalReels += c;
        }

        const existingRecordCount = parsed.recordCount || 0;
        const hasExistingBackup = (parsed.status === "SUCCESS" || Boolean(parsed.activeBackupId)) || existingRecordCount > 0;
        if (hasExistingBackup && (incomingRecordCount === 0 || incomingTotalReels === 0)) {
          console.warn(`Blocked destructive empty backup overwrite attempt: incomingRecords=${incomingRecordCount}, existingRecords=${existingRecordCount}`);
          return jsonResponse({
            success: false,
            code: "DESTRUCTIVE_EMPTY_BACKUP_REJECTED",
            error: "Cannot overwrite existing remote backup with empty or zero local records. Please restore your remote data first."
          }, 409, request);
        }

        // 7. Concurrency / In-Progress Reservation Check
        let fencingToken = 1;
        if (todayClaim.status === "IN_PROGRESS" || parsed.status === "IN_PROGRESS") {
          const claimAgeMs = nowMs - (todayClaim.claimTimeMs || parsed.claimTimeMs || 0);
          const isHeld = claimAgeMs < 60000;
          if (isHeld) {
            const isSameReq = (todayClaim.idempotencyKey === requestId) || (parsed.claimRequestId === requestId);
            return jsonResponse({
              success: false,
              error: isSameReq ? "Backup is currently being processed. Please wait." : "A backup is currently in progress for this account.",
              code: "BACKUP_IN_PROGRESS",
              lastBackupTimeMs,
              nextEligibleTimeMs,
              serverTimeMs: nowMs
            }, 409, request);
          }
          // Expired lease -> replacement steals lease with monotonic fencingToken
          fencingToken = Math.max(todayClaim.fencingToken || 1, parsed.claimTimeMs ? 1 : 1) + 1;
        } else if (todayClaim.status === "FAILED") {
          // Recovery from FAILED state in-place
          fencingToken = (todayClaim.fencingToken || 1) + 1;
        }

        // 8. Atomically reserve / claim slot using Firestore precondition
        const claimResult = await claimBackupSlotAtomic(uid, todayIstDate, requestId, nowMs, currentDoc, env, fencingToken);
        if (!claimResult.claimed) {
          return jsonResponse({
            success: false,
            error: "Another backup operation is already being processed for this account.",
            code: "BACKUP_CONCURRENT_CONFLICT",
            serverTimeMs: nowMs
          }, 409, request);
        }

        // 9. Execute the backup (atomic write of daily records, settings, Manifest, and SUCCESS status)
        const backupId = claimResult.backupId || `${todayIstDate}_${fencingToken}_${requestId}`;
        try {
          await commitFirestoreBackup(uid, todayIstDate, requestId, nowMs, dailyRecords, defaultDailyLimit, env, backupId, fencingToken);

          return jsonResponse({
            success: true,
            message: "Backup completed successfully",
            backupDate: todayIstDate,
            istDate: todayIstDate,
            backupId: backupId,
            activeBackupId: backupId,
            lastBackupTimeMs: nowMs,
            nextEligibleTimeMs,
            remainingCooldownMs,
            serverTimeMs: nowMs
          }, 200, request);
        } catch (backupErr) {
          console.error("Backup commit failed:", backupErr?.name || "Error");
          await releaseBackupSlotOnError(uid, backupErr?.name || "Commit failed", env, todayIstDate, claimResult?.updateTime);
          return jsonResponse({
            success: false,
            error: "Backup operation failed. Please try again.",
            details: backupErr?.name || "Commit failed"
          }, 500, request);
        }
      }

      // ---------------------------------------------------------------------
      // Multi-Part Chunked Backup Protocol: POST /api/backup/init
      // ---------------------------------------------------------------------
      if (path === "/api/backup/init") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }
        const uidRateCheck = await checkRateLimit(`uid_backup_init_${uid}`, 10, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_backup_init_${clientIp}`, 30, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const entitlement = await verifyUserSubscriptionEntitlement(uid, env);
        if (!entitlement.entitled) {
          return jsonResponse({ success: false, error: entitlement.reason, code: "ENTITLEMENT_REQUIRED" }, 403, request);
        }

        const body = await request.json().catch(() => ({}));
        const rawRequestId = (typeof body.requestId === "string" && body.requestId.trim()) ||
                             (typeof body.idempotencyKey === "string" && body.idempotencyKey.trim()) || null;
        if (!rawRequestId) {
          return jsonResponse({ success: false, error: "Missing or invalid requestId" }, 400, request);
        }

        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        const [todayClaimDoc, currentDoc] = await Promise.all([
          getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_BACKUP`, env),
          getFirestoreBackupStatus(uid, env)
        ]);
        const todayClaim = parseQuotaClaimDoc(todayClaimDoc);
        const parsedStatus = parseBackupStatusDoc(currentDoc);

        // Anti-Data-Loss Safety Gate:
        const requestedRecordCount = typeof body.totalRecords === "number" ? body.totalRecords : (typeof body.recordCount === "number" ? body.recordCount : -1);
        const hasExistingBackupInit = (parsedStatus.status === "SUCCESS" || Boolean(parsedStatus.activeBackupId)) || parsedStatus.recordCount > 0;
        if (hasExistingBackupInit && requestedRecordCount === 0) {
          return jsonResponse({
            success: false,
            code: "DESTRUCTIVE_EMPTY_BACKUP_REJECTED",
            error: "Cannot overwrite existing remote backup with empty or zero records. Please restore your remote data first."
          }, 409, request);
        }

        if (todayClaim.status === "COMPLETED" || (parsedStatus.status === "SUCCESS" && parsedStatus.lastBackupDate === todayIstDate)) {
          const nextEligibleTimeMs = getNextIstMidnightMs(nowMs);
          return jsonResponse({
            success: false,
            code: "BACKUP_RESTRICTED_24_HOURS",
            error: "You can back up your data once every 24 hours.",
            nextEligibleTimeMs
          }, 409, request);
        }

        // Concurrency / In-Progress Reservation Check
        if (todayClaim.status === "IN_PROGRESS") {
          // Idempotent retry with matching requestId
          if (todayClaim.idempotencyKey === rawRequestId && todayClaim.backupId) {
            return jsonResponse({
              success: true,
              idempotent: true,
              backupId: todayClaim.backupId,
              fencingToken: todayClaim.fencingToken || 1,
              chunkSize: 200,
              istDate: todayIstDate
            }, 200, request);
          }
          const claimAgeMs = nowMs - (todayClaim.claimTimeMs || 0);
          if (claimAgeMs < 60000) {
            return jsonResponse({
              success: false,
              error: "A backup is currently in progress for this account.",
              code: "BACKUP_IN_PROGRESS",
              serverTimeMs: nowMs
            }, 409, request);
          }
        }

        const fencingToken = (todayClaim.fencingToken || 0) + 1;
        const backupId = `${todayIstDate}_${fencingToken}_${rawRequestId}`;

        // Initialize manifest with status: UPLOADING and quarantineStatus: null
        const writes = [
          {
            update: {
              name: `projects/${env.FIREBASE_PROJECT_ID || "refocus-504119"}/databases/(default)/documents/daily_quota_claims/${uid}_${todayIstDate}_BACKUP`,
              fields: {
                uid: { stringValue: uid },
                status: { stringValue: "IN_PROGRESS" },
                fencingToken: { integerValue: fencingToken.toString() },
                backupId: { stringValue: backupId },
                idempotencyKey: { stringValue: rawRequestId },
                claimDate: { stringValue: todayIstDate },
                claimTimeMs: { integerValue: nowMs.toString() },
                expiresAtMs: { integerValue: (nowMs + 60000).toString() },
                updatedAtMs: { integerValue: nowMs.toString() }
              }
            },
            currentDocument: todayClaimDoc && todayClaimDoc.exists && todayClaimDoc.updateTime
              ? { updateTime: todayClaimDoc.updateTime }
              : { exists: false }
          },
          {
            update: {
              name: `projects/${env.FIREBASE_PROJECT_ID || "refocus-504119"}/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
              fields: {
                backupId: { stringValue: backupId },
                status: { stringValue: "UPLOADING" },
                uploadInitiatedAtMs: { integerValue: nowMs.toString() },
                recordCount: { integerValue: (Number(body.totalRecords) || 0).toString() },
                totalChunks: { integerValue: (Number(body.totalChunks) || 1).toString() },
                activeRestorePinCount: { integerValue: "0" },
                lastPinnedAtMs: { integerValue: "0" },
                quarantineStatus: { nullValue: null },
                quarantineReason: { nullValue: null },
                quarantineInitiatedAtMs: { nullValue: null },
                quarantineObservationExpiresAtMs: { nullValue: null },
                lastQuarantineClearedAtMs: { nullValue: null },
                updatedAt: { timestampValue: new Date(nowMs).toISOString() }
              }
            }
          },
          {
            update: {
              name: `projects/${env.FIREBASE_PROJECT_ID || "refocus-504119"}/databases/(default)/documents/users/${uid}/backup_snapshots/${backupId}`,
              fields: {
                backupId: { stringValue: backupId },
                status: { stringValue: "UPLOADING" },
                uploadInitiatedAtMs: { integerValue: nowMs.toString() }
              }
            }
          }
        ];

        try {
          await commitFirestoreBatch(writes, env);
          return jsonResponse({
            success: true,
            backupId,
            fencingToken,
            chunkSize: 200,
            istDate: todayIstDate
          }, 200, request);
        } catch (err) {
          if (err?.status === 412 || err?.status === 409) {
            return jsonResponse({
              success: false,
              error: "Another backup operation is already being processed for this account.",
              code: "BACKUP_CONCURRENT_CONFLICT"
            }, 409, request);
          }
          return jsonResponse({ success: false, error: "Failed to initialize chunked backup", details: err?.message }, 500, request);
        }
      }

      // ---------------------------------------------------------------------
      // Multi-Part Chunked Backup Protocol: POST /api/backup/chunk
      // ---------------------------------------------------------------------
      if (path === "/api/backup/chunk") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_backup_chunk_${uid}`, 60, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_backup_chunk_${clientIp}`, 120, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }
        const body = await request.json().catch(() => ({}));
        const backupId = typeof body.backupId === "string" ? body.backupId.trim() : null;
        const chunkIndex = typeof body.chunkIndex === "number" ? body.chunkIndex : 0;
        const rawRecords = Array.isArray(body.dailyRecords) ? body.dailyRecords : (Array.isArray(body.records) ? body.records : []);
        const dailyRecords = rawRecords;

        if (!backupId || dailyRecords.length === 0) {
          return jsonResponse({ success: false, error: "Missing backupId or dailyRecords" }, 400, request);
        }

        const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${backupId}`, env);
        const parsedManifest = parseManifestDoc(manifestDoc);
        if (!parsedManifest.exists || parsedManifest.status !== "UPLOADING") {
          return jsonResponse({ success: false, error: "Backup manifest not found or not in UPLOADING state" }, 409, request);
        }

        const nowMs = Date.now();
        const writes = [];
        const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";

        // Child records writes
        for (const rec of dailyRecords) {
          if (!rec || !rec.dateString) continue;
          const totalVal = rec.totalReelsCount !== undefined && rec.totalReelsCount !== null ? Number(rec.totalReelsCount) : null;
          const countValRaw = rec.count !== undefined && rec.count !== null ? Number(rec.count) : null;
          let resolvedCount = 0;
          if (totalVal !== null && !isNaN(totalVal) && totalVal > 0) {
            resolvedCount = totalVal;
          } else if (countValRaw !== null && !isNaN(countValRaw) && countValRaw > 0) {
            resolvedCount = countValRaw;
          } else if (totalVal === 0 || countValRaw === 0) {
            resolvedCount = 0;
          }
          const countVal = Math.max(0, Math.min(100000, resolvedCount));
          writes.push({
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_snapshots/${backupId}/daily_data/${rec.dateString}`,
              fields: {
                dateString: { stringValue: rec.dateString },
                totalReelsCount: { integerValue: countVal.toString() },
                count: { integerValue: countVal.toString() },
                dailyGoal: { integerValue: (Math.max(0, Math.min(100000, Number(rec.dailyGoal) || 0))).toString() },
                isSkullDay: { booleanValue: Boolean(rec.isSkullDay) },
                isStrictModeOn: rec.isStrictModeOn !== undefined ? { booleanValue: Boolean(rec.isStrictModeOn) } : { booleanValue: true },
                isStrictModeLocked: { booleanValue: Boolean(rec.isStrictModeLocked) },
                isLimitLocked: { booleanValue: Boolean(rec.isLimitLocked) },
                totalTimeSpentMillis: { integerValue: (Math.max(0, Number(rec.totalTimeSpentMillis) || 0)).toString() }
              }
            }
          });
        }

        // Dual-Document precondition: mutate manifest asserting updateTime
        writes.push({
          update: {
            name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
            fields: {
              ...manifestDoc.fields,
              lastChunkAtMs: { integerValue: nowMs.toString() }
            }
          },
          currentDocument: { updateTime: manifestDoc.updateTime }
        });

        try {
          await commitFirestoreBatch(writes, env);
          return jsonResponse({ success: true, chunkIndex }, 200, request);
        } catch (err) {
          const status = err?.status === 412 || err?.status === 409 ? 412 : 500;
          return jsonResponse({ success: false, error: "Chunk commit failed", details: err?.message }, status, request);
        }
      }

      // ---------------------------------------------------------------------
      // Multi-Part Chunked Backup Protocol: POST /api/backup/finalize
      // ---------------------------------------------------------------------
      if (path === "/api/backup/finalize") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_backup_finalize_${uid}`, 10, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_backup_finalize_${clientIp}`, 30, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const body = await request.json().catch(() => ({}));
        const backupId = typeof body.backupId === "string" ? body.backupId.trim() : null;
        const requestId = typeof body.requestId === "string" ? body.requestId.trim() : null;
        const defaultDailyLimit = typeof body.defaultDailyLimit === "number" ? body.defaultDailyLimit : 0;

        if (!backupId || !requestId) {
          return jsonResponse({ success: false, error: "Missing backupId or requestId" }, 400, request);
        }

        const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${backupId}`, env);
        const parsedManifest = parseManifestDoc(manifestDoc);
        if (!parsedManifest.exists) {
          return jsonResponse({ success: false, error: "Manifest not found or not in UPLOADING state" }, 409, request);
        }

        // Anti-Data-Loss Safety Gate:
        const existingStatus = await getFirestoreBackupStatus(uid, env);
        const parsedStatus = parseBackupStatusDoc(existingStatus);
        const manifestRecordCount = parsedManifest.recordCount || 0;
        if (parsedStatus.recordCount > 0 && manifestRecordCount === 0) {
          return jsonResponse({
            success: false,
            code: "DESTRUCTIVE_EMPTY_BACKUP_REJECTED",
            error: "Cannot finalize empty backup that would overwrite existing remote backup. Please restore first."
          }, 409, request);
        }

        if (parsedManifest.status === "COMMITTED") {
          const committedTime = parsedManifest.committedAtMs || Date.now();
          const nextEligibleTimeMs = getNextIstMidnightMs(committedTime);
          return jsonResponse({
            success: true,
            idempotent: true,
            backupId,
            message: "Backup already finalized successfully",
            lastBackupTimeMs: committedTime,
            nextEligibleTimeMs,
            serverTimeMs: Date.now()
          }, 200, request);
        }

        if (parsedManifest.status !== "UPLOADING") {
          return jsonResponse({ success: false, error: "Manifest not found or not in UPLOADING state" }, 409, request);
        }

        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";

        const writes = [
          {
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
              fields: {
                ...manifestDoc.fields,
                status: { stringValue: "COMMITTED" },
                committedAtMs: { integerValue: nowMs.toString() },
                defaultDailyLimit: { integerValue: defaultDailyLimit.toString() }
              }
            },
            currentDocument: { updateTime: manifestDoc.updateTime }
          },
          {
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup/status`,
              fields: {
                status: { stringValue: "SUCCESS" },
                activeBackupId: { stringValue: backupId },
                lastBackupDate: { stringValue: todayIstDate },
                lastBackupTimeMs: { integerValue: nowMs.toString() },
                lastRequestId: { stringValue: requestId },
                updatedAt: { timestampValue: new Date(nowMs).toISOString() }
              }
            }
          },
          {
            update: {
              name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${todayIstDate}_BACKUP`,
              fields: {
                uid: { stringValue: uid },
                status: { stringValue: "COMPLETED" },
                committedAtMs: { integerValue: nowMs.toString() },
                updatedAtMs: { integerValue: nowMs.toString() }
              }
            }
          },
          {
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_snapshots/${backupId}`,
              fields: {
                backupId: { stringValue: backupId },
                status: { stringValue: "COMMITTED" },
                recordCount: { integerValue: (Number(parsedManifest.recordCount) || 0).toString() },
                committedAtMs: { integerValue: nowMs.toString() }
              }
            }
          }
        ];

        try {
          await commitFirestoreBatch(writes, env);
          const nextEligibleTimeMs = getNextIstMidnightMs(nowMs);
          return jsonResponse({
            success: true,
            backupId,
            message: "Backup finalized successfully",
            lastBackupTimeMs: nowMs,
            nextEligibleTimeMs,
            serverTimeMs: nowMs
          }, 200, request);
        } catch (err) {
          if (err?.status === 412 || err?.status === 409) {
            const refreshedManifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${backupId}`, env);
            const refreshedManifest = parseManifestDoc(refreshedManifestDoc);
            if (refreshedManifest.exists && refreshedManifest.status === "COMMITTED") {
              const committedTime = refreshedManifest.committedAtMs || Date.now();
              return jsonResponse({
                success: true,
                idempotent: true,
                backupId,
                message: "Backup already finalized successfully",
                lastBackupTimeMs: committedTime,
                nextEligibleTimeMs: getNextIstMidnightMs(committedTime),
                serverTimeMs: Date.now()
              }, 200, request);
            }
          }
          const status = err?.status === 412 || err?.status === 409 ? 412 : 500;
          return jsonResponse({ success: false, error: "Finalize commit failed", details: err?.message }, status, request);
        }
      }

      // ---------------------------------------------------------------------
      // Multi-Part Chunked Backup Protocol: POST /api/backup/abort
      // ---------------------------------------------------------------------
      if (path === "/api/backup/abort") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_backup_abort_${uid}`, 20, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_backup_abort_${clientIp}`, 60, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }
        const body = await request.json().catch(() => ({}));
        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        await releaseBackupSlotOnError(uid, body.reason || "CLIENT_ABORT", env, todayIstDate);
        return jsonResponse({ success: true, aborted: true }, 200, request);
      }

      // ---------------------------------------------------------------------
      // Cloud Data Restore: POST /api/restore (Two-Phase Leased Reservation, Option C)
      // Rate Limit: 10 req / 60s per UID & 30 req / 3600s per IP
      // ---------------------------------------------------------------------
      if (path === "/api/restore") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_restore_${uid}`, 10, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_restore_${clientIp}`, 30, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        // 1. Entitlement check
        const entitlement = await verifyUserSubscriptionEntitlement(uid, env);
        if (!entitlement.entitled) {
          return jsonResponse({ success: false, error: entitlement.reason, code: "ENTITLEMENT_REQUIRED" }, 403, request);
        }

        // 2. Parse request body
        const body = await request.json().catch(() => ({}));
        const rawKey = (typeof body.idempotencyKey === "string" && body.idempotencyKey.trim()) ||
                       (typeof body.requestId === "string" && body.requestId.trim()) || null;
        if (!rawKey) {
          return jsonResponse({ success: false, error: "Missing or invalid idempotencyKey" }, 400, request);
        }
        const idempotencyKey = rawKey;

        // 3. Authoritative server dates
        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        const yesterdayIstDate = getIstDateString(nowMs - 86400000);

        // 4. Parallel read of claims and status pointer
        const [todayClaimDoc, yesterdayClaimDoc, statusDoc] = await Promise.all([
          getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_RESTORE`, env),
          getFirestoreDocument(`daily_quota_claims/${uid}_${yesterdayIstDate}_RESTORE`, env),
          getFirestoreBackupStatus(uid, env)
        ]);

        const todayClaim = parseQuotaClaimDoc(todayClaimDoc);
        const yesterdayClaim = parseQuotaClaimDoc(yesterdayClaimDoc);
        const parsedStatus = parseBackupStatusDoc(statusDoc);
        const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";

        // 5. Cross-Midnight resolution (Section 6.4)
        if (yesterdayClaim.exists && yesterdayClaim.idempotencyKey === idempotencyKey) {
          if (yesterdayClaim.status === "DISPATCHED") {
            // Replay without mutating manifest
            const records = await fetchSnapshotRecords(uid, yesterdayClaim.pinnedSnapshotId, env);
            return jsonResponse({
              success: true,
              idempotent: true,
              backupId: yesterdayClaim.pinnedSnapshotId,
              istDate: yesterdayIstDate,
              dailyRecords: records,
              defaultDailyLimit: 0,
              pinStatus: "PINNED",
              pinLeaseExpiresAtMs: yesterdayClaim.pinLeaseExpiresAtMs
            }, 200, request);
          }
          if (yesterdayClaim.status === "ACKNOWLEDGED") {
            return jsonResponse({
              success: true,
              acknowledged: true,
              idempotent: true,
              backupId: yesterdayClaim.pinnedSnapshotId,
              istDate: yesterdayIstDate
            }, 200, request);
          }
          if (yesterdayClaim.status === "EXPIRED_UNACKNOWLEDGED") {
            // Continuation vs Fallback
            if ((yesterdayClaim.rePinCount || 0) >= 1) {
              return jsonResponse({
                success: false,
                code: "SNAPSHOT_CLOSED_EXPIRED",
                error: "Previous restore session has expired and closed."
              }, 410, request);
            }

            const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${yesterdayClaim.pinnedSnapshotId}`, env);
            const parsedManifest = parseManifestDoc(manifestDoc);

            if (!parsedManifest.exists) {
              // Deleted -> mark claim CLOSED_EXPIRED
              const closeWrites = [{
                update: {
                  name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${yesterdayIstDate}_RESTORE`,
                  fields: {
                    ...yesterdayClaimDoc.fields,
                    status: { stringValue: "CLOSED_EXPIRED" },
                    closedReason: { stringValue: "SNAPSHOT_DELETED" }
                  }
                },
                currentDocument: { updateTime: yesterdayClaim.updateTime }
              }];
              await commitFirestoreBatch(closeWrites, env).catch(() => {});
              return jsonResponse({
                success: false,
                code: "SNAPSHOT_PURGED_AND_DELETED",
                error: "The backup snapshot has been rotated or deleted."
              }, 410, request);
            }

            if (parsedManifest.status !== "COMMITTED") {
              // Purging -> mark claim CLOSED_EXPIRED
              const closeWrites = [{
                update: {
                  name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${yesterdayIstDate}_RESTORE`,
                  fields: {
                    ...yesterdayClaimDoc.fields,
                    status: { stringValue: "CLOSED_EXPIRED" },
                    closedReason: { stringValue: "SNAPSHOT_PURGING" }
                  }
                },
                currentDocument: { updateTime: yesterdayClaim.updateTime }
              }];
              await commitFirestoreBatch(closeWrites, env).catch(() => {});
              return jsonResponse({
                success: false,
                code: "SNAPSHOT_BEING_PURGED",
                error: "The backup snapshot is currently being purged."
              }, 410, request);
            }

            // Intact COMMITTED snapshot: atomic CAS re-pin continuation
            const continuationWrites = [
              {
                update: {
                  name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${yesterdayClaim.pinnedSnapshotId}`,
                  fields: {
                    ...manifestDoc.fields,
                    activeRestorePinCount: { integerValue: (parsedManifest.activeRestorePinCount + 1).toString() },
                    lastPinnedAtMs: { integerValue: nowMs.toString() }
                  }
                },
                currentDocument: { updateTime: manifestDoc.updateTime }
              },
              {
                update: {
                  name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${yesterdayIstDate}_RESTORE`,
                  fields: {
                    ...yesterdayClaimDoc.fields,
                    status: { stringValue: "DISPATCHED" },
                    pinStatus: { stringValue: "PINNED" },
                    pinLeaseExpiresAtMs: { integerValue: (nowMs + 15 * 60 * 1000).toString() },
                    offlineGraceExpiresAtMs: { integerValue: (nowMs + 24 * 3600 * 1000).toString() },
                    rePinCount: { integerValue: ((yesterdayClaim.rePinCount || 0) + 1).toString() },
                    rePinnedAtMs: { integerValue: nowMs.toString() }
                  }
                },
                currentDocument: { updateTime: yesterdayClaim.updateTime }
              }
            ];

            try {
              await commitFirestoreBatch(continuationWrites, env);
              const records = await fetchSnapshotRecords(uid, yesterdayClaim.pinnedSnapshotId, env);
              return jsonResponse({
                success: true,
                resumedContinuation: true,
                backupId: yesterdayClaim.pinnedSnapshotId,
                istDate: yesterdayIstDate,
                dailyRecords: records,
                defaultDailyLimit: parsedManifest.defaultDailyLimit,
                pinStatus: "PINNED"
              }, 200, request);
            } catch (continuationErr) {
              return jsonResponse({
                success: false,
                code: "SNAPSHOT_BEING_PURGED",
                error: "Snapshot was locked or purged concurrently."
              }, 410, request);
            }
          }
          if (yesterdayClaim.status === "CLOSED_EXPIRED") {
            return jsonResponse({
              success: false,
              code: "SNAPSHOT_CLOSED_EXPIRED",
              error: "Previous restore session has expired and closed."
            }, 410, request);
          }
        }

        // 6. Evaluate today's claim
        if (todayClaim.exists) {
          if (todayClaim.status === "ACKNOWLEDGED") {
            if (todayClaim.idempotencyKey === idempotencyKey) {
              return jsonResponse({
                success: true,
                acknowledged: true,
                idempotent: true,
                backupId: todayClaim.pinnedSnapshotId,
                istDate: todayIstDate
              }, 200, request);
            }
            return jsonResponse({
              success: false,
              code: "RESTORE_LIMIT_EXCEEDED",
              error: "You can restore your data once every 24 hours.",
              istDate: todayIstDate
            }, 409, request);
          }

          if (todayClaim.status === "DISPATCHED") {
            if (todayClaim.idempotencyKey === idempotencyKey) {
              const records = await fetchSnapshotRecords(uid, todayClaim.pinnedSnapshotId, env);
              let limit = 0;
              const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${todayClaim.pinnedSnapshotId}`, env);
              if (manifestDoc && manifestDoc.fields) {
                const parsedManifest = parseManifestDoc(manifestDoc);
                limit = parsedManifest.defaultDailyLimit || 0;
              } else {
                const prefDoc = await getFirestoreDocument(`users/${uid}/settings/preferences`, env);
                if (prefDoc && prefDoc.fields) {
                  limit = Number(prefDoc.fields.defaultDailyLimit?.integerValue || prefDoc.fields.dailyLimit?.integerValue || 0);
                }
              }
              return jsonResponse({
                success: true,
                idempotent: true,
                backupId: todayClaim.pinnedSnapshotId,
                istDate: todayIstDate,
                dailyRecords: records,
                defaultDailyLimit: limit,
                pinStatus: "PINNED",
                pinLeaseExpiresAtMs: todayClaim.pinLeaseExpiresAtMs
              }, 200, request);
            }
            return jsonResponse({
              success: false,
              code: "RESTORE_IN_PROGRESS",
              error: "A restore operation is currently in progress for this account."
            }, 409, request);
          }

          if (todayClaim.status === "EXHAUSTED_ATTEMPTS") {
            return jsonResponse({
              success: false,
              code: "RESTORE_ATTEMPTS_EXHAUSTED",
              error: "Daily restore attempt limit reached (3 failed attempts)."
            }, 429, request);
          }

          if (todayClaim.status === "FAILED") {
            const currentAttempt = todayClaim.attemptCount || 1;
            if (currentAttempt >= 3) {
              const exhaustWrites = [{
                update: {
                  name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${todayIstDate}_RESTORE`,
                  fields: {
                    ...todayClaimDoc.fields,
                    status: { stringValue: "EXHAUSTED_ATTEMPTS" },
                    pinStatus: { stringValue: "CLOSED" }
                  }
                },
                currentDocument: { updateTime: todayClaim.updateTime }
              }];
              await commitFirestoreBatch(exhaustWrites, env).catch(() => {});
              return jsonResponse({
                success: false,
                code: "RESTORE_ATTEMPTS_EXHAUSTED",
                error: "Daily restore attempt limit reached (3 failed attempts)."
              }, 429, request);
            }

            const newAttempt = currentAttempt + 1;
            let targetBackupId = (typeof body.backupId === "string" && body.backupId.trim()) ||
                                 todayClaim.pinnedSnapshotId || parsedStatus.activeBackupId;

            let manifestDoc = targetBackupId ? await getFirestoreDocument(`users/${uid}/backup_manifests/${targetBackupId}`, env) : null;
            let parsedManifest = parseManifestDoc(manifestDoc);
            let isLegacy = false;
            let legacyRecords = [];
            let legacyLimit = 0;

            if (!targetBackupId || !parsedManifest.exists || parsedManifest.status !== "COMMITTED") {
              legacyRecords = await fetchSnapshotRecords(uid, "legacy", env);
              if (legacyRecords && legacyRecords.length > 0) {
                isLegacy = true;
                targetBackupId = targetBackupId || `legacy_backup_${uid}`;
                const legacyPrefDoc = await getFirestoreDocument(`users/${uid}/settings/preferences`, env);
                if (legacyPrefDoc && legacyPrefDoc.fields) {
                  legacyLimit = Number(legacyPrefDoc.fields.defaultDailyLimit?.integerValue || legacyPrefDoc.fields.dailyLimit?.integerValue || 0);
                }
              } else {
                if (!targetBackupId) {
                  return jsonResponse({ success: false, code: "NO_BACKUP_FOUND", error: "No cloud backup found" }, 404, request);
                }
                return jsonResponse({ success: false, code: "SNAPSHOT_NOT_COMMITTED", error: "Backup snapshot not found or not committed" }, 404, request);
              }
            }

            const retryWrites = [
              {
                update: {
                  name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${todayIstDate}_RESTORE`,
                  fields: {
                    uid: { stringValue: uid },
                    status: { stringValue: "DISPATCHED" },
                    pinStatus: { stringValue: "PINNED" },
                    attemptCount: { integerValue: newAttempt.toString() },
                    idempotencyKey: { stringValue: idempotencyKey },
                    pinnedSnapshotId: { stringValue: targetBackupId },
                    pinLeaseExpiresAtMs: { integerValue: (nowMs + 15 * 60 * 1000).toString() },
                    offlineGraceExpiresAtMs: { integerValue: (nowMs + 24 * 3600 * 1000).toString() },
                    dispatchedAtMs: { integerValue: nowMs.toString() },
                    updatedAtMs: { integerValue: nowMs.toString() }
                  }
                },
                currentDocument: { updateTime: todayClaim.updateTime }
              }
            ];

            if (!isLegacy && manifestDoc) {
              retryWrites.push({
                update: {
                  name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${targetBackupId}`,
                  fields: {
                    ...manifestDoc.fields,
                    activeRestorePinCount: { integerValue: (parsedManifest.activeRestorePinCount + 1).toString() },
                    lastPinnedAtMs: { integerValue: nowMs.toString() }
                  }
                },
                currentDocument: { updateTime: manifestDoc.updateTime }
              });
            }

            try {
              await commitFirestoreBatch(retryWrites, env);
              const records = isLegacy ? legacyRecords : await fetchSnapshotRecords(uid, targetBackupId, env);
              const defaultDailyLimit = isLegacy ? legacyLimit : parsedManifest.defaultDailyLimit;
              const totalRecords = records.length;
              const chunkSize = 200;
              const totalChunks = Math.ceil(totalRecords / chunkSize) || 1;
              return jsonResponse({
                success: true,
                resumedUnderAttempt: newAttempt,
                backupId: targetBackupId,
                istDate: todayIstDate,
                dailyRecords: records,
                records: records,
                totalRecords,
                totalChunks,
                chunkSize,
                defaultDailyLimit,
                pinStatus: "PINNED",
                pinLeaseExpiresAtMs: nowMs + 15 * 60 * 1000
              }, 200, request);
            } catch (err) {
              if (err?.status === 412 || err?.status === 409) {
                return jsonResponse({
                  success: false,
                  code: "RESTORE_CONCURRENT_CONFLICT",
                  error: "Another restore operation is already being processed for this account."
                }, 409, request);
              }
              return jsonResponse({ success: false, error: "Failed to resume restore attempt", details: err?.message }, 500, request);
            }
          }
        }

        // 7. UNCLAIMED: Initial restore reservation of the day
        const explicitBackupRequested = typeof body.backupId === "string" && body.backupId.trim().length > 0;
        let targetBackupId = (explicitBackupRequested ? body.backupId.trim() : null) || parsedStatus.activeBackupId;
        let manifestDoc = targetBackupId ? await getFirestoreDocument(`users/${uid}/backup_manifests/${targetBackupId}`, env) : null;
        let parsedManifest = parseManifestDoc(manifestDoc);
        let isLegacy = false;
        let legacyRecords = [];
        let legacyLimit = 0;

        if (!targetBackupId || !parsedManifest.exists || parsedManifest.status !== "COMMITTED") {
          // Fallback to legacy root daily_data ONLY if no specific snapshot was requested
          // and account has no valid committed snapshot manifest (legacy pre-manifest accounts)
          if (!explicitBackupRequested && (!targetBackupId || targetBackupId === "legacy" || targetBackupId.startsWith("legacy_"))) {
            legacyRecords = await fetchSnapshotRecords(uid, "legacy", env);
            if (legacyRecords && legacyRecords.length > 0) {
              isLegacy = true;
              targetBackupId = targetBackupId || `legacy_backup_${uid}`;
              const legacyPrefDoc = await getFirestoreDocument(`users/${uid}/settings/preferences`, env);
              if (legacyPrefDoc && legacyPrefDoc.fields) {
                legacyLimit = Number(legacyPrefDoc.fields.defaultDailyLimit?.integerValue || legacyPrefDoc.fields.dailyLimit?.integerValue || 0);
              }
            } else {
              return jsonResponse({ success: false, code: "NO_BACKUP_FOUND", error: "No cloud backup was found for this account." }, 404, request);
            }
          } else {
            if (!targetBackupId) {
              return jsonResponse({ success: false, code: "NO_BACKUP_FOUND", error: "No cloud backup was found for this account." }, 404, request);
            }
            return jsonResponse({ success: false, code: "SNAPSHOT_NOT_FOUND_OR_COMMITTED", error: "Cloud backup is not committed or not found." }, 404, request);
          }
        }

        const initialWrites = [
          {
            update: {
              name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${todayIstDate}_RESTORE`,
              fields: {
                uid: { stringValue: uid },
                status: { stringValue: "DISPATCHED" },
                idempotencyKey: { stringValue: idempotencyKey },
                pinnedSnapshotId: { stringValue: targetBackupId },
                pinStatus: { stringValue: "PINNED" },
                attemptCount: { integerValue: "1" },
                pinLeaseExpiresAtMs: { integerValue: (nowMs + 15 * 60 * 1000).toString() },
                offlineGraceExpiresAtMs: { integerValue: (nowMs + 24 * 3600 * 1000).toString() },
                dispatchedAtMs: { integerValue: nowMs.toString() },
                updatedAtMs: { integerValue: nowMs.toString() }
              }
            },
            currentDocument: { exists: false }
          }
        ];

        if (!isLegacy && manifestDoc) {
          initialWrites.push({
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${targetBackupId}`,
              fields: {
                ...manifestDoc.fields,
                activeRestorePinCount: { integerValue: (parsedManifest.activeRestorePinCount + 1).toString() },
                lastPinnedAtMs: { integerValue: nowMs.toString() }
              }
            },
            currentDocument: { updateTime: manifestDoc.updateTime }
          });
        }

        try {
          await commitFirestoreBatch(initialWrites, env);
          const records = isLegacy ? legacyRecords : await fetchSnapshotRecords(uid, targetBackupId, env);
          const defaultDailyLimit = isLegacy ? legacyLimit : parsedManifest.defaultDailyLimit;
          const totalRecords = records.length;
          const chunkSize = 200;
          const totalChunks = Math.ceil(totalRecords / chunkSize) || 1;
          return jsonResponse({
            success: true,
            dispatched: true,
            backupId: targetBackupId,
            istDate: todayIstDate,
            attemptCount: 1,
            dailyRecords: records,
            records: records,
            totalRecords,
            totalChunks,
            chunkSize,
            defaultDailyLimit,
            pinStatus: "PINNED",
            pinLeaseExpiresAtMs: nowMs + 15 * 60 * 1000
          }, 200, request);
        } catch (err) {
          if (err?.status === 412 || err?.status === 409) {
            // Concurrency race: re-read claim
            const recheckDoc = await getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_RESTORE`, env);
            const recheck = parseQuotaClaimDoc(recheckDoc);
            if (recheck.idempotencyKey === idempotencyKey) {
              const records = await fetchSnapshotRecords(uid, recheck.pinnedSnapshotId, env);
              let limit = 0;
              const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${recheck.pinnedSnapshotId}`, env);
              if (manifestDoc && manifestDoc.fields) {
                const parsedManifest = parseManifestDoc(manifestDoc);
                limit = parsedManifest.defaultDailyLimit || 0;
              }
              const totalRecords = records.length;
              const chunkSize = 200;
              const totalChunks = Math.ceil(totalRecords / chunkSize) || 1;
              return jsonResponse({
                success: true,
                idempotent: true,
                backupId: recheck.pinnedSnapshotId,
                istDate: todayIstDate,
                dailyRecords: records,
                records: records,
                totalRecords,
                totalChunks,
                chunkSize,
                defaultDailyLimit: limit,
                pinStatus: "PINNED",
                pinLeaseExpiresAtMs: recheck.pinLeaseExpiresAtMs
              }, 200, request);
            }
            return jsonResponse({
              success: false,
              code: "RESTORE_CONCURRENT_CONFLICT",
              error: "Another restore operation is already being processed for this account."
            }, 409, request);
          }
          return jsonResponse({ success: false, error: "Restore authorization failed", details: err?.message }, 500, request);
        }
      }

      // ---------------------------------------------------------------------
      // Cloud Data Restore: POST /api/restore/ack (Release-Aware Exactly-Once Decrement)
      // ---------------------------------------------------------------------
      if (path === "/api/restore/ack") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_restore_ack_${uid}`, 20, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_restore_ack_${clientIp}`, 60, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const body = await request.json().catch(() => ({}));
        const idempotencyKey = typeof body.idempotencyKey === "string" ? body.idempotencyKey.trim() : null;
        if (!idempotencyKey) {
          return jsonResponse({ success: false, error: "Missing idempotencyKey" }, 400, request);
        }

        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        const yesterdayIstDate = getIstDateString(nowMs - 86400000);
        const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";

        // Read claims in parallel
        const [todayClaimDoc, yesterdayClaimDoc] = await Promise.all([
          getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_RESTORE`, env),
          getFirestoreDocument(`daily_quota_claims/${uid}_${yesterdayIstDate}_RESTORE`, env)
        ]);

        let claimDoc = null;
        let claimDate = null;
        if (todayClaimDoc && todayClaimDoc.exists && parseQuotaClaimDoc(todayClaimDoc).idempotencyKey === idempotencyKey) {
          claimDoc = todayClaimDoc;
          claimDate = todayIstDate;
        } else if (yesterdayClaimDoc && yesterdayClaimDoc.exists && parseQuotaClaimDoc(yesterdayClaimDoc).idempotencyKey === idempotencyKey) {
          claimDoc = yesterdayClaimDoc;
          claimDate = yesterdayIstDate;
        }

        if (!claimDoc) {
          return jsonResponse({ success: false, error: "Restore claim not found" }, 404, request);
        }

        const claim = parseQuotaClaimDoc(claimDoc);

        // Case A: Pin already released or acknowledged -> safe duplicate replay with zero decrement
        if (claim.pinStatus === "RELEASED" || claim.status === "ACKNOWLEDGED") {
          return jsonResponse({ success: true, acknowledged: true, duplicate: true }, 200, request);
        }

        // Case B: Pin expired by cron -> pin already decremented, update claim without mutating manifest
        if (claim.pinStatus === "EXPIRED") {
          const writes = [{
            update: {
              name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${claimDate}_RESTORE`,
              fields: {
                ...claimDoc.fields,
                status: { stringValue: "ACKNOWLEDGED" },
                pinStatus: { stringValue: "RELEASED" },
                note: { stringValue: "EXPIRED_PIN_CONFIRMED" },
                acknowledgedAtMs: { integerValue: nowMs.toString() }
              }
            },
            currentDocument: { updateTime: claim.updateTime }
          }];
          await commitFirestoreBatch(writes, env).catch(() => {});
          return jsonResponse({ success: true, acknowledged: true, note: "EXPIRED_PIN_CONFIRMED" }, 200, request);
        }

        // Case C: Pin active PINNED -> atomic transition to RELEASED and decrement manifest pin count
        const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${claim.pinnedSnapshotId}`, env);
        const parsedManifest = parseManifestDoc(manifestDoc);

        const writes = [
          {
            update: {
              name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${claimDate}_RESTORE`,
              fields: {
                ...claimDoc.fields,
                status: { stringValue: "ACKNOWLEDGED" },
                pinStatus: { stringValue: "RELEASED" },
                acknowledgedAtMs: { integerValue: nowMs.toString() }
              }
            },
            currentDocument: { updateTime: claim.updateTime }
          }
        ];

        if (parsedManifest.exists) {
          writes.push({
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${claim.pinnedSnapshotId}`,
              fields: {
                ...manifestDoc.fields,
                activeRestorePinCount: { integerValue: Math.max(0, parsedManifest.activeRestorePinCount - 1).toString() }
              }
            },
            currentDocument: { updateTime: manifestDoc.updateTime }
          });
        }

        try {
          await commitFirestoreBatch(writes, env);
          return jsonResponse({ success: true, acknowledged: true }, 200, request);
        } catch (err) {
          if (err?.status === 412 || err?.status === 409) {
            // Re-read claim for duplicate ACK race or concurrent cron expiration
            const recheckDoc = await getFirestoreDocument(`daily_quota_claims/${uid}_${claimDate}_RESTORE`, env);
            const recheck = parseQuotaClaimDoc(recheckDoc);
            if (recheck.pinStatus === "RELEASED" || recheck.status === "ACKNOWLEDGED") {
              return jsonResponse({ success: true, acknowledged: true, duplicate: true }, 200, request);
            }
            if (recheck.pinStatus === "EXPIRED") {
              return jsonResponse({ success: true, acknowledged: true, note: "EXPIRED_PIN_CONFIRMED" }, 200, request);
            }
          }
          return jsonResponse({ success: false, error: "Failed to acknowledge restore", details: err?.message }, 500, request);
        }
      }

      // ---------------------------------------------------------------------
      // Cloud Data Restore: POST /api/restore/abort (Pre-Commit Clean Abort)
      // ---------------------------------------------------------------------
      if (path === "/api/restore/abort") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_restore_abort_${uid}`, 20, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_restore_abort_${clientIp}`, 60, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const body = await request.json().catch(() => ({}));
        const idempotencyKey = typeof body.idempotencyKey === "string" ? body.idempotencyKey.trim() : null;
        if (!idempotencyKey) {
          return jsonResponse({ success: false, error: "Missing idempotencyKey" }, 400, request);
        }

        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        const yesterdayIstDate = getIstDateString(nowMs - 86400000);
        const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";

        const [todayClaimDoc, yesterdayClaimDoc] = await Promise.all([
          getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_RESTORE`, env),
          getFirestoreDocument(`daily_quota_claims/${uid}_${yesterdayIstDate}_RESTORE`, env)
        ]);

        let claimDoc = null;
        let claimDate = null;
        if (todayClaimDoc && todayClaimDoc.exists && parseQuotaClaimDoc(todayClaimDoc).idempotencyKey === idempotencyKey) {
          claimDoc = todayClaimDoc;
          claimDate = todayIstDate;
        } else if (yesterdayClaimDoc && yesterdayClaimDoc.exists && parseQuotaClaimDoc(yesterdayClaimDoc).idempotencyKey === idempotencyKey) {
          claimDoc = yesterdayClaimDoc;
          claimDate = yesterdayIstDate;
        }

        if (!claimDoc) {
          return jsonResponse({ success: false, error: "Restore claim not found" }, 404, request);
        }

        const claim = parseQuotaClaimDoc(claimDoc);
        if (claim.pinStatus !== "PINNED") {
          return jsonResponse({ success: true, aborted: true, alreadyReleased: true }, 200, request);
        }

        const manifestDoc = await getFirestoreDocument(`users/${uid}/backup_manifests/${claim.pinnedSnapshotId}`, env);
        const parsedManifest = parseManifestDoc(manifestDoc);

        const currentAttempt = claim.attemptCount || 1;
        const isExhausted = currentAttempt >= 3;
        const newStatus = isExhausted ? "EXHAUSTED_ATTEMPTS" : "FAILED";
        const newPinStatus = isExhausted ? "CLOSED" : "RELEASED";

        const writes = [
          {
            update: {
              name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${claimDate}_RESTORE`,
              fields: {
                ...claimDoc.fields,
                status: { stringValue: newStatus },
                pinStatus: { stringValue: newPinStatus },
                failureReason: { stringValue: (body.reason || "PRE_COMMIT_ABORT").slice(0, 200) },
                abortedAtMs: { integerValue: nowMs.toString() }
              }
            },
            currentDocument: { updateTime: claim.updateTime }
          }
        ];

        if (parsedManifest.exists) {
          writes.push({
            update: {
              name: `projects/${projectId}/databases/(default)/documents/users/${uid}/backup_manifests/${claim.pinnedSnapshotId}`,
              fields: {
                ...manifestDoc.fields,
                activeRestorePinCount: { integerValue: Math.max(0, parsedManifest.activeRestorePinCount - 1).toString() }
              }
            },
            currentDocument: { updateTime: manifestDoc.updateTime }
          });
        }

        try {
          await commitFirestoreBatch(writes, env);
          return jsonResponse({
            success: true,
            aborted: true,
            status: newStatus,
            attemptCount: currentAttempt,
            remainingAttempts: Math.max(0, 3 - currentAttempt)
          }, 200, request);
        } catch (err) {
          if (err?.status === 412 || err?.status === 409) {
            const recheckDoc = await getFirestoreDocument(`daily_quota_claims/${uid}_${claimDate}_RESTORE`, env);
            const recheck = parseQuotaClaimDoc(recheckDoc);
            if (recheck.pinStatus !== "PINNED") {
              return jsonResponse({ success: true, aborted: true, alreadyReleased: true }, 200, request);
            }
          }
          return jsonResponse({ success: false, error: "Failed to abort restore", details: err?.message }, 500, request);
        }
      }

      // ---------------------------------------------------------------------
      // Cloud Data Restore: POST /api/restore/heartbeat (Lease Renewal)
      // ---------------------------------------------------------------------
      if (path === "/api/restore/heartbeat") {
        if (request.method !== "POST") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use POST." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_restore_hb_${uid}`, 60, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_restore_hb_${clientIp}`, 120, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const body = await request.json().catch(() => ({}));
        const idempotencyKey = typeof body.idempotencyKey === "string" ? body.idempotencyKey.trim() : null;
        if (!idempotencyKey) {
          return jsonResponse({ success: false, error: "Missing idempotencyKey" }, 400, request);
        }

        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        const yesterdayIstDate = getIstDateString(nowMs - 86400000);
        const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";

        const [todayClaimDoc, yesterdayClaimDoc] = await Promise.all([
          getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_RESTORE`, env),
          getFirestoreDocument(`daily_quota_claims/${uid}_${yesterdayIstDate}_RESTORE`, env)
        ]);

        let claimDoc = null;
        let claimDate = null;
        if (todayClaimDoc && todayClaimDoc.exists && parseQuotaClaimDoc(todayClaimDoc).idempotencyKey === idempotencyKey) {
          claimDoc = todayClaimDoc;
          claimDate = todayIstDate;
        } else if (yesterdayClaimDoc && yesterdayClaimDoc.exists && parseQuotaClaimDoc(yesterdayClaimDoc).idempotencyKey === idempotencyKey) {
          claimDoc = yesterdayClaimDoc;
          claimDate = yesterdayIstDate;
        }

        if (!claimDoc) {
          return jsonResponse({ success: false, code: "PIN_LEASE_EXPIRED", error: "Restore pin lease has expired" }, 410, request);
        }

        const claim = parseQuotaClaimDoc(claimDoc);
        if (claim.pinStatus !== "PINNED") {
          return jsonResponse({ success: false, code: "PIN_LEASE_EXPIRED", error: "Restore pin lease has expired" }, 410, request);
        }

        const pinLeaseExpiresAtMs = nowMs + 15 * 60 * 1000;
        const offlineGraceExpiresAtMs = nowMs + 24 * 3600 * 1000;

        const writes = [{
          update: {
            name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${claimDate}_RESTORE`,
            fields: {
              ...claimDoc.fields,
              pinLeaseExpiresAtMs: { integerValue: pinLeaseExpiresAtMs.toString() },
              offlineGraceExpiresAtMs: { integerValue: offlineGraceExpiresAtMs.toString() },
              lastHeartbeatAtMs: { integerValue: nowMs.toString() }
            }
          },
          currentDocument: { updateTime: claim.updateTime }
        }];

        try {
          await commitFirestoreBatch(writes, env);
          return jsonResponse({ success: true, heartbeat: true, pinLeaseExpiresAtMs, offlineGraceExpiresAtMs }, 200, request);
        } catch (err) {
          return jsonResponse({ success: false, code: "PIN_LEASE_EXPIRED", error: "Restore pin lease has expired" }, 410, request);
        }
      }

      // ---------------------------------------------------------------------
      // Cloud Data Restore: GET /api/restore/chunk (Chunk Stream Download & Renewal)
      // ---------------------------------------------------------------------
      if (path === "/api/restore/chunk") {
        if (request.method !== "GET") {
          return jsonResponse({ success: false, error: "Method Not Allowed. Use GET." }, 405, request);
        }

        const uidRateCheck = await checkRateLimit(`uid_restore_chunk_${uid}`, 60, 60000, { failClosed: true }, env);
        if (!uidRateCheck.allowed) {
          if (uidRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (uidRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests. Please try again later.", retryAfter: uidRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": uidRateCheck.retryAfterSec.toString() }
          );
        }

        const ipRateCheck = await checkRateLimit(`ip_restore_chunk_${clientIp}`, 120, 3600000, { failClosed: true }, env);
        if (!ipRateCheck.allowed) {
          if (ipRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (ipRateCheck.retryAfterSec || 60).toString() }
            );
          }
          return jsonResponse(
            { success: false, error: "Too many requests from this IP. Please try again later.", retryAfter: ipRateCheck.retryAfterSec },
            429,
            request,
            { "Retry-After": ipRateCheck.retryAfterSec.toString() }
          );
        }

        const urlObj = new URL(request.url);
        const idempotencyKey = urlObj.searchParams.get("idempotencyKey");
        const backupId = urlObj.searchParams.get("backupId");
        const idxStr = urlObj.searchParams.get("idx") || urlObj.searchParams.get("chunkIndex") || "0";
        const chunkIndex = parseInt(idxStr, 10) || 0;

        if (!idempotencyKey || !backupId) {
          return jsonResponse({ success: false, error: "Missing idempotencyKey or backupId" }, 400, request);
        }

        const nowMs = Date.now();
        const todayIstDate = getIstDateString(nowMs);
        const yesterdayIstDate = getIstDateString(nowMs - 86400000);
        const projectId = env.FIREBASE_PROJECT_ID || "refocus-504119";

        const [todayClaimDoc, yesterdayClaimDoc] = await Promise.all([
          getFirestoreDocument(`daily_quota_claims/${uid}_${todayIstDate}_RESTORE`, env),
          getFirestoreDocument(`daily_quota_claims/${uid}_${yesterdayIstDate}_RESTORE`, env)
        ]);

        let claimDoc = null;
        let claimDate = null;
        if (todayClaimDoc && todayClaimDoc.exists && parseQuotaClaimDoc(todayClaimDoc).idempotencyKey === idempotencyKey) {
          claimDoc = todayClaimDoc;
          claimDate = todayIstDate;
        } else if (yesterdayClaimDoc && yesterdayClaimDoc.exists && parseQuotaClaimDoc(yesterdayClaimDoc).idempotencyKey === idempotencyKey) {
          claimDoc = yesterdayClaimDoc;
          claimDate = yesterdayIstDate;
        }

        if (!claimDoc) {
          return jsonResponse({ success: false, code: "PIN_LEASE_EXPIRED", error: "Restore pin lease has expired" }, 410, request);
        }

        const claim = parseQuotaClaimDoc(claimDoc);
        if (claim.pinStatus !== "PINNED") {
          return jsonResponse({ success: false, code: "PIN_LEASE_EXPIRED", error: "Restore pin lease has expired" }, 410, request);
        }

        // Renew active streaming lease
        const pinLeaseExpiresAtMs = nowMs + 15 * 60 * 1000;
        const writes = [{
          update: {
            name: `projects/${projectId}/databases/(default)/documents/daily_quota_claims/${uid}_${claimDate}_RESTORE`,
            fields: {
              ...claimDoc.fields,
              pinLeaseExpiresAtMs: { integerValue: pinLeaseExpiresAtMs.toString() },
              lastHeartbeatAtMs: { integerValue: nowMs.toString() }
            }
          },
          currentDocument: { updateTime: claim.updateTime }
        }];
        await commitFirestoreBatch(writes, env).catch(() => {});

        const records = await fetchSnapshotRecords(uid, backupId, env);
        // Slice 200 records per chunk
        const start = chunkIndex * 200;
        const chunkRecords = records.slice(start, start + 200);
        const totalRecords = records.length;
        const totalChunks = Math.ceil(totalRecords / 200) || 1;

        return jsonResponse({
          success: true,
          chunkIndex,
          records: chunkRecords,
          dailyRecords: chunkRecords,
          totalRecords,
          totalChunks,
          chunkSize: 200,
          isLastChunk: start + chunkRecords.length >= totalRecords,
          pinLeaseExpiresAtMs
        }, 200, request);
      }

      // Rate limit unknown paths to prevent automated endpoint fuzzing / scanning (30 req / 60s per IP)
      const notFoundRateCheck = await checkRateLimit(`ip_404_${clientIp}`, 30, 60000, { failClosed: true }, env);
      if (!notFoundRateCheck.allowed) {
          if (notFoundRateCheck.unavailable) {
            return jsonResponse(
              { error: "Rate limit service temporarily unavailable. Please try again later.", code: "RATE_LIMITER_UNAVAILABLE" },
              503,
              request,
              { "Retry-After": (notFoundRateCheck.retryAfterSec || 60).toString() }
            );
          }
        return jsonResponse(
          { error: "Too many requests. Please try again later.", retryAfter: notFoundRateCheck.retryAfterSec },
          429,
          request,
          { "Retry-After": notFoundRateCheck.retryAfterSec.toString() }
        );
      }

      return jsonResponse({ error: "Not Found" }, 404, request);

    } catch (err) {
      console.error("Worker unhandled error:", err?.name || "Error");
      const isAuthErr = err.message && (err.message.includes("Unauthorized") || err.message.includes("AppCheckUnauthorized"));
      const statusCode = isAuthErr ? 401 : 500;
      const errorMsg = isAuthErr ? (err.message || "Unauthorized") : "Internal Server Error";
      return jsonResponse({ error: errorMsg }, statusCode, request);
    }
  },

  async scheduled(event, env, ctx) {
    try {
      const outcome = await processPendingAccountPurges(env, 25);
      if (!outcome.success) {
        console.error("Scheduled purge sweep aborted due to discovery failure:", outcome.error);
      }
      await reconcileExpiredPins(env);
    } catch (err) {
      console.error("Scheduled handler error:", err?.name || "Error");
    }
  }
};

export {
  checkRateLimit,
  rateLimitStore,
  claimSubscriptionCreationLockAtomic,
  finalizeSubscriptionCreationLock,
  releaseSubscriptionCreationLockOnError,
  recordSubscriptionUidMap,
  purgeAccountData,
  getFirestoreAccountStatus,
  recordFirestoreAccountTombstone,
  listPendingAccountTombstones,
  processPendingAccountPurges,
  claimPurgeLockAtomic,
  finalizePurgeTombstoneAtomic,
  getGoogleSubjectIdFromToken,
  computeTrialIdentityHash,
  getTrialIdentityRecord,
  reserveTrialIdentityAtomic,
  updateTrialIdentityRecord,
  getTrialRegistry,
  recordTrialRegistry,
  getFirestoreSubscription,
  IST_OFFSET_MS,
  getIstDateString,
  getNextIstMidnightMs,
  getFirestoreDocument,
  listFirestoreDocuments,
  deleteFirestoreDocument,
  commitFirestoreBatch,
  parseQuotaClaimDoc,
  parseManifestDoc,
  fetchSnapshotRecords,
  getFirestoreBackupStatus,
  parseBackupStatusDoc,
  claimBackupSlotAtomic,
  commitFirestoreBackup,
  releaseBackupSlotOnError,
  reconcileExpiredPins,
  auditSnapshotPinConsistency,
  pruneHistoricalCommittedBackups,
  cleanupAbandonedUploads,
  validateRazorpayCredentials,
  validateRazorpayPlanId,
  findSubscriptionsByUid,
  findDailyQuotaClaimsByUid,
  fetchAndVerifyRazorpaySubscription,
  reconcileSubscriptionActivation,
  reconcileSubscriptionCancelled,
  EXPECTED_PLAN_AMOUNT_PAISE,
  EXPECTED_PLAN_CURRENCY
};
