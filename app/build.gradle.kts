plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
}

// Keep signing resolution identical for variant configuration and release-task validation.
val releaseKeystorePath = System.getenv("KEYSTORE_PATH") ?: project.findProperty("KEYSTORE_PATH") as? String
val releaseKeystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: project.findProperty("KEYSTORE_PASSWORD") as? String
val releaseKeyAlias = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS") as? String
val releaseKeyPassword = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD") as? String

android {
    namespace = "com.reeltrak.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.reeltrak.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val isReleaseSigningConfigured = !releaseKeystorePath.isNullOrBlank() &&
            !releaseKeystorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank() &&
            file(releaseKeystorePath).isFile

    signingConfigs {
        if (isReleaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Explicitly set null: NEVER fall back to debug signing for release builds
                signingConfig = null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "ENVIRONMENT", "\"prod\"")
            buildConfigField("String", "WORKER_BASE_URL", "\"https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"refocus-504119\"")
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            val stagingWorkerUrl = (project.findProperty("STAGING_WORKER_BASE_URL") as? String)
                ?: System.getenv("STAGING_WORKER_BASE_URL")
                ?: "https://refocus-razorpay-worker-staging.refocus-razorpay-worker.workers.dev"
            val stagingFirebaseProjectId = (project.findProperty("STAGING_FIREBASE_PROJECT_ID") as? String)
                ?: System.getenv("STAGING_FIREBASE_PROJECT_ID")
                ?: "PLACEHOLDER_STAGING_FIREBASE_PROJECT_ID"

            buildConfigField("String", "ENVIRONMENT", "\"staging\"")
            buildConfigField("String", "WORKER_BASE_URL", "\"$stagingWorkerUrl\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$stagingFirebaseProjectId\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "RemoveWorkManagerInitializer"
        checkReleaseBuilds = true
        abortOnError = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            excludes += "**/libdatastore_shared_counter.so"
        }
    }

    sourceSets {
        getByName("test").assets.srcDirs(files("$projectDir/schemas"))
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    
    // Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Compose Material 3 & UI
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.6.0")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    
    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    
    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Google Auth & Credentials
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Firebase Auth & Firestore & App Check
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Gson for TypeConverters
    implementation("com.google.code.gson:gson:2.10.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Razorpay Android Standard Checkout SDK
    implementation("com.razorpay:checkout:1.6.38")

    // OkHttp for secure backend communication
    implementation("com.squareup.okhttp3:okhttp:4.12.0")


    // Google Play In-App Updates
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Checks whether production release signing is configured or explicitly unsigned without exposing secrets."
    notCompatibleWithConfigurationCache("Ad-hoc script task")
    val keystorePathProp = releaseKeystorePath
    val keystorePasswordProp = releaseKeystorePassword
    val keyAliasProp = releaseKeyAlias
    val keyPasswordProp = releaseKeyPassword
    doLast {
        val keystorePath = keystorePathProp
        val keystorePassword = keystorePasswordProp
        val keyAlias = keyAliasProp
        val keyPassword = keyPasswordProp

        val isConfigured = !keystorePath.isNullOrBlank() &&
                !keystorePassword.isNullOrBlank() &&
                !keyAlias.isNullOrBlank() &&
                !keyPassword.isNullOrBlank() &&
                file(keystorePath).exists()

        if (isConfigured) {
            println("==================================================")
            println("STATUS: SIGNED RELEASE CONFIGURED")
            println("Keystore Location: Found (${file(keystorePath!!).name})")
            println("Key Alias: $keyAlias")
            println("==================================================")
        } else {
            println("==================================================")
            println("STATUS: UNSIGNED LOCAL RELEASE")
            println("Notice: Production signing environment variables/properties are not set or keystore file not found.")
            println("Behavior: assembleRelease will produce an explicitly unsigned artifact (NEVER falls back to debug signing).")
            println("==================================================")
        }
    }
}

val validateStagingEnvironment by tasks.registering {
    group = "verification"
    description = "Enforces that staging builds use genuinely isolated, non-placeholder, non-production staging infrastructure."
    notCompatibleWithConfigurationCache("Ad-hoc script task")
    val projectDirObj = projectDir
    val stagingWorkerUrlProp = (project.findProperty("STAGING_WORKER_BASE_URL") as? String) ?: System.getenv("STAGING_WORKER_BASE_URL")
    doLast {
        val stagingServicesFile = File(projectDirObj, "src/staging/google-services.json")
        if (!stagingServicesFile.exists()) {
            throw GradleException("STAGING ISOLATION FAILED: 'app/src/staging/google-services.json' is missing. Staging cannot fall back to production google-services.json.")
        }
        val content = stagingServicesFile.readText()
        if (content.contains("refocus-504119") || content.contains("524118238647")) {
            throw GradleException("CRITICAL ISOLATION FAULT: 'app/src/staging/google-services.json' contains production Firebase project ID/number ('refocus-504119'). Staging must use a dedicated staging Firebase project.")
        }
        if (content.contains("PLACEHOLDER", ignoreCase = true)) {
            throw GradleException("STAGING CONFIGURATION INCOMPLETE: 'app/src/staging/google-services.json' contains placeholder values. Provide genuine staging Firebase project credentials before assembling staging builds.")
        }

        val workerUrl = stagingWorkerUrlProp ?: "https://refocus-razorpay-worker-staging.refocus-razorpay-worker.workers.dev"

        if (workerUrl.contains("refocus-razorpay-worker.refocus-razorpay-worker.workers.dev")) {
            throw GradleException("CRITICAL ISOLATION FAULT: Staging worker URL cannot point to the production Worker ('https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev').")
        }
        if (workerUrl.contains("PLACEHOLDER", ignoreCase = true)) {
            throw GradleException("STAGING CONFIGURATION INCOMPLETE: Staging worker URL contains placeholders: $workerUrl")
        }
    }
}

val validateProdEnvironment by tasks.registering {
    group = "verification"
    description = "Enforces that production builds use authoritative production infrastructure and never point to staging."
    notCompatibleWithConfigurationCache("Ad-hoc script task")
    val projectDirObj = projectDir
    doLast {
        val prodInFlavor = File(projectDirObj, "src/prod/google-services.json")
        val prodInRoot = File(projectDirObj, "google-services.json")
        val prodServicesFile = if (prodInFlavor.exists()) prodInFlavor else prodInRoot

        if (!prodServicesFile.exists()) {
            throw GradleException("PRODUCTION BUILD FAILED: Production google-services.json not found.")
        }
        val content = prodServicesFile.readText()
        if (!content.contains("refocus-504119") || !content.contains("524118238647")) {
            throw GradleException("PRODUCTION BUILD FAILED: Production google-services.json does not contain authoritative production project ('refocus-504119').")
        }
    }
}

tasks.configureEach {
    if (name.startsWith("packageStaging") || name.startsWith("bundleStaging")) {
        dependsOn(validateStagingEnvironment)
    }
    if (name.startsWith("packageProd") || name.startsWith("bundleProd")) {
        dependsOn(validateProdEnvironment)
    }
    if (name == "bundleProdRelease") {
        dependsOn("requireProdReleaseSigning")
    }
}

tasks.register("requireProdReleaseSigning") {
    group = "verification"
    description = "Fails unless the production release signing configuration is complete."
    notCompatibleWithConfigurationCache("Reads local signing configuration")
    doLast {
        val signingConfig = android.buildTypes.getByName("release").signingConfig
        val configured = signingConfig != null &&
            signingConfig.storeFile?.isFile == true &&
            !signingConfig.storePassword.isNullOrBlank() &&
            !signingConfig.keyAlias.isNullOrBlank() &&
            !signingConfig.keyPassword.isNullOrBlank()

        if (!configured) {
            throw GradleException(
                "Production release signing is incomplete. Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD (environment variables take precedence over Gradle properties) and verify the keystore file exists."
            )
        }
    }
}

tasks.withType<Test> {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(2, 4)
    forkEvery = 100L
}
