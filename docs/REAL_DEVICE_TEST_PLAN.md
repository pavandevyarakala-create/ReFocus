# Real-Device Release Candidate Test Plan & Execution Guide

## Overview
This document outlines the end-to-end physical device validation protocol for the **Re:Focus Android Release Candidate**.

---

## 1. Release Build Production Command

To compile a release build with minification, resource shrinking, ProGuard optimizations, and release signing, run:

```powershell
$env:KEYSTORE_PATH="path/to/release.keystore"
$env:KEYSTORE_PASSWORD="keystore_password"
$env:KEY_ALIAS="key_alias"
$env:KEY_PASSWORD="key_password"

.\gradlew clean assembleRelease
```

*Note: If signing environment variables are not supplied, Gradle sets `signingConfig = null` to guarantee an unsigned bundle rather than falling back to debug signing.*

---

## 2. Test Execution Matrix

| Test Suite | Objective | Test Steps | Expected Result | Result / Status |
| :--- | :--- | :--- | :--- | :--- |
| **A. Install & Launch** | Cold start & permission onboarding | 1. Install release build.<br>2. Launch app.<br>3. Walk through Accessibility permission setup.<br>4. Grant overlay & notification permissions. | App launches smoothly with 0 crashes; `ReelTrackerService` initializes and registers engine. | **Automated Suite: PASS**<br>*Manual on physical hardware: Awaiting live device session* |
| **B. Google Sign-In** | Auth & Session Persistence | 1. Tap "Sign in with Google".<br>2. Select Google Account.<br>3. Verify Firebase token authentication.<br>4. Force stop and reopen app. | User profile loads; session persists; no missing credential exception. | **Automated Suite: PASS**<br>*Manual on physical hardware: Awaiting live device session* |
| **C. Account Isolation** | Cross-Account Data Wipe | 1. Log in as Account A.<br>2. Scroll reels to create daily data.<br>3. Sign out.<br>4. Log in as Account B. | Account A's history is wiped locally; today's enforcement count is preserved. | **Automated Suite: PASS (BUG-CRIT-02)**<br>*Manual on physical hardware: Awaiting live device session* |
| **D. Reel Tracking** | Instagram Reels & YouTube Shorts Detection | 1. Open Instagram / YouTube Shorts.<br>2. Scroll forward, rapidly, and pause.<br>3. Switch between apps.<br>4. Lock and unlock screen. | Real-time counter advances monotonically (+1 per reel); 0 increments on ads/comments; overlay hides on screen-off. | **Automated Suite: PASS**<br>*Manual on physical hardware: Awaiting live device session* |
| **E. Daily Limit & Reminders** | Daily Limit Enforcement | 1. Configure test limit (e.g., 5 reels).<br>2. Scroll past limit.<br>3. Observe 50%, 90%, 100% notifications.<br>4. Fast forward device clock. | Notifications trigger on thresholds; limit locks at 100%; clock tampering does not unlock limit. | **Automated Suite: PASS (Phase 4)**<br>*Manual on physical hardware: Awaiting live device session* |
| **F. Strict Mode** | Focus Enforcement | 1. Enable Strict Mode.<br>2. Exceed limit.<br>3. Observe app closure/kickback when opening Reels. | User is gently navigated away from Reels feed without locking out other phone capabilities. | **Automated Suite: PASS**<br>*Manual on physical hardware: Awaiting live device session* |
| **G. Razorpay Subscription (TEST)** | Recurring Autopay Checkout | 1. Open Subscription BottomSheet.<br>2. Tap "Subscribe for ₹79/Month".<br>3. Complete Razorpay test autopay mandate.<br>4. Observe unlock and cancel-at-period-end flow. | Mandate verifies at Edge; Firestore transitions to ACTIVE; in-app cancel updates to cancel-at-period-end. | **Automated Suite: PASS (Phase 6)**<br>*Manual on physical hardware: Awaiting live device session* |
| **H. Server-Time Tampering** | Anti-Rollback & Forward Invariance | 1. Shift device clock +1 year.<br>2. Check subscription & limit status.<br>3. Shift device clock -30 days.<br>4. Restore automatic time. | `TrustedTimeManager` ignores device wall clock; trial and daily limits remain strictly anchored to server time. | **Automated Suite: PASS (BUG-HIGH-03 & Phase 4)**<br>*Manual on physical hardware: Awaiting live device session* |
| **I. Cloud Sync & Offline Merge** | Deterministic Safe Merge | 1. Disconnect WiFi/data.<br>2. Scroll reels offline.<br>3. Reconnect internet.<br>4. Verify cloud backup. | Offline records sync with $\max(\text{local}, \text{cloud})$; zero data loss (`BUG-HIGH-02`). | **Automated Suite: PASS (BUG-HIGH-02)**<br>*Manual on physical hardware: Awaiting live device session* |
| **J. Performance & Hot Paths** | $O(1)$ Memory & FPS Stability | 1. Continuous scrolling for 10 minutes.<br>2. Observe frame rates and CPU/battery. | Zero UI stutters; 0 background network calls on reel increments. | **Automated Suite: PASS**<br>*Manual on physical hardware: Awaiting live device session* |
