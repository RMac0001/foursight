plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)

    jvm("desktop")

    android {
        namespace = "io.github.rmac0001.foursight.core"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        named("desktopMain").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.jna.platform)
        }
        named("desktopTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
    }
}

sqldelight {
    databases {
        create("FourSightDatabase") {
            packageName.set("io.github.rmac0001.foursight.db")
            // Every schema change after version 1 needs a migration file in src/commonMain/sqldelight/migrations;
            // verifyMigrations checks the migrations rebuild the same schema.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            migrationOutputDirectory.set(layout.buildDirectory.dir("generated/migrations"))
            verifyMigrations.set(true)
        }
    }
}
