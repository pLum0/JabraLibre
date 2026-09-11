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
        versionCode = 14
        versionName = "0.3.7"
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
        release {
            isMinifyEnabled = false
            // AGP otherwise writes the git commit of the build tree into
            // META-INF/version-control-info.textproto, which makes the APK
            // depend on *which commit* was checked out rather than on the
            // sources. F-Droid builds the tagged commit, so any later commit
            // with identical sources would produce a different APK and the
            // reproducible-build check would fail on that one line.
            vcsInfo { include = false }
        }
    }
    // The dependency metadata block AGP adds is an opaque, Google-signed blob.
    // Leaving it out keeps the APK reproducible from source.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
