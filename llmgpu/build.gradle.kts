plugins {
    alias(libs.plugins.android.library)
}

// Variante GPU (OpenCL, noyaux Adreno) de llama.cpp, chargée seulement à la demande : si le
// téléphone n'expose pas libOpenCL.so, la variante CPU (module :llm) reste utilisée.
android {
    namespace = "com.karelisio.speech.llmgpu"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
                System.getenv("CMAKE_C_COMPILER_LAUNCHER")?.let {
                    arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=$it", "-DCMAKE_CXX_COMPILER_LAUNCHER=$it")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        jniLibs {
            // Bibliothèque de lien seulement : à l'exécution, celle du constructeur (/vendor) est utilisée.
            excludes += "**/libOpenCL.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":llm"))
}
