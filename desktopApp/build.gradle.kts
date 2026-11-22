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
    implementation(libs.mcp.kotlin.server)
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}

// Runs the CLI from a source checkout, for example: gradlew.bat :desktopApp:cli --args="connect tiktok".
// The installed `foursight` command comes from scripts/install-windows.ps1.
tasks.register<JavaExec>("cli") {
    group = "application"
    description = "Runs the FourSight command line."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.rmac0001.foursight.desktop.MainKt")
    standardInput = System.`in`
}

// A small Java runtime for the `foursight` command and the scheduled runs. The app image's bundled
// runtime has no java.exe or javaw.exe (jpackage strips them), so the CLI gets its own, linked with
// jlink from the build's JDK 21 toolchain. Used by scripts/install-windows.ps1.
val cliModules =
    listOf(
        "java.base",
        "java.desktop",
        "java.logging",
        "java.management",
        "java.instrument",
        "java.naming",
        "java.net.http",
        "java.prefs",
        "java.sql",
        "java.xml",
        "jdk.charsets",
        "jdk.crypto.ec",
        "jdk.httpserver",
        "jdk.unsupported",
        "jdk.zipfs",
    )
val jdkHome =
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .get()
        .metadata.installationPath.asFile
val cliRuntimeDir = layout.buildDirectory.dir("cli-runtime")
val cleanCliRuntime by tasks.registering(Delete::class) { delete(cliRuntimeDir) }
tasks.register<Exec>("cliRuntime") {
    group = "distribution"
    description = "Links the Java runtime used by the installed foursight command."
    dependsOn(cleanCliRuntime)
    inputs.property("modules", cliModules)
    outputs.dir(cliRuntimeDir)
    val jlink = File(jdkHome, if (System.getProperty("os.name").startsWith("Windows")) "bin/jlink.exe" else "bin/jlink")
    commandLine(
        jlink.absolutePath,
        "--add-modules",
        cliModules.joinToString(","),
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--output",
        cliRuntimeDir
            .get()
            .dir("image")
            .asFile.absolutePath,
    )
}

compose.desktop {
    application {
        mainClass = "io.github.rmac0001.foursight.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "FourSight"
            packageVersion = "0.1.0"
            description = "Personal creator analytics"
            // From suggestRuntimeModules (the loopback sign-in listener, the HTTP client, SQLite, JNA),
            // plus jdk.crypto.ec so the trimmed runtime can make modern TLS connections.
            modules(
                "java.instrument",
                "java.management",
                "java.net.http",
                "java.sql",
                "jdk.httpserver",
                "jdk.unsupported",
                "jdk.crypto.ec",
            )
            windows {
                menuGroup = "FourSight"
                perUserInstall = true
                // Fixed so later installers upgrade this one instead of installing beside it.
                upgradeUuid = "8d1f6c2e-5b7a-4c39-9e0f-3a6b2d4c7e18"
            }
        }
    }
}
