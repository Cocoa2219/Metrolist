plugins {
    id("com.android.application")
}

// Phase 0 probe: checks whether InnerTubeX resolves playable audio on Wear OS without a WebView PoToken.
android {
    namespace = "com.metrolist.wearprobe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.metrolist.wearprobe"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "probe"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":innertube"))
    implementation(libs.media3)
    implementation(libs.media3.okhttp)
    coreLibraryDesugaring(libs.desugaring)
    testImplementation(libs.junit)
}
