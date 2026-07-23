plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tailapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tailapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                // Oboe's prefab package is built against the shared STL; linking
                // the static one alongside it is the classic way to end up with
                // two copies of the C++ runtime in one process.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        ndk {
            // ONNX Runtime dominates the APK — ~29 MB of native code per ABI,
            // stored uncompressed. Shipping the two ABIs that matter (arm64 for
            // every phone this targets in practice, x86_64 for the emulator)
            // roughly halves it. A genuinely 32-bit ARM device would need
            // armeabi-v7a added back here.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        // Lets CMake resolve Oboe's headers and .so straight out of its AAR.
        prefab = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Protocol/repository tests run on the JVM but touch android.util.Log;
            // returning defaults keeps them from throwing "not mocked".
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation("androidx.core:core-ktx:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Low-latency mic capture. Picks AAudio or OpenSL ES per device; the JNI
    // bridge lives in src/main/cpp.
    implementation("com.google.oboe:oboe:1.10.0")

    // On-device genre classification (com.tailapp.genre). Runs the Discogs-EffNet
    // embedding model and its genre head; the weights are not in this repo, see
    // docs/genre-model.md.
    //
    // CPU execution provider only, deliberately: NNAPI is deprecated as of
    // Android 15, and ORT's NNAPI EP partitions an EfficientNet back onto the CPU
    // for most of its ops anyway, so a delegate buys overhead and a second code
    // path. One 2-second patch every 3 seconds is a few tens of ms of CPU.
    //
    // Not cheap: 116 MB of the 139 MB debug APK, because AGP stores .so
    // uncompressed and this module ships all four ABIs. An arm64-only,
    // compressed release build carries ~11 MB of it. See docs/genre-model.md.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}