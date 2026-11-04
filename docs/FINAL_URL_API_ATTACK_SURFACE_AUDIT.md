# Final URL & API Attack Surface Audit

**Application**: Re:Focus Android Application & Cloudflare Edge Worker  
**Package Name**: `com.reeltrak.app`  
**Worker Domain**: `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`  
**Audit Date**: September 22, 2026  
**Scope**: All Android network callers, Intent filters, and Cloudflare Worker HTTP REST routes.

---

## PART 1 — Android URL & Network Endpoint Inventory

| Endpoint / URL | Purpose / Target | Classification | Protocol | Dynamic Input / Parameter Risks |
| :--- | :--- | :---: | :---: | :---: |
| `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/time` | Authoritative time sync pre-login | Production (Worker) | HTTPS | Echo header `X-Client-Timestamp` safely parsed; no injection risk. |
| `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/ensure-subscription` | 7-Day trial initialization | Production (Worker) | HTTPS | Authenticated via Firebase ID token; UID derived from token. |
| `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/create-subscription` | Razorpay recurring mandate creation | Production (Worker) | HTTPS | Request body `planId` validated against server plan allowlist. |
| `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/verify-subscription` | Server mandate verification | Production (Worker) | HTTPS | Sub ID / Pay ID / Sig verified against HMAC and Razorpay API. |
| `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/cancel-subscription` | End-of-cycle cancellation | Production (Worker) | HTTPS | Sub ID retrieved from server Firestore doc (not client param). |
| `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/account/status` | Account status & lazy purge | Production (Worker) | HTTPS | Authenticated via Firebase ID token; UID derived from token. |
| `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/account/request-deletion` | 7-day soft-delete grace period | Production (Worker) | HTTPS | Authenticated via Firebase ID token; UID derived from token. |
| `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/account/cancel-deletion` | Account restoration | Production (Worker) | HTTPS | Authenticated via Firebase ID token; UID derived from token. |
| `https://refocus-504119.web.app/privacy.html` | Public privacy policy disclosure | Legal / Hosting | HTTPS | Static legal document. |
| `https://refocus-504119.web.app/terms.html` | Public terms of service disclosure | Legal / Hosting | HTTPS | Static legal document. |

### Flaggable Items Check:
- **Localhost / 127.0.0.1 / 10.0.2.2**: **NONE** in release source.
- **HTTP / Cleartext Traffic**: **BLOCKED** globally by `network_security_config.xml`.
- **Arbitrary User-Controlled URLs / WebViews**: **NONE**. Zero WebViews exist in the codebase.

---

## PART 2 — Cloudflare Worker Route Attack Surface Audit

| Route | Auth Req | App Check | UID Source | Rate Limit | Replay Guard | SSRF Protection | IDOR Protection | Result |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `GET /` & `GET /health` | Public | None | N/A | 60 req/min (IP) | N/A | Static response | N/A | **PASS** |
| `GET /api/time` | Public (App Check) | `validateAppCheck` | N/A | 120 req/min (IP) | No state modified | Static clock | N/A | **PASS** |
| `POST /api/ensure-subscription` | Firebase JWT | `validateAppCheck` | Token `sub`/`uid` | 10 req/min (UID) | Idempotent set | Static docUrl | Token-bound UID | **PASS** |
| `POST /api/create-subscription` | Firebase JWT | `validateAppCheck` | Token `sub`/`uid` | 5 req/min (UID) | State-bounded | Fixed Razorpay API | Token-bound UID | **PASS** |
| `POST /api/verify-subscription` | Firebase JWT | `validateAppCheck` | Token `sub`/`uid` | 5 req/min (UID) | HMAC + State | Fixed Razorpay API | Sub notes UID check | **PASS** |
| `POST /api/cancel-subscription` | Firebase JWT | `validateAppCheck` | Token `sub`/`uid` | 5 req/min (UID) | State-bounded | Fixed Razorpay API | Server Firestore lookup | **PASS** |
| `GET /api/account/status` | Firebase JWT | `validateAppCheck` | Token `sub`/`uid` | 30 req/min (UID) | No state modified | Static docUrl | Token-bound UID | **PASS** |
| `POST /api/account/request-deletion`| Firebase JWT | `validateAppCheck` | Token `sub`/`uid` | 5 req/min (UID) | Grace period state | Static docUrl | Token-bound UID | **PASS** |
| `POST /api/account/cancel-deletion` | Firebase JWT | `validateAppCheck` | Token `sub`/`uid` | 5 req/min (UID) | Grace period state | Static docUrl | Token-bound UID | **PASS** |
| `POST /api/razorpay-webhook` | Razorpay HMAC | N/A (Server-to-Server) | Metadata notes | 120 req/min (IP) | `processed_events` + TS | No outbound calls | Notes & Map lookup | **PASS** |

---

## PART 3 — URL Manipulation & Parameter Injection Analysis

- **`userId` / Firebase UID Tampering**: In all authenticated endpoints, the UID is extracted directly from the verified cryptographic payload of the RS256 Firebase ID token (`user.uid`). An attacker cannot supply an arbitrary `userId` in query params or JSON body to view or alter another account.
- **`subscriptionId` Manipulation**: In `POST /api/verify-subscription`, the worker inspects `subscription.notes.firebase_uid` returned from the Razorpay API. If the subscription belongs to another UID, it returns `403 Forbidden`. In `/api/cancel-subscription`, the `subscriptionId` is retrieved directly from the user's Firestore record, preventing attackers from cancelling arbitrary subscriptions.
- **`planId` Tampering**: In `POST /api/create-subscription`, the Worker strictly enforces `planId === env.RAZORPAY_PLAN_ID`. Webhook events with plan mismatches are logged and discarded.
- **CORS & Origin Spoofing**: `getCorsHeaders` strictly tests `Origin` against the explicit allowlist `["https://refocus-app.firebaseapp.com", "https://refocus-504119.web.app"]` and sends `Vary: Origin`. No wildcard `*` is permitted.

---

## PART 4 — SSRF & Open Redirect Review

- **Outbound HTTP Calls (`fetch`)**:
  - `https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com` (Hardcoded)
  - `https://firebaseappcheck.googleapis.com/v1/jwks` (Hardcoded)
  - `https://oauth2.googleapis.com/token` (Hardcoded)
  - `https://firestore.googleapis.com/v1/projects/.../documents/users/${uid}/...` (`uid` strictly validated from JWT)
  - `https://api.razorpay.com/v1/subscriptions/...` (`subId` strictly matched and authorized)
- **Zero Open Redirects**: Zero `redirect(...)` functions or user-controlled `Location` headers exist in the Worker.
- **SSRF Risk**: **ZERO**. No user-supplied URL or host header is ever passed to `fetch()`.

---

## PART 5 — Android Deep Links & Intent Attack Analysis

- **Intent Filters in `AndroidManifest.xml`**:
  - `MainActivity`: Only handles `android.intent.action.MAIN` and `android.intent.category.LAUNCHER`.
  - Zero custom URI schemes (e.g., `refocus://...`) or App Links are registered that could parse untrusted intent parameters or bypass authentication.
- **Accessibility Service (`ReelTrackerService`)**:
  - Protected by `android.permission.BIND_ACCESSIBILITY_SERVICE` (can only be bound by Android OS).

---

## PART 6 — Security Assessment Summary

| Category | Finding | Severity |
| :--- | :--- | :---: |
| **A. Complete URL Inventory** | Verified 10 canonical HTTPS URLs (Worker, Legal, Google APIs). | **PASS** |
| **B. Complete Endpoint Inventory** | 9 functional Worker routes + health check audited and hardened. | **PASS** |
| **C. Attack Vectors Tested** | SSRF, IDOR, CORS spoofing, Replay, Parameter injection, Open Redirects. | **PASS** |
| **D. Confirmed Vulnerabilities** | **NONE** found. | **PASS** |
| **E. Theoretical / Configuration Risks** | Switching App Check mode to `"enforced"` before Google Play App Signing SHA-256 registration would cause user lockout (currently protected in `"audit"` mode). | **LOW (Managed)** |
| **F. Required Remediation** | **NONE**. All input validation, rate limiting, and cryptographic boundaries are solid. | **PASS** |
| **G. Overall URL/API Security Readiness** | **100% READY FOR PRODUCTION & GOOGLE PLAY TESTING** | **PASS** |
