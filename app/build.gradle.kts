plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes the run number so every release gets a higher versionCode (installs as an update).
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.luokai.luoworldbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luokai.luoworldbox"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    signingConfigs {
        create("release") {
            // Public hobby key so every CI build is signed with the same identity and updates install cleanly.
            storeFile = file("luoworldbox.jks")
            storePassword = "luoworldbox"
            keyAlias = "luoworldbox"
            keyPassword = "luoworldbox"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
