plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.livesignalassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.livesignalassistant.forensic7202"
        minSdk = 26
        targetSdk = 35
        versionCode = 51
        versionName = "72.0.4-VISION-REPAIR"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed, non-secret debug key committed on purpose: every CI build is signed identically,
            // so a normal update with the same applicationId can install over the previous build and keep Experience.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("lsa.replay.dev", System.getenv("LSA_REPLAY_DEV") ?: "")
            it.systemProperty("lsa.replay.holdout", System.getenv("LSA_REPLAY_HOLDOUT") ?: "")
            it.systemProperty("lsa.replay.root", System.getenv("LSA_REPLAY_ROOT") ?: "")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
