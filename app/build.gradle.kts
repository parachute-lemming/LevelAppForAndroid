plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.parachute_lemming.level"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.parachute_lemming.level"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Release signing pulled from ~/.gradle/gradle.properties so the keystore + passwords
    // never live inside the repo. If the user-level properties aren't set (e.g. on a
    // fresh clone), release builds will fall back to the debug signing config.
    val releaseStorePath = providers.gradleProperty("LEVEL_KEYSTORE_FILE").orNull
    val releaseStorePass = providers.gradleProperty("LEVEL_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = providers.gradleProperty("LEVEL_KEY_ALIAS").orNull
    val releaseKeyPass = providers.gradleProperty("LEVEL_KEY_PASSWORD").orNull
    val canSignRelease = releaseStorePath != null && file(releaseStorePath).exists()

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
