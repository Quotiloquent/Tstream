plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noapmat.tsream"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.noapmat.tsream"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes { release { isMinifyEnabled = false } }
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
    implementation("org.videolan.android:libvlc-all:3.6.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
