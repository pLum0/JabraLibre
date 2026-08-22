plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.plum0.jabralibre"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.plum0.jabralibre"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.4"
    }
    signingConfigs {
        getByName("debug") {
            // fester Debug-Keystore im Repo → identische Signatur bei jedem CI-Build
            // (wichtig für Obtainium/Updates: kein Deinstallieren nötig)
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
