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

    buildTypes {
        debug {
            // The Wear OS emulator is x86_64; release builds stay ARM-only.
            ndk {
                abiFilters += "x86_64"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A personal build: signed with the debug key so it installs over a debug build, keeping the login.
            signingConfig = signingConfigs.getByName("debug")
            // -PwithEmulatorAbi adds x86_64 to try a release build on the emulator.
            if (providers.gradleProperty("withEmulatorAbi").isPresent) {
                ndk {
                    abiFilters += "x86_64"
                }
            }
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Romanization dictionaries are memory-mapped straight from the APK, which needs them stored, as in the phone app.
        noCompress += "lpd"
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

// Pretendard JP comes from the phone app's resources at build time rather than a second copy in git.
// Only Regular and Bold: each weight is ~5 MB.
abstract class SharedFontsTask : DefaultTask() {
    @get:InputFiles
    abstract val fonts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val fontDir = outputDir.get().asFile.resolve("font").apply { mkdirs() }
        fonts.forEach { it.copyTo(fontDir.resolve(it.name), overwrite = true) }
    }
}

val sharedFonts =
    tasks.register<SharedFontsTask>("sharedFonts") {
        fonts.from(rootProject.file("app/src/main/res/font/pretendardjp_regular.ttf"))
        fonts.from(rootProject.file("app/src/main/res/font/pretendardjp_bold.ttf"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(sharedFonts, SharedFontsTask::outputDir)
    }
}

dependencies {
    implementation(project(":extraction"))
    implementation(project(":lyrics"))
    // Romanization data for Japanese only; the phone app also ships Mandarin and Cantonese.
    implementation(libs.accompanist.lyrics.phonetics.data.japanese)
    implementation(libs.activity)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.navigation)
    implementation(libs.viewmodel.compose)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.session)
    implementation(libs.wear.ambient)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.palette)
    implementation(libs.datastore)
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugaring)
    testImplementation(libs.junit)
}
