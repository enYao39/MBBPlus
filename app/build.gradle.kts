plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.floatwindow.morebubblebutton"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.floatwindow.morebubblebutton"
        minSdk = 36
        targetSdk = 37
        versionCode = 5
        versionName = "1.5"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("[LOCAL_PATH_REDACTED]")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("[LOCAL_PATH_REDACTED]")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}