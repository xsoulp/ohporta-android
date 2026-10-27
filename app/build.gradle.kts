plugins {
    id("com.android.application")
}

android {
    namespace = "pt.xsoulp.domotica"
    compileSdk = 36

    defaultConfig {
        applicationId = "pt.xsoulp.domotica"
        minSdk = 30
        targetSdk = 36
        versionCode = 6
        versionName = "1.5"

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
        create("pixel8a") {
            initWith(getByName("debug"))
            isDebuggable = true
            versionNameSuffix = "-pixel-8a"
            buildConfigField("boolean", "USE_BIOMETRIC_AUTH", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
