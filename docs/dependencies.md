# Project Dependencies

This document catalogs the essential third-party libraries and frameworks powering the REEL application, located in `app/build.gradle.kts`.

## 1. Core Frameworks

### Jetpack Compose (`androidx.compose`)
- **Version**: BOM `2024.05.00`
- **Why it exists**: The declarative UI framework used for 100% of the visual interface.
- **Major APIs**: `Modifier`, `Canvas`, `AnimatedVisibility`, `LazyColumn`.
- **Alternatives**: XML Layouts (obsolete for new projects).

### Compose Navigation (`androidx.navigation:navigation-compose`)
- **Version**: `2.7.7`
- **Why it exists**: Manages the back stack, deep linking, and transitions between Compose screens.
- **Interaction**: Heavily interacts with Hilt (`hilt-navigation-compose`) to scope ViewModels to specific back stack entries.

## 2. Architecture & State

### Dagger Hilt (`com.google.dagger:hilt-android`)
- **Version**: `2.51.1`
- **Why it exists**: Dependency Injection framework. Manages the lifecycles of Repositories, DAOs, and singletons like `OverlayStateManager`.
- **Major APIs**: `@HiltViewModel`, `@AndroidEntryPoint`, `@Singleton`, `@Inject`.
- **Alternatives**: Koin (lighter, but Hilt provides better compile-time safety and standard Jetpack integration).

### Kotlin Coroutines (`kotlinx-coroutines-core`)
- **Why it exists**: Asynchronous programming for database queries, network calls (if added later), and event debouncing.
- **Major APIs**: `StateFlow`, `SharedFlow`, `viewModelScope`, `Dispatchers`.

## 3. Storage

### Room Database (`androidx.room`)
- **Version**: `2.6.1`
- **Why it exists**: SQLite object mapping. Stores the massive list of historical scrolling sessions efficiently.
- **Major APIs**: `@Dao`, `@Entity`, `RoomDatabase`.

### DataStore Preferences (`androidx.datastore:datastore-preferences`)
- **Version**: `1.1.1`
- **Why it exists**: Modern replacement for `SharedPreferences`. Fully asynchronous and Flow-based. Stores strict mode flags and daily limits.
