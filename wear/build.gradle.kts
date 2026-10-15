plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.metrolist.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.metrolist.wear"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Galaxy Watch 7 and most current watches run a 32-bit userspace on 64-bit CPUs.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
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
    implementation(project(":extraction"))
    implementation(libs.activity)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.datastore)
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugaring)
    testImplementation(libs.junit)
}
