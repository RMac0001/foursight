plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Roger's release key lives outside the repo. Its location and password are read from his user-level
 * `~/.gradle/gradle.properties` (never from the repo): foursight.keystore, foursight.keystore.password,
 * foursight.key.alias. Without them (CI, other machines) the release build is left unsigned.
 */
val keystorePath = providers.gradleProperty("foursight.keystore").orNull
val keystorePassword = providers.gradleProperty("foursight.keystore.password").orNull
val keyAliasName = providers.gradleProperty("foursight.key.alias").orNull ?: "foursight"

android {
    namespace = "io.github.rmac0001.foursight.android"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        // The application ID is permanent: the Android OAuth clients (Google, TikTok, Meta) are
        // registered against it.
        applicationId = "io.github.rmac0001.foursight"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (keystorePath != null && keystorePassword != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                // A PKCS12 keystore (keytool's default) uses one password for the store and the key.
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.ktor.client.android)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.work.runtime)
}
