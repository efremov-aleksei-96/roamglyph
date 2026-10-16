import java.io.File

plugins {
    id("com.android.application")
}

fun signingValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }

// Developer APKs must use one persistent, dedicated PRIVATE certificate.
 // Do not reuse the accidentally published pre-0.5 debug signing key.
val devKeystorePath = signingValue("ROAMGLYPH_DEV_KEYSTORE")
val devStorePassword = signingValue("ROAMGLYPH_DEV_KEYSTORE_PASSWORD")
val hasDevSigning =
    !devKeystorePath.isNullOrBlank() &&
    File(devKeystorePath).isFile &&
    !devStorePassword.isNullOrBlank()
val requireDevSigning =
    signingValue("ROAMGLYPH_REQUIRE_DEV_KEYSTORE")?.lowercase() in setOf("1", "true", "yes")

if (requireDevSigning && !hasDevSigning) {
    throw GradleException(
        "Persistent development APK signing is required. " +
            "Provide ROAMGLYPH_DEV_KEYSTORE and ROAMGLYPH_DEV_KEYSTORE_PASSWORD. " +
            "Never distribute a CI runner's temporary default debug-signed APK."
    )
}

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
val devRevision = (System.getenv("ROAMGLYPH_SOURCE_SHA")
    ?: System.getenv("GITHUB_SHA"))
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
        versionCode = 32
        versionName = "0.5.0-dev.32+g$devRevision"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.incremental"] = "true"
            }
        }
    }

    signingConfigs {
        if (hasDevSigning) {
            create("persistentDev") {
                storeFile = File(devKeystorePath!!)
                storeType = "PKCS12"
                storePassword = devStorePassword
                keyAlias = "roamglyph-dev"
                keyPassword = devStorePassword
            }
        }
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
        getByName("debug") {
            if (hasDevSigning) {
                signingConfig = signingConfigs.getByName("persistentDev")
            }
        }
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
