plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noapmat.tstream"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.noapmat.tstream"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // Only the helper library below is limited here. The APK ABIs come from `splits` (ndk.abiFilters would
        // conflict with it).
        externalNativeBuild { cmake { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }
    }

    // One APK per CPU architecture (each only carries its own native libs: libVLC is by far the biggest part of the
    // app) plus a universal one. Output: app-arm64-v8a-release.apk, app-armeabi-v7a-release.apk, app-universal-release.apk
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // libVLC ships x86 / x86_64 builds too; the universal APK would otherwise contain them although only the two
    // ARM ABIs are supported (libtorrent4j is only added for those).
    packaging { jniLibs { excludes += setOf("**/x86/**", "**/x86_64/**") } }

    // assembleRelease would otherwise also run lintVital; this project has only ever been built as debug, so don't
    // let a pre-existing lint finding block the APKs.
    lint { checkReleaseBuilds = false }

    // The UI is English only: drop the translations of libVLC / Material / AppCompat.
    androidResources { localeFilters += listOf("en") }

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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

val lt = "2.1.0-35"

dependencies {
    // BitTorrent engine (libtorrent via JNI) + native libs for each ABI
    implementation("org.libtorrent4j:libtorrent4j:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt")

    // Player engine: libVLC (software decoders for 10-bit HEVC etc., libass for real ASS/SSA rendering)
    implementation("org.videolan.android:libvlc-all:3.6.5")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
