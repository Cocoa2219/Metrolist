plugins {
    id("com.android.library")
}

android {
    namespace = "com.metrolist.extraction"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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
    api(project(":innertube"))
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugaring)
    testImplementation(libs.junit)
}
