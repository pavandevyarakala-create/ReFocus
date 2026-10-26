package com.reeltrak.app.features.legal.data

/**
 * Centralized in-app source of truth for Legal and Privacy documents.
 * Accurately reflects the production architecture of Re:Focus by FOCRAMEX.
 */
object LegalContent {
    const val LAST_UPDATED = "October 2026"

    data class LegalSection(
        val title: String,
        val content: String,
        val bullets: List<String> = emptyList()
    )

    val privacyPolicySections = listOf(
        LegalSection(
            title = "1. Overview & Privacy Philosophy",
            content = "Re:Focus by FOCRAMEX is built on a privacy-by-design foundation. We believe that tracking your digital habits and overcoming short-form video distraction should empower you without compromising your personal privacy. This Privacy Policy transparently explains what data we collect, why we collect it, how it is handled, and your rights regarding your information."
        ),
        LegalSection(
            title = "2. Information We Collect",
            content = "We collect only the minimum information necessary to provide reliable habit tracking, streak calculations, and account management:",
            bullets = listOf(
                "Information You Provide Directly: When you sign in with your Google account, we receive basic profile information (email address, display name, and profile picture URL). You also provide user-configured preferences such as your daily reel limit, goal intent, and strict mode preferences.",
                "Information Generated Through App Usage: The app records daily aggregate reel counts, total watch time spent in short-form video sessions (in milliseconds), session start and end timestamps, the platform source (Instagram Reels or YouTube Shorts), current and longest streak records, Skull Day occurrences, and discipline level milestones.",
                "Device & Security Attestation: To safeguard the service against unauthorized access and abuse, we collect device environment signals (Android OS version and installation origin) and cryptographic attestation tokens via Google Play Integrity and Firebase App Check."
            )
        ),
        LegalSection(
            title = "3. Android Permissions & Why They Are Required",
            content = "Re:Focus requests specific Android permissions strictly to deliver its core digital wellbeing functionality:",
            bullets = listOf(
                "Accessibility Service (BIND_ACCESSIBILITY_SERVICE): Required to detect when supported short-form video feeds (Instagram Reels and YouTube Shorts) are actively playing and count swipe transitions. All video detection and view inspection occur strictly in real-time on your device. When Strict Mode is enabled by you, the service triggers the standard system Back action (GLOBAL_ACTION_BACK) to exit target apps once limits are exceeded. Re:Focus NEVER reads or captures personal messages, keystrokes, passwords, contacts, or unrelated applications.",
                "Display Over Other Apps (SYSTEM_ALERT_WINDOW): Required to render the floating reel counter bubble directly over supported video apps, enabling real-time awareness without switching screens.",
                "Notifications (POST_NOTIFICATIONS): On Android 13+, allows Re:Focus to deliver daily limit warnings (such as 80% and 100% threshold alerts) and habit streak updates. This permission is optional.",
                "Internet Access (INTERNET): Required to authenticate your Google session, securely synchronize daily summary metrics and subscription entitlement, and communicate with our backend API."
            )
        ),
        LegalSection(
            title = "4. How Collected Information Is Used",
            content = "We use the collected information exclusively for the following purposes:",
            bullets = listOf(
                "Calculating your real-time reel counts, daily time spent, Success Rate, and discipline streak tiers (Seedling through Legend).",
                "Displaying your live floating counter and enforcing daily limits or Strict Mode lockouts when enabled.",
                "Synchronizing your daily summary metrics, onboarding tour status, and subscription access across sessions.",
                "Verifying subscription validity and managing payment status.",
                "Protecting the platform against abuse, fraudulent API calls, and unauthorized requests via cryptographic attestation."
            )
        ),
        LegalSection(
            title = "5. Data Storage & Cloud Synchronization",
            content = "Re:Focus maintains a clear separation between local device data and synchronized cloud data:",
            bullets = listOf(
                "Local On-Device Storage: Detailed second-by-second session records (exact start/end timestamps, app names, and individual reel scroll events) and local preferences are stored locally on your device in a sandboxed SQLite database (Android Room) and Jetpack DataStore.",
                "Cloud Synchronization: When signed into your account with an active subscription or trial, daily aggregated summaries (date, total reels count, daily goal, strict mode state), your user profile, and subscription status are synchronized with Google Cloud Firestore under your private user document (/users/{userId}).",
                "What Is NEVER Synchronized: Raw accessibility event streams, detailed second-by-second session logs, screen content, and personal communications remain strictly on your physical device and are NEVER transmitted to external servers."
            )
        ),
        LegalSection(
            title = "6. Third-Party Services Actually Used",
            content = "We do not sell, rent, or trade your data to third parties, data brokers, or advertising networks. We integrate only essential service providers required to operate the app:",
            bullets = listOf(
                "Google Identity & Firebase Authentication: Manages secure Google Sign-In and session tokens.",
                "Google Cloud Firestore: Cloud database hosting user profile documents, daily aggregated metrics, and subscription status under strict owner-only access rules.",
                "Google Play Integrity & Firebase App Check: Provides device attestation to ensure only authentic Re:Focus installations access backend APIs.",
                "Google Play Billing: Facilitates secure processing of digital subscriptions and in-app purchases through your Google account.",
                "Cloudflare Workers: Edge API infrastructure hosting server-authoritative time, subscription verification, and account deletion workflows.",
                "Google AdMob (Google Mobile Ads SDK): Serves user-initiated rewarded advertisements that allow you to unlock temporary extensions or trial allowances. AdMob may process device identifiers including the Google Advertising ID (AD_ID) and diagnostic data for ad serving and fraud prevention, governed by Google's Privacy Policy. AdMob SDK does not have access to your video-tracking habits or accessibility data.",
                "Razorpay: Secure payment gateway processing recurring monthly subscriptions via UPI and cards where applicable. Re:Focus NEVER handles, processes, or stores your credit/debit card numbers, UPI PINs, or banking credentials."
            )
        ),
        LegalSection(
            title = "7. Subscriptions & Payment Processing",
            content = "Re:Focus offers an optional 7-day free trial where applicable, followed by a recurring monthly subscription priced at ₹79/month. Subscriptions and digital purchases are billed through Google Play Billing (and where applicable under India User Choice Billing, alternative payment options such as Razorpay). Payments are securely handled by Google Play and Razorpay. Re:Focus NEVER handles, processes, or stores your credit/debit card numbers, UPI PINs, or banking credentials. You can cancel your subscription at any time via Google Play Subscriptions or directly within app Settings. Upon cancellation, your access remains active until the end of the current billing cycle."
        ),
        LegalSection(
            title = "8. Data Retention & Account Deletion",
            content = "You have full control over your account and data lifecycle:",
            bullets = listOf(
                "Data Retention: Your account profile and synced daily summaries are retained in Cloud Firestore for as long as your account remains active.",
                "In-App Account Deletion: You can initiate account deletion at any time via Settings → Delete Account.",
                "7-Day Soft-Delete Grace Period: When requested, your account enters a 7-day grace period (PENDING_DELETION). If you change your mind, you can log in and cancel the deletion request within 7 days.",
                "Permanent Purge: If not cancelled within 7 days, all user records in Firebase Authentication and all associated documents in Cloud Firestore are permanently and irrevocably deleted.",
                "Local Data Wipe: Logging out or initiating account deletion immediately erases all historical usage data and cached preferences from your device."
            )
        ),
        LegalSection(
            title = "9. Security Practices",
            content = "We implement robust technical safeguards to protect your information:",
            bullets = listOf(
                "All network communications use Transport Layer Security (TLS/HTTPS) with Strict-Transport-Security (HSTS).",
                "Cloud Firestore security rules enforce strict owner-only access (isOwner) and field whitelisting to prevent unauthorized data tampering.",
                "Backend endpoints enforce rate limiting and App Check cryptographic attestation.",
                "Zero sensitive payment data (card numbers or banking credentials) touches our application or servers."
            )
        ),
        LegalSection(
            title = "10. Children's Privacy",
            content = "Re:Focus is not directed toward children under 13 years of age (or the applicable minimum legal age in your jurisdiction). We do not knowingly collect personal information from children. If you become aware that a child has provided us with personal data without parental consent, please contact us immediately so we can remove the data."
        ),
        LegalSection(
            title = "11. Your Rights & Options",
            content = "Depending on your location, you have rights regarding your personal data, including the right to access the data we hold about you, request corrections, revoke permissions via Android Settings, cancel subscriptions, or request permanent deletion of your account and data at any time."
        ),
        LegalSection(
            title = "12. Changes to This Policy",
            content = "We may update this Privacy Policy from time to time. Any changes will be published in the application and on our companion website with an updated 'Last updated' date. Continued use of the app following any updates constitutes acceptance of the revised policy."
        ),
        LegalSection(
            title = "13. Contact Us",
            content = "If you have questions, concerns, or requests regarding this Privacy Policy or your data, please contact FOCRAMEX support at:\n\nEmail: support.refocus@gmail.com"
        )
    )

    val termsAndConditionsSections = listOf(
        LegalSection(
            title = "1. Acceptance of Terms",
            content = "By downloading, installing, or using the Re:Focus Android application, you agree to be bound by these Terms and Conditions. These terms constitute a legally binding agreement between you and FOCRAMEX. If you do not agree with any part of these terms, you must not download, install, or use the application."
        ),
        LegalSection(
            title = "2. Description of Service & Core Mechanics",
            content = "Re:Focus is a digital wellbeing and habit-tracking tool designed to help you monitor and regulate your viewing of short-form video platforms (including Instagram Reels and YouTube Shorts).",
            bullets = listOf(
                "Daily Limits & Skull Days: You configure your daily reel viewing limits. Exceeding your configured limit triggers a 'Skull Day', resetting your Current Streak to zero.",
                "Discipline Levels: Your discipline tier (Seedling through Legend) is determined by your Longest Streak and will never decrease due to a Skull Day.",
                "Strict Mode: An optional feature enabling strict enforcement to help you avoid surpassing your daily limit.",
                "The application is provided strictly for personal productivity and self-improvement purposes."
            )
        ),
        LegalSection(
            title = "3. User Accounts & Responsibilities",
            content = "To use Re:Focus, you must sign in with a valid Google account. You are responsible for maintaining the security of your account and device. You agree not to attempt to reverse-engineer, decompile, tamper with security controls, or abuse the service or its backend infrastructure. FOCRAMEX reserves the right to suspend or restrict accounts that violate these terms."
        ),
        LegalSection(
            title = "4. Subscriptions, Free Trials & Billing",
            content = "Re:Focus offers premium features through an optional recurring subscription:",
            bullets = listOf(
                "Pricing: Re:Focus Premium is priced at ₹79/month.",
                "Free Trial: Eligible new accounts may receive an initial 7-day free trial. You will not be charged if you cancel before the trial period expires.",
                "Payment Processing: All payments, UPI autopay mandates, and card transactions are processed securely through Razorpay. FOCRAMEX does not store or process your banking or card credentials.",
                "Cancellation: You can cancel your recurring subscription at any time directly in app Settings. Upon cancellation, your premium access will remain active until the end of the current paid billing period with no further charges.",
                "Refunds: Subscription fees are non-refundable except as required by applicable consumer protection laws."
            )
        ),
        LegalSection(
            title = "5. Required Android Permissions",
            content = "Re:Focus requires the Android Accessibility Service and 'Display over other apps' permissions to detect short-form video playback and render the live floating counter. You acknowledge that revoking or disabling these permissions prevents the application from detecting video feeds and providing habit tracking functionality."
        ),
        LegalSection(
            title = "6. Intellectual Property",
            content = "All software, trademarks, logos, UI designs, animations, and branding elements associated with Re:Focus and FOCRAMEX are the exclusive intellectual property of FOCRAMEX. You are granted a limited, personal, non-exclusive, non-transferable license to use the app for personal, non-commercial use."
        ),
        LegalSection(
            title = "7. Disclaimer of Warranties",
            content = "Re:Focus is provided on an 'AS IS' and 'AS AVAILABLE' basis without warranties of any kind, whether express or implied. While we strive for accuracy, FOCRAMEX does not guarantee that the app will be uninterrupted or error-free. Video detection depends on third-party platform view structures; updates to Instagram or YouTube may temporarily affect detection until compatibility updates are published."
        ),
        LegalSection(
            title = "8. Limitation of Liability",
            content = "To the maximum extent permitted by applicable law, FOCRAMEX and its developers shall not be liable for any indirect, incidental, special, consequential, or punitive damages arising out of your access to, use of, or inability to use the application."
        ),
        LegalSection(
            title = "9. Account Deletion & Termination",
            content = "You may delete your account at any time in app Settings. Account deletion includes a 7-day grace period during which you can restore your account. After 7 days, your account and all associated cloud data are permanently purged. FOCRAMEX reserves the right to terminate access for accounts that violate these terms."
        ),
        LegalSection(
            title = "10. Governing Law & Jurisdiction",
            content = "These Terms and Conditions shall be governed by, construed, and enforced in accordance with the laws of the Republic of India. Any legal dispute, controversy, or claim arising out of or relating to these terms or your use of Re:Focus shall be subject to the exclusive jurisdiction of the competent courts of India."
        ),
        LegalSection(
            title = "11. Changes to Terms",
            content = "We reserve the right to modify or replace these Terms and Conditions at any time. Updated terms will be posted within the application and on our companion website. Continued use of Re:Focus after modifications become effective constitutes acceptance of the new terms."
        ),
        LegalSection(
            title = "12. Contact Information",
            content = "If you have any questions or legal inquiries regarding these Terms and Conditions, please contact us at:\n\nEmail: support.refocus@gmail.com"
        )
    )
}
