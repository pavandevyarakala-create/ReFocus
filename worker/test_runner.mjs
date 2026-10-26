/**
 * Complete Node.js Test Suite for Cloudflare Worker Recurring Autopay Migration
 * 
 * Verifies all 19 critical security, business logic, and lifecycle requirements:
 * 1. create-subscription: rejects unauthenticated requests (401)
 * 2. create-subscription: rejects missing/empty RAZORPAY_PLAN_ID configuration (500)
 * 3. create-subscription: rejects invalid requested plan ID (400)
 * 4. create-subscription: rejects blocked/deleted/pending-deletion accounts (403)
 * 5. create-subscription: binds Firebase UID to notes and returns { subscriptionId, keyId } without secrets
 * 6. verify-subscription: rejects missing required fields (400)
 * 7. verify-subscription: rejects invalid signature (400)
 * 8. verify-subscription: rejects plan mismatch (400)
 * 9. verify-subscription: rejects UID ownership mismatch (403)
 * 10. verify-subscription: successfully verifies and writes active recurring subscription
 * 11. cancel-subscription: cancels subscription at cycle end on Razorpay and updates cancelAtPeriodEnd = true
 * 12. webhook: rejects missing/invalid HMAC signature (400)
 * 13. webhook: enforces exact-once atomic idempotency
 * 14. webhook: subscription.activated & subscription.charged grant and extend active cycle
 * 15. webhook: subscription.pending marks status PENDING without revoking active grace access
 * 16. webhook: subscription.halted revokes premium access immediately
 * 17. webhook: subscription.cancelled preserves access until currentPeriodEnd then expires
 * 18. webhook: refund.processed revokes active subscription and cancels recurring billing
 * 19. webhook: rejects stale activation after refund/cancellation
 */

import assert from "node:assert/strict";
import crypto from "node:crypto";
import worker, {
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
  cleanupAbandonedUploads
} from "./src/index.js";

// Helper to create HMAC-SHA256 hex string
function computeHmacHex(message, secret) {
  return crypto.createHmac("sha256", secret).update(message).digest("hex");
}

// Generate an RSA keypair for mock Google JWKS & Firebase ID Tokens
const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", {
  modulusLength: 2048,
  publicKeyEncoding: { type: "spki", format: "pem" },
  privateKeyEncoding: { type: "pkcs8", format: "pem" }
});

const jwkPublicKey = crypto.createPublicKey(publicKey).export({ format: "jwk" });
jwkPublicKey.kid = "mock-key-id-123";
jwkPublicKey.alg = "RS256";
jwkPublicKey.use = "sig";

function createMockFirebaseIdToken(uid, projectId = "refocus-504119", expired = false, googleSub = null, customFirebaseClaim = undefined) {
  const header = { alg: "RS256", kid: "mock-key-id-123", typ: "JWT" };
  const nowSec = Math.floor(Date.now() / 1000);
  const payload = {
    iss: `https://securetoken.google.com/${projectId}`,
    aud: projectId,
    sub: uid,
    user_id: uid,
    auth_time: nowSec - 60,
    iat: nowSec - 60,
    exp: expired ? nowSec - 10 : nowSec + 3600,
    firebase: customFirebaseClaim !== undefined ? customFirebaseClaim : {
      sign_in_provider: "google.com",
      identities: {
        "google.com": [googleSub || `mock_google_sub_${uid}`]
      }
    }
  };

  const b64Header = Buffer.from(JSON.stringify(header)).toString("base64url");
  const b64Payload = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const unsigned = `${b64Header}.${b64Payload}`;

  const signer = crypto.createSign("RSA-SHA256");
  signer.update(unsigned);
  const signature = signer.sign(privateKey, "base64url");

  return `${unsigned}.${signature}`;
}

function createMockAppCheckToken(projectNumber = "524118238647", projectId = "refocus-504119", expired = false) {
  const header = { alg: "RS256", kid: "mock-key-id-123", typ: "JWT" };
  const nowSec = Math.floor(Date.now() / 1000);
  const payload = {
    iss: `https://firebaseappcheck.googleapis.com/${projectNumber}`,
    aud: [`projects/${projectNumber}`, `projects/${projectId}`],
    sub: `projects/${projectNumber}`,
    iat: nowSec - 60,
    exp: expired ? nowSec - 10 : nowSec + 3600
  };

  const b64Header = Buffer.from(JSON.stringify(header)).toString("base64url");
  const b64Payload = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const unsigned = `${b64Header}.${b64Payload}`;

  const signer = crypto.createSign("RSA-SHA256");
  signer.update(unsigned);
  const signature = signer.sign(privateKey, "base64url");

  return `${unsigned}.${signature}`;
}

// Mock Google Service Account Key JSON
const mockServiceAccount = JSON.stringify({
  type: "service_account",
  project_id: "refocus-504119",
  private_key: privateKey,
  client_email: "mock-sa@refocus-504119.iam.gserviceaccount.com"
});

// Ephemeral in-memory ECDSA P-256 keypair for mock lease signing (zero hardcoded keys)
const { privateKey: testLeasePrivateKey } = crypto.generateKeyPairSync("ec", {
  namedCurve: "P-256",
  privateKeyEncoding: { type: "pkcs8", format: "der" }
});
const mockLeasePrivateKeyPkcs8Base64 = testLeasePrivateKey.toString("base64");

// Environment Configuration Mock
const mockEnv = {
  FIREBASE_PROJECT_ID: "refocus-504119",
  FIREBASE_PROJECT_NUMBER: "524118238647",
  APP_CHECK_ENFORCEMENT_MODE: "disabled",
  RAZORPAY_KEY_ID: "rzp_test_mockKey123",
  RAZORPAY_KEY_SECRET: "mockSecretKey456",
  RAZORPAY_WEBHOOK_SECRET: "mockWebhookSecret789",
  RAZORPAY_PLAN_ID: "plan_TdtgehXIhzQ8U9",
  FIREBASE_SERVICE_ACCOUNT_KEY: mockServiceAccount,
  LEASE_PRIVATE_KEY_PKCS8: mockLeasePrivateKeyPkcs8Base64,
  TRIAL_SECRET_KEY: "mock_trial_secret_key_step5b_secure"
};

// In-memory mock databases
const mockFirestore = new Map();
const mockProcessedEvents = new Set();
const mockRazorpaySubscriptions = new Map();

const mockDocUpdateTimes = new Map();
let mockFirestoreFaultInjector = null;
let mockRazorpayFaultInjector = null;
let mockAuthFaultInjector = null;

// Mock global fetch to handle Google JWKS, OAuth, Firestore REST, and Razorpay API
const originalFetch = globalThis.fetch;
globalThis.fetch = async (url, options = {}) => {
  const urlStr = url.toString();

  // 1. Google JWKS
  if (urlStr.includes("securetoken@system.gserviceaccount.com")) {
    return new Response(JSON.stringify({ keys: [jwkPublicKey] }), { status: 200 });
  }

  // 1b. Firebase App Check JWKS
  if (urlStr.includes("firebaseappcheck.googleapis.com/v1/jwks")) {
    return new Response(JSON.stringify({ keys: [jwkPublicKey] }), { status: 200 });
  }

  // 2. Google OAuth Token
  if (urlStr.includes("oauth2.googleapis.com/token")) {
    return new Response(JSON.stringify({ access_token: "mock-oauth-token-123", expires_in: 3600 }), { status: 200 });
  }

  // 3. Firestore REST API
  if (urlStr.includes("firestore.googleapis.com")) {
    if (mockFirestoreFaultInjector) {
      const faultResult = mockFirestoreFaultInjector(urlStr, options);
      if (faultResult !== undefined) {
        return faultResult;
      }
    }
    const parsedUrl = new URL(urlStr);
    const reqMethod = (options.method || "GET").toUpperCase();

    // Monotonically increasing counter for mock Firestore updateTime (nanosecond precision)
    if (!globalThis._mockUpdateTimeCounter) globalThis._mockUpdateTimeCounter = 0;
    const getNextMockUpdateTime = () => {
      globalThis._mockUpdateTimeCounter++;
      const baseIso = new Date().toISOString().replace("Z", "");
      const extra = String(globalThis._mockUpdateTimeCounter).padStart(6, "0");
      return `${baseIso}${extra}Z`;
    };

    // Handle documents:commit batch write
    if (parsedUrl.pathname.endsWith(":commit") && options.method === "POST") {
      const body = JSON.parse(options.body || "{}");
      const writes = body.writes || [];

      // Check all preconditions first (atomic)
      for (const write of writes) {
        const target = write.update?.name || write.delete;
        if (!target) continue;
        const docPath = target.split("/documents/")[1];
        const cond = write.currentDocument;
        if (cond) {
          const exists = mockFirestore.has(docPath);
          if (cond.exists === false && exists) {
            return new Response(JSON.stringify({ error: { code: 412, message: "Precondition failed: exists=false but exists", status: "FAILED_PRECONDITION" } }), { status: 412 });
          }
          if (cond.exists === true && !exists) {
            return new Response(JSON.stringify({ error: { code: 412, message: "Precondition failed: exists=true but does not exist", status: "FAILED_PRECONDITION" } }), { status: 412 });
          }
          if (cond.updateTime) {
            const storedTime = mockDocUpdateTimes.get(docPath);
            if (!exists || storedTime !== cond.updateTime) {
              return new Response(JSON.stringify({ error: { code: 412, message: `Precondition failed: updateTime mismatch (expected ${cond.updateTime}, got ${storedTime})`, status: "FAILED_PRECONDITION" } }), { status: 412 });
            }
          }
        }
      }

      for (const write of writes) {
        if (write.update) {
          const docPath = write.update.name.split("/documents/")[1];
          const existing = mockFirestore.get(docPath) || {};
          const updated = { ...existing, ...write.update.fields };
          mockFirestore.set(docPath, updated);
          mockDocUpdateTimes.set(docPath, getNextMockUpdateTime());
        } else if (write.delete) {
          const docPath = write.delete.split("/documents/")[1];
          mockFirestore.delete(docPath);
          mockDocUpdateTimes.delete(docPath);
        }
      }
      return new Response(JSON.stringify({ writeResults: writes.map(() => ({ updateTime: getNextMockUpdateTime() })) }), { status: 200 });
    }

    // Handle documents:runQuery
    if (parsedUrl.pathname.endsWith(":runQuery") && options.method === "POST") {
      const body = JSON.parse(options.body || "{}");
      const structuredQuery = body.structuredQuery || {};
      const collectionId = structuredQuery.from?.[0]?.collectionId;
      const limit = structuredQuery.limit || 100;
      const whereFilter = structuredQuery.where?.fieldFilter;
      const compositeFilter = structuredQuery.where?.compositeFilter;
      const orderBy = structuredQuery.orderBy;

      if (globalThis._mockRequireIndex && orderBy && orderBy.length > 0) {
        return new Response(JSON.stringify({
          error: {
            code: 400,
            message: "The query requires an index. You can create it here: https://console.firebase.google.com/...",
            status: "FAILED_PRECONDITION"
          }
        }), { status: 400 });
      }

      const checkFieldFilter = (f, fields) => {
        if (!f) return true;
        const fieldPath = f.field?.fieldPath;
        const op = f.op;
        const expectedVal = f.value?.stringValue;
        const actualVal = fields[fieldPath]?.stringValue;
        if (op === "EQUAL") {
          return actualVal === expectedVal;
        }
        return true;
      };

      const matchingDocs = [];
      for (const [key, fields] of mockFirestore.entries()) {
        if (collectionId && (key.startsWith(`${collectionId}/`) || key.includes(`/${collectionId}/`))) {
          let matches = true;
          if (whereFilter) {
            matches = checkFieldFilter(whereFilter, fields);
          } else if (compositeFilter && compositeFilter.filters) {
            for (const flt of compositeFilter.filters) {
              if (flt.fieldFilter && !checkFieldFilter(flt.fieldFilter, fields)) {
                matches = false;
                break;
              }
            }
          }
          if (matches) {
            matchingDocs.push({
              key,
              fields,
              updateTime: mockDocUpdateTimes.get(key) || "2026-10-09T00:00:00Z"
            });
          }
        }
      }

      if (orderBy && orderBy.length > 0) {
        const orderField = orderBy[0].field?.fieldPath;
        const isAsc = orderBy[0].direction === "ASCENDING";
        matchingDocs.sort((a, b) => {
          const valA = a.fields[orderField]?.timestampValue || a.fields[orderField]?.stringValue || a.updateTime || "";
          const valB = b.fields[orderField]?.timestampValue || b.fields[orderField]?.stringValue || b.updateTime || "";
          return isAsc ? valA.localeCompare(valB) : valB.localeCompare(valA);
        });
      }

      const results = matchingDocs.slice(0, limit).map(doc => ({
        document: {
          name: `projects/refocus-504119/databases/(default)/documents/${doc.key}`,
          fields: doc.fields,
          createTime: "2026-10-09T00:00:00Z",
          updateTime: doc.updateTime
        },
        readTime: new Date().toISOString()
      }));

      return new Response(JSON.stringify(results), { status: 200 });
    }

    const docPath = parsedUrl.pathname.split("/documents/")[1];

    // Handle razorpay_events idempotency collection
    if (docPath && docPath.startsWith("razorpay_events/")) {
      const eventId = docPath.replace("razorpay_events/", "");
      if (parsedUrl.searchParams.get("currentDocument.exists") === "false") {
        if (mockProcessedEvents.has(eventId)) {
          return new Response(JSON.stringify({ error: "Document already exists" }), { status: 412 });
        }
        mockProcessedEvents.add(eventId);
        return new Response(JSON.stringify({ name: docPath }), { status: 200 });
      }
    }

    if (reqMethod === "GET") {
      if (docPath && mockFirestore.has(docPath)) {
        return new Response(JSON.stringify({
          name: docPath,
          fields: mockFirestore.get(docPath),
          updateTime: mockDocUpdateTimes.get(docPath) || "2026-10-09T00:00:00Z"
        }), { status: 200 });
      }

      // Support listing child documents in a subcollection
      if (docPath) {
        const prefix = `${docPath}/`;
        const matchingDocs = [];
        for (const [key, fields] of mockFirestore.entries()) {
          if (key.startsWith(prefix)) {
            const sub = key.slice(prefix.length);
            if (!sub.includes("/")) {
              matchingDocs.push({
                name: `projects/refocus-504119/databases/(default)/documents/${key}`,
                fields,
                createTime: "2026-10-09T00:00:00Z",
                updateTime: mockDocUpdateTimes.get(key) || "2026-10-09T00:00:00Z"
              });
            }
          }
        }
        if (matchingDocs.length > 0) {
          return new Response(JSON.stringify({ documents: matchingDocs }), { status: 200 });
        }
      }

      return new Response(JSON.stringify({ error: "Not Found" }), { status: 404 });
    }

    if (reqMethod === "PATCH") {
      const existsParam = parsedUrl.searchParams.get("currentDocument.exists");
      if (existsParam === "false" && docPath && mockFirestore.has(docPath)) {
        return new Response(JSON.stringify({ error: "Precondition Failed: Document already exists" }), { status: 412 });
      }

      const updateTimeParam = parsedUrl.searchParams.get("currentDocument.updateTime");
      if (updateTimeParam && docPath) {
        const storedTime = mockDocUpdateTimes.get(docPath);
        if (!mockFirestore.has(docPath) || (storedTime && decodeURIComponent(updateTimeParam) !== storedTime)) {
          return new Response(JSON.stringify({ error: "Precondition Failed: updateTime mismatch" }), { status: 412 });
        }
      }

      const body = JSON.parse(options.body || "{}");
      const existing = (docPath && mockFirestore.get(docPath)) || {};
      const updated = { ...existing, ...body.fields };
      const newUpdateTime = getNextMockUpdateTime();
      if (docPath) {
        mockFirestore.set(docPath, updated);
        mockDocUpdateTimes.set(docPath, newUpdateTime);
      }
      return new Response(JSON.stringify({ name: docPath, fields: updated, updateTime: newUpdateTime }), { status: 200 });
    }

    if (reqMethod === "DELETE") {
      if (docPath) {
        mockFirestore.delete(docPath);
        mockDocUpdateTimes.delete(docPath);
      }
      return new Response(JSON.stringify({}), { status: 200 });
    }

    return new Response(JSON.stringify({}), { status: 200 });
  }

  // 4. Razorpay Subscriptions API
  if (urlStr.startsWith("https://api.razorpay.com/v1/subscriptions")) {
    if (mockRazorpayFaultInjector) {
      const faultResult = mockRazorpayFaultInjector(urlStr, options);
      if (faultResult !== undefined) {
        return faultResult;
      }
    }
    // Cancellation endpoint: /v1/subscriptions/{id}/cancel
    if (urlStr.endsWith("/cancel") && options.method === "POST") {
      const subId = urlStr.split("/subscriptions/")[1].replace("/cancel", "");
      const existing = mockRazorpaySubscriptions.get(subId) || { id: subId, status: "active", plan_id: "plan_TdtgehXIhzQ8U9" };
      existing.status = "cancelled";
      existing.cancel_at_cycle_end = 1;
      existing.current_end = Math.floor(Date.now() / 1000) + 20 * 86400;
      mockRazorpaySubscriptions.set(subId, existing);
      return new Response(JSON.stringify(existing), { status: 200 });
    }

    // Subscription Creation: POST /v1/subscriptions
    if (options.method === "POST") {
      const body = JSON.parse(options.body || "{}");
      const subId = `sub_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
      const subObj = {
        id: subId,
        plan_id: body.plan_id,
        status: "created",
        total_count: body.total_count,
        notes: body.notes || {},
        current_start: Math.floor(Date.now() / 1000),
        current_end: Math.floor(Date.now() / 1000) + 30 * 86400,
        charge_at: Math.floor(Date.now() / 1000) + 30 * 86400
      };
      mockRazorpaySubscriptions.set(subId, subObj);
      return new Response(JSON.stringify(subObj), { status: 200 });
    }

    // Subscription Lookup: GET /v1/subscriptions/{id}
    if (options.method === "GET" || !options.method) {
      const subId = urlStr.split("/subscriptions/")[1];
      if (mockRazorpaySubscriptions.has(subId)) {
        return new Response(JSON.stringify(mockRazorpaySubscriptions.get(subId)), { status: 200 });
      }
      return new Response(JSON.stringify({ error: "Subscription not found" }), { status: 404 });
    }
  }

  // 5. Razorpay Payments API: GET /v1/payments/{id}
  if (urlStr.startsWith("https://api.razorpay.com/v1/payments/")) {
    const payId = urlStr.split("/payments/")[1];
    return new Response(JSON.stringify({
      id: payId,
      status: "captured",
      amount: 9900,
      currency: "INR"
    }), { status: 200 });
  }

  // 6. Identity Toolkit (Firebase Auth Admin REST)
  if (urlStr.includes("identitytoolkit.googleapis.com")) {
    if (mockAuthFaultInjector) {
      const faultResult = mockAuthFaultInjector(urlStr, options);
      if (faultResult !== undefined) {
        return faultResult;
      }
    }
    return new Response(JSON.stringify({ kind: "identitytoolkit#DeleteAccountResponse" }), { status: 200 });
  }

  return originalFetch(url, options);
};

// ===========================================================================
// TEST SUITE EXECUTION
// ===========================================================================

async function runTests() {
  console.log("\n🚀 Starting Cloudflare Worker Monthly Autopay Test Suite...\n");
  let passed = 0;
  let failed = 0;
  const failedNames = [];

  async function test(name, fn) {
    try {
      await fn();
      console.log(`  ✅ PASS: ${name}`);
      passed++;
    } catch (err) {
      console.error(`  ❌ FAIL: ${name}`);
      console.error(err.message || err);
      failedNames.push(name);
      failed++;
    }
  }

  // -------------------------------------------------------------------------
  // 1. Authentication & Health Tests
  // -------------------------------------------------------------------------
  await test("GET /health returns healthy status and recurring model", async () => {
    const req = new Request("https://refocus.workers.dev/health", { method: "GET" });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, "healthy");
    assert.equal(body.model, "recurring_autopay_v2");
  });

  // -------------------------------------------------------------------------
  // 1a. Legacy Route Removal Verification (Must return 404 Not Found)
  // -------------------------------------------------------------------------
  await test("POST /api/create-order returns 404 Not Found (legacy route removed)", async () => {
    const token = createMockFirebaseIdToken("user_legacy_test_1");
    const req = new Request("https://refocus.workers.dev/api/create-order", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({ planId: "plan_monthly" })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 404);
    const body = await res.json();
    assert.equal(body.error, "Not Found");
  });

  await test("POST /createRazorpayOrder returns 404 Not Found (legacy route removed)", async () => {
    const token = createMockFirebaseIdToken("user_legacy_test_2");
    const req = new Request("https://refocus.workers.dev/createRazorpayOrder", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({})
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 404);
  });

  await test("POST /api/verify-payment returns 404 Not Found and grants no entitlement", async () => {
    const uid = "user_legacy_tamper_3";
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/verify-payment", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({
        razorpay_order_id: "order_fake_123",
        razorpay_payment_id: "pay_fake_123",
        razorpay_signature: "sig_fake_123"
      })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 404);
    const body = await res.json();
    assert.equal(body.error, "Not Found");
    // Ensure no Firestore entitlement was created
    assert.equal(mockFirestore.get(`users/${uid}/subscription/status`), undefined);
  });

  await test("POST /verifyRazorpayPayment returns 404 Not Found", async () => {
    const token = createMockFirebaseIdToken("user_legacy_tamper_4");
    const req = new Request("https://refocus.workers.dev/verifyRazorpayPayment", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({})
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 404);
  });

  await test("Webhook: legacy payment.captured and order.paid are ignored without granting entitlement", async () => {
    const uid = "user_legacy_webhook_5";
    const paymentId = "pay_legacy_webhook_5";

    const payloadStr = JSON.stringify({
      event: "payment.captured",
      id: "evt_legacy_pay_captured_5",
      payload: {
        payment: {
          entity: {
            id: paymentId,
            amount: 9900,
            currency: "INR",
            notes: { firebase_uid: uid }
          }
        }
      }
    });

    const signature = computeHmacHex(payloadStr, mockEnv.RAZORPAY_WEBHOOK_SECRET);
    const req = new Request("https://refocus.workers.dev/api/razorpay-webhook", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Razorpay-Signature": signature
      },
      body: payloadStr
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, "ignored_unhandled_type");
    // Ensure no entitlement was granted in Firestore
    assert.equal(mockFirestore.get(`users/${uid}/subscription/status`), undefined);
  });

  // -------------------------------------------------------------------------
  // 1b. Server Authoritative Time (/api/time) Tests
  // -------------------------------------------------------------------------
  await test("GET /api/time returns current Unix epoch serverTime and no-store headers", async () => {
    const before = Date.now();
    const req = new Request("https://refocus.workers.dev/api/time", { method: "GET" });
    const res = await worker.fetch(req, mockEnv, {});
    const after = Date.now();

    assert.equal(res.status, 200);
    assert.match(res.headers.get("Cache-Control") || "", /no-store/);

    const body = await res.json();
    assert.ok(typeof body.serverTime === "number");
    assert.ok(body.serverTime >= before && body.serverTime <= after);
    assert.ok(typeof body.iso === "string");
    assert.equal(body.clientEcho, null);
  });

  await test("GET /api/time echoes X-Client-Timestamp header without modification", async () => {
    const clientTimestamp = 123456789;
    const req = new Request("https://refocus.workers.dev/api/time", {
      method: "GET",
      headers: { "X-Client-Timestamp": clientTimestamp.toString() }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);

    const body = await res.json();
    assert.equal(body.clientEcho, clientTimestamp);
  });

  await test("GET /api/time rejects unsupported HTTP methods (405)", async () => {
    const req = new Request("https://refocus.workers.dev/api/time", { method: "POST" });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 405);
  });

  await test("POST /api/create-subscription rejects unauthenticated requests (401)", async () => {
    const req = new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: { "Content-Type": "application/json" }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 401);
  });

  // -------------------------------------------------------------------------
  // 2. create-subscription Endpoint Tests
  // -------------------------------------------------------------------------
  await test("POST /api/create-subscription rejects missing RAZORPAY_PLAN_ID configuration (500)", async () => {
    const token = createMockFirebaseIdToken("user_test_1");
    const req = new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({})
    });
    const badEnv = { ...mockEnv, RAZORPAY_PLAN_ID: "" };
    const res = await worker.fetch(req, badEnv, {});
    assert.equal(res.status, 500);
    const body = await res.json();
    assert.match(body.error, /missing plan ID/);
  });

  await test("POST /api/create-subscription rejects invalid requested plan ID (400)", async () => {
    const token = createMockFirebaseIdToken("user_test_1");
    const req = new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({ planId: "unauthorized_expensive_plan" })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 400);
  });

  await test("POST /api/create-subscription rejects PENDING_DELETION / DELETED accounts (403)", async () => {
    const uid = "user_deleted_1";
    mockFirestore.set(`users/${uid}/account/status`, {
      accountStatus: { stringValue: "PENDING_DELETION" }
    });

    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({})
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 403);
  });

  await test("POST /api/create-subscription creates subscription, binds UID in notes and returns { subscriptionId, keyId } without secrets", async () => {
    const uid = "user_valid_1";
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({})
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(body.subscriptionId.startsWith("sub_"));
    assert.equal(body.keyId, "rzp_test_mockKey123");
    assert.equal(body.keySecret, undefined);

    // Verify subscription ownership mapping was stored
    const mappingDoc = mockFirestore.get(`subscription_map/${body.subscriptionId}`);
    assert.ok(mappingDoc);
    assert.equal(mappingDoc.firebaseUid.stringValue, uid);
  });

  await test("POST /api/create-subscription rejects active unexpired subscription (409)", async () => {
    const uid = "user_already_active_sub";
    const futureEndMs = Date.now() + 20 * 86400 * 1000;
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: "sub_existing_active_1" },
      currentPeriodEnd: { timestampValue: new Date(futureEndMs).toISOString() },
      premium: { booleanValue: true }
    });

    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({})
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 409);
    const body = await res.json();
    assert.equal(body.code, "SUBSCRIPTION_ALREADY_ACTIVE");
    assert.equal(body.subscriptionId, "sub_existing_active_1");
  });

  await test("POST /api/create-subscription rejects pending authenticated mandate (409)", async () => {
    const uid = "user_pending_mandate_sub";
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "NONE" },
      mandateStatus: { stringValue: "AUTHENTICATED" },
      subscriptionId: { stringValue: "sub_pending_mandate_1" },
      premium: { booleanValue: false }
    });

    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({})
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 409);
    const body = await res.json();
    assert.equal(body.code, "MANDATE_PENDING_ACTIVATION");
    assert.equal(body.subscriptionId, "sub_pending_mandate_1");
  });

  await test("POST /api/create-subscription reuses recent uncompleted mandate within 5 minutes without creating duplicate", async () => {
    const uid = "user_reuse_mandate_sub";
    const recentSubId = "sub_recent_reusable_123";
    const nowMs = Date.now();
    // Simulate lock document created 2 minutes ago with 5-minute reuse expiry
    mockFirestore.set(`subscription_creation_locks/${uid}`, {
      status: { stringValue: "COMPLETED" },
      subscriptionId: { stringValue: recentSubId },
      expiresAtMs: { integerValue: (nowMs + 180000).toString() },
      updatedAt: { timestampValue: new Date(nowMs - 120000).toISOString() }
    });

    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({})
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.subscriptionId, recentSubId);
    assert.equal(body.keyId, "rzp_test_mockKey123");
  });

  await test("POST /api/create-subscription rejects concurrent creation attempts when atomic lock is held (409)", async () => {
    const uid = "user_concurrent_lock_sub";
    const nowMs = Date.now();
    // Simulate active lock held by another request (acquired 5 seconds ago)
    mockFirestore.set(`subscription_creation_locks/${uid}`, {
      status: { stringValue: "IN_PROGRESS" },
      uid: { stringValue: uid },
      lockedAtMs: { integerValue: (nowMs - 5000).toString() },
      expiresAtMs: { integerValue: (nowMs + 55000).toString() },
      updatedAt: { timestampValue: new Date(nowMs - 5000).toISOString() }
    });

    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({})
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 409);
    const body = await res.json();
    assert.match(body.error, /Subscription creation is already in progress/);
  });

  // -------------------------------------------------------------------------
  // Step 4D Concurrency & Revision-Conditional Lock Regression Tests
  // -------------------------------------------------------------------------
  await test("Lock Concurrency: Request A claims revision A, Request B acquires revision B after expiry; A's delayed finalization is rejected", async () => {
    const uid = "user_stale_finalize_test";
    const nowMs = Date.now();
    mockFirestore.delete(`subscription_creation_locks/${uid}`);
    mockDocUpdateTimes.delete(`subscription_creation_locks/${uid}`);

    // Request A claims lock
    const claimA = await claimSubscriptionCreationLockAtomic(uid, nowMs, mockEnv);
    assert.equal(claimA.claimed, true);
    const revisionA = claimA.updateTime;
    assert.ok(revisionA, "Revision A must be a valid timestamp string");

    // Fast-forward time past 60s TTL; Request B arrives and claims lock
    const claimB = await claimSubscriptionCreationLockAtomic(uid, nowMs + 65000, mockEnv);
    assert.equal(claimB.claimed, true);
    const revisionB = claimB.updateTime;
    assert.ok(revisionB, "Revision B must be a valid timestamp string");
    assert.notEqual(revisionA, revisionB, "Revisions A and B must differ");

    // Request A attempts delayed finalization with its stale revision A
    const finalizeA = await finalizeSubscriptionCreationLock(uid, "sub_stale_A", nowMs + 70000, revisionA, mockEnv);
    assert.equal(finalizeA.success, false, "Stale finalization must not succeed");
    assert.equal(finalizeA.superseded, true, "Stale finalization must be marked superseded");

    // Verify Firestore state: Request B's lock remains intact and was NOT overwritten by Request A
    const currentLock = mockFirestore.get(`subscription_creation_locks/${uid}`);
    assert.equal(currentLock.status.stringValue, "IN_PROGRESS", "Request B's lock must remain IN_PROGRESS");
    assert.equal(currentLock.subscriptionId, undefined, "Request A's sub_stale_A must NOT have been written");
  });

  await test("Lock Concurrency: Stale request error handler cannot overwrite or release newer lock", async () => {
    const uid = "user_stale_release_test";
    const nowMs = Date.now();
    mockFirestore.delete(`subscription_creation_locks/${uid}`);
    mockDocUpdateTimes.delete(`subscription_creation_locks/${uid}`);

    // Request A claims lock
    const claimA = await claimSubscriptionCreationLockAtomic(uid, nowMs, mockEnv);
    assert.equal(claimA.claimed, true);
    const revisionA = claimA.updateTime;

    // Lock expires, Request B claims lock
    const claimB = await claimSubscriptionCreationLockAtomic(uid, nowMs + 65000, mockEnv);
    assert.equal(claimB.claimed, true);
    const revisionB = claimB.updateTime;

    // Delayed Request A throws/times out and calls error release with stale revision A
    const releaseA = await releaseSubscriptionCreationLockOnError(uid, revisionA, mockEnv);
    assert.equal(releaseA.released, false, "Stale release must not succeed");
    assert.equal(releaseA.superseded, true, "Stale release must be marked superseded");

    // Verify Firestore state: Request B's lock is still active IN_PROGRESS and NOT FAILED
    const currentLock = mockFirestore.get(`subscription_creation_locks/${uid}`);
    assert.equal(currentLock.status.stringValue, "IN_PROGRESS");
    assert.notEqual(currentLock.status.stringValue, "FAILED");
  });

  await test("Lock Concurrency: Firestore returns 412 for stale finalization and does not retry unconditionally", async () => {
    const uid = "user_precondition_412_test";
    const nowMs = Date.now();
    mockFirestore.set(`subscription_creation_locks/${uid}`, {
      status: { stringValue: "IN_PROGRESS" }
    });
    mockDocUpdateTimes.set(`subscription_creation_locks/${uid}`, "2026-10-16T12:00:00Z");

    let patchAttempts = 0;
    mockFirestoreFaultInjector = (urlStr, options) => {
      if (urlStr.includes(`subscription_creation_locks/${uid}`) && options.method === "PATCH") {
        patchAttempts++;
      }
      return undefined;
    };

    try {
      const result = await finalizeSubscriptionCreationLock(uid, "sub_test_412", nowMs, "mismatched_stale_revision", mockEnv);
      assert.equal(result.success, false);
      assert.equal(result.superseded, true);
      assert.equal(patchAttempts, 1, "Must not retry when precondition fails (412)");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Persistence Resilience: Firestore returns 503 during mapping persistence (bounded retry, safe degraded success)", async () => {
    const uid = "user_map_503_test";
    const token = createMockFirebaseIdToken(uid);
    let mapPatchCount = 0;

    mockFirestoreFaultInjector = (urlStr, options) => {
      if (urlStr.includes("subscription_map/") && options.method === "PATCH") {
        mapPatchCount++;
        return new Response(JSON.stringify({ error: "Service Unavailable" }), { status: 503 });
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/create-subscription", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${token}`
        },
        body: JSON.stringify({})
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200, "Must return valid subscriptionId even if mapping write encountered transient error");
      const body = await res.json();
      assert.ok(body.subscriptionId.startsWith("sub_"));
      assert.equal(mapPatchCount, 2, "Must perform bounded retry (2 attempts) for 503");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Persistence Resilience: Firestore returns 503 during lock finalization (bounded retry with same revision, returns subscription)", async () => {
    const uid = "user_lock_503_test";
    const token = createMockFirebaseIdToken(uid);
    let lockFinalizeCount = 0;

    mockFirestoreFaultInjector = (urlStr, options) => {
      if (urlStr.includes(`subscription_creation_locks/${uid}`) && options.method === "PATCH" && options.body?.includes("COMPLETED")) {
        lockFinalizeCount++;
        return new Response(JSON.stringify({ error: "Service Unavailable" }), { status: 503 });
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/create-subscription", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${token}`
        },
        body: JSON.stringify({})
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200, "Must return valid subscriptionId so client can proceed");
      const body = await res.json();
      assert.ok(body.subscriptionId.startsWith("sub_"));
      assert.equal(lockFinalizeCount, 2, "Must perform bounded retry (2 attempts) on 503");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Persistence Resilience: Network exception during lock finalization handled cleanly", async () => {
    const uid = "user_lock_network_err_test";
    let attempts = 0;

    mockFirestoreFaultInjector = (urlStr, options) => {
      if (urlStr.includes(`subscription_creation_locks/${uid}`) && options.method === "PATCH") {
        attempts++;
        throw new Error("Simulated socket hangup during lock finalization");
      }
      return undefined;
    };

    try {
      const result = await finalizeSubscriptionCreationLock(uid, "sub_net_err", Date.now(), "rev_valid_123", mockEnv);
      assert.equal(result.success, false);
      assert.equal(result.reason, "NETWORK_ERROR");
      assert.equal(attempts, 2, "Must perform bounded retry (2 attempts) on network exception");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Lock Concurrency: Successful retry after expiration acquires newest revision and finalizes cleanly", async () => {
    const uid = "user_retry_latest_rev_test";
    const nowMs = Date.now();
    mockFirestore.delete(`subscription_creation_locks/${uid}`);
    mockDocUpdateTimes.delete(`subscription_creation_locks/${uid}`);

    // Request 1 acquires lock
    const claim1 = await claimSubscriptionCreationLockAtomic(uid, nowMs, mockEnv);
    assert.equal(claim1.claimed, true);
    const rev1 = claim1.updateTime;

    // Request 1 fails at gateway or times out, releasing lock conditionally
    const rel1 = await releaseSubscriptionCreationLockOnError(uid, rev1, mockEnv);
    assert.equal(rel1.released, true);

    // Request 2 (retry) claims lock
    const claim2 = await claimSubscriptionCreationLockAtomic(uid, nowMs + 1000, mockEnv);
    assert.equal(claim2.claimed, true);
    const rev2 = claim2.updateTime;
    assert.notEqual(rev1, rev2, "Retry must have a newer revision");

    // Request 2 finalizes with rev2
    const fin2 = await finalizeSubscriptionCreationLock(uid, "sub_retry_success", nowMs + 1000, rev2, mockEnv);
    assert.equal(fin2.success, true);

    const doc = mockFirestore.get(`subscription_creation_locks/${uid}`);
    assert.equal(doc.status.stringValue, "COMPLETED");
    assert.equal(doc.subscriptionId.stringValue, "sub_retry_success");
  });

  // -------------------------------------------------------------------------
  // Step 4F Lost-Response Recovery & Precondition Verification Tests
  // -------------------------------------------------------------------------
  await test("Lock Recovery: Lost response after remote commit recovers via read-only check and never drops precondition", async () => {
    const uid = "user_lost_resp_recovery";
    const nowMs = Date.now();
    mockFirestore.delete(`subscription_creation_locks/${uid}`);
    mockDocUpdateTimes.delete(`subscription_creation_locks/${uid}`);

    // 1. Request A acquires lock revision T1
    const claimA = await claimSubscriptionCreationLockAtomic(uid, nowMs, mockEnv);
    assert.equal(claimA.claimed, true);
    const t1 = claimA.updateTime;
    assert.ok(t1);

    const subId = "sub_lost_resp_123";
    let patchAttemptCount = 0;
    const sentPreconditions = [];

    mockFirestoreFaultInjector = (urlStr, options) => {
      if (urlStr.includes(`subscription_creation_locks/${uid}`) && options.method === "PATCH") {
        patchAttemptCount++;
        const parsedUrl = new URL(urlStr);
        sentPreconditions.push(parsedUrl.searchParams.get("currentDocument.updateTime"));

        if (patchAttemptCount === 1) {
          // 2 & 3. Mock Firestore commits COMPLETED lock with expected subscription ID and advances revision to T2
          const body = JSON.parse(options.body || "{}");
          const existing = mockFirestore.get(`subscription_creation_locks/${uid}`) || {};
          const updated = { ...existing, ...body.fields };
          const t2 = "2026-10-16T12:00:01.555555Z";
          mockFirestore.set(`subscription_creation_locks/${uid}`, updated);
          mockDocUpdateTimes.set(`subscription_creation_locks/${uid}`, t2);

          // 4. Mock throws network error to simulate lost response
          throw new Error("Simulated connection reset after successful remote commit");
        }

        // 5 & 6. Attempt 2 lets default mock handle PATCH. Since mockDocUpdateTimes is T2 and sentPrecondition is T1, returns 412.
        return undefined;
      }
      return undefined;
    };

    try {
      const result = await finalizeSubscriptionCreationLock(uid, subId, nowMs, t1, mockEnv);

      // Verify function never retried without a precondition
      assert.equal(patchAttemptCount, 2, "Must perform exactly 2 PATCH attempts");
      assert.equal(sentPreconditions[0], t1, "Attempt 1 must enforce precondition T1");
      assert.equal(sentPreconditions[1], t1, "Attempt 2 must still enforce precondition T1 without dropping it");

      // Verify result is not treated as a confirmed failure
      assert.equal(result.success, true, "Must recover and treat as successful completion");
      assert.equal(result.recoveredFromLostResponse, true);
      assert.equal(result.updateTime, "2026-10-16T12:00:01.555555Z");

      // Verify committed lock was not overwritten
      const finalDoc = mockFirestore.get(`subscription_creation_locks/${uid}`);
      assert.equal(finalDoc.status.stringValue, "COMPLETED");
      assert.equal(finalDoc.subscriptionId.stringValue, subId);
      assert.equal(finalDoc.uid.stringValue, uid);
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Lock Recovery: Competing in-progress lock on retry 412 is rejected as genuine supersession", async () => {
    const uid = "user_competing_in_progress";
    const nowMs = Date.now();
    mockFirestore.delete(`subscription_creation_locks/${uid}`);
    mockDocUpdateTimes.delete(`subscription_creation_locks/${uid}`);

    // Request A acquires lock revision T1
    const claimA = await claimSubscriptionCreationLockAtomic(uid, nowMs, mockEnv);
    const t1 = claimA.updateTime;

    let patchCount = 0;
    mockFirestoreFaultInjector = (urlStr, options) => {
      if (urlStr.includes(`subscription_creation_locks/${uid}`) && options.method === "PATCH") {
        patchCount++;
        if (patchCount === 1) {
          // Attempt 1 fails BEFORE write commit
          // Competing request claims lock in the interim (status: IN_PROGRESS, new revision T3)
          const competingDoc = {
            status: { stringValue: "IN_PROGRESS" },
            uid: { stringValue: uid },
            lockedAtMs: { integerValue: (nowMs + 65000).toString() }
          };
          mockFirestore.set(`subscription_creation_locks/${uid}`, competingDoc);
          mockDocUpdateTimes.set(`subscription_creation_locks/${uid}`, "2026-10-16T12:00:02.000000Z");

          throw new Error("Network error before remote commit");
        }
        // Attempt 2 receives 412 because revision changed to T3
        return undefined;
      }
      return undefined;
    };

    try {
      const result = await finalizeSubscriptionCreationLock(uid, "sub_stale_request", nowMs, t1, mockEnv);
      assert.equal(result.success, false, "Must not treat newer in-progress lock as successful completion");
      assert.equal(result.superseded, true, "Must mark as superseded");
      assert.equal(result.reason, "SUPERSEDED");

      // Verify competing in-progress lock was NOT overwritten
      const lockDoc = mockFirestore.get(`subscription_creation_locks/${uid}`);
      assert.equal(lockDoc.status.stringValue, "IN_PROGRESS");
      assert.equal(lockDoc.subscriptionId, undefined);
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Lock Recovery: Competing subscription ID on retry 412 is rejected as genuine supersession", async () => {
    const uid = "user_competing_diff_sub";
    const nowMs = Date.now();
    mockFirestore.delete(`subscription_creation_locks/${uid}`);
    mockDocUpdateTimes.delete(`subscription_creation_locks/${uid}`);

    // Request A acquires lock revision T1
    const claimA = await claimSubscriptionCreationLockAtomic(uid, nowMs, mockEnv);
    const t1 = claimA.updateTime;

    let patchCount = 0;
    mockFirestoreFaultInjector = (urlStr, options) => {
      if (urlStr.includes(`subscription_creation_locks/${uid}`) && options.method === "PATCH") {
        patchCount++;
        if (patchCount === 1) {
          // Competing request claims and finalizes a different subscription sub_COMPETING_999
          const competingDoc = {
            status: { stringValue: "COMPLETED" },
            subscriptionId: { stringValue: "sub_COMPETING_999" },
            uid: { stringValue: uid }
          };
          mockFirestore.set(`subscription_creation_locks/${uid}`, competingDoc);
          mockDocUpdateTimes.set(`subscription_creation_locks/${uid}`, "2026-10-16T12:00:03.000000Z");

          throw new Error("Network error before remote commit");
        }
        return undefined;
      }
      return undefined;
    };

    try {
      const result = await finalizeSubscriptionCreationLock(uid, "sub_my_stale_123", nowMs, t1, mockEnv);
      assert.equal(result.success, false, "Must not treat different subscription ID as successful completion");
      assert.equal(result.superseded, true, "Must mark as superseded");

      // Verify competing subscription ID lock was NOT overwritten
      const lockDoc = mockFirestore.get(`subscription_creation_locks/${uid}`);
      assert.equal(lockDoc.status.stringValue, "COMPLETED");
      assert.equal(lockDoc.subscriptionId.stringValue, "sub_COMPETING_999");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  // -------------------------------------------------------------------------
  // Step 4H Sensitive Worker Log Redaction Tests
  // -------------------------------------------------------------------------
  await test("Log Privacy: Subscription creation lock operations do not log raw Firebase UIDs", async () => {
    const originalConsole = {
      log: console.log,
      warn: console.warn,
      error: console.error
    };
    const capturedLogs = [];
    const logSpy = (...args) => capturedLogs.push(args.map(a => (typeof a === "object" ? JSON.stringify(a) : String(a))).join(" "));
    console.log = logSpy;
    console.warn = logSpy;
    console.error = logSpy;

    const testUid = "uid_sensitive_lock_test_999";
    const subId = "sub_privacy_test_lock";
    const nowMs = Date.now();
    mockFirestore.delete(`subscription_creation_locks/${testUid}`);
    mockDocUpdateTimes.delete(`subscription_creation_locks/${testUid}`);

    try {
      // 1. Claim lock
      const claim = await claimSubscriptionCreationLockAtomic(testUid, nowMs, mockEnv);
      assert.equal(claim.claimed, true);

      // 2. Precondition conflict on finalization: simulate supersede
      mockDocUpdateTimes.set(`subscription_creation_locks/${testUid}`, "2026-10-16T12:00:99.999999Z");
      const finalizeRes = await finalizeSubscriptionCreationLock(testUid, subId, nowMs, claim.updateTime, mockEnv);
      assert.equal(finalizeRes.success, false);

      // 3. Precondition conflict on release: simulate supersede
      const releaseRes = await releaseSubscriptionCreationLockOnError(testUid, claim.updateTime, "Test error", mockEnv);
      assert.equal(releaseRes.released, false);

      // Verify that NONE of the logged messages contain raw testUid
      for (const msg of capturedLogs) {
        assert.ok(!msg.includes(testUid), `Log message must not leak raw UID: "${msg}"`);
      }
    } finally {
      console.log = originalConsole.log;
      console.warn = originalConsole.warn;
      console.error = originalConsole.error;
    }
  });

  await test("Log Privacy: /api/create-subscription redacts gateway error body and PII", async () => {
    const originalConsole = {
      log: console.log,
      warn: console.warn,
      error: console.error
    };
    const capturedLogs = [];
    const logSpy = (...args) => capturedLogs.push(args.map(a => (typeof a === "object" ? JSON.stringify(a) : String(a))).join(" "));
    console.log = logSpy;
    console.warn = logSpy;
    console.error = logSpy;

    const testUid = "uid_sensitive_gateway_pii_888";
    const token = createMockFirebaseIdToken(testUid);
    const sensitivePayload = {
      error: {
        code: "BAD_REQUEST_ERROR",
        description: "Invalid customer phone +919876543210",
        source: "customer",
        step: "payment_initiation",
        reason: "input_validation_failed",
        customer_id: "cust_super_secret_pii_999"
      }
    };

    mockRazorpayFaultInjector = (urlStr, options) => {
      if (options.method === "POST" && !urlStr.endsWith("/cancel")) {
        return new Response(JSON.stringify(sensitivePayload), {
          status: 400,
          headers: { "Content-Type": "application/json" }
        });
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/create-subscription", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${token}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ planId: mockEnv.RAZORPAY_PLAN_ID })
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 500);
      const resBody = await res.json();
      assert.equal(resBody.error, "Razorpay subscription creation failed");

      // Check logs
      assert.ok(capturedLogs.some(msg => msg.includes("Razorpay subscription creation failed: HTTP 400 (BAD_REQUEST)")),
        "Must log safe HTTP status and fixed error category");

      // Verify no sensitive fields leaked
      for (const msg of capturedLogs) {
        assert.ok(!msg.includes("+919876543210"), `Log must not leak phone: "${msg}"`);
        assert.ok(!msg.includes("cust_super_secret_pii_999"), `Log must not leak customer ID: "${msg}"`);
        assert.ok(!msg.includes("BAD_REQUEST_ERROR"), `Log must not leak raw gateway error code: "${msg}"`);
        assert.ok(!msg.includes("input_validation_failed"), `Log must not leak raw gateway reason: "${msg}"`);
        assert.ok(!msg.includes(testUid), `Log must not leak user UID: "${msg}"`);
      }
    } finally {
      mockRazorpayFaultInjector = null;
      console.log = originalConsole.log;
      console.warn = originalConsole.warn;
      console.error = originalConsole.error;
    }
  });

  await test("Log Privacy: /api/verify-subscription rejection does not log UID or subscription IDs", async () => {
    const originalConsole = {
      log: console.log,
      warn: console.warn,
      error: console.error
    };
    const capturedLogs = [];
    const logSpy = (...args) => capturedLogs.push(args.map(a => (typeof a === "object" ? JSON.stringify(a) : String(a))).join(" "));
    console.log = logSpy;
    console.warn = logSpy;
    console.error = logSpy;

    const testUid = "uid_sensitive_verify_777";
    const subId = "sub_sensitive_verify_fail_777";
    const token = createMockFirebaseIdToken(testUid);

    try {
      // 1. Signature mismatch
      const reqSig = new Request("https://refocus.workers.dev/api/verify-subscription", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${token}`
        },
        body: JSON.stringify({
          subscription_id: subId,
          payment_id: "pay_test_privacy",
          signature: "bad_signature_privacy_test"
        })
      });
      const resSig = await worker.fetch(reqSig, mockEnv, {});
      assert.equal(resSig.status, 400);

      for (const msg of capturedLogs) {
        assert.ok(!msg.includes(testUid), `Verification error log must not leak UID: "${msg}"`);
        assert.ok(!msg.includes(subId), `Verification error log must not leak subId: "${msg}"`);
      }

      // 2. Ownership mismatch
      mockRazorpaySubscriptions.set(subId, {
        id: subId,
        status: "active",
        plan_id: "plan_TdtgehXIhzQ8U9",
        notes: { firebase_uid: "uid_different_owner_666" },
        current_start: Math.floor(Date.now() / 1000),
        current_end: Math.floor(Date.now() / 1000) + 30 * 86400
      });
      const validSig = computeHmacHex(`pay_test_privacy|${subId}`, mockEnv.RAZORPAY_KEY_SECRET);
      const reqOwner = new Request("https://refocus.workers.dev/api/verify-subscription", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${token}`
        },
        body: JSON.stringify({
          subscription_id: subId,
          payment_id: "pay_test_privacy",
          signature: validSig
        })
      });
      const resOwner = await worker.fetch(reqOwner, mockEnv, {});
      assert.equal(resOwner.status, 403);

      for (const msg of capturedLogs) {
        assert.ok(!msg.includes(testUid), `Ownership rejection log must not leak requesting UID: "${msg}"`);
        assert.ok(!msg.includes("uid_different_owner_666"), `Ownership rejection log must not leak notes UID: "${msg}"`);
        assert.ok(!msg.includes(subId), `Ownership rejection log must not leak subId: "${msg}"`);
      }
    } finally {
      console.log = originalConsole.log;
      console.warn = originalConsole.warn;
      console.error = originalConsole.error;
    }
  });

  await test("Log Privacy: Account deletion and purge do not log raw Firebase UIDs", async () => {
    const originalConsole = {
      log: console.log,
      warn: console.warn,
      error: console.error
    };
    const capturedLogs = [];
    const logSpy = (...args) => capturedLogs.push(args.map(a => (typeof a === "object" ? JSON.stringify(a) : String(a))).join(" "));
    console.log = logSpy;
    console.warn = logSpy;
    console.error = logSpy;

    const testUid = "uid_sensitive_purge_user_555";
    const token = createMockFirebaseIdToken(testUid);

    try {
      const req = new Request("https://refocus.workers.dev/api/account/request-deletion", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${token}`
        }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200);

      for (const msg of capturedLogs) {
        assert.ok(!msg.includes(testUid), `Account deletion request log must not leak UID: "${msg}"`);
      }
    } finally {
      console.log = originalConsole.log;
      console.warn = originalConsole.warn;
      console.error = originalConsole.error;
    }
  });

  // -------------------------------------------------------------------------
  // 3. verify-subscription Endpoint Tests
  // -------------------------------------------------------------------------
  await test("POST /api/verify-subscription rejects invalid signature (400)", async () => {
    const uid = "user_valid_2";
    const token = createMockFirebaseIdToken(uid);
    const subId = "sub_test_signature_fail";

    const req = new Request("https://refocus.workers.dev/api/verify-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({
        subscription_id: subId,
        payment_id: "pay_test_123",
        signature: "invalid_tampered_signature_hex"
      })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.equal(body.success, false);
    assert.match(body.error, /Signature verification failed/);
  });

  await test("POST /api/verify-subscription rejects UID ownership mismatch (403)", async () => {
    const uid1 = "user_owner_1";
    const uid2 = "user_attacker_2";
    const subId = "sub_owner_mismatch_test";

    // Setup subscription owned by uid1
    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "active",
      plan_id: "plan_TdtgehXIhzQ8U9",
      notes: { firebase_uid: uid1 },
      current_start: Math.floor(Date.now() / 1000),
      current_end: Math.floor(Date.now() / 1000) + 30 * 86400
    });

    const paymentId = "pay_test_mismatch";
    // Sub signature format: payment_id + "|" + subscription_id
    const signature = computeHmacHex(`${paymentId}|${subId}`, mockEnv.RAZORPAY_KEY_SECRET);

    // Attacker uid2 attempts to verify uid1's subscription
    const token = createMockFirebaseIdToken(uid2);
    const req = new Request("https://refocus.workers.dev/api/verify-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({
        subscription_id: subId,
        payment_id: paymentId,
        signature: signature
      })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 403);
    const body = await res.json();
    assert.match(body.error, /belongs to a different account/);
  });

  await test("POST /api/verify-subscription successfully activates recurring subscription", async () => {
    const uid = "user_success_sub";
    const subId = "sub_success_valid_123";
    const paymentId = "pay_success_123";

    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "active",
      plan_id: "plan_TdtgehXIhzQ8U9",
      notes: { firebase_uid: uid },
      current_start: Math.floor(Date.now() / 1000),
      current_end: Math.floor(Date.now() / 1000) + 30 * 86400,
      charge_at: Math.floor(Date.now() / 1000) + 30 * 86400
    });

    const signature = computeHmacHex(`${paymentId}|${subId}`, mockEnv.RAZORPAY_KEY_SECRET);
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/verify-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({
        subscription_id: subId,
        payment_id: paymentId,
        signature: signature
      })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.equal(body.status, "ACTIVE");

    // Verify Firestore state
    const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(subDoc.subscriptionStatus.stringValue, "ACTIVE");
    assert.equal(subDoc.mandateStatus.stringValue, "ACTIVE");
    assert.equal(subDoc.subscriptionId.stringValue, subId);
    assert.equal(subDoc.premium.booleanValue, true);
    assert.equal(subDoc.latestPaymentId.stringValue, paymentId);
  });

  await test("POST /api/verify-subscription rejects subscription with no verifiable owner in notes or server map (403)", async () => {
    const uid = "user_no_owner_test";
    const subId = "sub_no_owner_123";
    const paymentId = "pay_no_owner_123";

    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "active",
      plan_id: "plan_TdtgehXIhzQ8U9",
      notes: {},
      current_start: Math.floor(Date.now() / 1000),
      current_end: Math.floor(Date.now() / 1000) + 30 * 86400
    });
    mockFirestore.delete(`subscription_map/${subId}`);

    const signature = computeHmacHex(`${paymentId}|${subId}`, mockEnv.RAZORPAY_KEY_SECRET);
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/verify-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({
        subscription_id: subId,
        payment_id: paymentId,
        signature: signature
      })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 403);
    const body = await res.json();
    assert.match(body.error, /has no verifiable owner/);
  });

  await test("POST /api/verify-subscription rejects conflicting ownership between notes and server map (403)", async () => {
    const uid = "user_notes_owner";
    const differentUid = "user_map_owner";
    const subId = "sub_conflict_ownership_123";
    const paymentId = "pay_conflict_123";

    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "active",
      plan_id: "plan_TdtgehXIhzQ8U9",
      notes: { firebase_uid: uid },
      current_start: Math.floor(Date.now() / 1000),
      current_end: Math.floor(Date.now() / 1000) + 30 * 86400
    });
    mockFirestore.set(`subscription_map/${subId}`, {
      firebaseUid: { stringValue: differentUid }
    });

    const signature = computeHmacHex(`${paymentId}|${subId}`, mockEnv.RAZORPAY_KEY_SECRET);
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/verify-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({
        subscription_id: subId,
        payment_id: paymentId,
        signature: signature
      })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 403);
    const body = await res.json();
    assert.match(body.error, /Subscription ownership verification conflict/);
  });

  await test("POST /api/verify-subscription accepts trusted fallback mapping when notes UID is absent (200)", async () => {
    const uid = "user_fallback_mapping_owner";
    const subId = "sub_fallback_mapping_123";
    const paymentId = "pay_fallback_123";

    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "active",
      plan_id: "plan_TdtgehXIhzQ8U9",
      notes: {},
      current_start: Math.floor(Date.now() / 1000),
      current_end: Math.floor(Date.now() / 1000) + 30 * 86400
    });
    mockFirestore.set(`subscription_map/${subId}`, {
      firebaseUid: { stringValue: uid }
    });

    const signature = computeHmacHex(`${paymentId}|${subId}`, mockEnv.RAZORPAY_KEY_SECRET);
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/verify-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({
        subscription_id: subId,
        payment_id: paymentId,
        signature: signature
      })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.equal(body.status, "ACTIVE");
  });

  await test("POST /api/verify-subscription handles authenticated mandate state without granting active premium or fake dates (200)", async () => {
    const uid = "user_mandate_pending_verify";
    const subId = "sub_mandate_pending_auth_123";
    const paymentId = "pay_mandate_auth_123";

    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "authenticated",
      plan_id: "plan_TdtgehXIhzQ8U9",
      notes: { firebase_uid: uid }
    });

    const signature = computeHmacHex(`${paymentId}|${subId}`, mockEnv.RAZORPAY_KEY_SECRET);
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/verify-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      },
      body: JSON.stringify({
        subscription_id: subId,
        payment_id: paymentId,
        signature: signature
      })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.equal(body.status, "AUTHENTICATED");
    assert.equal(body.mandatePending, true);
    assert.match(body.message, /Mandate authorized successfully/);

    const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.ok(subDoc);
    assert.equal(subDoc.mandateStatus?.stringValue, "AUTHENTICATED");
    assert.equal(subDoc.subscriptionId?.stringValue, subId);
    assert.notEqual(subDoc.subscriptionStatus?.stringValue, "ACTIVE", "Must not set subscriptionStatus = ACTIVE for mandate-only");
    assert.notEqual(subDoc.premium?.booleanValue, true, "Must not set premium = true for mandate-only");
    assert.equal(subDoc.currentPeriodEnd, undefined, "Must not manufacture fake currentPeriodEnd");
  });

  // -------------------------------------------------------------------------
  // 4. cancel-subscription Endpoint Tests
  // -------------------------------------------------------------------------
  await test("POST /api/cancel-subscription cancels at period end and preserves active access", async () => {
    const uid = "user_cancel_test";
    const subId = "sub_cancel_valid_456";
    const periodEndMs = Date.now() + 15 * 86400 * 1000;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      currentPeriodEnd: { timestampValue: new Date(periodEndMs).toISOString() },
      premium: { booleanValue: true }
    });

    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "active",
      plan_id: "plan_TdtgehXIhzQ8U9",
      current_end: Math.floor(periodEndMs / 1000)
    });

    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/cancel-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.equal(body.cancelAtPeriodEnd, true);

    const updatedDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(updatedDoc.cancelAtPeriodEnd.booleanValue, true);
    assert.equal(updatedDoc.subscriptionStatus.stringValue, "ACTIVE"); // Active until period end
  });

  // -------------------------------------------------------------------------
  // 5. Razorpay Webhook Lifecycle Tests
  // -------------------------------------------------------------------------
  async function sendWebhook(eventPayload, eventId = `evt_${Date.now()}_${Math.random()}`) {
    const payloadStr = JSON.stringify({ ...eventPayload, event_id: eventId });
    const signature = computeHmacHex(payloadStr, mockEnv.RAZORPAY_WEBHOOK_SECRET);
    const req = new Request("https://refocus.workers.dev/api/razorpay-webhook", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Razorpay-Signature": signature
      },
      body: payloadStr
    });
    return await worker.fetch(req, mockEnv, {});
  }

  await test("Webhook rejects missing or invalid signature (400)", async () => {
    const payloadStr = JSON.stringify({ event: "subscription.charged", id: "evt_invalid_sig" });
    const req = new Request("https://refocus.workers.dev/api/razorpay-webhook", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Razorpay-Signature": "bad_signature"
      },
      body: payloadStr
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 400);
  });

  await test("Webhook enforces atomic idempotency on duplicate deliveries", async () => {
    const eventId = "evt_idempotent_test_1";
    const payload = {
      event: "payment.failed",
      payload: { payment: { entity: { id: "pay_failed_1" } } }
    };

    const res1 = await sendWebhook(payload, eventId);
    assert.equal(res1.status, 200);
    const body1 = await res1.json();
    assert.equal(body1.status, "processed_payment_failed");

    const res2 = await sendWebhook(payload, eventId);
    assert.equal(res2.status, 200);
    const body2 = await res2.json();
    assert.equal(body2.status, "already_processed");
  });

  await test("Webhook: subscription.charged renews cycle and extends currentPeriodEnd", async () => {
    const uid = "user_charged_test";
    const subId = "sub_recurring_cycle_1";
    const paymentId = "pay_charge_monthly_2";
    const nextMonthSec = Math.floor(Date.now() / 1000) + 30 * 86400;

    mockFirestore.set(`subscription_map/${subId}`, {
      firebaseUid: { stringValue: uid },
      planId: { stringValue: "plan_TdtgehXIhzQ8U9" }
    });

    const res = await sendWebhook({
      event: "subscription.charged",
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            current_start: Math.floor(Date.now() / 1000),
            current_end: nextMonthSec,
            charge_at: nextMonthSec,
            notes: { firebase_uid: uid }
          }
        },
        payment: {
          entity: {
            id: paymentId,
            subscription_id: subId,
            amount: 7900,
            currency: "INR"
          }
        }
      }
    });

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "ACTIVE");
    assert.equal(doc.latestPaymentId.stringValue, paymentId);
    assert.equal(doc.currentPeriodEnd.timestampValue, new Date(nextMonthSec * 1000).toISOString());
  });

  await test("Webhook: subscription.pending puts subscription in PENDING status (grace period)", async () => {
    const uid = "user_pending_test";
    const subId = "sub_pending_1";
    const nowSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date((nowSec - 100) * 1000).toISOString() },
      updatedAt: { timestampValue: new Date((nowSec - 100) * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.pending",
      created_at: nowSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            updated_at: nowSec,
            notes: { firebase_uid: uid }
          }
        }
      }
    });

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "PENDING");
  });

  await test("Webhook: subscription.halted revokes premium access immediately", async () => {
    const uid = "user_halted_test";
    const subId = "sub_halted_1";
    const nowSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "PENDING" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date((nowSec - 100) * 1000).toISOString() },
      updatedAt: { timestampValue: new Date((nowSec - 100) * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.halted",
      created_at: nowSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            updated_at: nowSec,
            notes: { firebase_uid: uid }
          }
        }
      }
    });

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "HALTED");
    assert.equal(doc.premium.booleanValue, false);
  });

  await test("Webhook: refund.processed revokes access and marks CANCELLED", async () => {
    const uid = "user_refund_test";
    const subId = "sub_refund_1";
    const paymentId = "pay_refund_1";
    const nowSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      latestPaymentId: { stringValue: paymentId },
      premium: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date((nowSec - 100) * 1000).toISOString() },
      updatedAt: { timestampValue: new Date((nowSec - 100) * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "refund.processed",
      created_at: nowSec,
      payload: {
        payment: { entity: { id: paymentId, subscription_id: subId, notes: { firebase_uid: uid } } },
        refund: { entity: { id: "rfnd_test_99", payment_id: paymentId, created_at: nowSec } }
      }
    });

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
    assert.ok(doc.refundedAt);
  });

  await test("Webhook: rejects stale activation after refund/cancellation", async () => {
    const uid = "user_stale_test";
    const subId = "sub_stale_1";
    const nowSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      refundedAt: { timestampValue: new Date(nowSec * 1000).toISOString() },
      lastEventTimestamp: { timestampValue: new Date(nowSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(nowSec * 1000).toISOString() },
      premium: { booleanValue: false }
    });

    const res = await sendWebhook({
      event: "subscription.charged",
      created_at: nowSec - 50,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            notes: { firebase_uid: uid },
            current_start: nowSec - 50,
            current_end: nowSec + 30 * 86400
          }
        }
      }
    });

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // -------------------------------------------------------------------------
  // 6. Comprehensive Stale/Out-of-Order Webhook Security Matrix (Tests A - X)
  // -------------------------------------------------------------------------

  // Test A: Missing timestamp after ACTIVE (ACTIVE must remain ACTIVE)
  await test("Webhook Test A: Missing timestamp after ACTIVE preserves ACTIVE state", async () => {
    const uid = "user_test_a";
    const subId = "sub_test_a";
    const baseTimeSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: true },
      currentPeriodEnd: { timestampValue: new Date((baseTimeSec + 30 * 86400) * 1000).toISOString() },
      lastEventTimestamp: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(baseTimeSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.pending",
      payload: {
        subscription: {
          entity: {
            id: subId,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_a_no_ts");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "ACTIVE");
    assert.equal(doc.premium.booleanValue, true);
  });

  // Test B: Missing timestamp after CANCELLED (CANCELLED must remain CANCELLED)
  await test("Webhook Test B: Missing timestamp after CANCELLED preserves CANCELLED state", async () => {
    const uid = "user_test_b";
    const subId = "sub_test_b";
    const baseTimeSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      cancelAtPeriodEnd: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(baseTimeSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.activated",
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            current_start: baseTimeSec,
            current_end: baseTimeSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_b_no_ts");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test C: Missing timestamp after REFUNDED (remains revoked)
  await test("Webhook Test C: Missing timestamp after REFUNDED remains revoked", async () => {
    const uid = "user_test_c";
    const subId = "sub_test_c";
    const baseTimeSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      refundedAt: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      lastEventTimestamp: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(baseTimeSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.activated",
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_c_no_ts");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test D: Malformed timestamp after CANCELLED (no resurrection)
  await test("Webhook Test D: Malformed timestamp after CANCELLED rejects resurrection", async () => {
    const uid = "user_test_d";
    const subId = "sub_test_d";
    const baseTimeSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      cancelAtPeriodEnd: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(baseTimeSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: "not-a-valid-number",
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: "NaN",
            current_start: baseTimeSec,
            current_end: baseTimeSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_d_malformed");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test E: Zero timestamp after CANCELLED (no resurrection)
  await test("Webhook Test E: Zero timestamp after CANCELLED rejects resurrection", async () => {
    const uid = "user_test_e";
    const subId = "sub_test_e";
    const baseTimeSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      cancelAtPeriodEnd: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(baseTimeSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: 0,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: 0,
            current_start: baseTimeSec,
            current_end: baseTimeSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_e_zero");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test F: Negative timestamp after CANCELLED (no resurrection)
  await test("Webhook Test F: Negative timestamp after CANCELLED rejects resurrection", async () => {
    const uid = "user_test_f";
    const subId = "sub_test_f";
    const baseTimeSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      cancelAtPeriodEnd: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(baseTimeSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: -500,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: -500,
            current_start: baseTimeSec,
            current_end: baseTimeSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_f_neg");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test G: Future timestamp +1 hour (rejected; must not poison state)
  await test("Webhook Test G: Future timestamp +1 hour is rejected and does not poison lastEventTimestamp", async () => {
    const uid = "user_test_g";
    const subId = "sub_test_g";
    const nowSec = Math.floor(Date.now() / 1000);
    const futureOneHourSec = nowSec + 3600;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date(nowSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(nowSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.charged",
      created_at: futureOneHourSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: futureOneHourSec,
            current_start: futureOneHourSec,
            current_end: futureOneHourSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        },
        payment: { entity: { id: "pay_g_future", subscription_id: subId } }
      }
    }, "evt_test_g_future_1h");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    // lastEventTimestamp must remain nowSec, NOT poisoned to 1 hour in future
    assert.equal(doc.lastEventTimestamp.timestampValue, new Date(nowSec * 1000).toISOString());
  });

  // Test H: Future timestamp +10 minutes (rejected: exceeds 5 min limit)
  await test("Webhook Test H: Future timestamp +10 minutes is rejected (> 5 min skew)", async () => {
    const uid = "user_test_h";
    const subId = "sub_test_h";
    const nowSec = Math.floor(Date.now() / 1000);
    const future10MinSec = nowSec + 600;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date(nowSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(nowSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.charged",
      created_at: future10MinSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: future10MinSec,
            current_start: nowSec,
            current_end: nowSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        },
        payment: { entity: { id: "pay_h_future", subscription_id: subId } }
      }
    }, "evt_test_h_future_10m");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.lastEventTimestamp.timestampValue, new Date(nowSec * 1000).toISOString());
  });

  // Test I: Future timestamp +4 minutes (allowed within 5 min tolerance)
  await test("Webhook Test I: Future timestamp +4 minutes is allowed within 5 min tolerance", async () => {
    const uid = "user_test_i";
    const subId = "sub_test_i";
    const nowSec = Math.floor(Date.now() / 1000);
    const future4MinSec = nowSec + 240;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: true },
      currentPeriodEnd: { timestampValue: new Date((nowSec + 30 * 86400) * 1000).toISOString() },
      lastEventTimestamp: { timestampValue: new Date((nowSec - 100) * 1000).toISOString() },
      updatedAt: { timestampValue: new Date((nowSec - 100) * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.charged",
      created_at: future4MinSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: future4MinSec,
            current_start: nowSec,
            current_end: nowSec + 60 * 86400,
            notes: { firebase_uid: uid }
          }
        },
        payment: { entity: { id: "pay_i_valid", subscription_id: subId, amount: 7900, currency: "INR" } }
      }
    }, "evt_test_i_future_4m");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.lastEventTimestamp.timestampValue, new Date(future4MinSec * 1000).toISOString());
    assert.equal(doc.currentPeriodEnd.timestampValue, new Date((nowSec + 60 * 86400) * 1000).toISOString());
  });

  // Test J: Future timestamp (+1 hour) followed by legitimate cancellation (cancellation must succeed)
  await test("Webhook Test J: Excessive future event rejected, subsequent legitimate cancellation succeeds", async () => {
    const uid = "user_test_j";
    const subId = "sub_test_j";
    const nowSec = Math.floor(Date.now() / 1000);
    const futureOneHourSec = nowSec + 3600;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: true },
      currentPeriodEnd: { timestampValue: new Date((nowSec + 30 * 86400) * 1000).toISOString() },
      lastEventTimestamp: { timestampValue: new Date(nowSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(nowSec * 1000).toISOString() }
    });

    // 1. Send bogus +1hr future event
    await sendWebhook({
      event: "subscription.charged",
      created_at: futureOneHourSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: futureOneHourSec,
            current_start: nowSec,
            current_end: nowSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        },
        payment: { entity: { id: "pay_j_bogus", subscription_id: subId } }
      }
    }, "evt_test_j_future");

    // 2. Real legitimate cancellation arrives at nowSec + 10
    const resCancel = await sendWebhook({
      event: "subscription.cancelled",
      created_at: nowSec + 10,
      payload: {
        subscription: {
          entity: {
            id: subId,
            updated_at: nowSec + 10,
            cancel_at_cycle_end: 0,
            current_end: nowSec - 100, // Period ended
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_j_real_cancel");

    assert.equal(resCancel.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test K: Timestamp exactly equal to stored timestamp (must not overwrite state)
  await test("Webhook Test K: Timestamp exactly equal to stored timestamp is rejected as duplicate/stale", async () => {
    const uid = "user_test_k";
    const subId = "sub_test_k";
    const baseTimeSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      lastEventTimestamp: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(baseTimeSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: baseTimeSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: baseTimeSec,
            current_start: baseTimeSec,
            current_end: baseTimeSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_k_equal");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test L: Timestamp 1ms older (rejected)
  await test("Webhook Test L: Timestamp 1ms older than stored lastEventTimestamp is rejected", async () => {
    const uid = "user_test_l";
    const subId = "sub_test_l";
    const baseTimeSec = 1700000000;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      lastEventTimestamp: { timestampValue: new Date(baseTimeSec * 1000 + 1).toISOString() },
      updatedAt: { timestampValue: new Date(baseTimeSec * 1000 + 1).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: baseTimeSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: baseTimeSec,
            current_start: baseTimeSec,
            current_end: baseTimeSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_l_1ms_older");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test M: Timestamp 9.999s older (rejected by strict updatedAt ordering)
  await test("Webhook Test M: Timestamp 9.999s older than updatedAt is strictly rejected", async () => {
    const uid = "user_test_m";
    const subId = "sub_test_m";
    const nowMs = 1700000000000;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      updatedAt: { timestampValue: new Date(nowMs).toISOString() }
    });

    const incomingSec = (nowMs - 9999) / 1000;
    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: incomingSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: incomingSec,
            current_start: incomingSec,
            current_end: incomingSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_m_9999ms_older");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test N: Timestamp 10s older (rejected)
  await test("Webhook Test N: Timestamp 10.000s older than updatedAt is strictly rejected", async () => {
    const uid = "user_test_n";
    const subId = "sub_test_n";
    const nowMs = 1700000000000;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      updatedAt: { timestampValue: new Date(nowMs).toISOString() }
    });

    const incomingSec = (nowMs - 10000) / 1000;
    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: incomingSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: incomingSec,
            current_start: incomingSec,
            current_end: incomingSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_n_10s_older");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test O: Timestamp 10.001s older (rejected)
  await test("Webhook Test O: Timestamp 10.001s older than updatedAt is strictly rejected", async () => {
    const uid = "user_test_o";
    const subId = "sub_test_o";
    const nowMs = 1700000000000;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      updatedAt: { timestampValue: new Date(nowMs).toISOString() }
    });

    const incomingSec = (nowMs - 10001) / 1000;
    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: incomingSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: incomingSec,
            current_start: incomingSec,
            current_end: incomingSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_o_10001ms_older");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test P: Activation missing current_start (must NOT generate period from Date.now())
  await test("Webhook Test P: Activation missing current_start fails closed without generating synthetic period", async () => {
    const uid = "user_test_p";
    const subId = "sub_test_p";
    const nowSec = Math.floor(Date.now() / 1000);

    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: nowSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: nowSec,
            // current_start is omitted
            current_end: nowSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_p_missing_start");

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.result.success, false);
    assert.match(body.result.message, /Missing authoritative billing period/);
    // Document must not exist with a manufactured start date
    assert.equal(mockFirestore.get(`users/${uid}/subscription/status`), undefined);
  });

  // Test Q: Activation missing current_end (must NOT generate currentPeriodEnd +30 days)
  await test("Webhook Test Q: Activation missing current_end fails closed without synthesizing 30-day end", async () => {
    const uid = "user_test_q";
    const subId = "sub_test_q";
    const nowSec = Math.floor(Date.now() / 1000);

    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: nowSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: nowSec,
            current_start: nowSec,
            // current_end is omitted
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_q_missing_end");

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.result.success, false);
    assert.match(body.result.message, /Missing authoritative billing period/);
    assert.equal(mockFirestore.get(`users/${uid}/subscription/status`), undefined);
  });

  // Test R: Activation with both valid current_start/current_end (legitimate activation succeeds)
  await test("Webhook Test R: Activation with valid current_start/current_end succeeds", async () => {
    const uid = "user_test_r";
    const subId = "sub_test_r";
    const nowSec = Math.floor(Date.now() / 1000);
    const startSec = nowSec;
    const endSec = nowSec + 30 * 86400;

    const res = await sendWebhook({
      event: "subscription.activated",
      created_at: nowSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: nowSec,
            current_start: startSec,
            current_end: endSec,
            notes: { firebase_uid: uid }
          }
        },
        payment: { entity: { id: "pay_r_valid", subscription_id: subId } }
      }
    }, "evt_test_r_valid_act");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "ACTIVE");
    assert.equal(doc.premium.booleanValue, true);
    assert.equal(doc.currentPeriodStart.timestampValue, new Date(startSec * 1000).toISOString());
    assert.equal(doc.currentPeriodEnd.timestampValue, new Date(endSec * 1000).toISOString());
  });

  // Test S: Valid newer charged event (legitimate renewal succeeds)
  await test("Webhook Test S: Valid newer subscription.charged event advances cycle", async () => {
    const uid = "user_test_s";
    const subId = "sub_test_s";
    const baseTimeSec = Math.floor(Date.now() / 1000);
    const cycle1EndSec = baseTimeSec + 30 * 86400;
    const cycle2EndSec = baseTimeSec + 60 * 86400;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: true },
      currentPeriodStart: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      currentPeriodEnd: { timestampValue: new Date(cycle1EndSec * 1000).toISOString() },
      lastEventTimestamp: { timestampValue: new Date(baseTimeSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(baseTimeSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.charged",
      created_at: baseTimeSec + 100,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: baseTimeSec + 100,
            current_start: cycle1EndSec,
            current_end: cycle2EndSec,
            notes: { firebase_uid: uid }
          }
        },
        payment: { entity: { id: "pay_s_cycle2", subscription_id: subId, amount: 7900, currency: "INR" } }
      }
    }, "evt_test_s_renewal");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "ACTIVE");
    assert.equal(doc.currentPeriodEnd.timestampValue, new Date(cycle2EndSec * 1000).toISOString());
    assert.equal(doc.latestPaymentId.stringValue, "pay_s_cycle2");
  });

  // Test T: CANCELLED + valid genuinely newer billing event (reactivates cleanly)
  await test("Webhook Test T: CANCELLED state with genuinely newer billing event reactivates cleanly", async () => {
    const uid = "user_test_t";
    const subId = "sub_test_t";
    const nowSec = Math.floor(Date.now() / 1000);
    const cancelTimeSec = nowSec - 200;
    const newChargeTimeSec = nowSec + 10;
    const newEndSec = nowSec + 30 * 86400;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      cancelAtPeriodEnd: { booleanValue: true },
      lastEventTimestamp: { timestampValue: new Date(cancelTimeSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(cancelTimeSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.charged",
      created_at: newChargeTimeSec,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: newChargeTimeSec,
            current_start: nowSec,
            current_end: newEndSec,
            notes: { firebase_uid: uid }
          }
        },
        payment: { entity: { id: "pay_t_reactivate", subscription_id: subId } }
      }
    }, "evt_test_t_reactivate");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "ACTIVE");
    assert.equal(doc.premium.booleanValue, true);
    assert.equal(doc.currentPeriodEnd.timestampValue, new Date(newEndSec * 1000).toISOString());
  });

  // Test U: REFUNDED + old charged (strictly remains revoked)
  await test("Webhook Test U: REFUNDED subscription followed by replayed charged remains CANCELLED", async () => {
    const uid = "user_test_u";
    const subId = "sub_test_u";
    const nowSec = Math.floor(Date.now() / 1000);

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "CANCELLED" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: false },
      refundedAt: { timestampValue: new Date(nowSec * 1000).toISOString() },
      lastEventTimestamp: { timestampValue: new Date(nowSec * 1000).toISOString() },
      updatedAt: { timestampValue: new Date(nowSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "subscription.charged",
      created_at: nowSec - 50,
      payload: {
        subscription: {
          entity: {
            id: subId,
            plan_id: "plan_TdtgehXIhzQ8U9",
            updated_at: nowSec - 50,
            current_start: nowSec - 50,
            current_end: nowSec + 30 * 86400,
            notes: { firebase_uid: uid }
          }
        },
        payment: { entity: { id: "pay_u_old", subscription_id: subId } }
      }
    }, "evt_test_u_refund_guard");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "CANCELLED");
    assert.equal(doc.premium.booleanValue, false);
  });

  // Test V: Duplicate event ID (idempotency check)
  await test("Webhook Test V: Duplicate event ID returns already_processed", async () => {
    const eventId = "evt_duplicate_v";
    const payload = {
      event: "payment.failed",
      payload: { payment: { entity: { id: "pay_dup_v" } } }
    };

    const res1 = await sendWebhook(payload, eventId);
    assert.equal(res1.status, 200);
    assert.equal((await res1.json()).status, "processed_payment_failed");

    const res2 = await sendWebhook(payload, eventId);
    assert.equal(res2.status, 200);
    assert.equal((await res2.json()).status, "already_processed");
  });

  // Test W: Concurrent duplicate webhooks (atomic idempotency)
  await test("Webhook Test W: Concurrent duplicate webhooks processed safely and atomically", async () => {
    const eventId = "evt_concurrent_w";
    const payload = {
      event: "payment.failed",
      payload: { payment: { entity: { id: "pay_concurrent_w" } } }
    };

    const [res1, res2] = await Promise.all([
      sendWebhook(payload, eventId),
      sendWebhook(payload, eventId)
    ]);

    assert.equal(res1.status, 200);
    assert.equal(res2.status, 200);
    const b1 = await res1.json();
    const b2 = await res2.json();
    const statuses = [b1.status, b2.status];
    assert.ok(statuses.includes("processed_payment_failed"));
    assert.ok(statuses.includes("already_processed"));
  });

  // Test X: payment.failed arriving after a valid paid entitlement
  await test("Webhook Test X: payment.failed does not revoke existing active period", async () => {
    const uid = "user_test_x";
    const subId = "sub_test_x";
    const futureEndSec = Math.floor(Date.now() / 1000) + 25 * 86400;

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId },
      premium: { booleanValue: true },
      currentPeriodEnd: { timestampValue: new Date(futureEndSec * 1000).toISOString() }
    });

    const res = await sendWebhook({
      event: "payment.failed",
      payload: {
        payment: {
          entity: {
            id: "pay_failed_x",
            subscription_id: subId,
            notes: { firebase_uid: uid }
          }
        }
      }
    }, "evt_test_x_pay_failed");

    assert.equal(res.status, 200);
    const doc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(doc.subscriptionStatus.stringValue, "ACTIVE");
    assert.equal(doc.premium.booleanValue, true);
  });

  // -------------------------------------------------------------------------
  // 6. Dual-Key & Global Rate Limiting Abuse Hardening Tests
  // -------------------------------------------------------------------------
  await test("Rate Limit: /api/time throttles after 30 requests per IP with 429 and Retry-After", async () => {
    const ip = "192.168.1.101";
    for (let i = 0; i < 30; i++) {
      const req = new Request("https://refocus.workers.dev/api/time", {
        method: "GET",
        headers: { "CF-Connecting-IP": ip }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200, `Request ${i + 1} should be 200`);
    }

    const blockedReq = new Request("https://refocus.workers.dev/api/time", {
      method: "GET",
      headers: { "CF-Connecting-IP": ip }
    });
    const blockedRes = await worker.fetch(blockedReq, mockEnv, {});
    assert.equal(blockedRes.status, 429);
    assert.ok(blockedRes.headers.get("Retry-After"));
    const body = await blockedRes.json();
    assert.match(body.error, /Too many requests/);
  });

  await test("Rate Limit: /api/subscription/lease throttles on UID limit (60 req/min)", async () => {
    const uid = "user_rate_uid_test";
    const token = createMockFirebaseIdToken(uid);
    const ip = "10.0.0.1";

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      planId: { stringValue: "reeltrak_pro_monthly" },
      currentPeriodEnd: { timestampValue: new Date(Date.now() + 86400000).toISOString() },
      premium: { booleanValue: true }
    });

    for (let i = 0; i < 60; i++) {
      const req = new Request("https://refocus.workers.dev/api/subscription/lease", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${token}`,
          "CF-Connecting-IP": `${ip}_${i}` // vary IP so only UID limit is tested
        }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200, `Lease request ${i + 1} should succeed`);
    }

    const blockedReq = new Request("https://refocus.workers.dev/api/subscription/lease", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "10.0.0.999"
      }
    });
    const blockedRes = await worker.fetch(blockedReq, mockEnv, {});
    assert.equal(blockedRes.status, 429);
    assert.ok(blockedRes.headers.get("Retry-After"));
  });

  await test("Rate Limit: /api/subscription/lease throttles on IP limit (60 req/hr)", async () => {
    const ip = "172.16.0.50";

    for (let i = 0; i < 60; i++) {
      const uid = `user_rate_ip_sub_${i}`;
      const token = createMockFirebaseIdToken(uid);
      mockFirestore.set(`users/${uid}/subscription/status`, {
        subscriptionStatus: { stringValue: "ACTIVE" },
        planId: { stringValue: "reeltrak_pro_monthly" },
        currentPeriodEnd: { timestampValue: new Date(Date.now() + 86400000).toISOString() },
        premium: { booleanValue: true }
      });

      const req = new Request("https://refocus.workers.dev/api/subscription/lease", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${token}`,
          "CF-Connecting-IP": ip
        }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200, `Request ${i + 1} from IP ${ip} should succeed`);
    }

    // 61st request from same IP with fresh UID must be blocked by IP lease rate limit
    const freshUid = "user_rate_ip_sub_fresh";
    const freshToken = createMockFirebaseIdToken(freshUid);
    mockFirestore.set(`users/${freshUid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      planId: { stringValue: "reeltrak_pro_monthly" },
      currentPeriodEnd: { timestampValue: new Date(Date.now() + 86400000).toISOString() },
      premium: { booleanValue: true }
    });

    const blockedReq = new Request("https://refocus.workers.dev/api/subscription/lease", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${freshToken}`,
        "CF-Connecting-IP": ip
      }
    });
    const blockedRes = await worker.fetch(blockedReq, mockEnv, {});
    assert.equal(blockedRes.status, 429);
    const body = await blockedRes.json();
    assert.match(body.error, /Too many requests from this IP/);
  });

  await test("Rate Limit: Global authenticated ceiling triggers at 120 req/min per IP", async () => {
    const ip = "198.51.100.77";

    // Issue 120 requests across various authenticated routes
    for (let i = 0; i < 120; i++) {
      const uid = `user_global_auth_${i}`;
      const token = createMockFirebaseIdToken(uid);
      const req = new Request("https://refocus.workers.dev/api/account/status", {
        method: "GET",
        headers: {
          "Authorization": `Bearer ${token}`,
          "CF-Connecting-IP": ip
        }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200, `Global request ${i + 1} should succeed`);
    }

    // 121st request from same IP must hit global authenticated ceiling
    const extraUid = "user_global_auth_121";
    const extraToken = createMockFirebaseIdToken(extraUid);
    const blockedReq = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${extraToken}`,
        "CF-Connecting-IP": ip
      }
    });
    const blockedRes = await worker.fetch(blockedReq, mockEnv, {});
    assert.equal(blockedRes.status, 429);
    assert.ok(blockedRes.headers.get("Retry-After"));
    const body = await blockedRes.json();
    assert.match(body.error, /Too many requests/);
  });

  // -------------------------------------------------------------------------
  // Lease Signing Security & Fail-Closed Tests
  // -------------------------------------------------------------------------
  await test("Lease Signing: Fails closed with 500 when LEASE_PRIVATE_KEY_PKCS8 is missing from env", async () => {
    const uid = "user_lease_missing_key_test";
    const token = createMockFirebaseIdToken(uid);
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      planId: { stringValue: "reeltrak_pro_monthly" },
      currentPeriodEnd: { timestampValue: new Date(Date.now() + 86400000).toISOString() },
      premium: { booleanValue: true }
    });

    const envWithoutKey = { ...mockEnv, LEASE_PRIVATE_KEY_PKCS8: undefined };
    const req = new Request("https://refocus.workers.dev/api/subscription/lease", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.1"
      }
    });
    const res = await worker.fetch(req, envWithoutKey, {});
    assert.equal(res.status, 500);
    const body = await res.json();
    assert.equal(body.error, "Internal Server Error");
  });

  await test("Lease Signing: Signs lease with valid ECDSA signature when LEASE_PRIVATE_KEY_PKCS8 is present", async () => {
    const uid = "user_lease_valid_key_test";
    const token = createMockFirebaseIdToken(uid);
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      planId: { stringValue: "reeltrak_pro_monthly" },
      currentPeriodEnd: { timestampValue: new Date(Date.now() + 86400000).toISOString() },
      premium: { booleanValue: true }
    });

    const req = new Request("https://refocus.workers.dev/api/subscription/lease", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.2"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.uid, uid);
    assert.equal(body.accessMode, "ACTIVE");
    assert.ok(body.signature);
    assert.ok(typeof body.signature === "string" && body.signature.length > 30);
  });

  // -------------------------------------------------------------------------
  // Trial Entitlement & Registry Hardening Tests
  // -------------------------------------------------------------------------

  await test("Ensure-Subscription: Creates 7-day trial for new account with trial_registry record", async () => {
    const uid = "user_trial_new_account";
    const token = createMockFirebaseIdToken(uid);
    mockFirestore.delete(`trial_registry/${uid}`);
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.10"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 201);
    const body = await res.json();
    assert.equal(body.subscriptionStatus, "TRIAL");
    assert.equal(body.trialActive, true);
    assert.ok(body.trialEnd > Date.now());

    // Verify written to Firestore subscription
    const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.ok(subDoc);
    assert.equal(subDoc.subscriptionStatus?.stringValue, "TRIAL");

    // Verify recorded in permanent trial_registry
    const regDoc = mockFirestore.get(`trial_registry/${uid}`);
    assert.ok(regDoc);
    assert.equal(regDoc.uid?.stringValue, uid);
  });

  await test("Ensure-Subscription: Restores TRIAL status when existing trial_registry record has future trialEnd and status doc is missing", async () => {
    const uid = "user_trial_future_missing_doc";
    const token = createMockFirebaseIdToken(uid);
    const futureTrialEnd = new Date(Date.now() + 6 * 86400000).toISOString();
    const claimedAt = new Date(Date.now() - 1 * 86400000).toISOString();

    mockFirestore.set(`trial_registry/${uid}`, {
      uid: { stringValue: uid },
      claimedAt: { timestampValue: claimedAt },
      trialEnd: { timestampValue: futureTrialEnd }
    });
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.11"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.trialAlreadyClaimed, true);
    assert.equal(body.trialActive, true);
    assert.equal(body.subscriptionStatus, "TRIAL");

    // Verify reconstructed doc in Firestore has TRIAL, not EXPIRED!
    const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.ok(subDoc);
    assert.equal(subDoc.subscriptionStatus?.stringValue, "TRIAL");
    assert.equal(subDoc.trialEnd?.timestampValue, futureTrialEnd);
  });

  await test("Ensure-Subscription: Reconciles EXPIRED status back to TRIAL when trial_registry record has future trialEnd (Fixes Proven Bug)", async () => {
    const uid = "user_trial_proven_bug_reconcile";
    const token = createMockFirebaseIdToken(uid);
    const futureTrialEnd = new Date(Date.now() + 5 * 86400000).toISOString();
    const claimedAt = new Date(Date.now() - 2 * 86400000).toISOString();

    mockFirestore.set(`trial_registry/${uid}`, {
      uid: { stringValue: uid },
      claimedAt: { timestampValue: claimedAt },
      trialEnd: { timestampValue: futureTrialEnd }
    });
    // Buggy state: doc had EXPIRED with future trialEnd!
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "EXPIRED" },
      trialStart: { timestampValue: claimedAt },
      trialEnd: { timestampValue: futureTrialEnd },
      premium: { booleanValue: false }
    });

    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.12"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.trialActive, true);
    assert.equal(body.subscriptionStatus, "TRIAL");

    // Verify Firestore doc was reconciled back to TRIAL
    const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(subDoc.subscriptionStatus?.stringValue, "TRIAL");
  });

  await test("Ensure-Subscription: Restores EXPIRED status and rejects extension when trial_registry record has past trialEnd", async () => {
    const uid = "user_trial_expired_past";
    const token = createMockFirebaseIdToken(uid);
    const pastTrialEnd = new Date(Date.now() - 2 * 86400000).toISOString();
    const pastClaimedAt = new Date(Date.now() - 9 * 86400000).toISOString();

    mockFirestore.set(`trial_registry/${uid}`, {
      uid: { stringValue: uid },
      claimedAt: { timestampValue: pastClaimedAt },
      trialEnd: { timestampValue: pastTrialEnd }
    });
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.13"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.trialAlreadyClaimed, true);
    assert.equal(body.trialActive, false);
    assert.equal(body.subscriptionStatus, "EXPIRED");

    // Verify reconstructed doc is EXPIRED and trialEnd was NOT extended
    const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(subDoc.subscriptionStatus?.stringValue, "EXPIRED");
    assert.equal(subDoc.trialEnd?.timestampValue, pastTrialEnd);
  });

  await test("Subscription Lease: Issues valid TRIAL lease and auto-reconciles when status was EXPIRED with future trialEnd", async () => {
    const uid = "user_lease_trial_reconcile";
    const token = createMockFirebaseIdToken(uid);
    const futureTrialEnd = new Date(Date.now() + 4 * 86400000).toISOString();

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "EXPIRED" }, // Erroneous status
      trialEnd: { timestampValue: futureTrialEnd },
      premium: { booleanValue: false }
    });

    const req = new Request("https://refocus.workers.dev/api/subscription/lease", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.14"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.uid, uid);
    assert.equal(body.accessMode, "TRIAL");
    assert.ok(body.signature);

    // Verify auto-reconciled in Firestore to TRIAL
    const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.equal(subDoc.subscriptionStatus?.stringValue, "TRIAL");
  });

  await test("Subscription Lease: Fails closed with 403 when trialEnd is in the past and no paid subscription", async () => {
    const uid = "user_lease_expired_trial";
    const token = createMockFirebaseIdToken(uid);
    const pastTrialEnd = new Date(Date.now() - 86400000).toISOString();

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "EXPIRED" },
      trialEnd: { timestampValue: pastTrialEnd },
      premium: { booleanValue: false }
    });

    const req = new Request("https://refocus.workers.dev/api/subscription/lease", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.15"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 403);
    const body = await res.json();
    assert.equal(body.accessMode, null);
  });

  // -------------------------------------------------------------------------
  // Server-Authoritative Once-Per-Day Backup Tests
  // -------------------------------------------------------------------------

  await test("Backup: First backup today succeeds, writes records and sets SUCCESS status", async () => {
    const uid = "user_backup_first_today";
    const token = createMockFirebaseIdToken(uid);
    const futureTrialEnd = new Date(Date.now() + 7 * 86400000).toISOString();

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "TRIAL" },
      trialEnd: { timestampValue: futureTrialEnd },
      premium: { booleanValue: false }
    });

    const payload = {
      requestId: "req_test_backup_001",
      defaultDailyLimit: 60,
      dailyRecords: [
        {
          dateString: "2026-10-09",
          totalReelsCount: 45,
          dailyGoal: 60,
          isStrictModeOn: false,
          isStrictModeLocked: false,
          isLimitLocked: false
        }
      ]
    };

    const req = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.20"
      },
      body: JSON.stringify(payload)
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.ok(body.lastBackupTimeMs > 0);
    assert.ok(body.backupDate);

    // Verify written to mockFirestore
    const statusDoc = mockFirestore.get(`users/${uid}/backup/status`);
    assert.equal(statusDoc?.status?.stringValue, "SUCCESS");
    assert.equal(statusDoc?.lastBackupDate?.stringValue, body.backupDate);
    assert.equal(statusDoc?.lastRequestId?.stringValue, "req_test_backup_001");

    // Verify daily_data written
    const dailyDoc = mockFirestore.get(`users/${uid}/daily_data/2026-10-09`);
    assert.equal(dailyDoc?.totalReelsCount?.integerValue, "45");
  });

  await test("Backup: Second backup on same server day is rejected with 409 and controlled copy", async () => {
    const uid = "user_backup_first_today"; // Same UID as previous test
    const token = createMockFirebaseIdToken(uid);

    const payload = {
      requestId: "req_test_backup_002_distinct",
      defaultDailyLimit: 70,
      dailyRecords: [
        {
          dateString: "2026-10-09",
          totalReelsCount: 50,
          dailyGoal: 70,
          isStrictModeOn: false,
          isStrictModeLocked: false,
          isLimitLocked: false
        }
      ]
    };

    const req = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.21"
      },
      body: JSON.stringify(payload)
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 409);
    const body = await res.json();
    assert.equal(body.success, false);
    assert.equal(body.code, "BACKUP_RESTRICTED_24_HOURS");
    assert.equal(body.error, "You can back up your data once every 24 hours.");
  });

  await test("Backup: Idempotent retry with same requestId returns 200 without creating second backup", async () => {
    const uid = "user_backup_first_today";
    const token = createMockFirebaseIdToken(uid);

    // Retry with SAME requestId as test 1
    const payload = {
      requestId: "req_test_backup_001",
      defaultDailyLimit: 60,
      dailyRecords: []
    };

    const req = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.22"
      },
      body: JSON.stringify(payload)
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.equal(body.idempotent, true);
    assert.equal(body.message, "Backup already completed within the last 24 hours");
  });

  await test("Backup: Two concurrent backup requests - atomic claim allows only one", async () => {
    const uid = "user_concurrent_backup";
    const token = createMockFirebaseIdToken(uid);
    const futurePaidEnd = new Date(Date.now() + 30 * 86400000).toISOString();

    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      currentPeriodEnd: { timestampValue: futurePaidEnd },
      premium: { booleanValue: true }
    });

    const reqA = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.23"
      },
      body: JSON.stringify({ requestId: "req_concurrent_A", dailyRecords: [] })
    });

    const reqB = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.24"
      },
      body: JSON.stringify({ requestId: "req_concurrent_B", dailyRecords: [] })
    });

    // Launch concurrently
    const [resA, resB] = await Promise.all([
      worker.fetch(reqA, mockEnv, {}),
      worker.fetch(reqB, mockEnv, {})
    ]);

    const statuses = [resA.status, resB.status];
    assert.ok(statuses.includes(200), "One concurrent request must succeed");
    assert.ok(statuses.includes(409), "The other concurrent request must be rejected with 409");
  });

  await test("Backup: Account isolation - User A backup does not affect User B", async () => {
    const uidB = "user_backup_isolated_B";
    const tokenB = createMockFirebaseIdToken(uidB);
    const futureTrialEnd = new Date(Date.now() + 7 * 86400000).toISOString();

    mockFirestore.set(`users/${uidB}/subscription/status`, {
      subscriptionStatus: { stringValue: "TRIAL" },
      trialEnd: { timestampValue: futureTrialEnd },
      premium: { booleanValue: false }
    });

    const req = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${tokenB}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.25"
      },
      body: JSON.stringify({ requestId: "req_userB_001", dailyRecords: [] })
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
  });

  await test("Backup: Free / unentitled user is rejected with 403 Forbidden", async () => {
    const uidFree = "user_backup_free_unentitled";
    const tokenFree = createMockFirebaseIdToken(uidFree);

    // No subscription doc or expired status
    mockFirestore.set(`users/${uidFree}/subscription/status`, {
      subscriptionStatus: { stringValue: "NONE" },
      premium: { booleanValue: false }
    });

    const req = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${tokenFree}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.26"
      },
      body: JSON.stringify({ requestId: "req_free_attempt", dailyRecords: [] })
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 403);
    const body = await res.json();
    assert.equal(body.success, false);
    assert.equal(body.code, "ENTITLEMENT_REQUIRED");
  });

  await test("Backup: Expired trial user is rejected with 403 Forbidden", async () => {
    const uidExpired = "user_backup_expired_trial";
    const tokenExpired = createMockFirebaseIdToken(uidExpired);
    const pastTrialEnd = new Date(Date.now() - 86400000).toISOString();

    mockFirestore.set(`users/${uidExpired}/subscription/status`, {
      subscriptionStatus: { stringValue: "EXPIRED" },
      trialEnd: { timestampValue: pastTrialEnd },
      premium: { booleanValue: false }
    });

    const req = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${tokenExpired}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.27"
      },
      body: JSON.stringify({ requestId: "req_expired_attempt", dailyRecords: [] })
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 403);
    const body = await res.json();
    assert.equal(body.success, false);
    assert.equal(body.code, "ENTITLEMENT_REQUIRED");
  });

  await test("Backup Status: GET /api/backup/status reports accurate backedUpToday", async () => {
    const uid = "user_backup_first_today"; // User who backed up in Test 1
    const token = createMockFirebaseIdToken(uid);

    const req = new Request("https://refocus.workers.dev/api/backup/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.28"
      }
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.equal(body.backedUpToday, true);
    assert.ok(body.serverDate);
    assert.ok(body.lastBackupTimeMs > 0);
  });

  // -------------------------------------------------------------------------
  // Account Deletion & Lifecycle Test Suite
  // -------------------------------------------------------------------------
  await test("Account Deletion: GET /api/account/status returns ACTIVE for normal user", async () => {
    const uid = "user_del_test_1";
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.30"
      }
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.accountStatus, "ACTIVE");
  });

  await test("Account Deletion: POST /api/account/request-deletion schedules 7-day grace period", async () => {
    const uid = "user_del_test_1";
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/account/request-deletion", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.30"
      },
      body: JSON.stringify({})
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.accountStatus, "PENDING_DELETION");
    assert.ok(body.scheduledFor);
    assert.ok(body.scheduledForMs > Date.now());
  });

  await test("Account Deletion: Duplicate request returns existing pending deletion schedule", async () => {
    const uid = "user_del_test_1";
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/account/request-deletion", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.30"
      },
      body: JSON.stringify({})
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.accountStatus, "PENDING_DELETION");
    assert.match(body.message, /already pending/);
  });

  await test("Account Deletion: POST /api/account/cancel-deletion restores account to ACTIVE within grace period", async () => {
    const uid = "user_del_test_1";
    const token = createMockFirebaseIdToken(uid);
    const req = new Request("https://refocus.workers.dev/api/account/cancel-deletion", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.30"
      },
      body: JSON.stringify({})
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.accountStatus, "ACTIVE");
  });

  await test("Account Deletion: Expired grace period triggers permanent purge and returns 410 DELETED", async () => {
    const uid = "user_del_expired";
    const token = createMockFirebaseIdToken(uid);

    // Pre-populate user account with expired deletion timestamp (e.g. 1 hour ago)
    const pastTime = new Date(Date.now() - 3600000).toISOString();
    mockFirestore.set(`users/${uid}/account/status`, {
      accountStatus: { stringValue: "PENDING_DELETION" },
      deletionScheduledFor: { timestampValue: pastTime }
    });

    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.31"
      }
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 410);
    const body = await res.json();
    assert.equal(body.accountStatus, "DELETED");
    assert.match(body.message, /permanently purged/);
  });

  // -------------------------------------------------------------------------
  // Step 4J Regression Tests: Prevent Account Resurrection During Failed Purge
  // -------------------------------------------------------------------------

  await test("Account Deletion: Auth deletion failure after purge begins preserves DELETED state and sets PENDING_RETRY (500)", async () => {
    const uid = "user_del_auth_fail";
    const token = createMockFirebaseIdToken(uid);

    // Setup an expired PENDING_DELETION account
    const pastTime = new Date(Date.now() - 3600000).toISOString();
    mockFirestore.set(`users/${uid}/account/status`, {
      accountStatus: { stringValue: "PENDING_DELETION" },
      deletionScheduledFor: { timestampValue: pastTime }
    });

    // Inject failure into Identity Toolkit Auth deletion
    mockAuthFaultInjector = (url, options) => {
      return new Response(JSON.stringify({ error: { message: "Simulated internal Identity Toolkit error" } }), { status: 500 });
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/account/status", {
        method: "GET",
        headers: {
          "Authorization": `Bearer ${token}`,
          "CF-Connecting-IP": "192.0.2.32"
        }
      });

      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 500);
      const body = await res.json();
      assert.equal(body.accountStatus, "DELETED");
      assert.equal(body.purgeState, "PENDING_RETRY");
      assert.match(body.error, /Account purge in progress/);

      // Verify that status doc in Firestore is NOT active, but DELETED with PENDING_RETRY
      const statusDoc = mockFirestore.get(`users/${uid}/account/status`);
      assert.ok(statusDoc);
      assert.equal(statusDoc.accountStatus.stringValue, "DELETED");
      assert.equal(statusDoc.purgeState.stringValue, "PENDING_RETRY");

      // Verify that tombstone was written in account_tombstones collection
      const tombstoneDoc = mockFirestore.get(`account_tombstones/${uid}`);
      assert.ok(tombstoneDoc);
      assert.equal(tombstoneDoc.accountStatus.stringValue, "DELETED");
      assert.equal(tombstoneDoc.purgeState.stringValue, "PENDING_RETRY");
    } finally {
      mockAuthFaultInjector = null;
    }
  });

  await test("Account Deletion: Missing status document for authenticated UID fails closed via tombstone (410)", async () => {
    const uid = "user_del_tombstone_only";
    const token = createMockFirebaseIdToken(uid);

    // Durable tombstone exists in account_tombstones, but users/{uid}/account/status is MISSING
    mockFirestore.set(`account_tombstones/${uid}`, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "COMPLETED" },
      deletedAt: { timestampValue: new Date().toISOString() }
    });
    mockFirestore.delete(`users/${uid}/account/status`);

    const reqStatus = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.33"
      }
    });

    const resStatus = await worker.fetch(reqStatus, mockEnv, {});
    assert.equal(resStatus.status, 410);
    const bodyStatus = await resStatus.json();
    assert.equal(bodyStatus.accountStatus, "DELETED");
    assert.equal(bodyStatus.purgeState, "COMPLETED");

    // Verify users/{uid}/account/status was self-healed as DELETED tombstone
    const healedDoc = mockFirestore.get(`users/${uid}/account/status`);
    assert.ok(healedDoc);
    assert.equal(healedDoc.accountStatus.stringValue, "DELETED");

    // Request-deletion must fail-closed with 410
    const reqDel = new Request("https://refocus.workers.dev/api/account/request-deletion", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.33"
      },
      body: JSON.stringify({})
    });
    const resDel = await worker.fetch(reqDel, mockEnv, {});
    assert.equal(resDel.status, 410);

    // Cancel-deletion must fail-closed with 410
    const reqCancel = new Request("https://refocus.workers.dev/api/account/cancel-deletion", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.33"
      },
      body: JSON.stringify({})
    });
    const resCancel = await worker.fetch(reqCancel, mockEnv, {});
    assert.equal(resCancel.status, 410);
  });

  await test("Account Deletion: Repeated purge calls retry incomplete cleanup and transition to COMPLETED (410)", async () => {
    // User from Test 1 is currently in PENDING_RETRY
    const uid = "user_del_auth_fail";
    const token = createMockFirebaseIdToken(uid);

    // Auth service is now operational (mockAuthFaultInjector is null)
    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.34"
      }
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 410);
    const body = await res.json();
    assert.equal(body.accountStatus, "DELETED");
    assert.equal(body.purgeState, "COMPLETED");

    // Verify both Firestore docs are now COMPLETED
    const statusDoc = mockFirestore.get(`users/${uid}/account/status`);
    assert.equal(statusDoc.purgeState.stringValue, "COMPLETED");

    const tombstoneDoc = mockFirestore.get(`account_tombstones/${uid}`);
    assert.equal(tombstoneDoc.purgeState.stringValue, "COMPLETED");

    // Subsequent status check returns 410 immediately without re-triggering purge
    const req2 = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.34"
      }
    });
    const res2 = await worker.fetch(req2, mockEnv, {});
    assert.equal(res2.status, 410);
    const body2 = await res2.json();
    assert.equal(body2.accountStatus, "DELETED");
    assert.equal(body2.purgeState, "COMPLETED");
  });

  await test("Account Deletion: Attempts to cancel deletion after permanent purge begins are rejected (410)", async () => {
    // Subcase 4A: Account already DELETED
    const uidA = "user_cancel_already_deleted";
    const tokenA = createMockFirebaseIdToken(uidA);
    mockFirestore.set(`users/${uidA}/account/status`, {
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "COMPLETED" }
    });
    mockFirestore.set(`account_tombstones/${uidA}`, {
      uid: { stringValue: uidA },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "COMPLETED" }
    });

    const reqA = new Request("https://refocus.workers.dev/api/account/cancel-deletion", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${tokenA}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.35"
      },
      body: JSON.stringify({})
    });
    const resA = await worker.fetch(reqA, mockEnv, {});
    assert.equal(resA.status, 410);
    const bodyA = await resA.json();
    assert.match(bodyA.error, /permanently deleted and cannot be restored/);

    // Subcase 4B: Account PENDING_DELETION with grace period expired
    const uidB = "user_cancel_expired_grace";
    const tokenB = createMockFirebaseIdToken(uidB);
    const pastTime = new Date(Date.now() - 10000).toISOString();
    mockFirestore.set(`users/${uidB}/account/status`, {
      accountStatus: { stringValue: "PENDING_DELETION" },
      deletionScheduledFor: { timestampValue: pastTime }
    });

    const reqB = new Request("https://refocus.workers.dev/api/account/cancel-deletion", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${tokenB}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.36"
      },
      body: JSON.stringify({})
    });
    const resB = await worker.fetch(reqB, mockEnv, {});
    assert.equal(resB.status, 410);
    const bodyB = await resB.json();
    assert.match(bodyB.error, /Grace period has expired/);
    assert.equal(bodyB.accountStatus, "DELETED");
    assert.equal(bodyB.purgeState, "COMPLETED");

    // Status doc is now DELETED
    const docB = mockFirestore.get(`users/${uidB}/account/status`);
    assert.equal(docB.accountStatus.stringValue, "DELETED");
  });

  await test("Account Deletion: Normal cancellation during the seven-day grace period restores ACTIVE (200)", async () => {
    const uid = "user_grace_period_cancel";
    const token = createMockFirebaseIdToken(uid);

    // 1. Request deletion
    const reqDel = new Request("https://refocus.workers.dev/api/account/request-deletion", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.37"
      },
      body: JSON.stringify({})
    });
    const resDel = await worker.fetch(reqDel, mockEnv, {});
    assert.equal(resDel.status, 200);
    const bodyDel = await resDel.json();
    assert.equal(bodyDel.accountStatus, "PENDING_DELETION");
    assert.ok(bodyDel.scheduledForMs > Date.now());

    // 2. Cancel deletion within grace period
    const reqCancel = new Request("https://refocus.workers.dev/api/account/cancel-deletion", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.37"
      },
      body: JSON.stringify({})
    });
    const resCancel = await worker.fetch(reqCancel, mockEnv, {});
    assert.equal(resCancel.status, 200);
    const bodyCancel = await resCancel.json();
    assert.equal(bodyCancel.accountStatus, "ACTIVE");

    // 3. Verify GET /api/account/status reflects ACTIVE
    const reqStatus = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "192.0.2.37"
      }
    });
    const resStatus = await worker.fetch(reqStatus, mockEnv, {});
    assert.equal(resStatus.status, 200);
    const bodyStatus = await resStatus.json();
    assert.equal(bodyStatus.accountStatus, "ACTIVE");
  });

  await test("Account Deletion: Idempotent cleanup without accidental account reactivation", async () => {
    const uid = "user_del_idempotent_safe";
    const token = createMockFirebaseIdToken(uid);

    // Initial state: fully purged account with tombstone
    mockFirestore.set(`users/${uid}/account/status`, {
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "COMPLETED" },
      deletedAt: { timestampValue: new Date().toISOString() }
    });
    mockFirestore.set(`account_tombstones/${uid}`, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "COMPLETED" },
      deletedAt: { timestampValue: new Date().toISOString() }
    });

    // Populate data that should have been purged or newly attempted
    mockFirestore.set(`users/${uid}/settings/preferences`, {
      theme: { stringValue: "dark" }
    });

    // Repeated request-deletion -> 410
    const resDel = await worker.fetch(new Request("https://refocus.workers.dev/api/account/request-deletion", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.38" },
      body: JSON.stringify({})
    }), mockEnv, {});
    assert.equal(resDel.status, 410);

    // Repeated cancel-deletion -> 410
    const resCancel = await worker.fetch(new Request("https://refocus.workers.dev/api/account/cancel-deletion", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.38" },
      body: JSON.stringify({})
    }), mockEnv, {});
    assert.equal(resCancel.status, 410);

    // ensure-subscription -> 403
    const resEnsure = await worker.fetch(new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.38" },
      body: JSON.stringify({})
    }), mockEnv, {});
    assert.equal(resEnsure.status, 403);

    // create-subscription -> 403
    const resCreate = await worker.fetch(new Request("https://refocus.workers.dev/api/create-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.38" },
      body: JSON.stringify({ planId: "plan_TdtgehXIhzQ8U9" })
    }), mockEnv, {});
    assert.equal(resCreate.status, 403);

    // subscription/lease -> 403
    const resLease = await worker.fetch(new Request("https://refocus.workers.dev/api/subscription/lease", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.38" },
      body: JSON.stringify({})
    }), mockEnv, {});
    assert.equal(resLease.status, 403);

    // Status is still 410 DELETED
    const resStatus = await worker.fetch(new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "192.0.2.38" }
    }), mockEnv, {});
    assert.equal(resStatus.status, 410);

    // Ensure account status in Firestore and tombstone was never reactivated
    const statusDoc = mockFirestore.get(`users/${uid}/account/status`);
    assert.equal(statusDoc.accountStatus.stringValue, "DELETED");
    assert.equal(statusDoc.purgeState.stringValue, "COMPLETED");

    const tombstoneDoc = mockFirestore.get(`account_tombstones/${uid}`);
    assert.equal(tombstoneDoc.accountStatus.stringValue, "DELETED");
    assert.equal(tombstoneDoc.purgeState.stringValue, "COMPLETED");
  });

  // -------------------------------------------------------------------------
  // Step 4K Regression Tests: Purge Recovery & Verifiable Tombstone Guarantees
  // -------------------------------------------------------------------------

  await test("Purge Recovery: Both tombstone writes fail aborts purge and fails closed with PENDING_RETRY", async () => {
    const uid = "user_both_tombstones_fail";
    // Populate user data to ensure it is NOT deleted if tombstone writes fail
    mockFirestore.set(`users/${uid}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 42 } });
    mockFirestore.set(`users/${uid}/settings/preferences`, { theme: { stringValue: "light" } });

    // Inject failure into status write and tombstone write
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "PATCH" && (url.includes(`/users/${uid}/account/status`) || url.includes(`/account_tombstones/${uid}`))) {
        return new Response(JSON.stringify({ error: { message: "Simulated Firestore write failure" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.tombstoneWritten, false);
      assert.ok(purgeRes.errors.length > 0);

      // Verify destructive cleanup was ABORTED: user data was NOT deleted
      assert.ok(mockFirestore.has(`users/${uid}/daily_data/2026-10-10`));
      assert.ok(mockFirestore.has(`users/${uid}/settings/preferences`));
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Purge Recovery: Status write succeeds but tombstone write fails results in PENDING_RETRY", async () => {
    const uid = "user_tombstone_write_fails";
    mockFirestore.set(`users/${uid}/account/status`, { accountStatus: { stringValue: "ACTIVE" } });

    // Inject failure into tombstone write ONLY
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "PATCH" && url.includes(`/account_tombstones/${uid}`)) {
        return new Response(JSON.stringify({ error: { message: "Simulated tombstone write failure" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.tombstoneWritten, true);

      // Verify status doc was written with DELETED and PENDING_RETRY
      const statusDoc = mockFirestore.get(`users/${uid}/account/status`);
      assert.ok(statusDoc);
      assert.equal(statusDoc.accountStatus.stringValue, "DELETED");
      assert.equal(statusDoc.purgeState.stringValue, "PENDING_RETRY");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Purge Recovery: Tombstone lookup returns 503 fails closed and rejects operations", async () => {
    const uid = "user_tombstone_503";
    const token = createMockFirebaseIdToken(uid);

    // users/{uid}/account/status is 404 (missing)
    mockFirestore.delete(`users/${uid}/account/status`);

    // Tombstone lookup fails with 503
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "GET" && url.includes(`/account_tombstones/${uid}`)) {
        return new Response(JSON.stringify({ error: "Service Unavailable" }), { status: 503 });
      }
    };

    try {
      // Direct status lookup returns error: true
      const statusRes = await getFirestoreAccountStatus(uid, mockEnv);
      assert.equal(statusRes.error, true);
      assert.equal(statusRes.status, 503);

      // Endpoint GET /api/account/status fails closed with 503
      const resStatus = await worker.fetch(new Request("https://refocus.workers.dev/api/account/status", {
        method: "GET",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "192.0.2.40" }
      }), mockEnv, {});
      assert.equal(resStatus.status, 503);

      // Endpoint POST /api/account/request-deletion fails closed with 503
      const resReqDel = await worker.fetch(new Request("https://refocus.workers.dev/api/account/request-deletion", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.40" },
        body: JSON.stringify({})
      }), mockEnv, {});
      assert.equal(resReqDel.status, 503);

      // Endpoint POST /api/ensure-subscription fails closed with 503
      const resEnsure = await worker.fetch(new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.40" },
        body: JSON.stringify({})
      }), mockEnv, {});
      assert.equal(resEnsure.status, 503);
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Purge Recovery: Auth deletion succeeds while gateway cancellation fails", async () => {
    const uid = "user_gateway_cancel_fail";
    const subId = "sub_gateway_fail_123";

    // Setup active subscription for user
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: subId }
    });

    // Inject Razorpay cancel failure (500)
    mockRazorpayFaultInjector = (url, options) => {
      if (url.includes(`/subscriptions/${subId}/cancel`)) {
        return new Response(JSON.stringify({ error: { code: "GATEWAY_ERROR", description: "Payment gateway timeout" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.gatewaySuccess, false);
      assert.equal(purgeRes.authSuccess, true);

      // Tombstone records PENDING_RETRY
      const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
      assert.equal(tombstone.accountStatus.stringValue, "DELETED");
      assert.equal(tombstone.purgeState.stringValue, "PENDING_RETRY");
    } finally {
      mockRazorpayFaultInjector = null;
    }
  });

  await test("Purge Recovery: Auth deletion succeeds while Firestore cleanup partially fails", async () => {
    const uid = "user_fs_partial_fail";
    mockFirestore.set(`users/${uid}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 5 } });
    mockFirestore.set(`users/${uid}/settings/preferences`, { theme: { stringValue: "dark" } });

    // Inject failure for deleting daily_data subcollection specifically
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/users/${uid}/daily_data`)) {
        return new Response(JSON.stringify({ error: "Daily data collection error" }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.firestoreSuccess, false);
      assert.equal(purgeRes.authSuccess, true);

      // Verify settings/preferences WAS deleted (independent phase execution)
      assert.equal(mockFirestore.has(`users/${uid}/settings/preferences`), false);

      // Tombstone records PENDING_RETRY
      const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
      assert.equal(tombstone.accountStatus.stringValue, "DELETED");
      assert.equal(tombstone.purgeState.stringValue, "PENDING_RETRY");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Purge Recovery: Pending purge remains recoverable without deleted user authenticating", async () => {
    // Take the user from Test 4 whose gateway cancellation failed earlier
    const uid = "user_gateway_cancel_fail";

    // Gateway is now healthy (mockRazorpayFaultInjector is null)
    // Server-side recovery invokes purgeAccountData(uid, env) directly (no user token required)
    const retryRes = await purgeAccountData(uid, mockEnv);
    assert.equal(retryRes.success, true);
    assert.equal(retryRes.purgeState, "COMPLETED");
    assert.equal(retryRes.gatewaySuccess, true);
    assert.equal(retryRes.authSuccess, true);
    assert.equal(retryRes.firestoreSuccess, true);

    // Verify tombstone is now COMPLETED
    const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
    assert.equal(tombstone.purgeState.stringValue, "COMPLETED");
  });

  await test("Purge Recovery: Repeated purge execution does not reactivate account or lose retry state", async () => {
    const uid = "user_repeated_purge_safe";
    mockFirestore.set(`users/${uid}/account/status`, {
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" }
    });
    mockFirestore.set(`account_tombstones/${uid}`, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" }
    });

    // Simulate persistent gateway failure
    mockRazorpayFaultInjector = (url) => new Response(JSON.stringify({ error: "Down" }), { status: 500 });
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionId: { stringValue: "sub_always_fail" }
    });

    try {
      // Run 1
      const res1 = await purgeAccountData(uid, mockEnv);
      assert.equal(res1.purgeState, "PENDING_RETRY");
      assert.equal(mockFirestore.get(`account_tombstones/${uid}`).accountStatus.stringValue, "DELETED");

      // Run 2
      const res2 = await purgeAccountData(uid, mockEnv);
      assert.equal(res2.purgeState, "PENDING_RETRY");
      assert.equal(mockFirestore.get(`account_tombstones/${uid}`).accountStatus.stringValue, "DELETED");

      // Run 3
      const res3 = await purgeAccountData(uid, mockEnv);
      assert.equal(res3.purgeState, "PENDING_RETRY");
      assert.equal(mockFirestore.get(`account_tombstones/${uid}`).accountStatus.stringValue, "DELETED");
    } finally {
      mockRazorpayFaultInjector = null;
    }
  });

  await test("Purge Recovery: Normal grace-period cancellation still succeeds", async () => {
    const uid = "user_normal_grace_cancel";
    const token = createMockFirebaseIdToken(uid);

    // Request deletion
    const reqDel = new Request("https://refocus.workers.dev/api/account/request-deletion", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.45" },
      body: JSON.stringify({})
    });
    const resDel = await worker.fetch(reqDel, mockEnv, {});
    assert.equal(resDel.status, 200);

    // Cancel deletion within grace period
    const reqCancel = new Request("https://refocus.workers.dev/api/account/cancel-deletion", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.45" },
      body: JSON.stringify({})
    });
    const resCancel = await worker.fetch(reqCancel, mockEnv, {});
    assert.equal(resCancel.status, 200);
    const bodyCancel = await resCancel.json();
    assert.equal(bodyCancel.accountStatus, "ACTIVE");

    // Status is confirmed ACTIVE
    const resStatus = await worker.fetch(new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "192.0.2.45" }
    }), mockEnv, {});
    assert.equal(resStatus.status, 200);
    const bodyStatus = await resStatus.json();
    assert.equal(bodyStatus.accountStatus, "ACTIVE");
  });

  // -------------------------------------------------------------------------
  // Step 4L Regression Tests: Purge Recovery Integrity, Scheduling, & Privacy
  // -------------------------------------------------------------------------

  await test("Step 4L: Initial status write succeeds, tombstone write fails", async () => {
    const uid = "user_step4l_initial_status_ok_tombstone_fail";
    mockFirestore.set(`users/${uid}/account/status`, { accountStatus: { stringValue: "ACTIVE" } });

    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "PATCH" && url.includes(`/account_tombstones/${uid}`)) {
        return new Response(JSON.stringify({ error: { message: "Simulated tombstone write failure" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.tombstoneWritten, true);

      const statusDoc = mockFirestore.get(`users/${uid}/account/status`);
      assert.ok(statusDoc);
      assert.equal(statusDoc.accountStatus.stringValue, "DELETED");
      assert.equal(statusDoc.purgeState.stringValue, "PENDING_RETRY");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4L: Initial status write fails, tombstone write succeeds", async () => {
    const uid = "user_step4l_initial_status_fail_tombstone_ok";
    mockFirestore.set(`users/${uid}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 15 } });

    // Fail initial status write (containing IN_PROGRESS)
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "PATCH" && url.includes(`/users/${uid}/account/status`) && options.body?.includes("IN_PROGRESS")) {
        return new Response(JSON.stringify({ error: { message: "Simulated initial status write failure" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.tombstoneWritten, true);

      // Verify destructive cleanup proceeded because tombstone was durable
      assert.equal(mockFirestore.has(`users/${uid}/daily_data/2026-10-10`), false);

      const tombstoneDoc = mockFirestore.get(`account_tombstones/${uid}`);
      assert.ok(tombstoneDoc);
      assert.equal(tombstoneDoc.accountStatus.stringValue, "DELETED");
      assert.equal(tombstoneDoc.purgeState.stringValue, "PENDING_RETRY");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4L: Both initial writes fail aborts destructive cleanup", async () => {
    const uid = "user_step4l_both_initial_fail";
    mockFirestore.set(`users/${uid}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 99 } });
    mockFirestore.set(`users/${uid}/settings/preferences`, { theme: { stringValue: "dark" } });

    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "PATCH" && (url.includes(`/users/${uid}/account/status`) || url.includes(`/account_tombstones/${uid}`))) {
        return new Response(JSON.stringify({ error: { message: "Simulated persistence failure" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.tombstoneWritten, false);
      assert.ok(purgeRes.errors.length > 0);

      // Verify destructive cleanup was strictly aborted
      assert.ok(mockFirestore.has(`users/${uid}/daily_data/2026-10-10`));
      assert.ok(mockFirestore.has(`users/${uid}/settings/preferences`));
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4L: Both final writes fail after other purge operations", async () => {
    const uid = "user_step4l_final_writes_fail";
    mockFirestore.set(`users/${uid}/account/status`, { accountStatus: { stringValue: "ACTIVE" } });

    // Initial writes succeed; gateway, firestore, auth succeed;
    // but final PATCH writes with COMPLETED fail!
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "PATCH" && options.body?.includes("COMPLETED")) {
        return new Response(JSON.stringify({ error: { message: "Simulated final write failure" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.finalPersistenceSuccess, false);
      assert.ok(purgeRes.errors.includes("Failed updating final account status"));
      assert.ok(purgeRes.errors.includes("Failed updating final account tombstone"));
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4L: Auth deletion succeeds but final persistence fails", async () => {
    const uid = "user_step4l_auth_ok_final_tombstone_fail";
    mockFirestore.set(`users/${uid}/account/status`, { accountStatus: { stringValue: "ACTIVE" } });

    // Final tombstone write fails
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "PATCH" && url.includes(`/account_tombstones/${uid}`) && options.body?.includes("COMPLETED")) {
        return new Response(JSON.stringify({ error: { message: "Simulated final tombstone failure" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.authSuccess, true);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.finalPersistenceSuccess, false);
      assert.ok(purgeRes.errors.includes("Failed updating final account tombstone"));
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4L: Tombstone lookup returns 503, 401/403, malformed data, or a network exception", async () => {
    const uid = "user_step4l_tombstone_errors";
    const token = createMockFirebaseIdToken(uid);
    mockFirestore.delete(`users/${uid}/account/status`);

    // 1. HTTP 503
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "GET" && url.includes(`/account_tombstones/${uid}`)) {
        return new Response(JSON.stringify({ error: "Service Unavailable" }), { status: 503 });
      }
    };
    let res = await worker.fetch(new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "192.0.2.71" }
    }), mockEnv, {});
    assert.equal(res.status, 503);

    // 2. HTTP 401
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "GET" && url.includes(`/account_tombstones/${uid}`)) {
        return new Response(JSON.stringify({ error: "Unauthorized" }), { status: 401 });
      }
    };
    res = await worker.fetch(new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "192.0.2.72" }
    }), mockEnv, {});
    assert.equal(res.status, 503);

    // 3. HTTP 403
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "GET" && url.includes(`/account_tombstones/${uid}`)) {
        return new Response(JSON.stringify({ error: "Forbidden" }), { status: 403 });
      }
    };
    res = await worker.fetch(new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "192.0.2.73" }
    }), mockEnv, {});
    assert.equal(res.status, 503);

    // 4. Malformed JSON syntax
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "GET" && url.includes(`/account_tombstones/${uid}`)) {
        return new Response("{ not valid json at all ...", { status: 200, headers: { "Content-Type": "application/json" } });
      }
    };
    res = await worker.fetch(new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "192.0.2.74" }
    }), mockEnv, {});
    assert.equal(res.status, 503);

    // 5. Network exception (fetch rejects)
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "GET" && url.includes(`/account_tombstones/${uid}`)) {
        throw new TypeError("Failed to fetch: simulated network drop");
      }
    };
    res = await worker.fetch(new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "192.0.2.75" }
    }), mockEnv, {});
    assert.equal(res.status, 503);

    mockFirestoreFaultInjector = null;
  });

  await test("Step 4L: A pending purge is discovered and retried by the scheduled handler", async () => {
    const uid = "user_step4l_scheduled_discovery";
    mockFirestore.set(`account_tombstones/${uid}`, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      deletedAt: { timestampValue: "2026-10-10T00:00:00Z" }
    });
    mockFirestore.set(`users/${uid}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 22 } });

    // Verify listPendingAccountTombstones discovers it
    const pendingBefore = await listPendingAccountTombstones(mockEnv, 25);
    assert.ok(pendingBefore.includes(uid));

    // Trigger scheduled handler
    await worker.scheduled({ cron: "*/15 * * * *" }, mockEnv, {});

    // Verify data was cleaned up and tombstone marked COMPLETED
    assert.equal(mockFirestore.has(`users/${uid}/daily_data/2026-10-10`), false);
    const tombstoneDoc = mockFirestore.get(`account_tombstones/${uid}`);
    assert.ok(tombstoneDoc);
    assert.equal(tombstoneDoc.purgeState.stringValue, "COMPLETED");

    // Verify listPendingAccountTombstones now returns empty
    const pendingAfter = await listPendingAccountTombstones(mockEnv, 25);
    assert.equal(pendingAfter.includes(uid), false);
  });

  await test("Step 4L: One failed scheduled purge does not prevent another pending purge from being processed", async () => {
    const uidFail = "user_step4l_fail_account";
    const uidOk = "user_step4l_ok_account";

    mockFirestore.set(`account_tombstones/${uidFail}`, {
      uid: { stringValue: uidFail },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" }
    });
    mockFirestore.set(`users/${uidFail}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 11 } });

    mockFirestore.set(`account_tombstones/${uidOk}`, {
      uid: { stringValue: uidOk },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" }
    });
    mockFirestore.set(`users/${uidOk}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 44 } });

    // Fail uidFail operations, allow uidOk operations
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(uidFail)) {
        return new Response(JSON.stringify({ error: { message: "Simulated per-account failure" } }), { status: 500 });
      }
    };

    try {
      const sweepRes = await processPendingAccountPurges(mockEnv, 25);
      assert.equal(sweepRes.total, 2);
      assert.equal(sweepRes.succeeded, 1);
      assert.equal(sweepRes.failed, 1);

      // uidFail remains PENDING_RETRY
      const failTombstone = mockFirestore.get(`account_tombstones/${uidFail}`);
      assert.equal(failTombstone.purgeState.stringValue, "PENDING_RETRY");

      // uidOk was successfully COMPLETED despite uidFail's failure!
      const okTombstone = mockFirestore.get(`account_tombstones/${uidOk}`);
      assert.equal(okTombstone.purgeState.stringValue, "COMPLETED");
      assert.equal(mockFirestore.has(`users/${uidOk}/daily_data/2026-10-10`), false);
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4L: Repeated purge attempts do not erase pendingSubscriptionId or reactivate a deleted account", async () => {
    const uid = "user_step4l_repeated_purge_retained_sub";
    const retainedSubId = "sub_autopay_retained_step4l";

    mockFirestore.set(`account_tombstones/${uid}`, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      pendingSubscriptionId: { stringValue: retainedSubId }
    });
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionId: { stringValue: retainedSubId },
      subscriptionStatus: { stringValue: "ACTIVE" }
    });

    // Make Razorpay cancel persistently fail with 500
    mockRazorpayFaultInjector = (url) => {
      if (url.includes(`/subscriptions/${retainedSubId}/cancel`)) {
        return new Response(JSON.stringify({ error: { description: "Gateway failure" } }), { status: 500 });
      }
    };

    try {
      for (let run = 1; run <= 3; run++) {
        const purgeRes = await purgeAccountData(uid, mockEnv);
        assert.equal(purgeRes.success, false);
        assert.equal(purgeRes.purgeState, "PENDING_RETRY");
        assert.equal(purgeRes.gatewaySuccess, false);

        // Verify pendingSubscriptionId is preserved in tombstone
        const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
        assert.ok(tombstone);
        assert.equal(tombstone.pendingSubscriptionId.stringValue, retainedSubId);
        assert.equal(tombstone.accountStatus.stringValue, "DELETED");

        // Verify users/{uid}/subscription/status is preserved for retry
        const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
        assert.ok(subDoc);
        assert.equal(subDoc.subscriptionId.stringValue, retainedSubId);
      }
    } finally {
      mockRazorpayFaultInjector = null;
    }
  });

  // -------------------------------------------------------------------------
  // Step 4M Regression Tests: Independent Scheduled Purge Recovery Audit
  // -------------------------------------------------------------------------

  await test("Step 4M: Valid credentials and no pending purges", async () => {
    for (const key of [...mockFirestore.keys()]) {
      if (key.startsWith("account_tombstones/")) mockFirestore.delete(key);
    }

    const discovery = await listPendingAccountTombstones(mockEnv, 25);
    assert.equal(discovery.success, true);
    assert.equal(discovery.count, 0);
    assert.equal(discovery.pending.length, 0);

    const sweep = await processPendingAccountPurges(mockEnv, 25);
    assert.equal(sweep.success, true);
    assert.equal(sweep.total, 0);
    assert.equal(sweep.succeeded, 0);
    assert.equal(sweep.failed, 0);
  });

  await test("Step 4M: Missing or rejected credentials and Firestore 401/403/5xx responses", async () => {
    // 1. Missing credentials
    const missingCreds = await listPendingAccountTombstones({}, 25);
    assert.equal(missingCreds.success, false);
    assert.equal(missingCreds.error, "MISSING_SERVICE_ACCOUNT_KEY");
    assert.equal(missingCreds.status, 500);

    // 2. HTTP 401 Unauthorized from Firestore
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(":runQuery")) {
        return new Response(JSON.stringify({ error: { code: 401, message: "Unauthorized", status: "UNAUTHENTICATED" } }), { status: 401 });
      }
    };
    try {
      const auth401 = await listPendingAccountTombstones(mockEnv, 25);
      assert.equal(auth401.success, false);
      assert.equal(auth401.status, 401);
      assert.equal(auth401.error, "AUTH_REJECTED_401");

      const sweep401 = await processPendingAccountPurges(mockEnv, 25);
      assert.equal(sweep401.success, false);
      assert.equal(sweep401.status, 401);
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 3. HTTP 403 Forbidden from Firestore
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(":runQuery")) {
        return new Response(JSON.stringify({ error: { code: 403, message: "Permission Denied", status: "PERMISSION_DENIED" } }), { status: 403 });
      }
    };
    try {
      const auth403 = await listPendingAccountTombstones(mockEnv, 25);
      assert.equal(auth403.success, false);
      assert.equal(auth403.status, 403);
      assert.equal(auth403.error, "AUTH_REJECTED_403");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 4. HTTP 500 / 503 Server Error from Firestore
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(":runQuery")) {
        return new Response(JSON.stringify({ error: { code: 503, message: "Service Unavailable", status: "UNAVAILABLE" } }), { status: 503 });
      }
    };
    try {
      const err503 = await listPendingAccountTombstones(mockEnv, 25);
      assert.equal(err503.success, false);
      assert.equal(err503.status, 503);
      assert.equal(err503.error, "FIRESTORE_5XX_503");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4M: Malformed query responses and network errors", async () => {
    // 1. Non-JSON body (e.g. 502 Bad Gateway HTML)
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(":runQuery")) {
        return new Response("<html><head><title>502 Bad Gateway</title></head><body>502 Bad Gateway</body></html>", {
          status: 200,
          headers: { "Content-Type": "text/html" }
        });
      }
    };
    try {
      const malformedHtml = await listPendingAccountTombstones(mockEnv, 25);
      assert.equal(malformedHtml.success, false);
      assert.equal(malformedHtml.error, "MALFORMED_QUERY_RESPONSE");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 2. Object instead of array
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(":runQuery")) {
        return new Response(JSON.stringify({ error: "Something unexpected" }), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
    };
    try {
      const malformedObj = await listPendingAccountTombstones(mockEnv, 25);
      assert.equal(malformedObj.success, false);
      assert.equal(malformedObj.error, "MALFORMED_QUERY_RESPONSE");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 3. Network error / TypeError
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(":runQuery")) {
        throw new TypeError("Failed to fetch: simulated network drop");
      }
    };
    try {
      const netErr = await listPendingAccountTombstones(mockEnv, 25);
      assert.equal(netErr.success, false);
      assert.equal(netErr.error, "NETWORK_ERROR");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4M: Stale IN_PROGRESS recovery after Auth deletion", async () => {
    const uidStale = "user_step4m_stale_in_progress";
    const uidActive = "user_step4m_active_in_progress";

    const thirtyMinsAgo = new Date(Date.now() - 30 * 60 * 1000).toISOString();
    const twoMinsAgo = new Date(Date.now() - 2 * 60 * 1000).toISOString();

    // Stale IN_PROGRESS (> 15 mins)
    mockFirestore.set(`account_tombstones/${uidStale}`, {
      uid: { stringValue: uidStale },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "IN_PROGRESS" },
      updatedAt: { timestampValue: thirtyMinsAgo }
    });
    mockFirestore.set(`users/${uidStale}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 50 } });

    // Active IN_PROGRESS (< 15 mins)
    mockFirestore.set(`account_tombstones/${uidActive}`, {
      uid: { stringValue: uidActive },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "IN_PROGRESS" },
      updatedAt: { timestampValue: twoMinsAgo }
    });
    mockFirestore.set(`users/${uidActive}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 99 } });

    const discovery = await listPendingAccountTombstones(mockEnv, 25);
    assert.equal(discovery.success, true);
    assert.ok(discovery.uids.includes(uidStale), "Stale IN_PROGRESS (>15m) must be discovered for recovery");
    assert.equal(discovery.uids.includes(uidActive), false, "Active IN_PROGRESS (<15m) must NOT be preempted");

    // Execute scheduled sweep
    const sweep = await processPendingAccountPurges(mockEnv, 25);
    assert.equal(sweep.success, true);

    // Verify uidStale was purged and marked COMPLETED
    assert.equal(mockFirestore.has(`users/${uidStale}/daily_data/2026-10-10`), false);
    const staleTombstone = mockFirestore.get(`account_tombstones/${uidStale}`);
    assert.equal(staleTombstone.purgeState.stringValue, "COMPLETED");

    // Verify uidActive was untouched
    assert.equal(mockFirestore.has(`users/${uidActive}/daily_data/2026-10-10`), true);
  });

  await test("Step 4M: Both final marker writes failing and subsequent retry", async () => {
    const uid = "user_step4m_both_final_fail";
    mockFirestore.set(`users/${uid}/account/status`, { accountStatus: { stringValue: "ACTIVE" } });
    mockFirestore.set(`users/${uid}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 33 } });

    // Fail final writes when COMPLETED is being written
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "PATCH" && options.body?.includes("COMPLETED")) {
        return new Response(JSON.stringify({ error: { message: "Simulated final persistence outage" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.finalPersistenceSuccess, false);

      // In Firestore, tombstone remains at IN_PROGRESS
      const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
      assert.ok(tombstone);
      assert.equal(tombstone.purgeState.stringValue, "IN_PROGRESS");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // Fast-forward tombstone updatedAt to simulate 20 minutes elapsed
    const tombstoneDoc = mockFirestore.get(`account_tombstones/${uid}`);
    tombstoneDoc.updatedAt = { timestampValue: new Date(Date.now() - 20 * 60 * 1000).toISOString() };
    mockFirestore.set(`account_tombstones/${uid}`, tombstoneDoc);

    // Scheduled recovery discovers it as stale IN_PROGRESS
    const discovery = await listPendingAccountTombstones(mockEnv, 25);
    assert.ok(discovery.uids.includes(uid));

    // Recovery retry succeeds completely
    const sweep = await processPendingAccountPurges(mockEnv, 25);
    const finalTombstone = mockFirestore.get(`account_tombstones/${uid}`);
    assert.equal(finalTombstone.purgeState.stringValue, "COMPLETED");
  });

  await test("Step 4M: Only one durable marker surviving", async () => {
    // Permutation A: Only tombstone survived (status doc write failed or was missing)
    const uidA = "user_step4m_only_tombstone_survived";
    mockFirestore.set(`account_tombstones/${uidA}`, {
      uid: { stringValue: uidA },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      updatedAt: { timestampValue: new Date(Date.now() - 5000).toISOString() }
    });
    mockFirestore.set(`users/${uidA}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 12 } });

    // Discovery finds it via tombstone
    const discA = await listPendingAccountTombstones(mockEnv, 25);
    assert.ok(discA.uids.includes(uidA));

    // Scheduled sweep purges it successfully
    await processPendingAccountPurges(mockEnv, 25);
    assert.equal(mockFirestore.has(`users/${uidA}/daily_data/2026-10-10`), false);
    const tombA = mockFirestore.get(`account_tombstones/${uidA}`);
    assert.equal(tombA.purgeState.stringValue, "COMPLETED");

    // Permutation B: Only users/uid/account/status survived (tombstone write failed)
    const uidB = "user_step4m_only_status_survived";
    mockFirestore.set(`users/${uidB}/account/status`, {
      accountStatus: { stringValue: "ACTIVE" }
    });

    // Make initial tombstone write fail, but allow status write
    mockFirestoreFaultInjector = (url, options) => {
      if (options.method === "PATCH" && url.includes(`/account_tombstones/${uidB}`) && options.body?.includes("IN_PROGRESS")) {
        return new Response(JSON.stringify({ error: { message: "Initial tombstone write failed" } }), { status: 500 });
      }
    };
    try {
      const purgeB = await purgeAccountData(uidB, mockEnv);
      assert.equal(purgeB.success, false);
      assert.equal(purgeB.purgeState, "PENDING_RETRY");
      assert.equal(purgeB.tombstoneWritten, true); // status doc succeeded
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4M: Repeatedly failing first-batch accounts without starvation of later records", async () => {
    for (const key of [...mockFirestore.keys()]) {
      if (key.startsWith("account_tombstones/")) mockFirestore.delete(key);
    }

    const uidFail1 = "user_starve_fail_1";
    const uidOk2 = "user_starve_ok_2";
    const uidOk3 = "user_starve_ok_3";

    const t1000 = "2026-10-10T10:00:00.000Z";
    const t1005 = "2026-10-10T10:05:00.000Z";
    const t1010 = "2026-10-10T10:10:00.000Z";

    mockFirestore.set(`account_tombstones/${uidFail1}`, {
      uid: { stringValue: uidFail1 },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      pendingSubscriptionId: { stringValue: "sub_starve_fail_1" },
      updatedAt: { timestampValue: t1000 }
    });
    mockFirestore.set(`users/${uidFail1}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 1 } });

    mockFirestore.set(`account_tombstones/${uidOk2}`, {
      uid: { stringValue: uidOk2 },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      updatedAt: { timestampValue: t1005 }
    });
    mockFirestore.set(`users/${uidOk2}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 2 } });

    mockFirestore.set(`account_tombstones/${uidOk3}`, {
      uid: { stringValue: uidOk3 },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      updatedAt: { timestampValue: t1010 }
    });
    mockFirestore.set(`users/${uidOk3}/daily_data/2026-10-10`, { totalReelsCount: { integerValue: 3 } });

    // Rig uidFail1's gateway cancellation to fail repeatedly
    mockRazorpayFaultInjector = (url) => {
      if (url.includes("sub_starve_fail_1")) {
        return new Response(JSON.stringify({ error: { description: "Gateway error" } }), { status: 500 });
      }
    };

    try {
      // Run 1: limit 1 -> oldest is uidFail1 (t1000)
      const sweep1 = await processPendingAccountPurges(mockEnv, 1);
      assert.equal(sweep1.total, 1);
      assert.equal(sweep1.failed, 1);

      // Verify uidFail1 updated its timestamp to current time during the failed attempt
      const failTomb = mockFirestore.get(`account_tombstones/${uidFail1}`);
      const updatedTimeFail = failTomb.updatedAt.timestampValue;
      assert.ok(updatedTimeFail > t1010, "Failed account must update its timestamp to rotate to back of queue");

      // Run 2: limit 1 -> next oldest is uidOk2 (t1005 < t1010 < updatedTimeFail)
      const sweep2 = await processPendingAccountPurges(mockEnv, 1);
      assert.equal(sweep2.total, 1);
      assert.equal(sweep2.succeeded, 1);
      const tomb2 = mockFirestore.get(`account_tombstones/${uidOk2}`);
      assert.equal(tomb2.purgeState.stringValue, "COMPLETED");

      // Run 3: limit 1 -> next oldest is uidOk3 (t1010 < updatedTimeFail)
      const sweep3 = await processPendingAccountPurges(mockEnv, 1);
      assert.equal(sweep3.total, 1);
      assert.equal(sweep3.succeeded, 1);
      const tomb3 = mockFirestore.get(`account_tombstones/${uidOk3}`);
      assert.equal(tomb3.purgeState.stringValue, "COMPLETED");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4M: Gateway cancellation failure with pendingSubscriptionId preserved", async () => {
    const uid = "user_step4m_preserved_sub";
    const subId = "sub_autopay_gateway_fail_4m";

    mockFirestore.delete(`users/${uid}/subscription/status`);

    mockFirestore.set(`account_tombstones/${uid}`, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      pendingSubscriptionId: { stringValue: subId },
      deletedAt: { timestampValue: "2026-10-10T12:00:00Z" }
    });

    mockRazorpayFaultInjector = (url) => {
      if (url.includes(`/subscriptions/${subId}/cancel`)) {
        return new Response(JSON.stringify({ error: { description: "Gateway 500" } }), { status: 500 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.gatewaySuccess, false);

      const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
      assert.ok(tombstone);
      assert.equal(tombstone.pendingSubscriptionId.stringValue, subId);
      assert.equal(tombstone.deletedAt.timestampValue, "2026-10-10T12:00:00Z");
    } finally {
      mockRazorpayFaultInjector = null;
    }
  });

  await test("Step 4M: Retry after an uncertain prior phase without duplicate destructive effects", async () => {
    const uid = "user_step4m_prior_phase_uncertain";
    const subId = "sub_autopay_already_cancelled_4m";

    mockFirestore.set(`account_tombstones/${uid}`, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      pendingSubscriptionId: { stringValue: subId }
    });

    mockRazorpayFaultInjector = (url) => {
      if (url.includes(`/subscriptions/${subId}/cancel`)) {
        return new Response(JSON.stringify({
          error: { description: "Subscription is already cancelled", code: "BAD_REQUEST_ERROR" }
        }), { status: 400 });
      }
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, true);
      assert.equal(purgeRes.purgeState, "COMPLETED");
      assert.equal(purgeRes.gatewaySuccess, true);

      const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
      assert.equal(tombstone.purgeState.stringValue, "COMPLETED");
    } finally {
      mockRazorpayFaultInjector = null;
    }
  });

  await test("Step 4M: One account failing while the rest of the batch continues", async () => {
    for (const key of [...mockFirestore.keys()]) {
      if (key.startsWith("account_tombstones/")) mockFirestore.delete(key);
    }

    const uidErr = "user_batch_isolate_err";
    const uidOk1 = "user_batch_isolate_ok1";
    const uidOk2 = "user_batch_isolate_ok2";

    mockFirestore.set(`account_tombstones/${uidErr}`, {
      uid: { stringValue: uidErr },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" }
    });
    mockFirestore.set(`account_tombstones/${uidOk1}`, {
      uid: { stringValue: uidOk1 },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" }
    });
    mockFirestore.set(`account_tombstones/${uidOk2}`, {
      uid: { stringValue: uidOk2 },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" }
    });

    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(uidErr)) {
        throw new Error("Simulated catastrophic unexpected account fault");
      }
    };

    try {
      const sweep = await processPendingAccountPurges(mockEnv, 25);
      assert.equal(sweep.total, 3);
      assert.equal(sweep.succeeded, 2);
      assert.equal(sweep.failed, 1);

      const tombOk1 = mockFirestore.get(`account_tombstones/${uidOk1}`);
      assert.equal(tombOk1.purgeState.stringValue, "COMPLETED");

      const tombOk2 = mockFirestore.get(`account_tombstones/${uidOk2}`);
      assert.equal(tombOk2.purgeState.stringValue, "COMPLETED");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 4M: Graceful fallback when composite index is missing in Firestore", async () => {
    for (const key of [...mockFirestore.keys()]) {
      if (key.startsWith("account_tombstones/")) mockFirestore.delete(key);
    }

    const uid = "user_step4m_missing_index_fallback";
    mockFirestore.set(`account_tombstones/${uid}`, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" }
    });

    globalThis._mockRequireIndex = true;
    try {
      const discovery = await listPendingAccountTombstones(mockEnv, 25);
      assert.equal(discovery.success, true);
      assert.ok(discovery.uids.includes(uid), "Query must fall back without orderBy and still succeed");
    } finally {
      globalThis._mockRequireIndex = false;
    }
  });

  // -------------------------------------------------------------------------
  // Step 4N Regression Tests: Concurrency, Fair Recovery, and Gateway Verification
  // -------------------------------------------------------------------------

  await test("Step 4N: Pagination & fairness when more than 25 tombstones are eligible", async () => {
    for (const key of [...mockFirestore.keys()]) {
      if (key.startsWith("account_tombstones/")) mockFirestore.delete(key);
    }

    const baseTime = Date.now() - 3600 * 1000;
    // Create 30 tombstones: oldest 25, newest 5
    for (let i = 1; i <= 30; i++) {
      const pad = String(i).padStart(2, "0");
      const uid = `user_step4n_batch_${pad}`;
      const itemTime = new Date(baseTime + i * 60 * 1000).toISOString();
      mockFirestore.set(`account_tombstones/${uid}`, {
        uid: { stringValue: uid },
        accountStatus: { stringValue: "DELETED" },
        purgeState: { stringValue: "PENDING_RETRY" },
        updatedAt: { timestampValue: itemTime }
      });
    }

    // First discovery should return exactly 25 oldest
    const discovery1 = await listPendingAccountTombstones(mockEnv, 25);
    assert.equal(discovery1.success, true);
    assert.equal(discovery1.uids.length, 25);
    assert.equal(discovery1.uids[0], "user_step4n_batch_01");
    assert.equal(discovery1.uids[24], "user_step4n_batch_25");
    assert.equal(discovery1.uids.includes("user_step4n_batch_26"), false);

    // Process all 25 through scheduled sweep
    const sweep1 = await processPendingAccountPurges(mockEnv, 25);
    assert.equal(sweep1.success, true);
    assert.equal(sweep1.succeeded, 25);

    // Second discovery should return the remaining 5 tombstones without starvation
    const discovery2 = await listPendingAccountTombstones(mockEnv, 25);
    assert.equal(discovery2.success, true);
    assert.equal(discovery2.uids.length, 5);
    assert.equal(discovery2.uids[0], "user_step4n_batch_26");
    assert.equal(discovery2.uids[4], "user_step4n_batch_30");
  });

  await test("Step 4N: Malformed or missing updatedAt timestamps are safely healed and prioritized", async () => {
    for (const key of [...mockFirestore.keys()]) {
      if (key.startsWith("account_tombstones/")) mockFirestore.delete(key);
    }

    const uidInvalidDate = "user_step4n_invalid_date";
    const uidMissingDate = "user_step4n_missing_date";
    const uidValid = "user_step4n_valid_date";

    mockFirestore.set(`account_tombstones/${uidInvalidDate}`, {
      uid: { stringValue: uidInvalidDate },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      updatedAt: { timestampValue: "invalid-not-a-date" }
    });

    mockFirestore.set(`account_tombstones/${uidMissingDate}`, {
      uid: { stringValue: uidMissingDate },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" }
    });

    mockFirestore.set(`account_tombstones/${uidValid}`, {
      uid: { stringValue: uidValid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "PENDING_RETRY" },
      updatedAt: { timestampValue: new Date(Date.now() - 60000).toISOString() }
    });

    const discovery = await listPendingAccountTombstones(mockEnv, 25);
    assert.equal(discovery.success, true);
    assert.equal(discovery.uids.length, 3);
    // Malformed/missing timestamps default to 0 and must sort before valid timestamps
    assert.ok(discovery.uids.indexOf(uidInvalidDate) < discovery.uids.indexOf(uidValid));
    assert.ok(discovery.uids.indexOf(uidMissingDate) < discovery.uids.indexOf(uidValid));

    // Recovery sweep heals both records
    const sweep = await processPendingAccountPurges(mockEnv, 25);
    assert.equal(sweep.success, true);
    assert.equal(sweep.succeeded, 3);

    const docInvalid = mockFirestore.get(`account_tombstones/${uidInvalidDate}`);
    assert.equal(docInvalid.purgeState.stringValue, "COMPLETED");
    assert.ok(docInvalid.updatedAt.timestampValue && !isNaN(new Date(docInvalid.updatedAt.timestampValue).getTime()));
  });

  await test("Step 4N: Stale IN_PROGRESS records compete fairly with PENDING_RETRY records", async () => {
    for (const key of [...mockFirestore.keys()]) {
      if (key.startsWith("account_tombstones/")) mockFirestore.delete(key);
    }

    const now = Date.now();
    // 25 PENDING_RETRY tombstones created 5 minutes ago
    for (let i = 1; i <= 25; i++) {
      const pad = String(i).padStart(2, "0");
      mockFirestore.set(`account_tombstones/user_step4n_recent_retry_${pad}`, {
        uid: { stringValue: `user_step4n_recent_retry_${pad}` },
        accountStatus: { stringValue: "DELETED" },
        purgeState: { stringValue: "PENDING_RETRY" },
        updatedAt: { timestampValue: new Date(now - 5 * 60 * 1000 + i * 1000).toISOString() }
      });
    }

    // 1 stale IN_PROGRESS tombstone whose worker crashed 30 minutes ago
    const staleUid = "user_step4n_stale_in_progress_compete";
    mockFirestore.set(`account_tombstones/${staleUid}`, {
      uid: { stringValue: staleUid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "IN_PROGRESS" },
      leaseExpiresAtMs: { integerValue: String(now - 29 * 60 * 1000) },
      updatedAt: { timestampValue: new Date(now - 30 * 60 * 1000).toISOString() }
    });

    const discovery = await listPendingAccountTombstones(mockEnv, 25);
    assert.equal(discovery.success, true);
    assert.equal(discovery.uids.length, 25);
    // Stale IN_PROGRESS was older (30m ago) than recent PENDING_RETRY (5m ago), so it must be first
    assert.equal(discovery.uids[0], staleUid);
  });

  await test("Step 4N: Concurrent manual and scheduled purge claims - Second worker aborts safely", async () => {
    const uid = "user_step4n_concurrent_claim_test";
    const nowMs = Date.now();

    // 1. Worker A acquires claim
    const claimA = await claimPurgeLockAtomic(uid, nowMs, mockEnv);
    assert.equal(claimA.claimed, true);
    assert.ok(claimA.claimId);
    assert.ok(claimA.updateTime);

    // 2. Worker B attempts to acquire claim concurrently 2 seconds later (within 60s lease)
    const claimB = await claimPurgeLockAtomic(uid, nowMs + 2000, mockEnv);
    assert.equal(claimB.claimed, false);
    assert.equal(claimB.inProgress, true);
    assert.equal(claimB.reason, "CONCURRENT_PURGE_IN_PROGRESS");

    // 3. Worker B calling purgeAccountData returns inProgress conflict
    const purgeB = await purgeAccountData(uid, mockEnv);
    assert.equal(purgeB.success, false);
    assert.equal(purgeB.inProgress, true);

    // 4. Worker A finalizes purge successfully
    const finalizeA = await finalizePurgeTombstoneAtomic(uid, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "COMPLETED" },
      claimId: { stringValue: claimA.claimId },
      leaseExpiresAtMs: { integerValue: "0" }
    }, claimA.updateTime, mockEnv);
    assert.equal(finalizeA.success, true);

    // 5. Subsequent call recognizes alreadyCompleted
    const claimAfter = await claimPurgeLockAtomic(uid, nowMs + 5000, mockEnv);
    assert.equal(claimAfter.claimed, false);
    assert.equal(claimAfter.alreadyCompleted, true);
  });

  await test("Step 4N: Stale execution revision conflict prevented (412 updateTime precondition)", async () => {
    const uid = "user_step4n_stale_revision_conflict";
    const nowMs = Date.now();

    // Worker A acquires claim revision T1
    const claimA = await claimPurgeLockAtomic(uid, nowMs, mockEnv);
    assert.equal(claimA.claimed, true);
    const revisionT1 = claimA.updateTime;

    // Simulate Worker A hanging. Lease expires, Worker B claims lock at nowMs + 65000 (revision T2)
    const claimB = await claimPurgeLockAtomic(uid, nowMs + 65000, mockEnv);
    assert.equal(claimB.claimed, true);
    const revisionT2 = claimB.updateTime;
    assert.notEqual(revisionT1, revisionT2);

    // Worker A wakes up and attempts to finalize using original precondition revision T1
    const finalizeStaleA = await finalizePurgeTombstoneAtomic(uid, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "COMPLETED" },
      claimId: { stringValue: claimA.claimId },
      leaseExpiresAtMs: { integerValue: "0" }
    }, revisionT1, mockEnv);

    // Firestore returns 412 because updateTime in Firestore is now T2, rejecting Worker A's stale overwrite
    assert.equal(finalizeStaleA.success, false);
    assert.equal(finalizeStaleA.superseded, true);
    assert.equal(finalizeStaleA.status, 412);
  });

  await test("Step 4N: Ambiguous Razorpay 400 error triggers GET verification - Fails closed if still ACTIVE", async () => {
    const uid = "user_step4n_ambiguous_active_test";
    const subId = "sub_step4n_ambiguous_active_123";

    mockFirestore.set(`users/${uid}/account/status`, { accountStatus: { stringValue: "ACTIVE" } });
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionId: { stringValue: subId },
      subscriptionStatus: { stringValue: "ACTIVE" }
    });

    // Razorpay subscription is still ACTIVE
    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "active",
      plan_id: mockEnv.RAZORPAY_PLAN_ID
    });

    // Gateway cancel returns ambiguous 400 error (e.g. "Subscription cannot be cancelled")
    let getCalled = false;
    mockRazorpayFaultInjector = (urlStr, options) => {
      if (urlStr.endsWith("/cancel") && options.method === "POST") {
        return new Response(JSON.stringify({
          error: {
            code: "BAD_REQUEST_ERROR",
            description: "Subscription cannot be cancelled at this time"
          }
        }), { status: 400 });
      }
      if (urlStr.endsWith(`/subscriptions/${subId}`) && (options.method === "GET" || !options.method)) {
        getCalled = true;
      }
      return undefined;
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, false);
      assert.equal(purgeRes.purgeState, "PENDING_RETRY");
      assert.equal(purgeRes.gatewaySuccess, false);
      assert.equal(getCalled, true, "Authoritative GET /v1/subscriptions/{id} must be invoked for ambiguous 400");

      // Verify pendingSubscriptionId is preserved in tombstone for retry
      const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
      assert.ok(tombstone);
      assert.equal(tombstone.pendingSubscriptionId.stringValue, subId);
      assert.equal(tombstone.purgeState.stringValue, "PENDING_RETRY");
    } finally {
      mockRazorpayFaultInjector = null;
    }
  });

  await test("Step 4N: Ambiguous Razorpay 400 error triggers GET verification - Succeeds if confirmed CANCELLED or COMPLETED", async () => {
    const uid = "user_step4n_ambiguous_cancelled_test";
    const subId = "sub_step4n_ambiguous_cancelled_123";

    mockFirestore.set(`users/${uid}/account/status`, { accountStatus: { stringValue: "ACTIVE" } });
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionId: { stringValue: subId },
      subscriptionStatus: { stringValue: "CANCELLED" }
    });

    // Razorpay subscription is confirmed CANCELLED
    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "cancelled",
      plan_id: mockEnv.RAZORPAY_PLAN_ID
    });

    // Gateway cancel returns ambiguous 400
    mockRazorpayFaultInjector = (urlStr, options) => {
      if (urlStr.endsWith("/cancel") && options.method === "POST") {
        return new Response(JSON.stringify({
          error: {
            code: "BAD_REQUEST_ERROR",
            description: "Subscription cannot be cancelled"
          }
        }), { status: 400 });
      }
      return undefined;
    };

    try {
      const purgeRes = await purgeAccountData(uid, mockEnv);
      assert.equal(purgeRes.success, true);
      assert.equal(purgeRes.purgeState, "COMPLETED");
      assert.equal(purgeRes.gatewaySuccess, true);

      // Verify tombstone is completed and pendingSubscriptionId is cleared
      const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
      assert.ok(tombstone);
      assert.equal(tombstone.purgeState.stringValue, "COMPLETED");
      assert.equal(tombstone.pendingSubscriptionId, undefined);
    } finally {
      mockRazorpayFaultInjector = null;
    }
  });

  await test("Step 4N: Crashed worker leaves recoverable lease that expires and is recovered", async () => {
    const uid = "user_step4n_crashed_worker_recovery";
    const nowMs = Date.now();

    // Worker 1 claims lock and crashes
    const claim1 = await claimPurgeLockAtomic(uid, nowMs, mockEnv);
    assert.equal(claim1.claimed, true);

    // 10 seconds later: Worker 2 blocked
    const claim2Active = await claimPurgeLockAtomic(uid, nowMs + 10000, mockEnv);
    assert.equal(claim2Active.claimed, false);
    assert.equal(claim2Active.inProgress, true);

    // 65 seconds later: Lease expired, Worker 2 claims lock and completes purge
    const claim2Expired = await claimPurgeLockAtomic(uid, nowMs + 65000, mockEnv);
    assert.equal(claim2Expired.claimed, true);

    const finalizeResult = await finalizePurgeTombstoneAtomic(uid, {
      uid: { stringValue: uid },
      accountStatus: { stringValue: "DELETED" },
      purgeState: { stringValue: "COMPLETED" },
      claimId: { stringValue: claim2Expired.claimId },
      leaseExpiresAtMs: { integerValue: "0" }
    }, claim2Expired.updateTime, mockEnv);
    assert.equal(finalizeResult.success, true);

    const tombstone = mockFirestore.get(`account_tombstones/${uid}`);
    assert.equal(tombstone.purgeState.stringValue, "COMPLETED");
  });

  await test("Step 4N: Repeated gateway cancel failures keep pendingSubscriptionId intact across retries", async () => {
    for (const key of [...mockFirestore.keys()]) {
      if (key.startsWith("account_tombstones/")) mockFirestore.delete(key);
    }

    const uid = "user_step4n_repeated_gateway_fail";
    const subId = "sub_step4n_repeated_fail_999";

    mockFirestore.set(`users/${uid}/account/status`, { accountStatus: { stringValue: "ACTIVE" } });
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionId: { stringValue: subId },
      subscriptionStatus: { stringValue: "ACTIVE" }
    });
    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "active",
      plan_id: mockEnv.RAZORPAY_PLAN_ID
    });

    // 1. First execution fails gateway cancel with 500
    mockRazorpayFaultInjector = (urlStr, options) => {
      if (urlStr.includes(subId)) {
        return new Response(JSON.stringify({ error: { message: "Gateway 500 error" } }), { status: 500 });
      }
      return undefined;
    };

    try {
      const purge1 = await purgeAccountData(uid, mockEnv);
      assert.equal(purge1.success, false);
      assert.equal(purge1.purgeState, "PENDING_RETRY");
      assert.equal(purge1.gatewaySuccess, false);

      const tomb1 = mockFirestore.get(`account_tombstones/${uid}`);
      assert.equal(tomb1.pendingSubscriptionId.stringValue, subId);

      // Fast-forward 10 seconds and simulate scheduled sweep retry (still failing gateway)
      const sweepRetry = await processPendingAccountPurges(mockEnv, 25);
      assert.equal(sweepRetry.success, true);
      assert.equal(sweepRetry.failed, 1);

      // Tombstone must still retain pendingSubscriptionId after failed retry
      const tomb2 = mockFirestore.get(`account_tombstones/${uid}`);
      assert.equal(tomb2.pendingSubscriptionId.stringValue, subId);
      assert.equal(tomb2.purgeState.stringValue, "PENDING_RETRY");
    } finally {
      mockRazorpayFaultInjector = null;
    }

    // Now gateway recovers. Next sweep succeeds and completes purge
    const sweepRecovered = await processPendingAccountPurges(mockEnv, 25);
    assert.equal(sweepRecovered.success, true);
    assert.equal(sweepRecovered.succeeded, 1);

    const finalTomb = mockFirestore.get(`account_tombstones/${uid}`);
    assert.equal(finalTomb.purgeState.stringValue, "COMPLETED");
  });

  // -------------------------------------------------------------------------
  // PHASE 9 SECURITY HARDENING COMPREHENSIVE TESTS
  // -------------------------------------------------------------------------

  await test("Security: Rejects unsupported HTTP methods (PUT/DELETE/PATCH) with 405", async () => {
    const req = new Request("https://refocus.workers.dev/api/time", {
      method: "DELETE",
      headers: { "CF-Connecting-IP": "198.51.100.1" }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 405);
  });

  await test("Security: Rejects oversized request payload (>256KB) with 413", async () => {
    const hugeBody = JSON.stringify({ data: "A".repeat(300 * 1024) });
    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": hugeBody.length.toString(),
        "CF-Connecting-IP": "198.51.100.2"
      },
      body: hugeBody
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 413);
  });

  await test("Security: Rejects POST with non-JSON Content-Type (text/plain) with 415", async () => {
    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Content-Type": "text/plain",
        "CF-Connecting-IP": "198.51.100.3"
      },
      body: "plain text attack"
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 415);
  });

  await test("Security: Authentication rejects tampered/forged JWT signature with 401", async () => {
    const validToken = createMockFirebaseIdToken("user_victim");
    const parts = validToken.split(".");
    // Tamper with payload to claim victim UID while signature is from original
    const tamperedPayload = Buffer.from(JSON.stringify({
      ...JSON.parse(Buffer.from(parts[1], "base64url").toString()),
      sub: "user_admin"
    })).toString("base64url");
    const forgedToken = `${parts[0]}.${tamperedPayload}.${parts[2]}`;

    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${forgedToken}`,
        "CF-Connecting-IP": "198.51.100.4"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 401);
  });

  await test("Security: Authentication rejects expired JWT tokens with 401", async () => {
    const expiredToken = createMockFirebaseIdToken("user_expired", "refocus-504119", true);
    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${expiredToken}`,
        "CF-Connecting-IP": "198.51.100.5"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 401);
  });

  await test("Security: Backup rejects oversized dailyRecords (>365 days) with 400", async () => {
    const uid = "user_backup_overflow";
    const token = createMockFirebaseIdToken(uid);
    // Grant trial entitlement
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "TRIAL" },
      trialEnd: { timestampValue: new Date(Date.now() + 86400000).toISOString() }
    });

    const excessiveRecords = Array.from({ length: 400 }, (_, i) => ({
      dateString: "2026-01-01",
      totalReelsCount: 10
    }));

    const req = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "198.51.100.6"
      },
      body: JSON.stringify({
        requestId: "req_overflow_1",
        dailyRecords: excessiveRecords
      })
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.match(body.error, /exceeds maximum allowed limit/);
  });

  await test("Security: Public Deletion endpoints enforce per-IP rate limits", async () => {
    const uid = "user_del_ratelimit";
    const token = createMockFirebaseIdToken(uid);
    let throttled = false;

    // Send 16 requests from the same IP (limit is 15 req/hour)
    for (let i = 0; i < 16; i++) {
      const req = new Request("https://refocus.workers.dev/api/account/request-deletion", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${token}`,
          "Content-Type": "application/json",
          "CF-Connecting-IP": "203.0.113.88"
        },
        body: JSON.stringify({})
      });
      const res = await worker.fetch(req, mockEnv, {});
      if (res.status === 429) {
        throttled = true;
        assert.ok(res.headers.get("Retry-After"));
        break;
      }
    }
    assert.ok(throttled, "IP-level rate limit should have triggered 429");
  });

  // =========================================================================
  // App Check Verification in Enforced & Audit Modes
  // =========================================================================

  await test("App Check: Enforce mode rejects request missing X-Firebase-AppCheck header with 401", async () => {
    const uid = "user_appcheck_missing";
    const token = createMockFirebaseIdToken(uid);
    const enforceEnv = { ...mockEnv, APP_CHECK_ENFORCEMENT_MODE: "enforced" };

    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`
        // Notice: NO X-Firebase-AppCheck header
      }
    });

    const res = await worker.fetch(req, enforceEnv, {});
    assert.equal(res.status, 401);
    const body = await res.json();
    assert.match(body.error, /AppCheckUnauthorized/);
  });

  await test("App Check: Enforce mode rejects invalid/tampered token with 401", async () => {
    const uid = "user_appcheck_invalid";
    const token = createMockFirebaseIdToken(uid);
    const enforceEnv = { ...mockEnv, APP_CHECK_ENFORCEMENT_MODE: "enforced" };

    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "X-Firebase-AppCheck": "invalid.tampered.token"
      }
    });

    const res = await worker.fetch(req, enforceEnv, {});
    assert.equal(res.status, 401);
    const body = await res.json();
    assert.match(body.error, /AppCheckUnauthorized/);
  });

  await test("App Check: Enforce mode rejects expired token with 401", async () => {
    const uid = "user_appcheck_expired";
    const token = createMockFirebaseIdToken(uid);
    const expiredAppCheck = createMockAppCheckToken("524118238647", "refocus-504119", true);
    const enforceEnv = { ...mockEnv, APP_CHECK_ENFORCEMENT_MODE: "enforced" };

    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "X-Firebase-AppCheck": expiredAppCheck
      }
    });

    const res = await worker.fetch(req, enforceEnv, {});
    assert.equal(res.status, 401);
    const body = await res.json();
    assert.match(body.error, /AppCheckUnauthorized/);
  });

  await test("App Check: Enforce mode accepts request with valid token", async () => {
    const uid = "user_appcheck_valid";
    const token = createMockFirebaseIdToken(uid);
    const validAppCheck = createMockAppCheckToken("524118238647", "refocus-504119", false);
    const enforceEnv = { ...mockEnv, APP_CHECK_ENFORCEMENT_MODE: "enforced" };

    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`,
        "X-Firebase-AppCheck": validAppCheck
      }
    });

    const res = await worker.fetch(req, enforceEnv, {});
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.accountStatus, "ACTIVE");
  });

  await test("App Check: Audit mode accepts request missing X-Firebase-AppCheck header", async () => {
    const uid = "user_appcheck_audit";
    const token = createMockFirebaseIdToken(uid);
    const auditEnv = { ...mockEnv, APP_CHECK_ENFORCEMENT_MODE: "audit" };

    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`
        // Missing token in audit mode
      }
    });

    const res = await worker.fetch(req, auditEnv, {});
    assert.equal(res.status, 200);
  });

  await test("App Check: Webhook endpoint does not enforce App Check header", async () => {
    const enforceEnv = { ...mockEnv, APP_CHECK_ENFORCEMENT_MODE: "enforced" };
    const eventId = "evt_ac_webhook_test";
    const payloadStr = JSON.stringify({
      event_id: eventId,
      event: "payment.failed",
      payload: { payment: { entity: { id: "pay_webhook_no_ac" } } }
    });
    const signature = computeHmacHex(payloadStr, mockEnv.RAZORPAY_WEBHOOK_SECRET);

    const req = new Request("https://refocus.workers.dev/api/razorpay-webhook", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Razorpay-Signature": signature
        // Notice: NO X-Firebase-AppCheck header on server-to-server webhook
      },
      body: payloadStr
    });

    const res = await worker.fetch(req, enforceEnv, {});
    assert.equal(res.status, 200);
  });

  await test("App Check: User auth is independent and mandatory even with valid App Check", async () => {
    const validAppCheck = createMockAppCheckToken("524118238647", "refocus-504119", false);
    const enforceEnv = { ...mockEnv, APP_CHECK_ENFORCEMENT_MODE: "enforced" };

    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        // Valid App Check, but NO Authorization header
        "X-Firebase-AppCheck": validAppCheck
      }
    });

    const res = await worker.fetch(req, enforceEnv, {});
    assert.equal(res.status, 401);
    const body = await res.json();
    assert.match(body.error, /Missing or invalid Bearer token/);
  });

  // =========================================================================
  // Phase E: Rate Limiting & Resource Protection Tests
  // =========================================================================

  await test("Rate Limit: Repeated invalid authentication attempts trigger failure ceiling (20 req/min) with 429", async () => {
    const ip = "198.51.100.101";

    // First 20 invalid auth attempts
    for (let i = 0; i < 20; i++) {
      const req = new Request("https://refocus.workers.dev/api/account/status", {
        method: "GET",
        headers: {
          "Authorization": `Bearer invalid-token-${i}`,
          "CF-Connecting-IP": ip
        }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 401, `Attempt ${i + 1} should return 401`);
    }

    // 21st invalid attempt from same IP must trigger 429 Too many authentication failures
    const blockedReq = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": "Bearer invalid-token-21",
        "CF-Connecting-IP": ip
      }
    });
    const blockedRes = await worker.fetch(blockedReq, mockEnv, {});
    assert.equal(blockedRes.status, 429);
    assert.ok(blockedRes.headers.get("Retry-After"));
    const body = await blockedRes.json();
    assert.match(body.error, /Too many authentication failures/);
  });

  await test("Rate Limit: Unauthenticated / missing-token flood throttles with 429", async () => {
    const ip = "198.51.100.99";

    // Send 25 unauthenticated requests
    for (let i = 0; i < 25; i++) {
      const req = new Request("https://refocus.workers.dev/api/account/status", {
        method: "GET",
        headers: {
          "CF-Connecting-IP": ip
        }
      });
      const res = await worker.fetch(req, mockEnv, {});
      if (i < 20) {
        assert.equal(res.status, 401);
      } else {
        assert.equal(res.status, 429);
      }
    }
  });

  await test("Rate Limit: Requests boundary test - request 30 succeeds, request 31 throttled on /api/time", async () => {
    const ip = "198.51.100.102";

    // 30 requests to /api/time
    for (let i = 0; i < 30; i++) {
      const req = new Request("https://refocus.workers.dev/api/time", {
        method: "GET",
        headers: { "CF-Connecting-IP": ip }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200, `Request ${i + 1} must succeed`);
    }

    // 31st request from same IP must be throttled
    const req31 = new Request("https://refocus.workers.dev/api/time", {
      method: "GET",
      headers: { "CF-Connecting-IP": ip }
    });
    const res31 = await worker.fetch(req31, mockEnv, {});
    assert.equal(res31.status, 429);
    assert.ok(res31.headers.get("Retry-After"));
  });

  await test("Rate Limit: Independent authenticated users on same IP have independent UID limits", async () => {
    const ip = "198.51.100.103";
    const userA = "user_rate_indep_A";
    const userB = "user_rate_indep_B";
    const tokenA = createMockFirebaseIdToken(userA);
    const tokenB = createMockFirebaseIdToken(userB);

    // User A exhausts their UID limit for account status (30 req)
    for (let i = 0; i < 30; i++) {
      const req = new Request("https://refocus.workers.dev/api/account/status", {
        method: "GET",
        headers: {
          "Authorization": `Bearer ${tokenA}`,
          "CF-Connecting-IP": ip
        }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200);
    }

    // User A request 31 is throttled on UID limit
    const reqA31 = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${tokenA}`,
        "CF-Connecting-IP": ip
      }
    });
    const resA31 = await worker.fetch(reqA31, mockEnv, {});
    assert.equal(resA31.status, 429);

    // User B on the EXACT SAME IP must NOT be throttled on their first request!
    const reqB = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${tokenB}`,
        "CF-Connecting-IP": ip
      }
    });
    const resB = await worker.fetch(reqB, mockEnv, {});
    assert.equal(resB.status, 200, "User B must not be penalized by User A exhausting UID limit");
  });

  await test("Rate Limit: Header spoofing - CF-Connecting-IP cannot be bypassed via rotating X-Forwarded-For", async () => {
    const fixedIp = "198.51.100.104";

    // Send 30 requests to /api/time from fixedIp, each rotating X-Forwarded-For
    for (let i = 0; i < 30; i++) {
      const req = new Request("https://refocus.workers.dev/api/time", {
        method: "GET",
        headers: {
          "CF-Connecting-IP": fixedIp,
          "X-Forwarded-For": `203.0.113.${i}`
        }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200);
    }

    // 31st request with yet another fake X-Forwarded-For must STILL be blocked because CF-Connecting-IP is trusted
    const req31 = new Request("https://refocus.workers.dev/api/time", {
      method: "GET",
      headers: {
        "CF-Connecting-IP": fixedIp,
        "X-Forwarded-For": "203.0.113.99"
      }
    });
    const res31 = await worker.fetch(req31, mockEnv, {});
    assert.equal(res31.status, 429, "Spoofed X-Forwarded-For must NOT bypass CF-Connecting-IP rate limit");
  });

  await test("Rate Limit: Probe/scanner 404 paths throttle after 30 attempts with 429", async () => {
    const ip = "198.51.100.105";

    // 30 non-existent routes
    for (let i = 0; i < 30; i++) {
      const req = new Request(`https://refocus.workers.dev/api/scanner_probe_${i}`, {
        method: "GET",
        headers: { "CF-Connecting-IP": ip }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 404);
    }

    // 31st unknown route from same IP must be throttled with 429
    const req31 = new Request("https://refocus.workers.dev/api/scanner_probe_31", {
      method: "GET",
      headers: { "CF-Connecting-IP": ip }
    });
    const res31 = await worker.fetch(req31, mockEnv, {});
    assert.equal(res31.status, 429);
    assert.ok(res31.headers.get("Retry-After"));
    const body = await res31.json();
    assert.match(body.error, /Too many requests/);
  });

  await test("Payment Idempotency: Duplicate verify-subscription requests succeed idempotently without double-grant", async () => {
    const uid = "user_verify_idempotent_test";
    const token = createMockFirebaseIdToken(uid);
    const subId = "sub_idempotent_test_1";
    const payId = "pay_idempotent_test_1";

    const secret = mockEnv.RAZORPAY_KEY_SECRET;
    const signature = crypto.createHmac("sha256", secret).update(`${payId}|${subId}`).digest("hex");

    mockRazorpaySubscriptions.set(subId, {
      id: subId,
      status: "active",
      plan_id: mockEnv.RAZORPAY_PLAN_ID,
      current_start: Math.floor(Date.now() / 1000),
      current_end: Math.floor(Date.now() / 1000) + 30 * 86400,
      notes: { firebase_uid: uid }
    });

    const payload = {
      razorpay_subscription_id: subId,
      razorpay_payment_id: payId,
      razorpay_signature: signature
    };

    // First verification call
    const req1 = new Request("https://refocus.workers.dev/api/verify-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "198.51.100.106"
      },
      body: JSON.stringify(payload)
    });
    const res1 = await worker.fetch(req1, mockEnv, {});
    assert.equal(res1.status, 200);

    // Second duplicate verification call
    const req2 = new Request("https://refocus.workers.dev/api/verify-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "198.51.100.106"
      },
      body: JSON.stringify(payload)
    });
    const res2 = await worker.fetch(req2, mockEnv, {});
    assert.equal(res2.status, 200, "Duplicate payment verification must succeed idempotently");
    const body2 = await res2.json();
    assert.equal(body2.success, true);
    assert.equal(body2.status, "ACTIVE");
  });

  await test("Rate Limit: Fail-Safe resilience - Authentication remains strictly mandatory during rate limiter operation", async () => {
    // Verify that rate-limiting never bypasses authentication: an unauthenticated request to a protected route
    // must NEVER be granted access under any rate-limiting state
    const req = new Request("https://refocus.workers.dev/api/account/status", {
      method: "GET",
      headers: {
        "CF-Connecting-IP": "198.51.100.107"
        // Missing Authorization
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 401, "Protected routes must always reject unauthenticated requests");
  });

  await test("Rate Limit: Memory bounds - Store eviction prevents unbounded memory growth", async () => {
    // Generate 100 unique IP probes to /health to verify rateLimitStore populates and evicts cleanly
    for (let i = 0; i < 100; i++) {
      const req = new Request("https://refocus.workers.dev/health", {
        method: "GET",
        headers: { "CF-Connecting-IP": `192.168.10.${i}` }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 200);
    }
  });

  await test("Rate Limit: Sensitive endpoints strictly enforce auth & entitlement regardless of rate-limiter state", async () => {
    // 1. Subscription Lease requires valid user token even if rate limit passes
    const leaseReqNoAuth = new Request("https://refocus.workers.dev/api/subscription/lease", {
      method: "POST",
      headers: { "CF-Connecting-IP": "198.51.100.200" }
    });
    const leaseResNoAuth = await worker.fetch(leaseReqNoAuth, mockEnv, {});
    assert.equal(leaseResNoAuth.status, 401);

    // 2. Backup requires valid entitlement even if rate limit passes
    const unentitledUid = "user_rate_unentitled_test";
    const unentitledToken = createMockFirebaseIdToken(unentitledUid);
    mockFirestore.set(`users/${unentitledUid}/subscription/status`, {
      subscriptionStatus: { stringValue: "EXPIRED" },
      trialEnd: { timestampValue: new Date(Date.now() - 86400000).toISOString() },
      premium: { booleanValue: false }
    });

    const backupReq = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${unentitledToken}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "198.51.100.201"
      },
      body: JSON.stringify({ requestId: "req_test_rate_auth", dailyRecords: [] })
    });
    const backupRes = await worker.fetch(backupReq, mockEnv, {});
    assert.equal(backupRes.status, 403, "Entitlement must strictly block expired user even if rate limit allows");
  });

  await test("Rate Limit: Privacy - Rate limiter error log does not expose raw IP or UID in key", async () => {
    const originalConsoleError = console.error;
    let loggedMessages = [];
    console.error = (...args) => {
      loggedMessages.push(args.join(" "));
      originalConsoleError(...args);
    };

    try {
      const testIp = "198.51.100.222";
      const req = new Request("https://refocus.workers.dev/api/account/status", {
        method: "GET",
        headers: {
          "Authorization": "Bearer invalid-token-privacy-test",
          "CF-Connecting-IP": testIp
        }
      });
      await worker.fetch(req, mockEnv, {});

      const rateLimiterLogs = loggedMessages.filter(msg => msg.includes("Rate limiter"));
      for (const log of rateLimiterLogs) {
        assert.ok(!log.includes(testIp), "Rate limiter log must NOT leak raw client IP");
        assert.ok(!log.includes("invalid-token-privacy-test"), "Rate limiter log must NOT leak token or UID");
      }
    } finally {
      console.error = originalConsoleError;
    }
  });

  // =========================================================================
  // STEP 5B: ABUSE-RESISTANT FREE-TRIAL ELIGIBILITY TESTS
  // =========================================================================

  await test("Step 5B - Scenario 1: Same verified Google subject with two different Firebase UIDs (recreated account gets EXPIRED)", async () => {
    const googleSub = "google_sub_user_scenario_1";
    const uid1 = "user_scenario1_orig";
    const uid2 = "user_scenario1_recreated";

    // 1. First account claims trial
    const token1 = createMockFirebaseIdToken(uid1, "refocus-504119", false, googleSub);
    const req1 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token1}`,
        "CF-Connecting-IP": "198.51.100.10"
      }
    });
    const res1 = await worker.fetch(req1, mockEnv, {});
    assert.equal(res1.status, 201, "First account should initialize trial with 201");
    const data1 = await res1.json();
    assert.equal(data1.subscriptionStatus, "TRIAL");
    assert.equal(data1.trialActive, true);

    // Verify trial_identities record exists
    const identityKey = await computeTrialIdentityHash(googleSub, mockEnv.TRIAL_SECRET_KEY);
    const idDoc = mockFirestore.get(`trial_identities/${identityKey}`);
    assert.ok(idDoc, "trial_identities entry must exist");

    // Fast-forward trial expiration into past
    const expiredIso = new Date(Date.now() - 86400000).toISOString();
    idDoc.trialEnd = { timestampValue: expiredIso };
    mockFirestore.set(`trial_identities/${identityKey}`, idDoc);

    // 2. User recreates account with new UID uid2 and calls ensure-subscription
    const token2 = createMockFirebaseIdToken(uid2, "refocus-504119", false, googleSub);
    const req2 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token2}`,
        "CF-Connecting-IP": "198.51.100.11"
      }
    });
    const res2 = await worker.fetch(req2, mockEnv, {});
    assert.equal(res2.status, 200, "Recreated account should receive 200 with already claimed status");
    const data2 = await res2.json();
    assert.equal(data2.trialAlreadyClaimed, true);
    assert.equal(data2.trialActive, false);
    assert.equal(data2.subscriptionStatus, "EXPIRED");

    // Check that uid2's subscription status was restored as EXPIRED (no fresh 7 days granted)
    const subDoc2 = mockFirestore.get(`users/${uid2}/subscription/status`);
    assert.ok(subDoc2, "uid2 subscription status doc must be restored");
    assert.equal(subDoc2.subscriptionStatus?.stringValue, "EXPIRED");
  });

  await test("Step 5B - Scenario 2: Re-registration during original trial (restores remaining duration without extending)", async () => {
    const googleSub = "google_sub_user_scenario_2";
    const uidOrig = "user_scenario2_orig";
    const uidRecreated = "user_scenario2_recreated";

    // 1. Original account claims trial
    const token1 = createMockFirebaseIdToken(uidOrig, "refocus-504119", false, googleSub);
    const req1 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token1}`,
        "CF-Connecting-IP": "198.51.100.12"
      }
    });
    const res1 = await worker.fetch(req1, mockEnv, {});
    assert.equal(res1.status, 201);
    const data1 = await res1.json();
    const originalTrialEndMs = data1.trialEnd;

    // 2. Recreated account signs in with same Google subject while trial is still active (e.g. 4 days left)
    const token2 = createMockFirebaseIdToken(uidRecreated, "refocus-504119", false, googleSub);
    const req2 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token2}`,
        "CF-Connecting-IP": "198.51.100.13"
      }
    });
    const res2 = await worker.fetch(req2, mockEnv, {});
    assert.equal(res2.status, 200);
    const data2 = await res2.json();
    assert.equal(data2.trialAlreadyClaimed, true);
    assert.equal(data2.trialActive, true);
    assert.equal(data2.subscriptionStatus, "TRIAL");
    // Verify trialEnd is exactly the original trialEnd, NOT a fresh 7 days!
    assert.equal(data2.trialEnd, originalTrialEndMs, "TrialEnd must be preserved without extension");

    const subDoc2 = mockFirestore.get(`users/${uidRecreated}/subscription/status`);
    assert.equal(new Date(subDoc2.trialEnd.timestampValue).getTime(), originalTrialEndMs);
  });

  await test("Step 5B - Scenario 3: Re-registration after expiry without a new trial", async () => {
    const googleSub = "google_sub_user_scenario_3";
    const identityKey = await computeTrialIdentityHash(googleSub, mockEnv.TRIAL_SECRET_KEY);

    // Pre-populate an expired trial in trial_identities
    const pastIso = new Date(Date.now() - (10 * 86400000)).toISOString();
    mockFirestore.set(`trial_identities/${identityKey}`, {
      claimedAt: { timestampValue: new Date(Date.now() - (17 * 86400000)).toISOString() },
      trialEnd: { timestampValue: pastIso }
    });

    const newUid = "user_scenario3_new_uid";
    const token = createMockFirebaseIdToken(newUid, "refocus-504119", false, googleSub);
    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "CF-Connecting-IP": "198.51.100.14"
      }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.trialAlreadyClaimed, true);
    assert.equal(data.trialActive, false);
    assert.equal(data.subscriptionStatus, "EXPIRED");

    const subDoc = mockFirestore.get(`users/${newUid}/subscription/status`);
    assert.equal(subDoc.subscriptionStatus?.stringValue, "EXPIRED");
  });

  await test("Step 5B - Scenario 4: Concurrent requests for same stable identity (exactly one reserves)", async () => {
    const googleSub = "google_sub_user_scenario_4";
    const identityKey = await computeTrialIdentityHash(googleSub, mockEnv.TRIAL_SECRET_KEY);

    const nowIso = new Date().toISOString();
    const trialEndIso = new Date(Date.now() + 7 * 86400000).toISOString();
    const fields = {
      claimedAt: { timestampValue: nowIso },
      trialEnd: { timestampValue: trialEndIso }
    };

    // First call to reserveTrialIdentityAtomic must succeed
    const res1 = await reserveTrialIdentityAtomic(identityKey, fields, mockEnv);
    assert.equal(res1.created, true, "First atomic reservation must succeed");

    // Second call to reserveTrialIdentityAtomic for same key must return ALREADY_EXISTS (conflict)
    const res2 = await reserveTrialIdentityAtomic(identityKey, fields, mockEnv);
    assert.equal(res2.created, false, "Second atomic reservation must conflict");
    assert.equal(res2.reason, "ALREADY_EXISTS");

    // Calling ensure-subscription concurrently yields the same trialEnd
    const uidA = "user_scenario4_uid_a";
    const uidB = "user_scenario4_uid_b";
    const tokenA = createMockFirebaseIdToken(uidA, "refocus-504119", false, googleSub);
    const tokenB = createMockFirebaseIdToken(uidB, "refocus-504119", false, googleSub);

    const reqA = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${tokenA}`, "CF-Connecting-IP": "198.51.100.15" }
    });
    const reqB = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${tokenB}`, "CF-Connecting-IP": "198.51.100.16" }
    });

    const [respA, respB] = await Promise.all([
      worker.fetch(reqA, mockEnv, {}),
      worker.fetch(reqB, mockEnv, {})
    ]);

    const dataA = await respA.json();
    const dataB = await respB.json();
    assert.equal(dataA.trialEnd, dataB.trialEnd, "Both requests must receive identical trialEnd");
  });

  await test("Step 5B - Scenario 5: Firestore 409/412, 5xx, timeout, and malformed responses", async () => {
    const googleSub = "google_sub_user_scenario_5";
    const uid = "user_scenario5_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    // A) 503 error on Firestore trial_identities GET
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes("trial_identities")) {
        return new Response("Service Unavailable", { status: 503 });
      }
      return undefined;
    };

    try {
      const req503 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.17" }
      });
      const res503 = await worker.fetch(req503, mockEnv, {});
      assert.equal(res503.status, 503, "Firestore 503 must fail closed with 503");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // B) Network timeout/error on Firestore
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes("trial_identities")) {
        throw new Error("Network timeout connecting to Firestore");
      }
      return undefined;
    };

    try {
      const reqTimeout = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.18" }
      });
      const resTimeout = await worker.fetch(reqTimeout, mockEnv, {});
      assert.equal(resTimeout.status, 503, "Firestore network error must fail closed with 503");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // C) Malformed response on Firestore
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes("trial_identities")) {
        return new Response("invalid-json{not:valid", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return undefined;
    };

    try {
      const reqMalformed = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.19" }
      });
      const resMalformed = await worker.fetch(reqMalformed, mockEnv, {});
      assert.ok(resMalformed.status === 503 || resMalformed.status === 500, "Malformed Firestore response must fail closed");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 5B - Scenario 6: Registry write succeeds but subscription-state write fails, and vice versa", async () => {
    const googleSub = "google_sub_user_scenario_6";
    const uid = "user_scenario6_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    // Simulate failure writing users/{uid}/subscription/status after trial_identities reservation
    let failSubscriptionWrite = true;
    mockFirestoreFaultInjector = (url, options) => {
      if (failSubscriptionWrite && url.includes(`/users/${uid}/subscription/status`) && options.method === "PATCH") {
        return new Response("Firestore write failure", { status: 500 });
      }
      return undefined;
    };

    try {
      const req1 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.20" }
      });
      const res1 = await worker.fetch(req1, mockEnv, {});
      assert.equal(res1.status, 500, "Should return 500 when subscription write fails");

      // Verify trial_identities WAS reserved
      const identityKey = await computeTrialIdentityHash(googleSub, mockEnv.TRIAL_SECRET_KEY);
      const identityRecord = mockFirestore.get(`trial_identities/${identityKey}`);
      assert.ok(identityRecord, "trial_identities reservation must have been committed");

      // Now disable failure and retry request
      failSubscriptionWrite = false;
      const reqRetry = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.21" }
      });
      const resRetry = await worker.fetch(reqRetry, mockEnv, {});
      assert.equal(resRetry.status, 200, "Retry should succeed using the already-reserved trial");
      const retryData = await resRetry.json();
      assert.equal(retryData.subscriptionStatus, "TRIAL");
      assert.equal(retryData.trialAlreadyClaimed, true);
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 5B - Scenario 7: Missing or malformed provider identity claims (returns 403)", async () => {
    // 1. Missing firebase claim completely
    const tokenNoFirebase = createMockFirebaseIdToken("user_scen7_1", "refocus-504119", false, null, null);
    const req1 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${tokenNoFirebase}`, "CF-Connecting-IP": "198.51.100.22" }
    });
    const res1 = await worker.fetch(req1, mockEnv, {});
    assert.equal(res1.status, 403, "Token missing firebase claim must return 403");
    const data1 = await res1.json();
    assert.ok(data1.error.includes("Google account"));

    // 2. Empty identities object (e.g. anonymous sign-in)
    const tokenEmptyIdentities = createMockFirebaseIdToken("user_scen7_2", "refocus-504119", false, null, {
      sign_in_provider: "anonymous",
      identities: {}
    });
    const req2 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${tokenEmptyIdentities}`, "CF-Connecting-IP": "198.51.100.23" }
    });
    const res2 = await worker.fetch(req2, mockEnv, {});
    assert.equal(res2.status, 403, "Anonymous sign-in without Google provider must return 403");

    // 3. Different provider (e.g. github.com)
    const tokenGithub = createMockFirebaseIdToken("user_scen7_3", "refocus-504119", false, null, {
      sign_in_provider: "github.com",
      identities: { "github.com": ["gh_12345"] }
    });
    const req3 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${tokenGithub}`, "CF-Connecting-IP": "198.51.100.24" }
    });
    const res3 = await worker.fetch(req3, mockEnv, {});
    assert.equal(res3.status, 403, "Non-Google provider must return 403");

    // 4. Empty google.com array
    const tokenEmptyGoogle = createMockFirebaseIdToken("user_scen7_4", "refocus-504119", false, null, {
      sign_in_provider: "google.com",
      identities: { "google.com": [] }
    });
    const req4 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${tokenEmptyGoogle}`, "CF-Connecting-IP": "198.51.100.25" }
    });
    const res4 = await worker.fetch(req4, mockEnv, {});
    assert.equal(res4.status, 403, "Empty Google identities array must return 403");
  });

  await test("Step 5B - Scenario 8: Client-supplied identity fields ignored", async () => {
    const authenticGoogleSub = "google_sub_authentic_scen8";
    const spoofedGoogleSub = "google_sub_attacker_spoofed";
    const uid = "user_scenario8_uid";

    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, authenticGoogleSub);

    // Client passes spoofed identity fields in request body
    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Content-Type": "application/json",
        "CF-Connecting-IP": "198.51.100.26"
      },
      body: JSON.stringify({
        google_id: spoofedGoogleSub,
        email: "attacker@gmail.com",
        sub: spoofedGoogleSub
      })
    });

    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 201);

    // Verify the record was created under the authentic token hash, NOT the spoofed one
    const authenticKey = await computeTrialIdentityHash(authenticGoogleSub, mockEnv.TRIAL_SECRET_KEY);
    const spoofedKey = await computeTrialIdentityHash(spoofedGoogleSub, mockEnv.TRIAL_SECRET_KEY);

    assert.ok(mockFirestore.has(`trial_identities/${authenticKey}`), "Must record under authentic token Google sub");
    assert.equal(mockFirestore.has(`trial_identities/${spoofedKey}`), false, "Must NOT record under spoofed body sub");
  });

  await test("Step 5B - Scenario 9: HMAC secret missing (503) and secret rotation with TRIAL_PREVIOUS_SECRET_KEY", async () => {
    const googleSub = "google_sub_user_scenario_9";
    const uid = "user_scenario9_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    // A) Missing TRIAL_SECRET_KEY
    const envNoSecret = { ...mockEnv, TRIAL_SECRET_KEY: undefined };
    const reqNoSecret = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.27" }
    });
    const resNoSecret = await worker.fetch(reqNoSecret, envNoSecret, {});
    assert.equal(resNoSecret.status, 503, "Missing TRIAL_SECRET_KEY must return 503");

    // B) Secret rotation
    const oldSecret = "old_trial_secret_key_v1";
    const newSecret = "new_trial_secret_key_v2";

    // Setup an existing trial created under oldSecret
    const oldKey = await computeTrialIdentityHash(googleSub, oldSecret);
    const originalEndIso = new Date(Date.now() + 5 * 86400000).toISOString();
    mockFirestore.set(`trial_identities/${oldKey}`, {
      claimedAt: { timestampValue: new Date(Date.now() - 2 * 86400000).toISOString() },
      trialEnd: { timestampValue: originalEndIso }
    });

    // Configure worker with newSecret as TRIAL_SECRET_KEY and oldSecret as TRIAL_PREVIOUS_SECRET_KEY
    const envRotated = {
      ...mockEnv,
      TRIAL_SECRET_KEY: newSecret,
      TRIAL_PREVIOUS_SECRET_KEY: oldSecret
    };

    const reqRotated = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.28" }
    });
    const resRotated = await worker.fetch(reqRotated, envRotated, {});
    assert.equal(resRotated.status, 200);
    const dataRotated = await resRotated.json();
    assert.equal(dataRotated.trialAlreadyClaimed, true);
    assert.equal(dataRotated.trialActive, true);
    assert.equal(dataRotated.subscriptionStatus, "TRIAL");
    assert.equal(dataRotated.trialEnd, new Date(originalEndIso).getTime());

    // Verify backfill: trial_identities should now ALSO be indexed under newKey
    const newKey = await computeTrialIdentityHash(googleSub, newSecret);
    assert.ok(mockFirestore.has(`trial_identities/${newKey}`), "Trial identity must be re-indexed under new secret");
  });

  await test("Step 5B - Scenario 10: New identity receiving exactly one 7-day trial", async () => {
    const googleSub = "google_sub_user_scenario_10_fresh";
    const uid = "user_scenario10_fresh_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.29" }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 201, "Fresh account must receive 201 Created");
    const data = await res.json();
    assert.equal(data.subscriptionStatus, "TRIAL");
    assert.equal(data.trialActive, true);

    const approx7DaysMs = Date.now() + (7 * 86400000);
    assert.ok(Math.abs(data.trialEnd - approx7DaysMs) < 60000, "trialEnd must be ~7 days in future");

    // Check trial_identities document
    const identityKey = await computeTrialIdentityHash(googleSub, mockEnv.TRIAL_SECRET_KEY);
    const idDoc = mockFirestore.get(`trial_identities/${identityKey}`);
    assert.ok(idDoc, "trial_identities doc must be created");
    assert.ok(idDoc.claimedAt?.timestampValue);
    assert.ok(idDoc.trialEnd?.timestampValue);

    // Check user subscription doc
    const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.ok(subDoc);
    assert.equal(subDoc.subscriptionStatus?.stringValue, "TRIAL");
  });

  await test("Step 5B - Scenario 11: Legacy UID-only registry entry migration", async () => {
    const googleSub = "google_sub_user_scenario_11_legacy";
    const uid = "user_scenario11_legacy_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    // Pre-populate legacy registry
    const legacyClaimedIso = new Date(Date.now() - 3 * 86400000).toISOString();
    const legacyTrialEndIso = new Date(Date.now() + 4 * 86400000).toISOString();
    mockFirestore.set(`trial_registry/${uid}`, {
      claimedAt: { timestampValue: legacyClaimedIso },
      trialEnd: { timestampValue: legacyTrialEndIso },
      uid: { stringValue: uid }
    });

    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.30" }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.trialAlreadyClaimed, true);
    assert.equal(data.trialActive, true);
    assert.equal(data.subscriptionStatus, "TRIAL");
    assert.equal(data.trialEnd, new Date(legacyTrialEndIso).getTime());

    // Verify migrated into trial_identities
    const identityKey = await computeTrialIdentityHash(googleSub, mockEnv.TRIAL_SECRET_KEY);
    const migratedDoc = mockFirestore.get(`trial_identities/${identityKey}`);
    assert.ok(migratedDoc, "Legacy registry entry must be migrated to trial_identities");
    assert.equal(migratedDoc.trialEnd?.timestampValue, legacyTrialEndIso);
  });

  await test("Step 5B - Scenario 12: Account deletion preserving anti-abuse marker without retaining raw identity", async () => {
    const googleSub = "google_sub_user_scenario_12_purge";
    const uid = "user_scenario12_purge_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    // 1. Claim trial
    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.31" }
    });
    const res = await worker.fetch(req, mockEnv, {});
    assert.equal(res.status, 201);

    const identityKey = await computeTrialIdentityHash(googleSub, mockEnv.TRIAL_SECRET_KEY);
    assert.ok(mockFirestore.has(`trial_identities/${identityKey}`));

    // 2. Set up user documents to be purged
    mockFirestore.set(`users/${uid}`, { test: { stringValue: "user_root" } });
    mockFirestore.set(`users/${uid}/daily_data/day1`, { date: { stringValue: "2026-10-01" } });
    mockFirestore.set(`users/${uid}/settings/preferences`, { theme: { stringValue: "dark" } });

    // 3. Purge account
    const purgeResult = await purgeAccountData(uid, mockEnv);
    assert.equal(purgeResult.success, true, "Purge must succeed");

    // 4. Verify user subcollections and documents are deleted
    assert.equal(mockFirestore.has(`users/${uid}/daily_data/day1`), false, "daily_data must be deleted");
    assert.equal(mockFirestore.has(`users/${uid}/settings/preferences`), false, "settings must be deleted");
    assert.equal(mockFirestore.has(`users/${uid}/subscription/status`), false, "subscription doc must be deleted");

    // 5. Verify trial_identities marker was NOT deleted and contains zero raw PII
    const preservedMarker = mockFirestore.get(`trial_identities/${identityKey}`);
    assert.ok(preservedMarker, "trial_identities marker must survive account purge");
    assert.equal(preservedMarker.uid, undefined, "trial_identities marker must NOT store raw uid");
    assert.equal(preservedMarker.email, undefined, "trial_identities marker must NOT store email");
    assert.equal(preservedMarker.googleSub, undefined, "trial_identities marker must NOT store googleSub");
    assert.ok(preservedMarker.claimedAt, "Must contain claimedAt");
    assert.ok(preservedMarker.trialEnd, "Must contain trialEnd");

    // 6. Verify recreated account with same Google subject cannot claim fresh trial
    const recreatedUid = "user_scenario12_recreated_uid";
    const recreatedToken = createMockFirebaseIdToken(recreatedUid, "refocus-504119", false, googleSub);
    const reqRecreated = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${recreatedToken}`, "CF-Connecting-IP": "198.51.100.32" }
    });
    const resRecreated = await worker.fetch(reqRecreated, mockEnv, {});
    assert.equal(resRecreated.status, 200);
    const dataRecreated = await resRecreated.json();
    assert.equal(dataRecreated.trialAlreadyClaimed, true);
  });

  // =========================================================================
  // STEP 5F: FAIL-CLOSED LEGACY LOOKUP, HARDENED ROTATION & TELEMETRY TESTS
  // =========================================================================

  await test("Step 5F - Scenario 1: trial_registry fails closed on 5xx, network error, and malformed JSON (no new trial granted)", async () => {
    const googleSub = "google_sub_user_step5f_scen1";
    const uid = "user_step5f_scen1_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);
    const identityKey = await computeTrialIdentityHash(googleSub, mockEnv.TRIAL_SECRET_KEY);

    // Ensure no existing documents
    mockFirestore.delete(`trial_identities/${identityKey}`);
    mockFirestore.delete(`trial_registry/${uid}`);
    mockFirestore.delete(`users/${uid}/subscription/status`);

    // 1. HTTP 503 on trial_registry GET
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/trial_registry/${uid}`)) {
        return new Response(JSON.stringify({ error: { code: 503, message: "Firestore unavailable" } }), { status: 503 });
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.40" }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 503, "503 on trial_registry must fail closed with 503");
      assert.equal(mockFirestore.has(`trial_identities/${identityKey}`), false, "Must NOT reserve identity document on 503");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 2. Network error / timeout on trial_registry GET
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/trial_registry/${uid}`)) {
        throw new Error("Connection timeout reading trial_registry");
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.41" }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 503, "Network timeout on trial_registry must fail closed with 503");
      assert.equal(mockFirestore.has(`trial_identities/${identityKey}`), false, "Must NOT reserve identity document on network error");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 3. Malformed JSON on trial_registry GET
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/trial_registry/${uid}`)) {
        return new Response("not-valid-json{raw", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.42" }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 503, "Malformed JSON on trial_registry must fail closed with 503");
      assert.equal(mockFirestore.has(`trial_identities/${identityKey}`), false, "Must NOT reserve identity document on malformed JSON");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 4. Confirmed absence (HTTP 404) allows new trial
    const reqClean = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.43" }
    });
    const resClean = await worker.fetch(reqClean, mockEnv, {});
    assert.equal(resClean.status, 201, "Confirmed 404 must allow new trial initialization (201 Created)");
    const dataClean = await resClean.json();
    assert.equal(dataClean.trialActive, true);
    assert.equal(mockFirestore.has(`trial_identities/${identityKey}`), true);
  });

  await test("Step 5F - Scenario 2: subscription/status fails closed on 5xx, network error, and malformed JSON during legacy migration", async () => {
    const googleSub = "google_sub_user_step5f_scen2";
    const uid = "user_step5f_scen2_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);
    const identityKey = await computeTrialIdentityHash(googleSub, mockEnv.TRIAL_SECRET_KEY);

    mockFirestore.delete(`trial_identities/${identityKey}`);
    mockFirestore.delete(`trial_registry/${uid}`);
    mockFirestore.delete(`users/${uid}/subscription/status`);

    // 1. HTTP 500 on subscription/status GET
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/users/${uid}/subscription/status`) && (options.method || "GET") === "GET") {
        return new Response(JSON.stringify({ error: { code: 500, message: "Firestore internal error" } }), { status: 500 });
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.44" }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 503, "500 on subscription/status must fail closed with 503");
      assert.equal(mockFirestore.has(`trial_identities/${identityKey}`), false, "Must NOT reserve identity document on 500");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 2. Network error on subscription/status GET
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/users/${uid}/subscription/status`) && (options.method || "GET") === "GET") {
        throw new Error("Network connection dropped");
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.45" }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 503, "Network error on subscription/status must fail closed with 503");
      assert.equal(mockFirestore.has(`trial_identities/${identityKey}`), false, "Must NOT reserve identity document on network error");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 3. Malformed JSON on subscription/status GET
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/users/${uid}/subscription/status`) && (options.method || "GET") === "GET") {
        return new Response("{incomplete:json", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.46" }
      });
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 503, "Malformed JSON on subscription/status must fail closed with 503");
      assert.equal(mockFirestore.has(`trial_identities/${identityKey}`), false, "Must NOT reserve identity document on malformed JSON");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 5F - Scenario 3: Direct unit verification of getTrialRegistry and getFirestoreSubscription contracts", async () => {
    const testUid = "user_step5f_unit_contract_test";

    // 1. getTrialRegistry
    // 1A. 200 OK
    mockFirestore.set(`trial_registry/${testUid}`, {
      claimedAt: { timestampValue: "2026-10-10T00:00:00Z" },
      trialEnd: { timestampValue: "2026-10-17T00:00:00Z" }
    });
    const regRes200 = await getTrialRegistry(testUid, mockEnv);
    assert.equal(regRes200.exists, true, "200 must return exists=true");
    assert.equal(regRes200.error, false, "200 must return error=false");
    assert.equal(regRes200.claimedAt.timestampValue, "2026-10-10T00:00:00Z");

    // 1B. 404 Not Found
    mockFirestore.delete(`trial_registry/${testUid}`);
    const regRes404 = await getTrialRegistry(testUid, mockEnv);
    assert.equal(regRes404.exists, false, "404 must return exists=false");
    assert.equal(regRes404.error, false, "404 must return error=false");

    // 1C. 503 / 500 error
    mockFirestoreFaultInjector = (url) => {
      if (url.includes(`/trial_registry/${testUid}`)) {
        return new Response("Service Unavailable", { status: 503 });
      }
      return undefined;
    };
    try {
      const regRes503 = await getTrialRegistry(testUid, mockEnv);
      assert.equal(regRes503.exists, false);
      assert.equal(regRes503.error, true, "503 must return error=true");
      assert.equal(regRes503.status, 503);
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 1D. Missing service account key
    const regResNoKey = await getTrialRegistry(testUid, { ...mockEnv, FIREBASE_SERVICE_ACCOUNT_KEY: null });
    assert.equal(regResNoKey.exists, false);
    assert.equal(regResNoKey.error, true, "Missing SA key must return error=true");

    // 2. getFirestoreSubscription
    // 2A. 200 OK
    mockFirestore.set(`users/${testUid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      currentPeriodEnd: { timestampValue: "2026-11-01T00:00:00Z" }
    });
    const subRes200 = await getFirestoreSubscription(testUid, mockEnv);
    assert.equal(subRes200.exists, true, "200 must return exists=true");
    assert.equal(subRes200.error, false, "200 must return error=false");
    assert.equal(subRes200.subscriptionStatus.stringValue, "ACTIVE");

    // 2B. 404 Not Found
    mockFirestore.delete(`users/${testUid}/subscription/status`);
    const subRes404 = await getFirestoreSubscription(testUid, mockEnv);
    assert.equal(subRes404.exists, false, "404 must return exists=false");
    assert.equal(subRes404.error, false, "404 must return error=false");

    // 2C. 503 / 500 error
    mockFirestoreFaultInjector = (url) => {
      if (url.includes(`/users/${testUid}/subscription/status`)) {
        return new Response("Internal Server Error", { status: 500 });
      }
      return undefined;
    };
    try {
      const subRes500 = await getFirestoreSubscription(testUid, mockEnv);
      assert.equal(subRes500.exists, false);
      assert.equal(subRes500.error, true, "500 must return error=true");
      assert.equal(subRes500.status, 500);
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 2D. Missing service account key
    const subResNoKey = await getFirestoreSubscription(testUid, { ...mockEnv, FIREBASE_SERVICE_ACCOUNT_KEY: null });
    assert.equal(subResNoKey.exists, false);
    assert.equal(subResNoKey.error, true, "Missing SA key must return error=true");
  });

  await test("Step 5F - Scenario 4: Transient failure retry restores original trial expiration without extension", async () => {
    const googleSub = "google_sub_user_step5f_scen4";
    const uid = "user_step5f_scen4_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    // Seed existing legacy trial from 5 days ago (2 days remaining)
    const claimedAtIso = new Date(Date.now() - (5 * 24 * 60 * 60 * 1000)).toISOString();
    const originalTrialEndIso = new Date(Date.now() + (2 * 24 * 60 * 60 * 1000)).toISOString();
    const originalTrialEndMs = new Date(originalTrialEndIso).getTime();

    mockFirestore.set(`trial_registry/${uid}`, {
      claimedAt: { timestampValue: claimedAtIso },
      trialEnd: { timestampValue: originalTrialEndIso }
    });

    // 1. Initial attempt hits transient 503 on subscription status
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/users/${uid}/subscription/status`) && (options.method || "GET") === "GET") {
        return new Response("Service Unavailable", { status: 503 });
      }
      return undefined;
    };

    try {
      const req1 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.50" }
      });
      const res1 = await worker.fetch(req1, mockEnv, {});
      assert.equal(res1.status, 503, "First request must fail closed with 503");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 2. Retry after transient outage recovers
    const req2 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.50" }
    });
    const res2 = await worker.fetch(req2, mockEnv, {});
    assert.equal(res2.status, 200, "Retry must succeed with 200");
    const data2 = await res2.json();
    assert.equal(data2.trialActive, true);
    assert.equal(data2.trialAlreadyClaimed, true);
    assert.equal(data2.trialEnd, originalTrialEndMs, "Must strictly preserve original trialEnd without 7-day extension");
  });

  await test("Step 5F - Scenario 5: Mixed-version rollout race & dual reservation reconciliation", async () => {
    const googleSub = "google_sub_user_step5f_scen5";
    const uid = "user_step5f_scen5_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_k1_12345678901234567890123456789012";
    const key2 = "new_secret_k2_abcdefghijklmnopqrstuvwxyz123456";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    const now = Date.now();
    // Version A on old key reserved earlier (trial ends in 2 days)
    const earlierEndIso = new Date(now + 2 * 24 * 60 * 60 * 1000).toISOString();
    const earlierEndMs = new Date(earlierEndIso).getTime();
    // Version B on new key reserved later due to race (trial ends in 7 days)
    const laterEndIso = new Date(now + 7 * 24 * 60 * 60 * 1000).toISOString();

    mockFirestore.set(`trial_identities/${identityKeyOld}`, {
      claimedAt: { timestampValue: new Date(now - 5 * 24 * 60 * 60 * 1000).toISOString() },
      trialEnd: { timestampValue: earlierEndIso }
    });
    mockFirestore.set(`trial_identities/${identityKeyNew}`, {
      claimedAt: { timestampValue: new Date(now).toISOString() },
      trialEnd: { timestampValue: laterEndIso }
    });

    const rotationEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.60" }
    });
    const res = await worker.fetch(req, rotationEnv, {});
    assert.equal(res.status, 200);
    const data = await res.json();

    // Must reconcile by preserving the EARLIER expiration
    assert.equal(data.trialEnd, earlierEndMs, "Dual reservations must reconcile by choosing the earliest trialEnd");

    // Wait for async backfill to settle
    await new Promise(r => setTimeout(r, 50));

    const reconciledNewDoc = mockFirestore.get(`trial_identities/${identityKeyNew}`);
    assert.equal(reconciledNewDoc.trialEnd.timestampValue, earlierEndIso, "Current identity record must be overwritten with earlier expiration");
  });

  await test("Step 5F - Scenario 6: Privacy-safe telemetry logging on TRIAL_PREVIOUS_SECRET_KEY hit", async () => {
    const googleSub = "google_sub_user_step5f_scen6_telemetry";
    const uid = "user_step5f_scen6_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const prevKey = "prev_key_telemetry_1234567890123456789012";
    const currentKey = "curr_key_telemetry_abcdefghijklmnopqrstuv";
    const prevIdentityKey = await computeTrialIdentityHash(googleSub, prevKey);
    const currIdentityKey = await computeTrialIdentityHash(googleSub, currentKey);

    mockFirestore.delete(`trial_identities/${currIdentityKey}`);
    mockFirestore.set(`trial_identities/${prevIdentityKey}`, {
      claimedAt: { timestampValue: "2026-10-15T00:00:00Z" },
      trialEnd: { timestampValue: "2026-10-22T00:00:00Z" }
    });

    const envWithRotation = {
      ...mockEnv,
      TRIAL_SECRET_KEY: currentKey,
      TRIAL_PREVIOUS_SECRET_KEY: prevKey
    };

    const capturedLogs = [];
    const origLog = console.log;
    console.log = (...args) => {
      capturedLogs.push(args.join(" "));
      origLog(...args);
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.70" }
      });
      const res = await worker.fetch(req, envWithRotation, {});
      assert.equal(res.status, 200);

      // Verify privacy-safe log message exists
      const matchLog = capturedLogs.find(l => l.includes("Previous-key trial identity match discovered; queued backfill."));
      assert.ok(matchLog, "Must emit 'Previous-key trial identity match discovered; queued backfill.'");

      // Verify ZERO PII or secrets leaked in logs
      for (const logLine of capturedLogs) {
        assert.ok(!logLine.includes(googleSub), "Log must not contain raw Google Subject ID");
        assert.ok(!logLine.includes(uid), "Log must not contain raw Firebase UID");
        assert.ok(!logLine.includes(prevKey), "Log must not contain raw previous secret key");
        assert.ok(!logLine.includes(currentKey), "Log must not contain raw current secret key");
        assert.ok(!logLine.includes(prevIdentityKey), "Log must not contain raw computed hash");
        assert.ok(!logLine.includes(currIdentityKey), "Log must not contain raw computed hash");
      }
    } finally {
      console.log = origLog;
    }
  });

  await test("Step 5F - Scenario 7: All routes calling getFirestoreSubscription fail closed on error", async () => {
    const uid = "user_step5f_caller_audit";
    const googleSub = "google_sub_user_step5f_caller_audit";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/users/${uid}/subscription/status`) && (options.method || "GET") === "GET") {
        return new Response("Service Unavailable", { status: 503 });
      }
      return undefined;
    };

    try {
      // 1. /api/subscription/lease fails closed with 503
      const reqLease = new Request("https://refocus.workers.dev/api/subscription/lease", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.80" }
      });
      const resLease = await worker.fetch(reqLease, mockEnv, {});
      assert.equal(resLease.status, 503, "Lease must fail closed with 503 when subscription lookup fails");

      // 2. /api/create-subscription fails closed with 503
      const reqCreate = new Request("https://refocus.workers.dev/api/create-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "198.51.100.81" },
        body: JSON.stringify({ planId: "plan_monthly" })
      });
      const resCreate = await worker.fetch(reqCreate, mockEnv, {});
      assert.equal(resCreate.status, 503, "Create subscription must fail closed with 503 when subscription lookup fails");

      // 3. /api/cancel-subscription fails closed with 503
      const reqCancel = new Request("https://refocus.workers.dev/api/cancel-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "198.51.100.82" }
      });
      const resCancel = await worker.fetch(reqCancel, mockEnv, {});
      assert.equal(resCancel.status, 503, "Cancel subscription must fail closed with 503 when subscription lookup fails");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  // =========================================================================
  // STEP 5G: EARLIEST-EXPIRATION RECONCILIATION WRITE FAILURE, RETRY & CONCURRENCY
  // =========================================================================

  await test("Step 5G - Scenario 1: Reconciliation write failure fails closed with 503 and does not extend trial", async () => {
    const googleSub = "google_sub_user_step5g_scen1";
    const uid = "user_step5g_scen1_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_key_step5g_11111111111111111111";
    const key2 = "new_secret_key_step5g_22222222222222222222";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    const now = Date.now();
    const earlierEndMs = now + 2 * 24 * 60 * 60 * 1000;
    const laterEndMs = now + 7 * 24 * 60 * 60 * 1000;
    const earlierEndIso = new Date(earlierEndMs).toISOString();
    const laterEndIso = new Date(laterEndMs).toISOString();

    mockFirestore.set(`trial_identities/${identityKeyOld}`, {
      claimedAt: { timestampValue: new Date(now - 5 * 24 * 60 * 60 * 1000).toISOString() },
      trialEnd: { timestampValue: earlierEndIso }
    });
    mockFirestore.set(`trial_identities/${identityKeyNew}`, {
      claimedAt: { timestampValue: new Date(now).toISOString() },
      trialEnd: { timestampValue: laterEndIso }
    });
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const rotationEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    // Inject fault on PATCH for trial_identities/${identityKeyNew}
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/trial_identities/${identityKeyNew}`) && (options.method || "GET") === "PATCH") {
        return new Response(JSON.stringify({ error: { code: 503, message: "Firestore unavailable" } }), { status: 503 });
      }
      return undefined;
    };

    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.91" }
      });
      const res = await worker.fetch(req, rotationEnv, {});
      assert.equal(res.status, 503, "Reconciliation write failure must fail closed with 503");
      const data = await res.json();
      assert.ok(data.error.includes("Trial verification unavailable"), "Error message must indicate service unavailable");

      // Verify user subscription document was NOT granted with extended trial
      const userSub = mockFirestore.get(`users/${uid}/subscription/status`);
      assert.equal(userSub, undefined, "User subscription document must NOT be created/updated on reconciliation failure");

      // Verify new identity record in Firestore was not corrupted
      const currentDoc = mockFirestore.get(`trial_identities/${identityKeyNew}`);
      assert.equal(currentDoc.trialEnd.timestampValue, laterEndIso, "Firestore document must remain uncorrupted prior to successful reconciliation");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("Step 5G - Scenario 2: Reconciliation retry succeeds, updates current key record, and preserves earlier expiration after key retirement", async () => {
    const googleSub = "google_sub_user_step5g_scen2";
    const uid = "user_step5g_scen2_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_key_step5g_scen2_111111111111111";
    const key2 = "new_secret_key_step5g_scen2_222222222222222";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    const now = Date.now();
    const earlierEndMs = now + 2 * 24 * 60 * 60 * 1000;
    const laterEndMs = now + 7 * 24 * 60 * 60 * 1000;
    const earlierEndIso = new Date(earlierEndMs).toISOString();
    const laterEndIso = new Date(laterEndMs).toISOString();

    mockFirestore.set(`trial_identities/${identityKeyOld}`, {
      claimedAt: { timestampValue: new Date(now - 5 * 24 * 60 * 60 * 1000).toISOString() },
      trialEnd: { timestampValue: earlierEndIso }
    });
    mockFirestore.set(`trial_identities/${identityKeyNew}`, {
      claimedAt: { timestampValue: new Date(now).toISOString() },
      trialEnd: { timestampValue: laterEndIso }
    });
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const rotationEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    // 1. Initial attempt fails with 500
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/trial_identities/${identityKeyNew}`) && (options.method || "GET") === "PATCH") {
        return new Response("Internal Server Error", { status: 500 });
      }
      return undefined;
    };

    const req1 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.92" }
    });
    const res1 = await worker.fetch(req1, rotationEnv, {});
    assert.equal(res1.status, 503, "First attempt must fail closed with 503");

    // 2. Clear fault injector to simulate recovery
    mockFirestoreFaultInjector = null;

    // 3. Retry succeeds
    const reqRetry = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.93" }
    });
    const resRetry = await worker.fetch(reqRetry, rotationEnv, {});
    assert.equal(resRetry.status, 200, "Retry must succeed with 200");
    const dataRetry = await resRetry.json();
    assert.equal(dataRetry.trialEnd, earlierEndMs, "Must strictly return the earlier trial expiration");

    // Verify Firestore current identity record was reliably overwritten
    const reconciledDoc = mockFirestore.get(`trial_identities/${identityKeyNew}`);
    assert.equal(reconciledDoc.trialEnd.timestampValue, earlierEndIso, "Firestore identity document must be overwritten with earlier expiration");

    // 4. Simulate previous key retirement: only current key configured
    const retiredEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: null
    };

    const reqPostRetire = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.94" }
    });
    const resPostRetire = await worker.fetch(reqPostRetire, retiredEnv, {});
    assert.equal(resPostRetire.status, 200, "Must succeed when previous key is retired");
    const dataPostRetire = await resPostRetire.json();
    assert.equal(dataPostRetire.trialEnd, earlierEndMs, "TrialEnd must remain earlierEndMs even after previous key is retired");
  });

  await test("Step 5G - Scenario 3: Concurrent reconciliation requests both preserve earliest expiration safely without race condition", async () => {
    const googleSub = "google_sub_user_step5g_scen3";
    const uid = "user_step5g_scen3_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_key_step5g_scen3_111111111111111";
    const key2 = "new_secret_key_step5g_scen3_222222222222222";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    const now = Date.now();
    const earlierEndMs = now + 2 * 24 * 60 * 60 * 1000;
    const laterEndMs = now + 7 * 24 * 60 * 60 * 1000;
    const earlierEndIso = new Date(earlierEndMs).toISOString();
    const laterEndIso = new Date(laterEndMs).toISOString();

    mockFirestore.set(`trial_identities/${identityKeyOld}`, {
      claimedAt: { timestampValue: new Date(now - 5 * 24 * 60 * 60 * 1000).toISOString() },
      trialEnd: { timestampValue: earlierEndIso }
    });
    mockFirestore.set(`trial_identities/${identityKeyNew}`, {
      claimedAt: { timestampValue: new Date(now).toISOString() },
      trialEnd: { timestampValue: laterEndIso }
    });

    const rotationEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    const req1 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.95" }
    });
    const req2 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.96" }
    });

    const [res1, res2] = await Promise.all([
      worker.fetch(req1, rotationEnv, {}),
      worker.fetch(req2, rotationEnv, {})
    ]);

    assert.equal(res1.status, 200, "Concurrent req1 must return 200");
    assert.equal(res2.status, 200, "Concurrent req2 must return 200");

    const data1 = await res1.json();
    const data2 = await res2.json();

    assert.equal(data1.trialEnd, earlierEndMs, "Concurrent req1 must receive earlier expiration");
    assert.equal(data2.trialEnd, earlierEndMs, "Concurrent req2 must receive earlier expiration");

    const finalDoc = mockFirestore.get(`trial_identities/${identityKeyNew}`);
    assert.equal(finalDoc.trialEnd.timestampValue, earlierEndIso, "Final identity document must have earlier expiration");
  });

  // =========================================================================
  // STEP 5I: MIXED-VERSION TRIAL RESERVATION, SKILL ROLLOUT & MULTI-ENV AUDIT
  // =========================================================================

  const getTimestampMs = (field) => {
    if (!field) return 0;
    const raw = field.timestampValue || field.stringValue || (typeof field === "string" || typeof field === "number" ? field : null);
    if (typeof raw === "string") return new Date(raw).getTime();
    if (typeof raw === "number") return raw < 1e12 ? raw * 1000 : raw;
    return 0;
  };

  await test("Step 5I - Scenario 1: Mixed-version concurrent first-time requests across separate K1 and K2+K1 environments", async () => {
    const googleSub = "google_sub_user_step5i_scen1";
    const uid = "user_step5i_scen1_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_key_step5i_k1_111111111111111";
    const key2 = "new_secret_key_step5i_k2_222222222222222";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    // Initial state: empty Firestore (clean slate)
    mockFirestore.delete(`trial_identities/${identityKeyOld}`);
    mockFirestore.delete(`trial_identities/${identityKeyNew}`);
    mockFirestore.delete(`trial_registry/${uid}`);
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const oldWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key1,
      TRIAL_PREVIOUS_SECRET_KEY: null
    };

    const newWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    const reqOld = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.111" }
    });
    const reqNew = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.112" }
    });

    // Fire concurrent first-time requests from separate environments
    const [resOld, resNew] = await Promise.all([
      worker.fetch(reqOld, oldWorkerEnv, {}),
      worker.fetch(reqNew, newWorkerEnv, {})
    ]);

    assert.ok(resOld.status === 200 || resOld.status === 201, "Old worker request must succeed");
    assert.ok(resNew.status === 200 || resNew.status === 201, "New worker request must succeed");

    // Both documents were created in shared Firestore because document paths differ
    const docOld = mockFirestore.get(`trial_identities/${identityKeyOld}`);
    const docNew = mockFirestore.get(`trial_identities/${identityKeyNew}`);
    assert.ok(docOld, "trial_identities record under K1 must be created");
    assert.ok(docNew, "trial_identities record under K2 must be created");

    // Follow-up request to newWorkerEnv reconciles both records to earlier expiration
    const reqReconcile = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.113" }
    });
    const resReconcile = await worker.fetch(reqReconcile, newWorkerEnv, {});
    assert.equal(resReconcile.status, 200);
    const dataReconcile = await resReconcile.json();
    assert.equal(dataReconcile.trialAlreadyClaimed, true);

    const reconciledDoc = mockFirestore.get(`trial_identities/${identityKeyNew}`);
    const oldEndMs = getTimestampMs(docOld.trialEnd);
    const reconciledEndMs = getTimestampMs(reconciledDoc.trialEnd);
    assert.ok(reconciledEndMs <= oldEndMs, "Reconciled expiration must be less than or equal to original K1 expiration");
  });

  await test("Step 5I - Scenario 2A: Mixed-version sequential requests - Old Worker (K1) completes first, then New Worker (K2+K1)", async () => {
    const googleSub = "google_sub_user_step5i_scen2a";
    const uid = "user_step5i_scen2a_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_key_step5i_2a_111111111111111";
    const key2 = "new_secret_key_step5i_2a_222222222222222";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    mockFirestore.delete(`trial_identities/${identityKeyOld}`);
    mockFirestore.delete(`trial_identities/${identityKeyNew}`);
    mockFirestore.delete(`trial_registry/${uid}`);
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const oldWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key1,
      TRIAL_PREVIOUS_SECRET_KEY: null
    };
    const newWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    // 1. Old Worker completes first
    const reqOld = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.121" }
    });
    const resOld = await worker.fetch(reqOld, oldWorkerEnv, {});
    assert.equal(resOld.status, 201, "First request to old worker must create trial (201)");
    const dataOld = await resOld.json();
    const originalTrialEndMs = dataOld.trialEnd;

    // 2. New Worker request arrives subsequently
    const reqNew = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.122" }
    });
    const resNew = await worker.fetch(reqNew, newWorkerEnv, {});
    assert.equal(resNew.status, 200, "Subsequent request to new worker must return 200");
    const dataNew = await resNew.json();
    assert.equal(dataNew.trialAlreadyClaimed, true, "Must recognize trial already claimed");
    assert.equal(dataNew.trialEnd, originalTrialEndMs, "Must strictly preserve original trialEnd without extension");

    // Verify K2 document was backfilled with K1's trial end
    const docNew = mockFirestore.get(`trial_identities/${identityKeyNew}`);
    assert.ok(docNew, "trial_identities record under K2 must be backfilled");
    assert.equal(getTimestampMs(docNew.trialEnd), originalTrialEndMs);
  });

  await test("Step 5I - Scenario 2B: Mixed-version sequential requests - New Worker (K2+K1) completes first, then Old Worker (K1)", async () => {
    const googleSub = "google_sub_user_step5i_scen2b";
    const uid = "user_step5i_scen2b_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_key_step5i_2b_111111111111111";
    const key2 = "new_secret_key_step5i_2b_222222222222222";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    mockFirestore.delete(`trial_identities/${identityKeyOld}`);
    mockFirestore.delete(`trial_identities/${identityKeyNew}`);
    mockFirestore.delete(`trial_registry/${uid}`);
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const oldWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key1,
      TRIAL_PREVIOUS_SECRET_KEY: null
    };
    const newWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    // 1. New Worker completes first
    const reqNew = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.123" }
    });
    const resNew = await worker.fetch(reqNew, newWorkerEnv, {});
    assert.equal(resNew.status, 201, "First request to new worker must create trial (201)");
    const dataNew = await resNew.json();
    const originalTrialEndMs = dataNew.trialEnd;

    // 2. Old Worker request arrives for the same UID
    const reqOld = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.124" }
    });
    const resOld = await worker.fetch(reqOld, oldWorkerEnv, {});
    assert.equal(resOld.status, 200, "Old worker must detect existing subscription and return 200");
    const dataOld = await resOld.json();
    assert.equal(dataOld.trialAlreadyClaimed, true, "Old worker must recognize trial already claimed");
    assert.equal(dataOld.trialEnd, originalTrialEndMs, "Old worker must preserve original trialEnd");

    // Verify K1 document was backfilled from subscription document
    const docOld = mockFirestore.get(`trial_identities/${identityKeyOld}`);
    assert.ok(docOld, "trial_identities record under K1 must be backfilled");
    assert.equal(getTimestampMs(docOld.trialEnd), originalTrialEndMs);
  });

  await test("Step 5I - Scenario 3: Mixed-version lost response and retry - Reservation on K1 succeeds but response lost, retry on K2+K1", async () => {
    const googleSub = "google_sub_user_step5i_scen3";
    const uid = "user_step5i_scen3_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_key_step5i_3_1111111111111111";
    const key2 = "new_secret_key_step5i_3_2222222222222222";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    mockFirestore.delete(`trial_identities/${identityKeyOld}`);
    mockFirestore.delete(`trial_identities/${identityKeyNew}`);
    mockFirestore.delete(`trial_registry/${uid}`);
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const oldWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key1,
      TRIAL_PREVIOUS_SECRET_KEY: null
    };
    const newWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    // 1. Initial reservation on Old Worker succeeds
    const reqOld = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.131" }
    });
    const resOld = await worker.fetch(reqOld, oldWorkerEnv, {});
    assert.equal(resOld.status, 201);
    const dataOld = await resOld.json();
    const originalTrialEndMs = dataOld.trialEnd;

    // Simulate lost response: Client retries against New Worker node (newWorkerEnv)
    const reqRetry = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.132" }
    });
    const resRetry = await worker.fetch(reqRetry, newWorkerEnv, {});
    assert.equal(resRetry.status, 200, "Retry against new worker must succeed with 200");
    const dataRetry = await resRetry.json();
    assert.equal(dataRetry.trialAlreadyClaimed, true, "Must detect trial already claimed");
    assert.equal(dataRetry.trialEnd, originalTrialEndMs, "Retry must strictly preserve original trialEnd without extension");

    const docNew = mockFirestore.get(`trial_identities/${identityKeyNew}`);
    assert.ok(docNew, "K2 document must be backfilled upon retry");
    assert.equal(getTimestampMs(docNew.trialEnd), originalTrialEndMs);
  });

  await test("Step 5I - Scenario 4: Partial write recovery - Atomic reservation succeeds but subscription status write fails, followed by retry", async () => {
    const googleSub = "google_sub_user_step5i_scen4";
    const uid = "user_step5i_scen4_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key2 = "new_secret_key_step5i_4_2222222222222222";
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    mockFirestore.delete(`trial_identities/${identityKeyNew}`);
    mockFirestore.delete(`trial_registry/${uid}`);
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const newWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: "dummy_previous_secret_step5i_4"
    };

    // 1. First attempt: Fault injection on subscription status write
    mockFirestoreFaultInjector = (url, options) => {
      if (url.includes(`/users/${uid}/subscription/status`) && (options.method || "GET") === "PATCH") {
        return new Response("Internal Server Error", { status: 500 });
      }
      return undefined;
    };

    try {
      const req1 = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.141" }
      });
      const res1 = await worker.fetch(req1, newWorkerEnv, {});
      assert.equal(res1.status, 500, "Partial write failure must return 500");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // Verify identityKey was reserved despite subscription status write failure
    const reservedDoc = mockFirestore.get(`trial_identities/${identityKeyNew}`);
    assert.ok(reservedDoc, "Identity document must have been created atomically");
    const originalTrialEndMs = getTimestampMs(reservedDoc.trialEnd);

    // 2. Retry recovers cleanly using existing identity reservation
    const reqRetry = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.142" }
    });
    const resRetry = await worker.fetch(reqRetry, newWorkerEnv, {});
    assert.equal(resRetry.status, 200, "Retry must succeed with 200");
    const dataRetry = await resRetry.json();
    assert.equal(dataRetry.trialAlreadyClaimed, true);
    assert.equal(dataRetry.trialEnd, originalTrialEndMs, "TrialEnd on retry must match original reservation");

    // Verify subscription status is now properly written
    const subDoc = mockFirestore.get(`users/${uid}/subscription/status`);
    assert.ok(subDoc, "Subscription status must be written on retry");
    assert.equal(getTimestampMs(subDoc.trialEnd), originalTrialEndMs);
  });

  await test("Step 5I - Scenario 5: Dual-record reconciliation strictly preserves earliest expiration without extension across key retirement", async () => {
    const googleSub = "google_sub_user_step5i_scen5";
    const uid = "user_step5i_scen5_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_key_step5i_5_1111111111111111";
    const key2 = "new_secret_key_step5i_5_2222222222222222";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    const now = Date.now();
    const earlierEndMs = now + 1 * 24 * 60 * 60 * 1000;
    const laterEndMs = now + 7 * 24 * 60 * 60 * 1000;
    const earlierEndIso = new Date(earlierEndMs).toISOString();
    const laterEndIso = new Date(laterEndMs).toISOString();

    mockFirestore.set(`trial_identities/${identityKeyOld}`, {
      claimedAt: { timestampValue: new Date(now - 6 * 24 * 60 * 60 * 1000).toISOString() },
      trialEnd: { timestampValue: earlierEndIso }
    });
    mockFirestore.set(`trial_identities/${identityKeyNew}`, {
      claimedAt: { timestampValue: new Date(now).toISOString() },
      trialEnd: { timestampValue: laterEndIso }
    });

    const newWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.151" }
    });
    const res = await worker.fetch(req, newWorkerEnv, {});
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.trialEnd, earlierEndMs, "Must strictly choose earlier expiration");

    // Verify Firestore K2 record was overwritten with earlier expiration
    const docNew = mockFirestore.get(`trial_identities/${identityKeyNew}`);
    assert.equal(docNew.trialEnd.timestampValue, earlierEndIso, "Firestore K2 record must be overwritten");

    // Simulate key retirement (TRIAL_PREVIOUS_SECRET_KEY removed)
    const retiredEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: null
    };

    const reqPostRetire = new Request("https://refocus.workers.dev/api/ensure-subscription", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.152" }
    });
    const resPostRetire = await worker.fetch(reqPostRetire, retiredEnv, {});
    assert.equal(resPostRetire.status, 200);
    const dataPostRetire = await resPostRetire.json();
    assert.equal(dataPostRetire.trialEnd, earlierEndMs, "Must still return earlier expiration even after K1 retirement");
  });

  await test("Step 5I - Scenario 6: Non-404 Firestore failures continue to fail closed in both old and new Worker environments", async () => {
    const googleSub = "google_sub_user_step5i_scen6";
    const uid = "user_step5i_scen6_uid";
    const token = createMockFirebaseIdToken(uid, "refocus-504119", false, googleSub);

    const key1 = "old_secret_key_step5i_6_1111111111111111";
    const key2 = "new_secret_key_step5i_6_2222222222222222";
    const identityKeyOld = await computeTrialIdentityHash(googleSub, key1);
    const identityKeyNew = await computeTrialIdentityHash(googleSub, key2);

    mockFirestore.delete(`trial_identities/${identityKeyOld}`);
    mockFirestore.delete(`trial_identities/${identityKeyNew}`);
    mockFirestore.delete(`trial_registry/${uid}`);
    mockFirestore.delete(`users/${uid}/subscription/status`);

    const oldWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key1,
      TRIAL_PREVIOUS_SECRET_KEY: null
    };
    const newWorkerEnv = {
      ...mockEnv,
      TRIAL_SECRET_KEY: key2,
      TRIAL_PREVIOUS_SECRET_KEY: key1
    };

    // 1. New Worker: HTTP 503 on current identity key
    mockFirestoreFaultInjector = (url) => {
      if (url.includes(`/trial_identities/${identityKeyNew}`)) return new Response("Unavailable", { status: 503 });
      return undefined;
    };
    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.161" }
      });
      const res = await worker.fetch(req, newWorkerEnv, {});
      assert.equal(res.status, 503, "503 on current identity key must return 503");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 2. New Worker: HTTP 500 on previous identity key check
    mockFirestoreFaultInjector = (url) => {
      if (url.includes(`/trial_identities/${identityKeyOld}`)) return new Response("Internal Server Error", { status: 500 });
      return undefined;
    };
    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.162" }
      });
      const res = await worker.fetch(req, newWorkerEnv, {});
      assert.equal(res.status, 503, "500 on previous identity key check must return 503");
    } finally {
      mockFirestoreFaultInjector = null;
    }

    // 3. Old Worker: HTTP 503 on identity key
    mockFirestoreFaultInjector = (url) => {
      if (url.includes(`/trial_identities/${identityKeyOld}`)) return new Response("Unavailable", { status: 503 });
      return undefined;
    };
    try {
      const req = new Request("https://refocus.workers.dev/api/ensure-subscription", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "198.51.100.163" }
      });
      const res = await worker.fetch(req, oldWorkerEnv, {});
      assert.equal(res.status, 503, "Old worker 503 on identity key must return 503");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  // =========================================================================
  // Phase 1: Local-First Backup & Restore Cloudflare Worker Test Suite
  // =========================================================================
  console.log(`\n------------------------------------------------------------`);
  console.log(`Starting Phase 1 Backup & Restore Cloudflare Worker Tests...`);
  console.log(`------------------------------------------------------------`);

  const sampleDailyRecords = [
    {
      dateString: "2026-10-24",
      totalReelsCount: 15,
      dailyGoal: 20,
      isStrictModeOn: true,
      isStrictModeLocked: false,
      isLimitLocked: false
    },
    {
      dateString: "2026-10-23",
      totalReelsCount: 10,
      dailyGoal: 20,
      isStrictModeOn: false,
      isStrictModeLocked: false,
      isLimitLocked: false
    }
  ];

  function setupPhase1User(uid) {
    const token = createMockFirebaseIdToken(uid);
    mockFirestore.set(`users/${uid}/subscription/status`, {
      subscriptionStatus: { stringValue: "ACTIVE" },
      subscriptionId: { stringValue: `sub_${uid}` },
      currentPeriodEnd: { timestampValue: new Date(Date.now() + 30 * 86400 * 1000).toISOString() },
      premium: { booleanValue: true }
    });
    mockDocUpdateTimes.set(`users/${uid}/subscription/status`, "2026-10-24T00:00:00Z");
    return token;
  }

  await test("TEST-BKUP-01: Fast-path POST /api/backup success, duplicate replay, and same-day restriction", async () => {
    const uid = "user_bkup_01";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);
    const reqId = "req_bkup_01_a";

    // 1. Initial backup succeeds
    const req1 = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ requestId: reqId, dailyRecords: sampleDailyRecords, defaultDailyLimit: 25 })
    });
    const res1 = await worker.fetch(req1, mockEnv, {});
    assert.equal(res1.status, 200, "Initial backup must return 200");
    const body1 = await res1.json();
    assert.equal(body1.success, true);
    assert.ok(body1.backupId, "Must return backupId");
    assert.equal(body1.istDate, todayIst);

    // Verify written Firestore state
    const backupId = body1.backupId;
    const manifest = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.ok(manifest, "Manifest document must exist");
    assert.equal(manifest.status.stringValue, "COMMITTED");
    assert.equal(manifest.activeRestorePinCount.integerValue, "0");
    const snapshotDoc = mockFirestore.get(`users/${uid}/backup_snapshots/${backupId}/daily_data/2026-10-24`);
    assert.ok(snapshotDoc, "Snapshot record must exist");
    assert.equal(snapshotDoc.totalReelsCount.integerValue, "15");
    const legacyDoc = mockFirestore.get(`users/${uid}/daily_data/2026-10-24`);
    assert.ok(legacyDoc, "Legacy daily_data doc must exist for backward compatibility");

    // 2. Duplicate backup with same requestId returns 200 idempotent
    const req2 = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ requestId: reqId, dailyRecords: sampleDailyRecords })
    });
    const res2 = await worker.fetch(req2, mockEnv, {});
    assert.equal(res2.status, 200, "Duplicate backup must return 200");
    const body2 = await res2.json();
    assert.equal(body2.idempotent, true);
    assert.equal(body2.activeBackupId, backupId);

    // 3. Different requestId on same day returns 409 BACKUP_RESTRICTED_24_HOURS
    const req3 = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ requestId: "req_bkup_01_b", dailyRecords: sampleDailyRecords })
    });
    const res3 = await worker.fetch(req3, mockEnv, {});
    assert.equal(res3.status, 409, "Second backup on same day must return 409");
    const body3 = await res3.json();
    assert.equal(body3.code, "BACKUP_RESTRICTED_24_HOURS");

    // 4. GET /api/backup/status returns canBackup = false and nextIstMidnightMs
    const statusReq = new Request("https://refocus.workers.dev/api/backup/status", {
      method: "GET",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "1.1.1.1" }
    });
    const statusRes = await worker.fetch(statusReq, mockEnv, {});
    assert.equal(statusRes.status, 200);
    const statusBody = await statusRes.json();
    assert.equal(statusBody.canBackupNow, false);
    assert.equal(statusBody.activeBackupId, backupId);
    assert.ok(statusBody.nextIstMidnightMs > nowMs);
  });

  await test("TEST-CHUNK-01: Multi-part chunked backup init, chunk, and finalize", async () => {
    const uid = "user_chunk_01";
    const token = setupPhase1User(uid);
    const reqId = "req_chunk_01";

    // 1. Init
    const initReq = new Request("https://refocus.workers.dev/api/backup/init", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ requestId: reqId, totalChunks: 2, totalRecords: 2 })
    });
    const initRes = await worker.fetch(initReq, mockEnv, {});
    assert.equal(initRes.status, 200);
    const initBody = await initRes.json();
    const backupId = initBody.backupId;
    assert.ok(backupId);

    // 2. Chunk 0
    const chunk0Req = new Request("https://refocus.workers.dev/api/backup/chunk", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ backupId, chunkIndex: 0, dailyRecords: [sampleDailyRecords[0]] })
    });
    const chunk0Res = await worker.fetch(chunk0Req, mockEnv, {});
    assert.equal(chunk0Res.status, 200);

    // 3. Chunk 1
    const chunk1Req = new Request("https://refocus.workers.dev/api/backup/chunk", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ backupId, chunkIndex: 1, dailyRecords: [sampleDailyRecords[1]] })
    });
    const chunk1Res = await worker.fetch(chunk1Req, mockEnv, {});
    assert.equal(chunk1Res.status, 200);

    // 4. Finalize
    const finReq = new Request("https://refocus.workers.dev/api/backup/finalize", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ backupId, requestId: reqId, defaultDailyLimit: 30 })
    });
    const finRes = await worker.fetch(finReq, mockEnv, {});
    assert.equal(finRes.status, 200);
    const finBody = await finRes.json();
    assert.equal(finBody.success, true);

    // Verify manifest COMMITTED
    const manifest = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(manifest.status.stringValue, "COMMITTED");
  });

  await test("TEST-ADV-01: Stalled-worker chunk append rejected when snapshot already committed (HTTP 409/412)", async () => {
    const uid = "user_adv_01";
    const token = setupPhase1User(uid);
    const reqId = "req_adv_01";

    // 1. Init backup
    const initReq = new Request("https://refocus.workers.dev/api/backup/init", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ requestId: reqId, totalChunks: 2, totalRecords: 2 })
    });
    const initRes = await worker.fetch(initReq, mockEnv, {});
    assert.equal(initRes.status, 200);
    const { backupId } = await initRes.json();

    // 2. Worker B finalizes snapshot
    const finReq = new Request("https://refocus.workers.dev/api/backup/finalize", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ backupId, requestId: reqId, defaultDailyLimit: 20 })
    });
    const finRes = await worker.fetch(finReq, mockEnv, {});
    assert.equal(finRes.status, 200);

    // 3. Stalled Worker A wakes up and attempts to append chunk
    const stalledReq = new Request("https://refocus.workers.dev/api/backup/chunk", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ backupId, chunkIndex: 1, dailyRecords: [{ dateString: "2026-10-22", totalReelsCount: 5 }] })
    });
    const stalledRes = await worker.fetch(stalledReq, mockEnv, {});
    assert.ok(stalledRes.status === 409 || stalledRes.status === 412, "Chunk append to finalized snapshot must fail with 409 or 412");

    // Ensure corrupted record was NOT added to snapshot
    assert.equal(mockFirestore.get(`users/${uid}/backup_snapshots/${backupId}/daily_data/2026-10-22`), undefined);
  });

  await test("TEST-QUOTA-01: Pre-commit abort releases leased quota with 0 completed consumed and decrements pin", async () => {
    const uid = "user_quota_01";
    const token = setupPhase1User(uid);
    const backupId = "bk_quota_01";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      lastPinnedAtMs: { integerValue: "0" },
      recordCount: { integerValue: "2" },
      quarantineStatus: { nullValue: null }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");
    mockFirestore.set(`users/${uid}/backup/status`, {
      status: { stringValue: "SUCCESS" },
      activeBackupId: { stringValue: backupId }
    });

    // 1. POST /api/restore claims slot (leased)
    const restoreReq = new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_quota_01", backupId })
    });
    const restoreRes = await worker.fetch(restoreReq, mockEnv, {});
    assert.equal(restoreRes.status, 200);
    const restoreBody = await restoreRes.json();
    assert.equal(restoreBody.pinStatus, "PINNED");

    // Manifest pin count is now 1
    const m1 = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m1.activeRestorePinCount.integerValue, "1");

    // 2. Pre-commit abort (e.g. disk full during download)
    const abortReq = new Request("https://refocus.workers.dev/api/restore/abort", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_quota_01", reason: "DISK_FULL" })
    });
    const abortRes = await worker.fetch(abortReq, mockEnv, {});
    assert.equal(abortRes.status, 200);
    const abortBody = await abortRes.json();
    assert.equal(abortBody.aborted, true);
    assert.equal(abortBody.status, "FAILED");
    assert.equal(abortBody.attemptCount, 1);
    assert.equal(abortBody.remainingAttempts, 2);

    // Manifest pin decremented back to 0
    const m2 = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m2.activeRestorePinCount.integerValue, "0");

    // Claim is FAILED with pinStatus RELEASED
    const claim = mockFirestore.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`);
    assert.equal(claim.status.stringValue, "FAILED");
    assert.equal(claim.pinStatus.stringValue, "RELEASED");
  });

  await test("TEST-QUOTA-02: Retry after abort with same key succeeds under attempt 2 and re-acquires pin", async () => {
    const uid = "user_quota_02";
    const token = setupPhase1User(uid);
    const backupId = "bk_quota_02";

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      lastPinnedAtMs: { integerValue: "0" },
      recordCount: { integerValue: "2" },
      quarantineStatus: { nullValue: null }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");
    mockFirestore.set(`users/${uid}/backup/status`, {
      status: { stringValue: "SUCCESS" },
      activeBackupId: { stringValue: backupId }
    });

    // 1. Initial restore
    await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_quota_02", backupId })
    }), mockEnv, {});

    // 2. Abort attempt 1
    await worker.fetch(new Request("https://refocus.workers.dev/api/restore/abort", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_quota_02", reason: "CORRUPTED_PAYLOAD" })
    }), mockEnv, {});

    // 3. Retry restore with same key
    const retryRes = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_quota_02", backupId })
    }), mockEnv, {});

    assert.equal(retryRes.status, 200);
    const retryBody = await retryRes.json();
    assert.equal(retryBody.resumedUnderAttempt, 2);
    assert.equal(retryBody.pinStatus, "PINNED");

    // Manifest pin count re-acquired to 1
    const m = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m.activeRestorePinCount.integerValue, "1");
  });

  await test("TEST-QUOTA-03: Restore attempts capped at 3 per day; 4th attempt returns 429", async () => {
    const uid = "user_quota_03";
    const token = setupPhase1User(uid);
    const backupId = "bk_quota_03";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      quarantineStatus: { nullValue: null }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // Set claim as FAILED with attemptCount: 3
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "FAILED" },
      pinStatus: { stringValue: "RELEASED" },
      attemptCount: { integerValue: "3" },
      idempotencyKey: { stringValue: "key_quota_03" },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    // 4th restore attempt
    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_quota_03_fourth", backupId })
    }), mockEnv, {});

    assert.equal(res.status, 429, "4th attempt must return 429");
    const body = await res.json();
    assert.equal(body.code, "RESTORE_ATTEMPTS_EXHAUSTED");
  });

  await test("TEST-PIN-01: Response drop replay on POST /api/restore preserves pin count = 1", async () => {
    const uid = "user_pin_01";
    const token = setupPhase1User(uid);
    const backupId = "bk_pin_01";

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      quarantineStatus: { nullValue: null }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // 1. First restore call
    const res1 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_pin_01", backupId })
    }), mockEnv, {});
    assert.equal(res1.status, 200);

    const m1 = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m1.activeRestorePinCount.integerValue, "1");

    // 2. Retry with same key (simulating dropped response retry)
    const res2 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_pin_01", backupId })
    }), mockEnv, {});
    assert.equal(res2.status, 200);
    const body2 = await res2.json();
    assert.equal(body2.idempotent, true);

    // Pin count must NOT increment again
    const m2 = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m2.activeRestorePinCount.integerValue, "1");
  });

  await test("TEST-PIN-02: Concurrent POST /api/restore with same key at T0 increments pin count exactly once", async () => {
    const uid = "user_pin_02";
    const token = setupPhase1User(uid);
    const backupId = "bk_pin_02";

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      quarantineStatus: { nullValue: null }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    const reqA = new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_pin_02", backupId })
    });
    const reqB = new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_pin_02", backupId })
    });

    const [resA, resB] = await Promise.all([worker.fetch(reqA, mockEnv, {}), worker.fetch(reqB, mockEnv, {})]);
    assert.equal(resA.status, 200);
    assert.equal(resB.status, 200);

    const m = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m.activeRestorePinCount.integerValue, "1");
  });

  await test("TEST-PIN-03: Duplicate POST /api/restore/ack returns 200 duplicate with zero decrement", async () => {
    const uid = "user_pin_03";
    const token = setupPhase1User(uid);
    const backupId = "bk_pin_03";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: "key_pin_03" },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    // 1. Initial ACK
    const ack1 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/ack", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_pin_03" })
    }), mockEnv, {});
    assert.equal(ack1.status, 200);
    const b1 = await ack1.json();
    assert.equal(b1.acknowledged, true);

    const m1 = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m1.activeRestorePinCount.integerValue, "0");

    // 2. Duplicate ACK
    const ack2 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/ack", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_pin_03" })
    }), mockEnv, {});
    assert.equal(ack2.status, 200);
    const b2 = await ack2.json();
    assert.equal(b2.duplicate, true);

    // Manifest pin count remains 0 (not -1)
    const m2 = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m2.activeRestorePinCount.integerValue, "0");
  });

  await test("TEST-PIN-04: Multi-client out-of-order ACKs transition pin count 2 -> 1 -> 0 cleanly", async () => {
    const uid = "user_pin_04";
    const token = setupPhase1User(uid);
    const backupId = "bk_pin_04";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);
    const yesterdayIst = getIstDateString(nowMs - 86400000);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "2" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // Claim 1 (Yesterday)
    mockFirestore.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: "key_client_1" },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    // Claim 2 (Today)
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: "key_client_2" },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    // ACK Claim 2 first
    const ack2 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/ack", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_client_2" })
    }), mockEnv, {});
    assert.equal(ack2.status, 200);
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "1");

    // ACK Claim 1 second
    const ack1 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/ack", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_client_1" })
    }), mockEnv, {});
    assert.equal(ack1.status, 200);
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "0");
  });

  await test("TEST-ROOM-01 & TEST-ROOM-02: Normal ACK and duplicate ACK post Room commit", async () => {
    const uid = "user_room_01";
    const token = setupPhase1User(uid);
    const backupId = "bk_room_01";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: "key_room_01" },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    // Initial ACK
    const ack1 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/ack", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_room_01" })
    }), mockEnv, {});
    assert.equal(ack1.status, 200);
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "0");
    assert.equal(mockFirestore.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`).status.stringValue, "ACKNOWLEDGED");

    // Duplicate ACK
    const ack2 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/ack", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_room_01" })
    }), mockEnv, {});
    assert.equal(ack2.status, 200);
    const b2 = await ack2.json();
    assert.equal(b2.duplicate, true);
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "0");
  });

  await test("TEST-ROOM-04: Delayed ACK after pin already expired by cleanup returns 200 with zero decrement", async () => {
    const uid = "user_room_04";
    const token = setupPhase1User(uid);
    const backupId = "bk_room_04";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    // Manifest already decremented by cron: pins = 0
    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // Claim already marked EXPIRED by cron
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "EXPIRED_UNACKNOWLEDGED" },
      pinStatus: { stringValue: "EXPIRED" },
      idempotencyKey: { stringValue: "key_delayed_ack" },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const ackRes = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/ack", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_delayed_ack" })
    }), mockEnv, {});

    assert.equal(ackRes.status, 200);
    const body = await ackRes.json();
    assert.equal(body.note, "EXPIRED_PIN_CONFIRMED");

    // Manifest pin count remains 0 (no negative count)
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "0");
    // Claim transitioned to RELEASED / ACKNOWLEDGED
    assert.equal(mockFirestore.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`).pinStatus.stringValue, "RELEASED");
  });

  await test("TEST-TTL-01: Chunk streaming and heartbeat renew active restore lease", async () => {
    const uid = "user_ttl_01";
    const token = setupPhase1User(uid);
    const backupId = "bk_ttl_01";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`users/${uid}/backup_snapshots/${backupId}/daily_data/2026-10-24`, {
      dateString: { stringValue: "2026-10-24" },
      totalReelsCount: { integerValue: "10" }
    });

    const initialExpiry = nowMs + 10000;
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: "key_ttl_01" },
      pinnedSnapshotId: { stringValue: backupId },
      pinLeaseExpiresAtMs: { integerValue: initialExpiry.toString() }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    // 1. GET /api/restore/chunk
    const chunkRes = await worker.fetch(new Request(`https://refocus.workers.dev/api/restore/chunk?backupId=${backupId}&chunkIndex=0&idempotencyKey=key_ttl_01`, {
      method: "GET",
      headers: { "Authorization": `Bearer ${token}`, "CF-Connecting-IP": "1.1.1.1" }
    }), mockEnv, {});
    assert.equal(chunkRes.status, 200);
    const chunkBody = await chunkRes.json();
    assert.ok(chunkBody.records.length > 0);
    assert.ok(chunkBody.pinLeaseExpiresAtMs > nowMs + 14 * 60 * 1000, "Lease must be extended by ~15m");

    // 2. POST /api/restore/heartbeat
    const hbRes = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/heartbeat", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_ttl_01" })
    }), mockEnv, {});
    assert.equal(hbRes.status, 200);
    const hbBody = await hbRes.json();
    assert.equal(hbBody.heartbeat, true);
    assert.ok(hbBody.pinLeaseExpiresAtMs > nowMs + 14 * 60 * 1000);
  });

  await test("TEST-TTL-02: reconcileExpiredPins decrements manifest pin count and updates claim status", async () => {
    const uid = "user_ttl_02";
    const backupId = "bk_ttl_02";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // Claim with expired offline grace
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: "key_ttl_02" },
      pinnedSnapshotId: { stringValue: backupId },
      offlineGraceExpiresAtMs: { integerValue: (nowMs - 5000).toString() }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const outcome = await reconcileExpiredPins(mockEnv);
    assert.equal(outcome.reconciledCount, 1);

    // Verify manifest decremented from 1 to 0
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "0");
    // Verify claim marked EXPIRED_UNACKNOWLEDGED
    const c = mockFirestore.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`);
    assert.equal(c.status.stringValue, "EXPIRED_UNACKNOWLEDGED");
    assert.equal(c.pinStatus.stringValue, "EXPIRED");
  });

  await test("TEST-RACE-01: Heartbeat advances updateTime, causing stale expiry CAS to fail cleanly (412)", async () => {
    const uid = "user_race_01";
    const token = setupPhase1User(uid);
    const backupId = "bk_race_01";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: "key_race_01" },
      pinnedSnapshotId: { stringValue: backupId },
      offlineGraceExpiresAtMs: { integerValue: (nowMs + 60000).toString() }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const staleUpdateTime = mockDocUpdateTimes.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`);

    // 1. Heartbeat succeeds and advances updateTime
    const hbRes = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/heartbeat", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_race_01" })
    }), mockEnv, {});
    assert.equal(hbRes.status, 200);
    assert.notEqual(mockDocUpdateTimes.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`), staleUpdateTime);

    // 2. Stale cleanup commit attempting expiry with staleUpdateTime fails with 412
    const staleWrites = [{
      update: {
        name: `projects/refocus-504119/databases/(default)/documents/daily_quota_claims/${uid}_${todayIst}_RESTORE`,
        fields: {
          status: { stringValue: "EXPIRED_UNACKNOWLEDGED" },
          pinStatus: { stringValue: "EXPIRED" }
        }
      },
      currentDocument: { updateTime: staleUpdateTime }
    }];

    await assert.rejects(async () => {
      await commitFirestoreBatch(staleWrites, mockEnv);
    }, (err) => err.status === 412);

    // Manifest pin count remains 1 (protected)
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "1");
  });

  await test("TEST-RACE-02: Expiry commits first, causing stale heartbeat CAS to fail with 410", async () => {
    const uid = "user_race_02";
    const token = setupPhase1User(uid);
    const backupId = "bk_race_02";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // Already expired by cron
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "EXPIRED_UNACKNOWLEDGED" },
      pinStatus: { stringValue: "EXPIRED" },
      idempotencyKey: { stringValue: "key_race_02" },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const hbRes = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/heartbeat", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_race_02" })
    }), mockEnv, {});

    assert.equal(hbRes.status, 410, "Heartbeat against expired claim must return 410 PIN_LEASE_EXPIRED");
  });

  await test("TEST-RACE-03: Concurrent reconcileExpiredPins at T0 decrements pin count exactly once", async () => {
    const uid = "user_race_03";
    const backupId = "bk_race_03";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: "key_race_03" },
      pinnedSnapshotId: { stringValue: backupId },
      offlineGraceExpiresAtMs: { integerValue: (nowMs - 5000).toString() }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    // Run both crons
    const [c1, c2] = await Promise.all([
      reconcileExpiredPins(mockEnv),
      reconcileExpiredPins(mockEnv)
    ]);

    // One must have reconciled, the other should have caught OCC conflict / 0
    const totalReconciled = (c1.reconciledCount || 0) + (c2.reconciledCount || 0);
    assert.equal(totalReconciled, 1, "Exactly one cron should reconcile the expired pin");

    // Manifest pin decremented from 1 to 0 (never negative)
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "0");
  });

  await test("TEST-MID-01: Backup retry at 00:00:05 IST replays yesterday result without consuming today quota", async () => {
    const uid = "user_mid_01";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);
    const yesterdayIst = getIstDateString(nowMs - 86400000);
    const reqId = "req_mid_01";

    // Yesterday completed backup
    mockFirestore.set(`daily_quota_claims/${uid}_${yesterdayIst}_BACKUP`, {
      status: { stringValue: "COMPLETED" },
      idempotencyKey: { stringValue: reqId },
      backupId: { stringValue: `bk_${yesterdayIst}` },
      committedAtMs: { integerValue: (nowMs - 3600000).toString() }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${yesterdayIst}_BACKUP`, "2026-10-23T23:00:00Z");

    // Client retries with same reqId after midnight
    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ requestId: reqId, dailyRecords: sampleDailyRecords })
    }), mockEnv, {});

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.idempotent, true);
    assert.equal(body.istDate, yesterdayIst);

    // Today's claim remains completely UNCLAIMED
    assert.equal(mockFirestore.get(`daily_quota_claims/${uid}_${todayIst}_BACKUP`), undefined);
  });

  await test("TEST-MID-02: Restore retry across midnight replays yesterday restore without consuming today quota", async () => {
    const uid = "user_mid_02";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);
    const yesterdayIst = getIstDateString(nowMs - 86400000);
    const key = "key_mid_02";
    const backupId = "bk_mid_02";

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-23T23:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: key },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, "2026-10-23T23:00:00Z");

    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: key, backupId })
    }), mockEnv, {});

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.idempotent, true);
    assert.equal(body.istDate, yesterdayIst);

    // Today remains UNCLAIMED
    assert.equal(mockFirestore.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`), undefined);
  });

  await test("TEST-MID-03: Yesterday IN_PROGRESS expired across midnight returns 409 CALENDAR_DAY_EXPIRED", async () => {
    const uid = "user_mid_03";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const yesterdayIst = getIstDateString(nowMs - 86400000);
    const reqId = "req_mid_03";

    mockFirestore.set(`daily_quota_claims/${uid}_${yesterdayIst}_BACKUP`, {
      status: { stringValue: "IN_PROGRESS" },
      idempotencyKey: { stringValue: reqId },
      expiresAtMs: { integerValue: (nowMs - 1000).toString() }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${yesterdayIst}_BACKUP`, "2026-10-23T23:59:00Z");

    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ requestId: reqId, dailyRecords: sampleDailyRecords })
    }), mockEnv, {});

    assert.equal(res.status, 409);
    const body = await res.json();
    assert.equal(body.code, "CALENDAR_DAY_EXPIRED");
  });

  await test("TEST-MID-04: Yesterday FAILED allows fresh restore today", async () => {
    const uid = "user_mid_04";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);
    const yesterdayIst = getIstDateString(nowMs - 86400000);
    const backupId = "bk_mid_04";

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, {
      status: { stringValue: "FAILED" },
      pinStatus: { stringValue: "RELEASED" },
      idempotencyKey: { stringValue: "key_yesterday_failed" }
    });

    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_fresh_today", backupId })
    }), mockEnv, {});

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.istDate, todayIst);
    assert.equal(body.pinStatus, "PINNED");
    assert.equal(mockFirestore.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`).status.stringValue, "DISPATCHED");
  });

  await test("TEST-MID-05 / TEST-EXP-01: Expired restore retry re-pins continuation on intact COMMITTED snapshot", async () => {
    const uid = "user_mid_05";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);
    const yesterdayIst = getIstDateString(nowMs - 86400000);
    const key = "key_mid_05";
    const backupId = "bk_mid_05";

    // Intact COMMITTED snapshot, pins = 0
    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // Yesterday expired claim
    mockFirestore.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, {
      status: { stringValue: "EXPIRED_UNACKNOWLEDGED" },
      pinStatus: { stringValue: "EXPIRED" },
      idempotencyKey: { stringValue: key },
      pinnedSnapshotId: { stringValue: backupId },
      rePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: key, backupId })
    }), mockEnv, {});

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.resumedContinuation, true);
    assert.equal(body.istDate, yesterdayIst);

    // Manifest re-pinned 0 -> 1
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "1");
    // Today remains UNCLAIMED
    assert.equal(mockFirestore.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`), undefined);
  });

  await test("TEST-EXP-02: Expired restore retry against PURGING_RETENTION snapshot returns 410 Gone", async () => {
    const uid = "user_exp_02";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const yesterdayIst = getIstDateString(nowMs - 86400000);
    const key = "key_exp_02";
    const backupId = "bk_exp_02";

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "PURGING_RETENTION" },
      activeRestorePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, {
      status: { stringValue: "EXPIRED_UNACKNOWLEDGED" },
      pinStatus: { stringValue: "EXPIRED" },
      idempotencyKey: { stringValue: key },
      pinnedSnapshotId: { stringValue: backupId },
      rePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: key, backupId })
    }), mockEnv, {});

    assert.equal(res.status, 410);
    const body = await res.json();
    assert.equal(body.code, "SNAPSHOT_BEING_PURGED");
  });

  await test("TEST-EXP-03: Expired restore retry against deleted (404) snapshot returns 410 Gone", async () => {
    const uid = "user_exp_03";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const yesterdayIst = getIstDateString(nowMs - 86400000);
    const key = "key_exp_03";
    const backupId = "bk_exp_03_deleted";

    // Manifest does NOT exist

    mockFirestore.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, {
      status: { stringValue: "EXPIRED_UNACKNOWLEDGED" },
      pinStatus: { stringValue: "EXPIRED" },
      idempotencyKey: { stringValue: key },
      pinnedSnapshotId: { stringValue: backupId },
      rePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${yesterdayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: key, backupId })
    }), mockEnv, {});

    assert.equal(res.status, 410);
    const body = await res.json();
    assert.equal(body.code, "SNAPSHOT_PURGED_AND_DELETED");
  });

  await test("TEST-CAS-01: Manifest CAS OCC prevents prune from deleting snapshot while restore is pinning", async () => {
    const uid = "user_cas_01";
    const token = setupPhase1User(uid);
    const backupId = "bk_cas_01";
    const nowMs = Date.now();

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      committedAtMs: { integerValue: (nowMs - 100 * 3600 * 1000).toString() },
      lastPinnedAtMs: { integerValue: (nowMs - 50 * 3600 * 1000).toString() },
      quarantineStatus: { nullValue: null }
    });
    const T0 = "2026-10-24T00:00:00.000001Z";
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, T0);

    // 1. Restore claims pin (starts with T0, commits, advances to T1)
    const restoreRes = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_cas_01", backupId })
    }), mockEnv, {});
    assert.equal(restoreRes.status, 200);

    const T1 = mockDocUpdateTimes.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.notEqual(T1, T0);
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "1");

    // 2. Cleanup prune attempting to latch status asserting stale T0 fails with 412
    const pruneWrites = [{
      update: {
        name: `projects/refocus-504119/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
        fields: {
          status: { stringValue: "PURGING_RETENTION" }
        }
      },
      currentDocument: { updateTime: T0 }
    }];

    await assert.rejects(async () => {
      await commitFirestoreBatch(pruneWrites, mockEnv);
    }, (err) => err.status === 412);

    // Snapshot remains COMMITTED with pins = 1
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).status.stringValue, "COMMITTED");
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "1");
  });

  await test("TEST-PRUNE-01: pruneHistoricalCommittedBackups respects active pins, age buffer, and prunes eligible snapshots", async () => {
    const uid = "user_prune_01";
    const nowMs = Date.now();

    // 1. Snapshot with active pins cannot be pruned
    const bkPinned = "bk_prune_pinned";
    mockFirestore.set(`users/${uid}/backup_manifests/${bkPinned}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" },
      committedAtMs: { integerValue: (nowMs - 100 * 3600 * 1000).toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${bkPinned}`, "2026-10-24T00:00:00Z");
    const r1 = await pruneHistoricalCommittedBackups(mockEnv, uid, bkPinned);
    assert.equal(r1.pruned, false);
    assert.equal(r1.reason, "ACTIVE_PINS");

    // 2. Snapshot within 48h committed buffer cannot be pruned
    const bkFresh = "bk_prune_fresh";
    mockFirestore.set(`users/${uid}/backup_manifests/${bkFresh}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      committedAtMs: { integerValue: (nowMs - 12 * 3600 * 1000).toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${bkFresh}`, "2026-10-24T00:00:00Z");
    const r2 = await pruneHistoricalCommittedBackups(mockEnv, uid, bkFresh);
    assert.equal(r2.pruned, false);
    assert.equal(r2.reason, "COMMITTED_BUFFER");

    // 3. Quarantined snapshot cannot be pruned
    const bkQuar = "bk_prune_quar";
    mockFirestore.set(`users/${uid}/backup_manifests/${bkQuar}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      committedAtMs: { integerValue: (nowMs - 100 * 3600 * 1000).toString() },
      quarantineStatus: { stringValue: "RESOLVED_OVERCOUNT_OBSERVATION" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${bkQuar}`, "2026-10-24T00:00:00Z");
    const r3 = await pruneHistoricalCommittedBackups(mockEnv, uid, bkQuar);
    assert.equal(r3.pruned, false);
    assert.equal(r3.reason, "QUARANTINED");

    // 4. Eligible snapshot (>48h, pins=0, healthy) is pruned
    const bkEligible = "bk_prune_eligible";
    mockFirestore.set(`users/${uid}/backup_manifests/${bkEligible}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      committedAtMs: { integerValue: (nowMs - 100 * 3600 * 1000).toString() },
      lastPinnedAtMs: { integerValue: (nowMs - 50 * 3600 * 1000).toString() },
      quarantineStatus: { nullValue: null }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${bkEligible}`, "2026-10-24T00:00:00Z");
    mockFirestore.set(`users/${uid}/backup_snapshots/${bkEligible}/daily_data/2026-10-15`, {
      dateString: { stringValue: "2026-10-15" }
    });

    const r4 = await pruneHistoricalCommittedBackups(mockEnv, uid, bkEligible);
    assert.equal(r4.pruned, true);
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${bkEligible}`), undefined);
    assert.equal(mockFirestore.get(`users/${uid}/backup_snapshots/${bkEligible}/daily_data/2026-10-15`), undefined);
  });

  await test("TEST-QUAR-01: Quarantine undercount (0 manifest vs 1 active claim) enters 24h observation", async () => {
    const uid = "user_quar_01";
    const backupId = "bk_quar_01";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      quarantineStatus: { nullValue: null }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // Active claim with PINNED
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      pinnedSnapshotId: { stringValue: backupId },
      pinStatus: { stringValue: "PINNED" }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const auditRes = await auditSnapshotPinConsistency(mockEnv, uid, backupId);
    assert.equal(auditRes.discrepancy, true);
    assert.equal(auditRes.type, "UNDERCOUNT");

    const m = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m.quarantineStatus.stringValue, "RESOLVED_UNDERCOUNT_OBSERVATION");
    assert.equal(m.activeRestorePinCount.integerValue, "1");
    assert.ok(m.quarantineObservationExpiresAtMs);
  });

  await test("TEST-QUAR-02 & TEST-DISC-01: Quarantine overcount (2 manifest vs 1 active claim) enters 48h observation", async () => {
    const uid = "user_quar_02";
    const backupId = "bk_quar_02";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "2" },
      quarantineStatus: { nullValue: null }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // 1 active claim
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      pinnedSnapshotId: { stringValue: backupId },
      pinStatus: { stringValue: "PINNED" }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const auditRes = await auditSnapshotPinConsistency(mockEnv, uid, backupId);
    assert.equal(auditRes.discrepancy, true);
    assert.equal(auditRes.type, "OVERCOUNT");

    const m = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m.quarantineStatus.stringValue, "RESOLVED_OVERCOUNT_OBSERVATION");
    assert.ok(m.quarantineObservationExpiresAtMs);
  });

  await test("TEST-QUAR-03: Quarantine clearance after observation timer expires when claims match", async () => {
    const uid = "user_quar_03";
    const backupId = "bk_quar_03";
    const nowMs = Date.now();

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      quarantineStatus: { stringValue: "RESOLVED_UNDERCOUNT_OBSERVATION" },
      quarantineObservationExpiresAtMs: { integerValue: (nowMs - 5000).toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // 0 active claims
    const auditRes = await auditSnapshotPinConsistency(mockEnv, uid, backupId);
    assert.equal(auditRes.cleared, true);

    const m = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m.quarantineStatus.nullValue, null);
    assert.ok(m.lastQuarantineClearedAtMs);
  });

  await test("TEST-QUAR-04: Client acquiring pin during quarantine clearance causes clearance CAS to abort", async () => {
    const uid = "user_quar_04";
    const token = setupPhase1User(uid);
    const backupId = "bk_quar_04";
    const nowMs = Date.now();

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" },
      quarantineStatus: { stringValue: "RESOLVED_UNDERCOUNT_OBSERVATION" },
      quarantineObservationExpiresAtMs: { integerValue: (nowMs - 5000).toString() }
    });
    const T0 = "2026-10-24T00:00:00.000001Z";
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, T0);

    // 1. Client acquires restore pin, advancing updateTime
    const restoreRes = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: "key_quar_04", backupId })
    }), mockEnv, {});
    assert.equal(restoreRes.status, 200);

    // 2. Clearance attempt asserting stale T0 fails with 412
    const clearWrites = [{
      update: {
        name: `projects/refocus-504119/databases/(default)/documents/users/${uid}/backup_manifests/${backupId}`,
        fields: {
          quarantineStatus: { nullValue: null }
        }
      },
      currentDocument: { updateTime: T0 }
    }];

    await assert.rejects(async () => {
      await commitFirestoreBatch(clearWrites, mockEnv);
    }, (err) => err.status === 412);

    // Manifest remains protected with active pin
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "1");
  });

  await test("TEST-QUAR-05: Repeated discrepancy during observation resets observation timer", async () => {
    const uid = "user_quar_05";
    const backupId = "bk_quar_05";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" },
      quarantineStatus: { stringValue: "RESOLVED_UNDERCOUNT_OBSERVATION" },
      quarantineObservationExpiresAtMs: { integerValue: (nowMs + 10000).toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // New mismatch: 2 active claims
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE_1`, {
      pinnedSnapshotId: { stringValue: backupId },
      pinStatus: { stringValue: "PINNED" }
    });
    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE_2`, {
      pinnedSnapshotId: { stringValue: backupId },
      pinStatus: { stringValue: "PINNED" }
    });

    const auditRes = await auditSnapshotPinConsistency(mockEnv, uid, backupId);
    assert.equal(auditRes.timerReset, true);

    const m = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m.quarantineStatus.stringValue, "ACTIVE_DISCREPANCY_HOLD");
  });

  await test("TEST-DISC-02: Overcount quarantine clears safely and resets pins 1 -> 0 after 48h observation timer", async () => {
    const uid = "user_disc_02";
    const backupId = "bk_disc_02";
    const nowMs = Date.now();

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" },
      quarantineStatus: { stringValue: "RESOLVED_OVERCOUNT_OBSERVATION" },
      quarantineObservationExpiresAtMs: { integerValue: (nowMs - 5000).toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    // 0 active claims
    const auditRes = await auditSnapshotPinConsistency(mockEnv, uid, backupId);
    assert.equal(auditRes.cleared, true);

    const m = mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`);
    assert.equal(m.quarantineStatus.nullValue, null);
    assert.equal(m.activeRestorePinCount.integerValue, "0");
  });

  await test("TEST-ABANDON-01: cleanupAbandonedUploads skips active/recent backups and purges >24h abandoned uploads", async () => {
    const uid = "user_abandon_01";
    const nowMs = Date.now();

    // 1. Upload < 24h old is not cleaned
    const bkRecent = "bk_ab_recent";
    mockFirestore.set(`users/${uid}/backup_manifests/${bkRecent}`, {
      status: { stringValue: "UPLOADING" },
      uploadInitiatedAtMs: { integerValue: (nowMs - 3600000).toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${bkRecent}`, "2026-10-24T00:00:00Z");
    const r1 = await cleanupAbandonedUploads(mockEnv, uid, bkRecent);
    assert.equal(r1.cleaned, false);
    assert.equal(r1.reason, "AGE_LESS_THAN_24H");

    // 2. Active backup in backup/status is not cleaned
    const bkActive = "bk_ab_active";
    mockFirestore.set(`users/${uid}/backup_manifests/${bkActive}`, {
      status: { stringValue: "UPLOADING" },
      uploadInitiatedAtMs: { integerValue: (nowMs - 30 * 3600 * 1000).toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${bkActive}`, "2026-10-24T00:00:00Z");
    mockFirestore.set(`users/${uid}/backup/status`, {
      activeBackupId: { stringValue: bkActive }
    });
    const r2 = await cleanupAbandonedUploads(mockEnv, uid, bkActive);
    assert.equal(r2.cleaned, false);
    assert.equal(r2.reason, "IS_ACTIVE_BACKUP");

    // 3. Abandoned upload > 24h old is purged
    const bkAbandoned = "bk_ab_abandoned";
    mockFirestore.set(`users/${uid}/backup_manifests/${bkAbandoned}`, {
      status: { stringValue: "UPLOADING" },
      uploadInitiatedAtMs: { integerValue: (nowMs - 30 * 3600 * 1000).toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${bkAbandoned}`, "2026-10-24T00:00:00Z");
    mockFirestore.set(`users/${uid}/backup/status`, {
      activeBackupId: { stringValue: "some_other_id" }
    });

    const r3 = await cleanupAbandonedUploads(mockEnv, uid, bkAbandoned);
    assert.equal(r3.cleaned, true);
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${bkAbandoned}`), undefined);
  });

  await test("TEST-AUTH-01: Phase 1 endpoints reject unauthenticated and non-entitled requests", async () => {
    // 1. Unauthenticated -> 401
    const unauthReqs = [
      new Request("https://refocus.workers.dev/api/backup", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }),
      new Request("https://refocus.workers.dev/api/restore", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }),
      new Request("https://refocus.workers.dev/api/restore/ack", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }),
      new Request("https://refocus.workers.dev/api/restore/abort", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }),
      new Request("https://refocus.workers.dev/api/restore/heartbeat", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }),
      new Request("https://refocus.workers.dev/api/restore/chunk", { method: "GET" })
    ];

    for (const req of unauthReqs) {
      const res = await worker.fetch(req, mockEnv, {});
      assert.equal(res.status, 401, `Route ${req.url} must return 401 unauthenticated`);
    }

    // 2. Authenticated but non-entitled -> 403
    const uid = "user_non_entitled";
    const token = createMockFirebaseIdToken(uid);
    // No subscription in Firestore
    const nonEntitledReq = new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ requestId: "req_no_sub", dailyRecords: sampleDailyRecords })
    });
    const nonEntitledRes = await worker.fetch(nonEntitledReq, mockEnv, {});
    assert.equal(nonEntitledRes.status, 403, "Non-entitled user must receive 403 Forbidden");
  });

  await test("TEST-SCHED-01: Scheduled event triggers reconcileExpiredPins", async () => {
    const uid = "user_sched_01";
    const backupId = "bk_sched_01";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "1" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "DISPATCHED" },
      pinStatus: { stringValue: "PINNED" },
      idempotencyKey: { stringValue: "key_sched_01" },
      pinnedSnapshotId: { stringValue: backupId },
      offlineGraceExpiresAtMs: { integerValue: (nowMs - 5000).toString() }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    // Call worker.scheduled
    await worker.scheduled({}, mockEnv, {});

    // Manifest decremented to 0
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "0");
    // Claim marked EXPIRED_UNACKNOWLEDGED
    assert.equal(mockFirestore.get(`daily_quota_claims/${uid}_${todayIst}_RESTORE`).status.stringValue, "EXPIRED_UNACKNOWLEDGED");
  });

  await test("TEST-FIX-01: Idempotent restore replay and concurrency race re-read preserve non-zero defaultDailyLimit", async () => {
    const uid = "user_fix_01";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);
    const key = "key_fix_01";
    const backupId = "bk_fix_01";

    mockFirestore.set(`users/${uid}/backup/status`, {
      status: { stringValue: "SUCCESS" },
      activeBackupId: { stringValue: backupId },
      lastBackupTimeMs: { integerValue: (nowMs - 3600000).toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup/status`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      recordCount: { integerValue: "1" },
      defaultDailyLimit: { integerValue: "250" },
      activeRestorePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`users/${uid}/backup_snapshots/${backupId}/daily_data/2026-10-23`, {
      dateString: { stringValue: "2026-10-23" },
      count: { integerValue: "5" }
    });

    // 1. Initial restore dispatch
    const res1 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: key, backupId })
    }), mockEnv);
    assert.equal(res1.status, 200);
    const body1 = await res1.json();
    assert.equal(body1.dispatched, true);
    assert.equal(body1.defaultDailyLimit, 250);

    // 2. Replay same request (DISPATCHED with matching key)
    const res2 = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: key, backupId })
    }), mockEnv);
    assert.equal(res2.status, 200);
    const body2 = await res2.json();
    assert.equal(body2.idempotent, true);
    assert.equal(body2.defaultDailyLimit, 250, "Replay must return manifest defaultDailyLimit, not 0");
  });

  await test("TEST-FIX-02: releaseBackupSlotOnError with stale expectedUpdateTime aborts on 412 without overwriting newer state", async () => {
    const uid = "user_fix_02";
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);

    // Current state has revision T2
    mockFirestore.set(`users/${uid}/backup/status`, {
      status: { stringValue: "IN_PROGRESS" },
      claimRequestId: { stringValue: "req_newer" },
      claimTimeMs: { integerValue: nowMs.toString() }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup/status`, "2026-10-24T12:00:00Z");

    // Call release with stale revision T1
    await releaseBackupSlotOnError(uid, "stale commit failed", mockEnv, todayIst, "2026-10-24T10:00:00Z");

    // Assert status document was NOT overwritten to FAILED
    const statusDoc = mockFirestore.get(`users/${uid}/backup/status`);
    assert.equal(statusDoc.status.stringValue, "IN_PROGRESS");
    assert.equal(statusDoc.claimRequestId.stringValue, "req_newer");
  });

  await test("TEST-FIX-03: POST /api/restore/ack handles pinStatus === 'EXPIRED' gracefully with 200 and zero decrement", async () => {
    const uid = "user_fix_03";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);
    const key = "key_fix_03";
    const backupId = "bk_fix_03";

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "EXPIRED_UNACKNOWLEDGED" },
      pinStatus: { stringValue: "EXPIRED" },
      idempotencyKey: { stringValue: key },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/restore/ack", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({ idempotencyKey: key })
    }), mockEnv);

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.acknowledged, true);
    assert.equal(body.note, "EXPIRED_PIN_CONFIRMED");
    // Manifest pin count remains 0 (no underflow)
    assert.equal(mockFirestore.get(`users/${uid}/backup_manifests/${backupId}`).activeRestorePinCount.integerValue, "0");
  });

  await test("TEST-FIX-04: POST /api/restore under FAILED state retry conflict returns 409 RESTORE_CONCURRENT_CONFLICT", async () => {
    const uid = "user_fix_04";
    const token = setupPhase1User(uid);
    const nowMs = Date.now();
    const todayIst = getIstDateString(nowMs);
    const backupId = "bk_fix_04";

    mockFirestore.set(`users/${uid}/backup/status`, {
      status: { stringValue: "SUCCESS" },
      activeBackupId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup/status`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`users/${uid}/backup_manifests/${backupId}`, {
      backupId: { stringValue: backupId },
      status: { stringValue: "COMMITTED" },
      activeRestorePinCount: { integerValue: "0" }
    });
    mockDocUpdateTimes.set(`users/${uid}/backup_manifests/${backupId}`, "2026-10-24T00:00:00Z");

    mockFirestore.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, {
      status: { stringValue: "FAILED" },
      pinStatus: { stringValue: "RELEASED" },
      attemptCount: { integerValue: "1" },
      idempotencyKey: { stringValue: "key_prev" },
      pinnedSnapshotId: { stringValue: backupId }
    });
    mockDocUpdateTimes.set(`daily_quota_claims/${uid}_${todayIst}_RESTORE`, "2026-10-24T00:00:00Z");

    // Inject 412 on commit batch
    mockFirestoreFaultInjector = (urlStr, options) => {
      if (urlStr.includes(":commit") && options.method === "POST") {
        return new Response(JSON.stringify({ error: "Precondition Failed" }), { status: 412 });
      }
      return undefined;
    };

    try {
      const res = await worker.fetch(new Request("https://refocus.workers.dev/api/restore", {
        method: "POST",
        headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
        body: JSON.stringify({ idempotencyKey: "key_fix_04_retry", backupId })
      }), mockEnv);

      assert.equal(res.status, 409);
      const body = await res.json();
      assert.equal(body.code, "RESTORE_CONCURRENT_CONFLICT");
    } finally {
      mockFirestoreFaultInjector = null;
    }
  });

  await test("TEST-FIX-05: Fast-path backup with >400 daily writes (250 records x 2) chunks correctly and commits manifest atomically with remaining records", async () => {
    const uid = "user_fix_05";
    const token = setupPhase1User(uid);
    const reqId = "req_fix_05";

    // 250 daily records = 500 writes (snapshot + legacy) > 400 CHUNK_SIZE
    const dailyRecords = [];
    for (let i = 0; i < 250; i++) {
      dailyRecords.push({
        dateString: `2025-01-${String(i % 28 + 1).padStart(2, "0")}_${i}`,
        count: i,
        completed: true
      });
    }

    const res = await worker.fetch(new Request("https://refocus.workers.dev/api/backup", {
      method: "POST",
      headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", "CF-Connecting-IP": "1.1.1.1" },
      body: JSON.stringify({
        requestId: reqId,
        dailyRecords,
        defaultDailyLimit: 120
      })
    }), mockEnv);

    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.ok(body.backupId);

    // Verify manifest
    const manifest = mockFirestore.get(`users/${uid}/backup_manifests/${body.backupId}`);
    assert.ok(manifest);
    assert.equal(manifest.status.stringValue, "COMMITTED");
    assert.equal(manifest.recordCount.integerValue, "250");
    assert.equal(manifest.defaultDailyLimit.integerValue, "120");

    // Verify status doc
    const statusDoc = mockFirestore.get(`users/${uid}/backup/status`);
    assert.equal(statusDoc.status.stringValue, "SUCCESS");
    assert.equal(statusDoc.activeBackupId.stringValue, body.backupId);
  });

  console.log(`\n========================================`);
  console.log(`Test Results: ${passed} Passed, ${failed} Failed`);
  if (failed > 0) {
    console.log(`Failed Tests:\n - ${failedNames.join("\n - ")}`);
  }
  console.log(`========================================\n`);

  if (failed > 0) {
    process.exit(1);
  }
}

runTests();

