# Folder Structure & Architecture (Native Android / Kotlin)

## Overview
To ensure ReelTrak is highly scalable, maintainable, and production-ready, the application follows a **Feature-Driven Clean Architecture** combined with **MVVM (Model-View-ViewModel)**. 

Because we are building a complex background-heavy app (Accessibility Services, Overlays), separating the UI from the background logic is critical. 

## Architectural Layers
1. **Presentation (UI)**: Built with Jetpack Compose. Handles rendering the QuestUI aesthetic and observing ViewModels.
2. **Domain**: Pure Kotlin. Contains the core business logic (e.g., `CheckDailyLimitUseCase`), independent of any Android framework.
3. **Data**: Handles Room Database, DataStore (Preferences), and usage stats retrieval.
4. **Services**: Android-specific background workers (Accessibility Service, Overlay Managers).

---

## Complete Folder Structure

```text
com.reeltrak.app
│
├── core/                               # Shared resources and base classes
│   ├── ui/                             # Global UI components & Design System
│   │   ├── theme/                      # QuestUI colors, typography, shapes
│   │   └── components/                 # Reusable glassmorphic panels, glowing buttons
│   ├── utils/                          # Extensions and helper functions
│   ├── di/                             # Dependency Injection (Hilt) modules
│   └── navigation/                     # App-wide navigation routes
│
├── domain/                             # Enterprise & Business Logic (No Android dependencies)
│   ├── models/                         # Core entities (Session, DailyBudget)
│   ├── repository/                     # Interfaces for data layer
│   └── usecases/                       
│       ├── CalculateScrollVelocityUseCase.kt
│       ├── DeductDailyBudgetUseCase.kt
│       └── GetWeeklyReportUseCase.kt
│
├── data/                               # Data layer implementation
│   ├── local/                          # Local Storage
│   │   ├── entity/                     # Room DB Entities
│   │   ├── dao/                        # Data Access Objects
│   │   └── database/                   # AppDatabase configuration
│   ├── preferences/                    # DataStore (Strict Mode, Theme settings)
│   └── repository_impl/                # Implementation of Domain interfaces
│
├── services/                           # The Core Engine (Android Specific)
│   ├── accessibility/                  # Screen parsing and scroll detection
│   │   ├── ReelDetectionService.kt
│   │   └── ViewNodeParser.kt
│   ├── overlay/                        # System Alert Window managers
│   │   ├── RealTimeCounterOverlay.kt
│   │   ├── MorningInterceptOverlay.kt
│   │   └── LimitReachedOverlay.kt
│   ├── boot/                           # Boot receivers to restart services
│   └── tracking/                       # Foreground service for session management
│
├── features/                           # Feature Modules (Self-contained)
│   │
│   ├── onboarding/                     # First-time setup
│   │   ├── ui/                         # Screens (Permissions, Intent Setup)
│   │   └── viewmodel/                  # OnboardingViewModel
│   │
│   ├── dashboard/                      # Main screen
│   │   ├── ui/                         # Ring charts, Quick stats
│   │   └── viewmodel/                  # DashboardViewModel
│   │
│   ├── analytics/                      # Deep dive reports
│   │   ├── ui/                         # QuestUI ledger screens
│   │   └── viewmodel/                  # AnalyticsViewModel
│   │
│   └── settings/                       # App limits and Strict Mode
│       ├── ui/                         
│       └── viewmodel/                  
│
└── ReelTrakApplication.kt              # App entry point (Hilt initialization)
```

## Architectural Rules
1. **Dependency Rule**: The `domain` layer must NEVER import anything from `data` or `presentation` (or any Android SDK). It is pure Kotlin logic.
2. **Unidirectional Data Flow (UDF)**: The UI sends "Events" to the ViewModel. The ViewModel updates a single "State" object, which the UI observes.
3. **No Direct Service-to-UI Communication**: The `ReelDetectionService` writes data to the `local` database. The `DashboardViewModel` observes the database via Kotlin Flows. They never talk to each other directly to prevent memory leaks and crashes.
4. **Single Source of Truth**: The local Room Database is the absolute source of truth for all tracked scroll data.

## Why this Structure?
By isolating the `services` (the fragile part dealing with Android APIs) from the `features` (the UI), we ensure that if Instagram updates its UI and breaks our `ViewNodeParser.kt`, we only have to fix that one file in the `services/accessibility` folder without touching the rest of the application.
