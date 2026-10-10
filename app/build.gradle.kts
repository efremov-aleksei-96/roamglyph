import java.io.File

plugins {
    id("com.android.application")
}

fun signingValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseKeystorePath = signingValue("ROAMGLYPH_KEYSTORE")
val releaseStorePassword = signingValue("ROAMGLYPH_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("ROAMGLYPH_KEY_ALIAS")
val releaseKeyPassword = signingValue("ROAMGLYPH_KEY_PASSWORD")

val hasReleaseSigning =
    !releaseKeystorePath.isNullOrBlank() &&
    File(releaseKeystorePath).exists() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

// Every tested source revision gets a visible dev build identity, while the
// integer versionCode increases across user-distributed development builds.
val devRevision = System.getenv("GITHUB_SHA")
    ?.take(8)
    ?.lowercase()
    ?: "local"

val requireReleaseSigning =
    signingValue("ROAMGLYPH_REQUIRE_KEYSTORE")?.lowercase() in setOf("1", "true", "yes")

if (requireReleaseSigning && !hasReleaseSigning) {
    throw GradleException(
        "ROAMGLYPH_REQUIRE_KEYSTORE is enabled but release signing is incomplete. " +
            "Provide ROAMGLYPH_KEYSTORE, ROAMGLYPH_KEYSTORE_PASSWORD, " +
            "ROAMGLYPH_KEY_ALIAS, and ROAMGLYPH_KEY_PASSWORD."
    )
}

android {
    namespace = "com.sensareth.roamglyph"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.sensareth.roamglyph"
        minSdk = 29
        targetSdk = 36
        versionCode = 16
        versionName = "0.5.0-dev.16+g$devRevision"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.incremental"] = "true"
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("com.uber:h3-android:4.5.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.room:room-runtime:2.8.5")
    annotationProcessor("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
}
