# Build System

The build configuration of REEL is managed via Gradle Kotlin DSL (`build.gradle.kts`).

## 1. Gradle Configuration

### Project-Level
- **Plugins**: Android Application, Kotlin Android, Kotlin Compose, KSP (Kotlin Symbol Processing), and Hilt.

### App-Level
- **Compile SDK**: 34
- **Min SDK**: 26 (Android 8.0 Oreo). Required for modern accessibility features and Compose compatibility.
- **Target SDK**: 34 (Android 14).

## 2. Kotlin Symbol Processing (KSP)

REEL uses KSP instead of the older KAPT for annotation processing. This drastically improves compilation times.
- **Libraries using KSP**: Room (`androidx.room:room-compiler`), Dagger Hilt (`com.google.dagger:hilt-android-compiler`).

## 3. Build Optimization Assessment

As noted in `performance_baseline.md` and the recent performance audit, the current build script lacks a defined `release` variant.

**Missing Configurations (To-Do for Production):**
```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```
*Without these flags, the generated APK will contain unused Compose and Material 3 code, drastically inflating the binary size and preventing R8 optimizations like method inlining and dead-code stripping.*

## 4. APK vs AAB Generation

- For local debugging, standard APKs are generated via `./gradlew assembleDebug`.
- For Google Play distribution, the project must be built as an Android App Bundle (AAB) via `./gradlew bundleRelease` to enable Dynamic Delivery and density-specific APK generation.
