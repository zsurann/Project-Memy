plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nexa.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexa.assistant"
        minSdk = 29
        targetSdk = 36
        // Su GitHub ogni compilazione ha un numero più alto, così l'aggiornamento si installa sopra.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"

        // Vivo X300 Pro e quasi tutti i telefoni moderni sono a 64 bit ARM.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // Chiave fissa del progetto: così ogni nuovo APK si installa sopra il precedente
        // senza dover disinstallare. Vedi README per usare una chiave privata tua.
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
    }
    buildFeatures {
        compose = true
    }
    packaging {
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

    // Riconoscimento vocale offline (funziona senza servizi Google)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.19.1@aar")

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
}
