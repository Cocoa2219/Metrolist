plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// Lyrics providers, parsing and romanization shared by the phone and watch apps. The code keeps its original
// packages (com.metrolist.music.lyrics, com.metrolist.kugou, ...) so the phone app's imports didn't change.
android {
    namespace = "com.metrolist.lyrics"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's FileDescriptor interceptor goes through jdk.internal.access.SharedSecrets, which the JDK
        // doesn't export to unnamed modules.
        unitTests.all {
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":innertube"))
    api(libs.accompanist.lyrics.core)
    // Only the engine: each app adds the language data packs it ships and hands them to LyricsPhonetics.
    api(libs.accompanist.lyrics.phonetics.runtime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugaring)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
