// Signing is configured only from a private PKCS#12 keystore.
// Never check the keystore or its password into the public repository.
val stableSigningFile = providers.environmentVariable("AGENTBRIDGE_SIGNING_STORE_FILE").orNull
val stableSigningPassword = providers.environmentVariable("AGENTBRIDGE_SIGNING_PASSWORD").orNull
val hasStableSigning = !stableSigningFile.isNullOrBlank() && !stableSigningPassword.isNullOrBlank()

plugins { id("com.android.application") }

android {
    namespace = "com.pluginslab.agentbridge.hooks"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pluginslab.agentbridge.hooks"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
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
}

dependencies {
    // Provided by Vector 2.2 / libxposed API 102 in injected app processes.
    compileOnly("io.github.libxposed:api:102.0.0")
}
