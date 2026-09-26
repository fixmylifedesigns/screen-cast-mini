plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystoreFile = rootProject.file("app/keystore.jks")

android {
    namespace = "com.fixmylife.screencastmini"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fixmylife.screencastmini"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("shared") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "selfiescreen"
                keyAlias = "selfie"
                keyPassword = "selfiescreen"
            }
        }
    }

    buildTypes {
        debug {
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
