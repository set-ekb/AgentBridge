plugins { id("com.android.application") }

android {
    namespace = "com.pluginslab.agentbridge.hooks"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pluginslab.agentbridge.hooks"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
