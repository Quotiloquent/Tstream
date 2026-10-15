import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noapmat.tstream"
    // libmpv 1.0.0 requires compileSdk >= 36 (checkDebugAarMetadata). This only allows newer APIs at compile time;
    // targetSdk below (runtime behaviour) is deliberately left alone.
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    androidResources {
        // The UI is English only: drop the translations of Material / AppCompat.
        localeFilters += listOf("en")
    }

    defaultConfig {
        applicationId = "com.noapmat.tstream"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.1"
        // Only the helper library below is limited here. The APK ABIs come from `splits` (ndk.abiFilters would
        // conflict with it).
        externalNativeBuild { cmake { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }
    }

    // One APK per CPU architecture (each only carries its own native libs: libmpv + FFmpeg are by far the biggest part
    // of the app) plus a universal one. Output: app-arm64-v8a-release.apk, app-armeabi-v7a-release.apk, app-universal-release.apk
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // libmpv ships x86 / x86_64 builds too; the universal APK would otherwise contain them although only the two
    // ARM ABIs are supported (libtorrent4j is only added for those).
    packaging {
        jniLibs {
            excludes += setOf("**/x86/**", "**/x86_64/**")
            useLegacyPackaging = true
        }
    }

    // assembleRelease would otherwise also run lintVital; this project has only ever been built as debug, so don't
    // let a pre-existing lint finding block the APKs.
    lint { checkReleaseBuilds = false }

    buildTypes {
        release {
            isMinifyEnabled = true      // R8: removes unused code and optimises the rest
            isShrinkResources = true    // removes unused resources
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs directly (sideloading). Use a real
            // signingConfig here if the app is ever published.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // (the old kotlinOptions { } block is an error since Kotlin 2.2)
    buildFeatures { viewBinding = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val lt = "2.1.0-35"

dependencies {
    // BitTorrent engine (libtorrent via JNI) + native libs for each ABI
    implementation("org.libtorrent4j:libtorrent4j:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt")

    // Player engine: libmpv (MediaCodec hardware decoding with FFmpeg software fallback for 10-bit HEVC etc.,
    // libass for real ASS/SSA rendering, GPU renderer, user-tunable through mpv options). mpv 0.41 + FFmpeg 8.1.
    // Built with Kotlin 2.2, hence the Kotlin plugin version in the root build.gradle.kts.
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
