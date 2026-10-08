plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.livesignalassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.livesignalassistant.recency71727expv2r3"
        minSdk = 26
        targetSdk = 35
        versionCode = 49
        versionName = "72.0.2-FORENSIC"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    testImplementation("junit:junit:4.13.2")
}
