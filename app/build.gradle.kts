import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// sherpa-onnx n'est pas publié sur Maven : on récupère l'AAR officiel de la release GitHub,
// épinglé par version + SHA-256.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Téléchargement de $url")
    val tmp = File(sherpaAar.path + ".part")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val actual = sha256(tmp)
    check(actual == sherpaSha256) { "SHA-256 inattendu pour sherpa-onnx AAR : $actual" }
    tmp.renameTo(sherpaAar)
}

val gitSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short=8", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "dev" }

android {
    namespace = "com.karelisio.speech"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.karelisio.speech"
        minSdk = 29
        targetSdk = 37
        // Numéro de run CI : chaque APK publié remplace le précédent sans désinstallation.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "Karelisio/Speech"
        buildConfigField("String", "UPDATE_URL", "\"https://github.com/$repo/releases/download/dev-latest/version.json\"")

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // Toujours compiler le natif en Release (-O3), même pour le variant debug :
                // les mesures de perf doivent être représentatives.
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

    signingConfigs {
        // Keystore de release fourni par la CI (secrets GitHub). À défaut, clé de dev
        // commitée pour que les APK successifs restent installables par-dessus.
        create("release") {
            val ks = System.getenv("SPEECH_KEYSTORE")
            if (ks != null && File(ks).exists()) {
                storeFile = File(ks)
                storePassword = System.getenv("SPEECH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SPEECH_KEY_ALIAS")
                keyPassword = System.getenv("SPEECH_KEY_PASSWORD")
            } else {
                storeFile = file("dev.keystore")
                storePassword = "speech-dev"
                keyAlias = "speech-dev"
                keyPassword = "speech-dev"
            }
        }
        getByName("debug") {
            storeFile = file("dev.keystore")
            storePassword = "speech-dev"
            keyAlias = "speech-dev"
            keyPassword = "speech-dev"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Captures d'écran (Roborazzi) : seulement avec -Pscreenshots, pas en CI.
                if (!project.hasProperty("screenshots")) test.exclude("**/screenshots/**")
                test.systemProperty("roborazzi.test.record", "true")
                System.getenv("ROBOLECTRIC_REPO")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
                test.maxHeapSize = "3g"
            }
        }
    }

    packaging {
        jniLibs {
            // Les .so sont chargés directement depuis l'APK (non compressés, alignés 16 Ko).
            useLegacyPackaging = false
            // API C/C++ de sherpa-onnx inutilisées : seul le JNI (+ onnxruntime) est chargé.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

dependencies {
    implementation(files(sherpaAar))
    implementation(project(":llm"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
