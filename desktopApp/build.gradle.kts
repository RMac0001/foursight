import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.core)
}

// Runs the CLI from a source checkout, for example: gradlew.bat :desktopApp:cli --args="connect tiktok".
// The installed `foursight` console command comes later in step 3.
tasks.register<JavaExec>("cli") {
    group = "application"
    description = "Runs the FourSight command line."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rmac0001.foursight.desktop.MainKt")
    standardInput = System.`in`
}

compose.desktop {
    application {
        mainClass = "io.github.rmac0001.foursight.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "FourSight"
            packageVersion = "0.1.0"
            description = "Personal creator analytics"
            windows {
                menuGroup = "FourSight"
                perUserInstall = true
                // Fixed so later installers upgrade this one instead of installing beside it.
                upgradeUuid = "8d1f6c2e-5b7a-4c39-9e0f-3a6b2d4c7e18"
            }
        }
    }
}
