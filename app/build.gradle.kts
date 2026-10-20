// Signing is configured only from a private PKCS#12 keystore.
// Never check the keystore or its password into the public repository.
val stableSigningFile = providers.environmentVariable("AGENTBRIDGE_SIGNING_STORE_FILE").orNull
val stableSigningPassword = providers.environmentVariable("AGENTBRIDGE_SIGNING_PASSWORD").orNull
val hasStableSigning = !stableSigningFile.isNullOrBlank() && !stableSigningPassword.isNullOrBlank()

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.pluginslab.agentbridge"
    compileSdk = 35

    // Use system aapt2 on Termux (AGP's bundled one is x86_64)
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.pluginslab.agentbridge"
        minSdk = 29
        targetSdk = 34
        versionCode = 35
        versionName = "0.8.5-local"
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(stableSigningFile!!)
                storePassword = stableSigningPassword
                keyAlias = "agentbridge"
                keyPassword = stableSigningPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // CI only distributes release APKs; local debug builds may remain ephemeral.
            if (hasStableSigning) signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasStableSigning) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.*"
        }
    }
}

dependencies {
    // Ktor server (CIO engine - lightweight for Android)
    implementation("io.ktor:ktor-server-cio:3.1.1")
    implementation("io.ktor:ktor-server-sse:3.1.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("io.ktor:ktor-server-auth:3.1.1")

    // MCP Kotlin SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.4.0")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

// Unit tests exercise the agent policy without a phone or API key.
dependencies { testImplementation("junit:junit:4.13.2") }
