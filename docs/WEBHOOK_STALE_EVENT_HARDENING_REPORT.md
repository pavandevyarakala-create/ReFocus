# WEBHOOK STALE-EVENT & STATE-MACHINE HARDENING REPORT

**Date:** 2026-09-21  
**Target:** `worker/src/index.js` (`handleRazorpayWebhook`, `reconcileSubscription*`)  
**Scope:** Prevention of out-of-order, replayed, or stale Razorpay webhook delivery causing subscription state regression, premium resurrection, or cycle rollback.  
**Auditor:** Antigravity Backend Engineering & Security  

---

## 1. Vulnerability & Root Cause Analysis

### Vulnerability Description
In distributed webhook delivery architectures (such as Razorpay webhooks), network retries or asynchronous queue processing can cause events to arrive out of order (e.g., an earlier `subscription.pending` or `subscription.activated` arriving after a later `subscription.cancelled` or `subscription.charged`).

### Previous Limitation
Previous event deduplication relied solely on `eventId` idempotency in Firestore (`razorpay_events/${eventId}`). If an attacker or gateway retry mechanism delivered an older event under a different event ID, or if events arrived out of chronological order, the state machine would write the older event's payload directly into Firestore, causing:
1. Revocation of active premium if an old `pending` event arrived after a successful `charged` event.
2. Undoing cancellations if an old `activated` event arrived after user cancellation.
3. Resurrecting refunded/revoked subscriptions.

---

## 2. Implementation & State Transition Guards

### 2.1 Authoritative Timestamp Extraction (`extractAuthoritativeEventTimeMs`)
Every incoming Razorpay webhook payload is parsed for authoritative epoch timestamps in order of priority:
1. `entity.updated_at` (Razorpay subscription/payment update epoch seconds)
2. `entity.created_at` (Razorpay entity creation epoch seconds)
3. `entity.current_start` (Billing cycle start epoch seconds)
4. `eventPayload.created_at` / `eventPayload.event_created_at` (Razorpay webhook event creation epoch seconds)

### 2.2 Monotonic Stale Event Check (`isEventStale`)
Before mutating any Firestore subscription document, the reconciler checks:
```javascript
function isEventStale(existingFields, incomingEventTimeMs) {
  if (!existingFields) return false;

  // 1. Check against recorded last event timestamp
  const storedEventTimeStr = existingFields.lastEventTimestamp?.timestampValue;
  if (storedEventTimeStr && incomingEventTimeMs) {
    const storedEventTimeMs = new Date(storedEventTimeStr).getTime();
    if (incomingEventTimeMs < storedEventTimeMs) {
      return true;
    }
  }

  // 2. Check against updatedAt timestamp (with 10s clock-drift tolerance)
  const storedUpdatedAtStr = existingFields.updatedAt?.timestampValue;
  if (storedUpdatedAtStr && incomingEventTimeMs) {
    const storedUpdatedAtMs = new Date(storedUpdatedAtStr).getTime();
    if (incomingEventTimeMs < (storedUpdatedAtMs - 10000)) {
      return true;
    }
  }

  return false;
}
```

### 2.3 Transition Rules Enforced
1. **ACTIVE / CHARGED → Old PENDING:** Ignored; active cycle and premium status preserved.
2. **ACTIVE / CHARGED → Old CHARGED:** Ignored; does not truncate `currentPeriodEnd`.
3. **CANCELLED → Old ACTIVATED / CHARGED:** Ignored; cancelled state preserved.
4. **REFUNDED → Old CHARGED:** Ignored; refunded/cancelled state strictly preserved.
5. **Brand New Event ID with Old Timestamp:** Idempotency doc recorded, but state mutation rejected by timestamp guard.
6. **Missing Timestamps:** Safely processed using current server time without throwing or crashing.

---

## 3. Concurrency & Idempotency Architecture

1. **Atomic Event Locking:** `recordProcessedEventAtomic(eventId, ...)` uses Firestore's condition `currentDocument.exists=false` via REST PATCH. If two identical webhook requests hit the worker simultaneously, exactly one succeeds and the other receives `already_processed`.
2. **Deterministic Mutation:** All reconciliation functions (`reconcileSubscriptionActivation`, `reconcileSubscriptionPending`, `reconcileSubscriptionHalted`, `reconcileSubscriptionCancelled`, `reconcileSubscriptionCompleted`, `reconcileSubscriptionRefund`) write `lastEventTimestamp` and `updatedAt` atomically within the subscription status document update mask.
3. **UID & HMAC Verification Intact:** All signature verification (`X-Razorpay-Signature`), plan ID matching, and UID mapping resolution remain active.

---

## 4. Test Matrix & Validation Results

The Cloudflare Worker test runner (`worker/test_runner.mjs`) was executed with 32 automated unit and integration tests covering all lifecycle edges:

| Test ID | Test Scenario | Expected Outcome | Result |
|:---:|---|---|:---:|
| **Test A** | ACTIVE → old PENDING | ACTIVE state preserved; PENDING ignored | **PASS** |
| **Test B** | ACTIVE → old CHARGED | Future `currentPeriodEnd` preserved; cycle not rolled back | **PASS** |
| **Test C** | CANCELLED → old ACTIVATED | Maintained CANCELLED status; no entitlement revival | **PASS** |
| **Test D** | REFUNDED → old CHARGED | Maintained CANCELLED status; access remains revoked | **PASS** |
| **Test E** | Replayed old event with NEW event ID | Stale event ignored by `lastEventTimestamp` check | **PASS** |
| **Test F** | Duplicate webhook event ID | Returns HTTP 200 with status `already_processed` | **PASS** |
| **Test G** | Two concurrent webhook deliveries | Atomic idempotency lock prevents duplicate processing | **PASS** |
| **Test H** | Valid newer event after older event | Successfully advances `currentPeriodEnd` | **PASS** |
| **Test I** | Event without usable timestamp | Gracefully processed without runtime exception | **PASS** |
| **Test J** | `payment.failed` after valid paid entitlement | Existing active entitlement preserved | **PASS** |
| **Test K** | Refund followed by old `subscription.charged` | Subscription remains permanently CANCELLED | **PASS** |
| **Test L** | Cancellation followed by old `subscription.activated` | Subscription remains CANCELLED | **PASS** |

### Test Runner Execution Output:
```
========================================
Test Results: 32 Passed, 0 Failed
========================================
```

---

## 5. Security & Verification Confirmation

- **HMAC Verification:** Raw body `HMAC-SHA256` signature verification against `RAZORPAY_WEBHOOK_SECRET` remains strictly enforced.
- **UID Ownership Binding:** All webhooks map to verified Firestore UIDs via `notes.firebase_uid` or `subscription_map`.
- **Anti-Resurrection:** Refunds and cancellations cannot be undone by replayed webhooks.
- **Zero Client Impact:** No Android app changes or schema regressions were introduced.
