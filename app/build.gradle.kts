import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is read from keystore.properties (gitignored). Without it
// the release build falls back to the debug key, which is fine for local
// testing but must be replaced before Play Store upload.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Crash reporting DSN is read from sentry.properties (gitignored). Empty by
// default: the app then runs without any crash reporting, so the build and
// the app never require an account.
val sentryProps = Properties().apply {
    val f = rootProject.file("sentry.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val sentryDsn = (sentryProps.getProperty("dsn") ?: "").trim()
    .replace("\\", "\\\\").replace("\"", "\\\"")

// Hosted privacy-policy URL, set at build time with -PprivacyPolicyUrl=…
// (or in gradle.properties). Empty by default: the About dialog then only
// shows the in-app policy text.
val privacyPolicyUrl = (project.findProperty("privacyPolicyUrl") as String?)
    ?.trim().orEmpty()
    .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.sharenet.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sharenet.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.findByName("debug")
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
        viewBinding = true
        buildConfig = true // exposes BuildConfig.VERSION_NAME to the About screen
    }

    defaultConfig {
        // Empty until the developer drops a DSN into sentry.properties.
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        // Empty unless built with -PprivacyPolicyUrl=https://…
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.zxing.core) // QR rendering for the join-info code
    implementation(libs.sentry.android) // crash reporting, opt-in via DSN

    // JVM unit tests only — the HTTP proxy and state machine are pure Kotlin
    // with zero Android imports, so they run fast on the plain JVM.
    testImplementation(libs.junit)

    // Instrumented smoke test (device/emulator): `./gradlew connectedDebugAndroidTest`.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
