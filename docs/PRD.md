# Product Specification Document: ReelTrak

## 1. Product Overview
### Vision
To empower individuals to reclaim their time and mental well-being by providing actionable awareness of their short-form video consumption habits.

### Mission
To build the most accurate, privacy-first digital wellbeing tool for Android that helps users understand and reduce compulsive scrolling on platforms like Instagram Reels, YouTube Shorts, Facebook Reels, and Snapchat Spotlight.

### Problem Statement
Short-form video platforms are designed with infinite scroll mechanics and highly optimized recommendation algorithms that encourage compulsive, unconscious consumption. Users often lose track of time ("doomscrolling"), leading to decreased productivity, worsened mental health, and sleep disruption. Standard OS-level screen time tools only measure overall app usage, failing to distinguish between intentional usage (e.g., watching a specific YouTube tutorial) and compulsive scrolling (YouTube Shorts).

### Why This App Should Exist
There is a massive gap in OS-level digital wellbeing tools. Users need a granular, context-aware tracker that specifically measures "scroll time" and "reels watched" rather than just "app open time."

### Target Audience
- **Students & Professionals**: Seeking to improve focus and productivity.
- **Mental Health Conscious Individuals**: Looking to reduce dopamine addiction and anxiety.
- **Parents**: Wanting to monitor or manage their teenagers' specific social media habits.
- **Content Creators**: Who want to separate work (posting) from mindless consumption.

### User Personas
- **The Procrastinator (Alex, 22)**: Opens Instagram to reply to a DM, ends up scrolling Reels for 45 minutes. Needs interruptions and reality checks.
- **The Quantified Selfer (Jordan, 30)**: Loves data, charts, and metrics. Wants to know exactly how many Reels they watch per minute and peak consumption hours.
- **The Intentional User (Maya, 28)**: Wants to use YouTube for learning but completely block YouTube Shorts.

### Real-world Use Cases
- A user sets a daily limit of 50 Reels. The app warns them at 40 and blocks the UI at 50.
- A user wants to see how their short-form video consumption correlates with their late-night sleep disruptions.
- A user realizes they scroll 2x faster on TikTok than Instagram, highlighting different engagement patterns.

### Expected Impact
Helping users reduce short-form video consumption by an average of 40%, returning hundreds of hours of productive time per year while improving mental clarity.

---

## 2. Market Research
### Existing Competitors
- **Digital Wellbeing (Google) / Screen Time (Apple)**: Tracks overall app usage.
- **AppBlock / StayFree / ActionDash**: Block apps based on time limits or schedules.
- **OneSec**: Forces users to wait (take a breath) before opening distracting apps.

### Their Strengths
- OS-level integration (Google Digital Wellbeing).
- Simple to use and understand.
- Broad app coverage.

### Their Weaknesses
- **Lack of Granularity**: None can differentiate between watching a 20-minute educational YouTube video and watching 40 YouTube Shorts in 20 minutes.
- **Static Interventions**: Time limits are easily bypassed and feel punitive rather than educational.
- **No Consumption Metrics**: They track time, not "items consumed" (e.g., "You watched 120 videos").

### Gaps in the Market
- **Context-Aware Tracking**: Identifying the *sub-section* of an app (e.g., Reels vs. Feed vs. Stories).
- **Behavioral Insights**: Tracking scroll speed and frequency.

### Opportunities for Differentiation
- Counting individual videos (Reels/Shorts) instead of just time.
- Scroll velocity analytics (how fast users swipe).
- Real-time interventions (e.g., overlaying a subtle counter on the screen while scrolling).

### Why Users Would Switch
Standard time blockers don't work for apps that have dual purposes (e.g., Instagram for messaging vs. Instagram for Reels). Users will adopt this app because it targets the specific behavior they want to curb without entirely blocking the communication tools they rely on.

---

## 3. Core Objectives
### Primary Goals
- Accurately detect and track short-form video consumption across major social apps using Android APIs.
- Provide users with granular, undeniable data about their scrolling habits.

### Secondary Goals
- Implement frictionless friction (e.g., interventions that break the trance of infinite scrolling).
- Ensure zero data leaves the device to guarantee absolute privacy.

### Long-term Vision
To become a comprehensive behavioral health platform that uses local AI to detect compulsive tech patterns and gently nudge users toward healthier habits, eventually partnering with researchers to study digital addiction.

---

## 4. Complete Feature List

### Must Have
- **Reel/Short Detection Engine**: Background service to detect when a user is in the "Shorts/Reels" section of supported apps.
- **Consumption Counter**: Tracks both time spent and the number of videos scrolled past.
- **Daily Dashboard**: Simple UI showing today's stats (Total Time, Total Reels, Apps breakdown).
- **Daily Intent Contract**: A daily morning intercept that requires the user to manually input their Reel limit for the day.
- **Smart Suggestions**: The app subtly suggests a limit slightly lower than the previous day to encourage progressive reduction (e.g., "Yesterday you watched 50, want to try 45 today?").
- **Privacy-First Local Storage**: All tracking data saved locally using Room Database.

### Should Have
- **Real-time Overlay**: A subtle, non-intrusive floating widget that shows the current session time or video count while the user is scrolling.
- **Interventions (Bumps)**: Gentle vibrations or screen dimming when a user crosses a specific threshold (e.g., 15 minutes in one session).
- **Weekly & Monthly Reports**: Aggregated data showing trends and averages.
- **App Specific Granularity**: Toggle tracking for specific apps (e.g., track YT Shorts but ignore TikTok if the user wants).

### Could Have
- **Scroll Velocity Tracking**: Analyzing how quickly users are swiping to detect "zombie scrolling" vs "engaged watching."
- **Strict Mode**: Un-bypassable limits requiring a reboot or long password to disable.
- **Widget Support**: Homescreen widgets displaying current day's stats.
- **Data Export**: Ability to export stats to CSV for personal analysis.

### Future Features
- **Predictive Nudges**: Using local ML to predict when a user is about to fall into a doomscroll session based on time of day and behavior, warning them before they open the app.
- **Wearable Integration**: Sending haptic nudges to a smartwatch during long sessions.
- **Social Accountability**: Opt-in sharing of stats with a "digital sponsor" or friend.

---

## 5. Technical Feasibility

### Reel/Short Detection Engine
- **Can Android support it?**: Yes, primarily through Accessibility APIs.
- **Required APIs**: `AccessibilityService` (to read view nodes and detect scroll events/UI identifiers of Reels/Shorts), `UsageStatsManager` (to know which app is in foreground).
- **Accessibility Service**: **CRITICAL**. This is the only way to see *inside* another app's UI to know if the user is on the "Reels" tab vs the "Messages" tab.
- **Background Execution**: Requires a Foreground Service with a persistent notification to ensure the OS doesn't kill the Accessibility Service.
- **Battery Optimization**: Needs to be exempted from battery optimization (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) to run reliably.
- **Overlay (SYSTEM_ALERT_WINDOW)**: Required for the real-time counter widget and block screens.
- **Limitations**: Apps (like Instagram/YouTube) frequently change their UI node IDs and structures. The Accessibility parser must be robust, relying on view bounds, text, and structural heuristics rather than hardcoded IDs.
- **Privacy Concerns**: Accessibility Services can read *everything* on the screen, including passwords and private messages. This requires immense user trust.
- **Google Play Policy**: **HIGH RISK**. Google restricts Accessibility APIs strictly for users with disabilities. However, they have exceptions for apps that provide "Accessibility for Digital Wellbeing" or screen tracking, provided they do not collect data off-device and have clear disclosures.
- **Feasibility Rating**: **6/10** (Technically very possible, but high maintenance due to UI updates from third-party apps, and high policy risk on Google Play).

---

## 6. User Journey
- **First Install**: User opens the app, sees a polished value proposition.
- **Permissions**: Guided, step-by-step onboarding explaining *why* Accessibility and Overlay permissions are needed, emphasizing local-only privacy.
- **Onboarding**: User selects which apps they struggle with and sets a baseline goal (e.g., "Keep it under 100 Reels a day").
- **Daily Intent Setting**: The first time the user opens a tracked app each day, an overlay intercepts them asking, "How many Reels today?". They input a number. A subtle suggestion text says "Try 45?" if they watched 50 yesterday.
- **Daily Usage**: When they navigate to Reels, a tiny, translucent counter appears at the top corner: "45 left". It decrements as they scroll, treating their limit as a budget.
- **Weekly Usage**: User receives a local notification on Sunday morning: "You scrolled 20% less this week. Here's your report."
- **Monthly Reports**: Deep dive into trends—e.g., "Thursday nights are your highest consumption times."
- **Returning Users**: Dashboard opens directly to today's progress rings.
- **Inactive Users**: If the accessibility service drops, the app sends a critical notification to re-enable it.
- **Power Users**: Customizing overlay opacity, setting complex rules (e.g., "Allow YouTube Shorts only on weekends").

---

## 7. User Flow
- **Splash Screen** -> **Onboarding (Permissions & Setup)** -> **Main Dashboard**.
- **Daily Intent Intercept**: A full-screen overlay that appears on the first open of a target app, featuring a numpad to set the day's limit and a smart progressive suggestion.
- **Main Dashboard**: Shows today's time and remaining budget. A large ring chart counting down to 0. 
  - *Interaction*: Tap ring -> Opens **Daily Detail Screen** (timeline of sessions).
  - *Interaction*: Tap 'Apps' list -> Opens **App Specific Settings** (Strict mode toggles, tracked apps).
- **Stats Tab**: Navigation bar. Shows Weekly/Monthly/Yearly graphs.
- **Settings Tab**: Navigation bar. 
  - *Buttons*: "Manage Permissions" (Checks service status), "Privacy Policy", "Export Data".
- **Intervention Flow (In other apps)**:
  - User hits limit -> Overlay screen pops up covering the app. 
  - *Buttons on Overlay*: "Close App" (navigates to Home), "Give me 5 more mins" (if strict mode is off).

---

### UI & Design System (QuestUI Hybrid)
Based on the `questui-DESIGN` specification, the app utilizes a hybrid aesthetic that gamifies digital wellbeing into a "Quest" while maintaining premium modern UX where necessary:

- **The Morning Intercept (Glassmorphism):** The daily limit input screen uses a premium glassmorphic overlay. Heavy background blur (25dp-40dp), a dark `#1A1A1A` fill at 40% opacity, and a 1dp glowing gold edge (`#CA8A04`) to simulate a physical piece of enchanted glass over the target app. The numpad uses satisfying haptic feedback.
- **In-App Counter (The Status Orb):** The floating widget uses the QuestUI "full" radius (circle). It features a dark `#2C1A10` background, a 2px `#CA8A04` glowing border, and uses the **Cinzel** font to display the remaining Reels budget. It acts as a magical ward protecting the user.
- **Analytics Menu & Dashboard:** The main app interface fully embraces the QuestUI RPG theme. Cards use the Elevated style (`#3D2517` fill, gold top-border accents). Typography relies on Cinzel for headers and Spectral for body text in a parchment color (`#F5E6D3`). Successes (staying under limit) use `#22C55E`, while breaking the limit triggers a "Damage" UI using `#991B1B`.

---

## 8. Data Model (Conceptual Only)
- **User Profile**: Setup status, app preferences, premium status.
- **Settings**: Global limits, strict mode toggle, overlay preferences, theme.
- **App Usage (Entity)**: Package name, tracking enabled, custom limit.
- **Sessions**: Start timestamp, end timestamp, package name, total time, total items scrolled.
- **Daily Statistics**: Date, total time across all apps, total count across all apps, list of Session IDs.
- **Achievements**: Milestones reached (e.g., "Under 50 Reels for 7 days straight").

---

## 9. Analytics
- **Daily Reels/Shorts Count**: Absolute number of items scrolled.
- **Average Session Duration**: How long a typical doomscroll lasts once triggered.
- **Longest Session**: The worst offender of the day/week.
- **Peak Hours**: Heatmap showing time of day vs. consumption volume.
- **Reels Per Minute (Velocity)**: Calculated by count / session time. High RPM indicates mindless zombie scrolling; low RPM indicates engaged watching.
- **Daily/Weekly/Monthly Trends**: Moving averages to show progress.
- **Trigger Points**: Which app the user opened *before* navigating to Reels.

---

## 10. Reports
- **Daily Report**: Simple notification at 8 PM. "You spent 45 mins on Reels today."
- **Weekly Report**: Sundays. Compares this week to last week. Highlights the "Most Distracting App."
- **Monthly Report**: Calendar view showing "green" days (under limit) and "red" days (over limit).
- **Yearly / Lifetime Summary**: Cumulative data. "You saved 120 hours this year compared to your baseline."

---

## 11. Edge Cases (QA Perspective)
- **Phone Restart**: Foreground service must launch on `BOOT_COMPLETED` to resume tracking.
- **Permission Revoked**: User manually disables Accessibility. App must detect this via system broadcasts or periodic checks and alert the user.
- **App Force Stopped**: User kills the tracker to bypass limits. Needs a watchdog or persistent notification to discourage this.
- **Battery Saver**: OS restricts background activity. App must prompt user for exemptions.
- **Target App Updates UI**: Instagram changes its View node for Reels. The heuristic parser fails. Fallback mechanism needed (e.g., detecting rapid scroll gestures + media playing as a proxy for Reels).
- **Timezone Change**: Data structures must store everything in UTC and localize on the UI layer to prevent double counting or lost days during travel.
- **Offline Mode**: App must function 100% offline. No cloud dependency for core tracking.
- **Multiple Users / Work Profiles**: Android handles these as separate environments. Service must specify it tracks the current user profile.

---

## 12. Privacy & Security
- **Data Collection**: Collects package names, timestamps, and scroll events.
- **NOT Collected**: Screen contents (text, images, usernames, videos, passwords). The Accessibility service must aggressively filter events, parsing only specific structural nodes.
- **Storage**: 100% Local (SQLite/Room). No cloud sync unless explicitly requested and End-to-End Encrypted (Future feature).
- **Encryption**: Database can be encrypted via SQLCipher to prevent rooted devices from extracting behavioral data easily.
- **Permissions Justification**: Accessibility is required to differentiate between the 'Feed' and 'Reels' UI. UsageStats cannot do this.
- **Google Play Compliance**: Must feature a prominent, unskippable in-app disclosure before requesting Accessibility, explaining *exactly* what is parsed and ensuring no data leaves the device.

---

## 13. Performance Goals
- **Battery Consumption**: < 2% of total battery usage over 24 hours. The accessibility listener must be highly optimized, ignoring non-scroll/non-window-state-change events instantly to prevent CPU wake-locks.
- **RAM Usage**: < 50MB. Foreground service must be lightweight.
- **Storage**: < 20MB for database. Data older than 1 year should be aggregated (e.g., compress daily sessions into a single daily summary to save space).
- **Background Work**: Minimal network calls. Only async disk writes.

---

## 14. Monetization Ideas
- **Free Version**: Basic tracking for up to 2 apps (e.g., Instagram and YouTube). Basic daily limits.
- **Premium Version (ReelTrak Pro)**: Unlimited apps, granular analytics (velocity, heatmaps), custom overlays, strict mode.
- **Subscription Ideas**: $1.99/month or $14.99/year.
- **One-time Purchase**: Lifetime license for $39.99 (highly appealing to utility app users).
- **Ethical Monetization**: **NEVER monetize user data**. No third-party ad SDKs (AdMob, etc.) as they compromise the privacy-first promise and consume battery.
- **Never Monetized**: Core tracking accuracy. We do not degrade detection for free users.

---

## 15. Future Roadmap
- **Version 1**: Core detection engine, basic dashboard, daily limits, local storage. Support for IG and YT.
- **Version 2**: Weekly/Monthly reports, Real-time Overlays, Support for TikTok and FB Reels.
- **Version 3**: "Strict Mode", Velocity tracking, Advanced Data Viz.
- **Long-term**: Machine Learning integration to predict usage patterns.
- **Enterprise/Clinician**: A version where therapists can (with user consent) view aggregated behavioral data for ADHD/Addiction treatment.
- **Wearable Integration**: Nudges on WearOS.

---

## 16. Risks
- **Technical Risks**: Extreme fragmentation of Android devices and aggressive OEM battery management (Samsung, Xiaomi, Huawei) killing the background service silently.
- **Business/Policy Risks**: Google Play rejects the app due to Accessibility Service policies. This is the single highest point of failure.
- **Maintenance Risks**: Target apps (Instagram, YouTube) constantly A/B test their UI. The Accessibility heuristic parser will require continuous updates, demanding high maintenance overhead.
- **User Adoption Risks**: Users might find the reality of their data depressing and uninstall the app out of guilt.

---

## 17. Final Evaluation

### Strengths
- Solves a highly specific, modern, and growing pain point that standard OS tools fail to address.
- Privacy-first approach builds deep user trust.
- Data granularity (tracking items vs. time) offers unique behavioral insights.

### Weaknesses
- Heavy reliance on Accessibility Services, which is a fragile API not intended for this use case.
- Constant cat-and-mouse game with third-party app UI updates.
- Aggressive OEM battery killers.

### Unique Selling Points (USPs)
- The only app that tracks *how many* Reels you've watched, not just how long the app has been open.
- Scroll velocity analytics.
- Real-time in-app overlays to break the scrolling trance.

### Likelihood of Success
Moderate to High in terms of product-market fit. High risk in terms of technical maintenance and Google Play compliance. If the technical hurdles can be managed, the organic demand for this specific solution is massive.

### Challenges in Development
Building a robust Accessibility parser that doesn't hardcode View IDs (which change weekly) but instead relies on structural heuristics (e.g., a ViewPager containing a VideoView and a vertical scroll architecture).

### Challenges in Publishing
Google's automated review bots frequently reject apps using `BIND_ACCESSIBILITY_SERVICE` unless they fall under specific categories. It will likely require multiple appeals and a highly detailed video demonstration to prove it falls under "Digital Wellbeing" exceptions.

### Suggestions for Improvement (Technical Pivot)
To mitigate the risk of Google Play rejection and UI maintenance, consider an alternative technical approach for Version 1: 
Instead of deep Accessibility parsing, use `UsageStatsManager` combined with **Network Traffic Analysis** (via a local VPN interface, `VpnService`) to detect the specific bursty data patterns of short-form video streaming. While less precise for counting individual videos, it is vastly more stable against UI updates and less likely to trigger Google's Accessibility policy violations. This hybrid approach could ensure the app survives the Google Play review process while still providing superior granularity to standard screen time apps.
