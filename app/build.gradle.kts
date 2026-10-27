plugins {
    id("com.android.application")
}

android {
    namespace = "com.xsoulp.ohporta"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xsoulp.ohporta"
        minSdk = 30
        targetSdk = 36
        versionCode = 8
        versionName = "1.7"

        buildConfigField("boolean", "USE_BIOMETRIC_AUTH", "true")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
        create("pin") {
            initWith(getByName("debug"))
            isDebuggable = true
            versionNameSuffix = "-pin"
            buildConfigField("boolean", "USE_BIOMETRIC_AUTH", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
