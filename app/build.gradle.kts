import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------------------------
// sherpa-onnx: scarica l'AAR ufficiale (non è pubblicato su Maven) e ne verifica l'impronta SHA-256.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar: File get() = file("libs/sherpa-onnx-$sherpaVersion.aar")

run {
    fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    if (!sherpaAar.exists() || sha(sherpaAar) != sherpaSha256) {
        sherpaAar.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        logger.lifecycle("Scarico sherpa-onnx $sherpaVersion…")
        URI(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
        check(sha(sherpaAar) == sherpaSha256) { "Impronta SHA-256 di sherpa-onnx non valida" }
    }
}

android {
    // L'identificativo interno resta quello storico: cambiarlo creerebbe una seconda app sul telefono,
    // senza le impostazioni e le chiavi già salvate.
    namespace = "com.nexa.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexa.assistant"
        minSdk = 29
        targetSdk = 36
        // Su GitHub ogni compilazione ha un numero più alto, così l'aggiornamento si installa sopra.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // Vivo X300 Pro e quasi tutti i telefoni moderni sono a 64 bit ARM.
        ndk { abiFilters += listOf("arm64-v8a") }

        // Da qui l'app scarica gli aggiornamenti (release "ultima-versione"). Prova prima il nuovo nome
        // del repository, poi quello storico: funziona sia prima sia dopo la rinomina su GitHub.
        buildConfigField(
            "String", "UPDATE_BASE_URLS",
            "\"https://github.com/zsurann/Project-Memy/releases/download/ultima-versione," +
                "https://github.com/zsurann/Memy/releases/download/ultima-versione," +
                "https://github.com/zsurann/Project-Nexa/releases/download/ultima-versione\"",
        )
    }

    signingConfigs {
        // Chiave fissa del progetto: così ogni nuovo APK si installa sopra il precedente
        // senza dover disinstallare. I nomi "nexa" sono quelli storici dell'app (prima si chiamava Nexa):
        // cambiarli significherebbe cambiare firma e perdere gli aggiornamenti.
        create("nexa") {
            storeFile = rootProject.file("keystore/nexa-debug.jks")
            storePassword = System.getenv("NEXA_KEYSTORE_PASSWORD") ?: "nexa-debug"
            keyAlias = System.getenv("NEXA_KEY_ALIAS") ?: "nexa"
            keyPassword = System.getenv("NEXA_KEY_PASSWORD") ?: "nexa-debug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("nexa")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("nexa")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Facoltativo: mirror di Maven Central per Robolectric (utile se Maven Central limita le richieste).
            System.getenv("ROBOLECTRIC_REPO")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            // Facoltativo: librerie native sherpa-onnx per Linux, per provare il riconoscimento vocale sul computer.
            System.getenv("SHERPA_JNI_DIR")?.let { test.systemProperty("java.library.path", it) }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // sherpa-onnx: da Kotlin si usa solo la libreria JNI (che dipende da onnxruntime); le API C/C++ non servono.
            excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
            // Librerie compresse nell'APK: download degli aggiornamenti più leggero.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }
}

dependencies {
    // Cervello: SDK ufficiale Anthropic (Claude)
    implementation("com.anthropic:anthropic-java:3.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Riconoscimento vocale offline di alta qualità: sherpa-onnx (modello NVIDIA Parakeet TDT 0.6B v3 + Silero VAD).
    // L'AAR ufficiale viene scaricato dalle release di GitHub al primo build (vedi task downloadSherpaOnnx).
    implementation(files(sherpaAar))
    // Per estrarre il modello (archivio .tar.bz2) sul telefono.
    implementation("org.apache.commons:commons-compress:1.28.0")

    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Screenshot delle schermate (Robolectric con grafica nativa)
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

