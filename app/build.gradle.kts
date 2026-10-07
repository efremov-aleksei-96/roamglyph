plugins {
    id("com.android.application")
}

android {
    namespace = "com.sensareth.roamglyph"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = file("roamglyph-debug.keystore")
            storePassword = "android"
            keyAlias = "roamglyphdebug"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.sensareth.roamglyph"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("com.uber:h3-android:4.5.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.18.0")
}
