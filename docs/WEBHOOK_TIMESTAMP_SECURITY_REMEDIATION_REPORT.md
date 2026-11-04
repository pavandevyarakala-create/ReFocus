# Webhook Timestamp & Stale-Event Security Remediation Report

**Date:** September 21, 2026  
**Auditor & Implementation Team:** Antigravity Backend Security Subsystem  
**Target:** `worker/src/index.js` and `worker/test_runner.mjs`  
**Status:** **FULLY REMEDIATED & VERIFIED (44/44 Tests Passing)**

---

## 1. Executive Summary

This report documents the security remediation implemented for the webhook timestamp handling and stale-event protection subsystem in the Cloudflare Worker (`worker/src/index.js`). All five confirmed vulnerabilities from [`docs/WEBHOOK_TIMESTAMP_EDGE_CASE_AUDIT.md`](file:///c:/Users/pavan/OneDrive/Documents/REEL/docs/WEBHOOK_TIMESTAMP_EDGE_CASE_AUDIT.md) have been resolved using minimal, robust, fail-closed security logic.

All existing core security capabilities—including HMAC-SHA256 signature verification, exact-once atomic idempotency, UID ownership enforcement, plan binding, and refund immutability—have been preserved.

---

## 2. Summary of Original Vulnerabilities & Exact Fixes

| Vulnerability | Root Cause | Exact Security Remediation |
|---|---|---|
| **1. Non-Uniform Timestamp Extraction** | Webhook handlers passed `entity = null`, ignoring `payload.subscription.entity.updated_at` and `payload.payment.entity.created_at`. | `extractAuthoritativeEventTimeMs` now uniformly inspects subscription, payment, and refund entities across all events before falling back to top-level event timestamps. |
| **2. Future Timestamp Poisoning** | Webhooks with far-future timestamps were accepted and stored in `lastEventTimestamp`, causing all subsequent legitimate events to be permanently rejected as stale. | Enforced a 5-minute maximum future clock-skew threshold (`Date.now() + 300,000ms`). Any candidate exceeding this skew is discarded as unverifiable (`null`) without poisoning database records. |
| **3. Fail-Open on Missing / Malformed Timestamps** | `isEventStale` returned `false` when `incomingEventTimeMs === null`, treating un-timestamped or malformed events as unconditionally fresh and allowing status rollbacks. | `isEventStale` now **fails closed**: if `incomingEventTimeMs` is `null` (missing, NaN, string, $\le 0$, or excessive future skew) on an existing subscription, it is strictly treated as stale/unverifiable and state mutations are rejected. |
| **4. Synthetic 30-Day Entitlement Creation** | `reconcileSubscriptionActivation` defaulted missing `current_start` to `Date.now()` and `current_end` to `Date.now() + 30 days`. | Completely eliminated synthetic period generation. If Razorpay omits valid billing period timestamps (`current_start` / `current_end`) and no stored period exists, the activation fails closed (`success: false`, `status: INCOMPLETE`). |
| **5. 10-Second Negative `updatedAt` Tolerance Boundary** | `incomingEventTimeMs < (storedUpdatedAtMs - 10000)` allowed events emitted up to 10s before a state update (e.g. cancellation) to overwrite newer state. | Replaced with strict ordering: `incomingEventTimeMs <= storedEventTimeMs` (equal timestamps rejected) and `incomingEventTimeMs < storedUpdatedAtMs` (zero tolerance for past events). |

---

## 3. Detailed Architectural Specifications

### 3.1 Deterministic Timestamp Precedence Strategy
`extractAuthoritativeEventTimeMs(entity, eventPayload)` enforces the following deterministic hierarchy:
1. **Explicit Entity Candidate:** `entity.updated_at` $\rightarrow$ `entity.created_at` $\rightarrow$ `entity.current_start`
2. **Payload Subscription Entity:** `eventPayload.payload.subscription.entity.updated_at` $\rightarrow$ `created_at` $\rightarrow$ `current_start`
3. **Payload Payment Entity:** `eventPayload.payload.payment.entity.updated_at` $\rightarrow$ `created_at`
4. **Payload Refund Entity:** `eventPayload.payload.refund.entity.created_at`
5. **Top-Level Webhook Event:** `eventPayload.created_at` $\rightarrow$ `eventPayload.event_created_at`
6. **Candidate Validator:** Discards all values that are not finite positive numbers (`typeof !== "number" || !Number.isFinite(val) || isNaN(val) || val <= 0`), converts seconds to milliseconds, and rejects timestamps $> \text{Date.now()} + 5\text{ minutes}$.

### 3.2 Monotonic State Transition & Terminal-State Policy
* **`CANCELLED` (with `refundedAt`):** Permanently immutable. Guard 1 in `reconcileSubscriptionActivation` unconditionally blocks any reactivation.
* **`CANCELLED` (without refund) / `HALTED`:** Cannot be resurrected by older, equal, or un-timestamped events. Reactivation requires:
  1. Valid, verified Razorpay identity and configured plan ID.
  2. Timestamp strictly newer than `lastEventTimestamp` (`incomingEventTimeMs > storedEventTimeMs`) and $\ge \text{storedUpdatedAtMs}$.
  3. Authoritative billing period from Razorpay (`current_start` and `current_end` where `current_end * 1000 > Date.now()`).
* **`PENDING` Events:** An un-timestamped `subscription.pending` cannot downgrade an existing `ACTIVE` subscription.

---

## 4. Test Matrix & Verification Results

The complete Node.js test suite (`worker/test_runner.mjs`) was executed with **44 comprehensive tests**, including the dedicated adversarial matrix (**Tests A through X**):

```
========================================
Test Results: 44 Passed, 0 Failed
========================================
```

### Complete Test Breakdown:

| Test ID | Category | Scenario / Assertion | Status |
|:---:|---|---|:---:|
| **1-4** | Auth & Health | Healthcheck, `/api/time` synchronization, method validation | ✅ PASS |
| **5-9** | `create-subscription` | Auth enforcement, plan validation, account deletion guards, UID notes binding | ✅ PASS |
| **10-13** | `verify-subscription` | Missing fields (400), invalid sig (400), UID mismatch (403), active activation | ✅ PASS |
| **14** | `cancel-subscription` | Authoritative cancellation via REST, `cancelAtPeriodEnd: true` | ✅ PASS |
| **15** | Signature Security | HMAC-SHA256 rejection on bad signature | ✅ PASS |
| **16** | Idempotency | Exact-once atomic idempotency via `razorpay_events` collection | ✅ PASS |
| **17** | Lifecycle | `subscription.charged` renews active cycle | ✅ PASS |
| **18** | Lifecycle | `subscription.pending` marks PENDING with grace access | ✅ PASS |
| **19** | Lifecycle | `subscription.halted` revokes premium access immediately | ✅ PASS |
| **20** | Lifecycle | `refund.processed` revokes access, records `refundedAt`, sets CANCELLED | ✅ PASS |
| **A** | Adversarial Matrix | Missing timestamp after ACTIVE preserves ACTIVE | ✅ PASS |
| **B** | Adversarial Matrix | Missing timestamp after CANCELLED preserves CANCELLED | ✅ PASS |
| **C** | Adversarial Matrix | Missing timestamp after REFUNDED remains revoked | ✅ PASS |
| **D** | Adversarial Matrix | Malformed timestamp (`"NaN"`) after CANCELLED rejects resurrection | ✅ PASS |
| **E** | Adversarial Matrix | Zero timestamp (`0`) after CANCELLED rejects resurrection | ✅ PASS |
| **F** | Adversarial Matrix | Negative timestamp (`-500`) after CANCELLED rejects resurrection | ✅ PASS |
| **G** | Adversarial Matrix | Future timestamp +1 hour is rejected and does not poison `lastEventTimestamp` | ✅ PASS |
| **H** | Adversarial Matrix | Future timestamp +10 minutes is rejected (> 5 min future skew) | ✅ PASS |
| **I** | Adversarial Matrix | Future timestamp +4 minutes is accepted within 5 min skew tolerance | ✅ PASS |
| **J** | Adversarial Matrix | Excessive future event rejected, subsequent legitimate cancellation succeeds | ✅ PASS |
| **K** | Adversarial Matrix | Timestamp exactly equal to stored timestamp is rejected as duplicate/stale | ✅ PASS |
| **L** | Adversarial Matrix | Timestamp 1ms older than `lastEventTimestamp` is rejected | ✅ PASS |
| **M** | Adversarial Matrix | Timestamp 9.999s older than `updatedAt` is strictly rejected | ✅ PASS |
| **N** | Adversarial Matrix | Timestamp 10.000s older than `updatedAt` is strictly rejected | ✅ PASS |
| **O** | Adversarial Matrix | Timestamp 10.001s older than `updatedAt` is strictly rejected | ✅ PASS |
| **P** | Adversarial Matrix | Activation missing `current_start` fails closed without synthetic date | ✅ PASS |
| **Q** | Adversarial Matrix | Activation missing `current_end` fails closed without synthetic +30 days | ✅ PASS |
| **R** | Adversarial Matrix | Activation with valid `current_start`/`current_end` succeeds | ✅ PASS |
| **S** | Adversarial Matrix | Valid newer `subscription.charged` event advances billing cycle | ✅ PASS |
| **T** | Adversarial Matrix | CANCELLED state with genuinely newer billing event reactivates cleanly | ✅ PASS |
| **U** | Adversarial Matrix | REFUNDED subscription followed by replayed charged remains CANCELLED | ✅ PASS |
| **V** | Adversarial Matrix | Duplicate event ID returns `already_processed` | ✅ PASS |
| **W** | Adversarial Matrix | Concurrent duplicate webhooks processed atomically | ✅ PASS |
| **X** | Adversarial Matrix | `payment.failed` webhook does not revoke existing active cycle | ✅ PASS |

---

## 5. Security Invariant Confirmation

1. **HMAC-SHA256 Verification:** Intact. Missing or invalid signature headers return HTTP 400.
2. **Event Idempotency:** Intact. Atomic Firestore document write to `razorpay_events/${eventId}` ensures exact-once processing.
3. **UID Ownership Binding:** Intact. Webhooks resolve Firebase UID from notes or subscription map before processing.
4. **Configured Plan Verification:** Intact. Mismatches against `env.RAZORPAY_PLAN_ID` are logged and ignored (`ignored_plan_mismatch`).
5. **Refund Protection:** Intact. Guard 1 prevents reactivation of any refunded subscription.
6. **Zero Client Impact:** No Android client code changes were required.

---

## 6. Conclusion

The Cloudflare Worker webhook state machine is now **provably fail-closed, monotonic, and resistant to timestamp manipulation, replay attacks, future clock poisoning, and accidental entitlement manufacturing**.
