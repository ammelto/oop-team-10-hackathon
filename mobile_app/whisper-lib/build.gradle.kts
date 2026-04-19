plugins {
    id("com.android.library")
}

android {
    namespace = "com.whispercpp"
    compileSdk = 35

    defaultConfig {
        minSdk = 30

        ndk {
            abiFilters.clear()
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DGGML_CCACHE=OFF",
                    "-DGGML_OPENMP=ON",
                    "-DGGML_LTO=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                cFlags += listOf(
                    "-O3",
                    "-ffast-math",
                    "-fno-finite-math-only",
                    "-march=armv8.2-a+fp16+dotprod",
                )
                cppFlags += listOf(
                    "-O3",
                    "-ffast-math",
                    "-fno-finite-math-only",
                    "-fvectorize",
                    "-march=armv8.2-a+fp16+dotprod",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
