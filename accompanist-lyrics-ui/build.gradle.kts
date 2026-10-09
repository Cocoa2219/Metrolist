// Fork of com.mocharealm.accompanist:lyrics-ui 2.0.0-rc.2 (Apache-2.0, see LICENSE), flattened from
// Kotlin Multiplatform to an Android-only library so Metrolist can change its rendering.
plugins {
    id("com.android.library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.mocharealm.accompanist.lyrics.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_compiler_config.conf"))
}

dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.material3)
    implementation(libs.gaze.capsule)
    api(libs.accompanist.lyrics.core)
}
