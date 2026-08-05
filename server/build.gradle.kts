import de.undercouch.gradle.tasks.download.Download
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.time.Instant

plugins {
    id(
        libs.plugins.kotlin.jvm
            .get()
            .pluginId,
    )
    id(
        libs.plugins.kotlin.serialization
            .get()
            .pluginId,
    )
    id(
        libs.plugins.ktlint
            .get()
            .pluginId,
    )
    application
    alias(libs.plugins.shadowjar)
    id(
        libs.plugins.buildconfig
            .get()
            .pluginId,
    )
    id(
        libs.plugins.jte
            .get()
            .pluginId,
    )
}

dependencies {
    // Shared
    implementation(libs.bundles.shared)
    testImplementation(libs.bundles.sharedTest)

    // OkHttp
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)

    // Javalin api
    implementation(libs.bundles.javalin)
    implementation(libs.bundles.jackson)

    // GraphQL
    implementation(libs.graphql.kotlin.server)
    implementation(libs.graphql.kotlin.scheme)
    implementation(libs.graphql.java.scalars)

    // Exposed ORM
    implementation(libs.bundles.exposed)
    implementation(libs.postgres)
    implementation(libs.h2)
    implementation(libs.hikaricp)

    // Exposed Migrations
    implementation(libs.exposed.migrations)

    // tray icon
    implementation(libs.bundles.systemtray)

    // dependencies of Mihon (Tachiyomi) extensions, some are duplicate, keeping it here for reference
    implementation(libs.injekt)
    implementation(libs.okhttp.core)
    implementation(libs.rxjava)
    implementation(libs.jsoup)

    // ComicInfo
    implementation(libs.serialization.xml.core)
    implementation(libs.serialization.xml)

    // Sort
    implementation(libs.sort)

    // asm for ByteCodeEditor(fixing SimpleDateFormat) (must match Dex2Jar version)
    implementation(libs.asm)

    // Disk & File
    implementation(libs.cache4k)
    implementation(libs.zip4j)
    implementation(libs.commonscompress)
    implementation(libs.junrar)

    // AES/CBC/PKCS7Padding Cypher provider for zh.copymanga
    implementation(libs.bouncycastle)

    // AndroidCompat
    implementation(projects.androidCompat)
    implementation(projects.androidCompat.config)

    // i18n
    implementation(projects.server.i18n)

    // Settings module
    implementation(projects.server.serverConfig)

    // uncomment to test extensions directly
//    implementation(fileTree("lib/"))
    implementation(kotlin("script-runtime"))

    testImplementation(libs.mockk)

    implementation(libs.cron4j)

    implementation(libs.cronUtils)

    implementation(libs.jwt)

    compileOnly(libs.kte)
}

jte {
    generate()
}

application {
    applicationDefaultJvmArgs =
        listOf(
            "-Djunrar.extractor.thread-keep-alive-seconds=30",
        )
    mainClass.set(MainClass)
}

sourceSets {
    main {
        resources {
            srcDir("src/main/resources")
            srcDir("build/generated/src/main/resources")
        }
        kotlin {
            srcDir("build/generated/src/main/kotlin")
        }
    }
    test {
        resources {
            srcDir("build/generated/src/test/resources")
        }
    }
}

buildConfig {
    className("BuildConfig")
    packageName("suwayomi.tachidesk.server.generated")

    useKotlinOutput()

    fun quoteWrap(obj: Any): String = """"$obj""""

    buildConfigField("String", "NAME", quoteWrap(rootProject.name))
    buildConfigField("String", "VERSION", quoteWrap(getTachideskVersion()))
    buildConfigField("String", "REVISION", quoteWrap(getTachideskRevision()))
    buildConfigField("String", "BUILD_TYPE", quoteWrap(if (System.getenv("ProductBuildType") == "Stable") "Stable" else "Preview"))
    buildConfigField("long", "BUILD_TIME", Instant.now().epochSecond.toString())

    buildConfigField("String", "WEBUI_TAG", quoteWrap(webUIRevisionTag))

    buildConfigField("String", "GITHUB", quoteWrap("https://github.com/Suwayomi/Suwayomi-Server"))
    buildConfigField("String", "DISCORD", quoteWrap("https://discord.gg/DDZdqZWaHA"))
    buildConfigField("String", "JCEF_VERSION", quoteWrap(libs.versions.jcef.get()))
    buildConfigField("String", "JCEF_JBR_RELEASE", quoteWrap(webviewJbrRelease))
}

tasks {
    shadowJar {
        isZip64 = true
        manifest {
            attributes(
                "Main-Class" to MainClass,
                "Implementation-Title" to rootProject.name,
                "Implementation-Vendor" to "The Suwayomi Project",
                "Specification-Version" to getTachideskVersion(),
                "Implementation-Version" to getTachideskRevision(),
                "Multi-Release" to true, // needed for polyglot
                "X-JBR-Release" to webviewJbrRelease,
            )
        }
        archiveBaseName.set(rootProject.name)
        archiveVersion.set(getTachideskVersion())
        archiveClassifier.set("")
        destinationDirectory.set(File("$rootDir/server/build"))
        mergeServiceFiles()
    }

    test {
        useJUnitPlatform {
            if (!project.hasProperty("masstest")) {
                exclude("**/masstest/*")
            }
        }
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }

    withType<KotlinJvmCompile> {
        compilerOptions {
            freeCompilerArgs.add(
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            )
        }
    }

    named<Copy>("processResources") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mustRunAfter("buildLocalWebUI")
    }

    /**
     * Builds the WebUI from the local Suwayomi-WebUI repository and packages it as WebUI.zip.
     * Set the Gradle property "webui.skip" to "true" to skip this task (e.g. -Pwebui.skip=true).
     * The local WebUI path can be overridden via -Pwebui.path=../Suwayomi-WebUI.
     */
    register("buildLocalWebUI") {
        group = "build"
        description = "Build WebUI from local Suwayomi-WebUI repository"

        val localWebUIPath = project.findProperty("webui.path") as? String
            ?: "${rootProject.projectDir}/../Suwayomi-WebUI"

        val pnpmCmd = sequenceOf(
            "${System.getenv("APPDATA")}\\npm\\pnpm.cmd",
            "${System.getenv("HOME")}/.local/share/pnpm/pnpm",
            "/usr/local/bin/pnpm",
            "pnpm",
        ).firstOrNull { candidate ->
            runCatching {
                ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
        } ?: "pnpm"

        val webuiBuildDir = "$localWebUIPath/build"
        val destZip = "${project.projectDir}/src/main/resources/WebUI.zip"

        onlyIf {
            val skip = project.findProperty("webui.skip") as? String
            if (skip == "true") {
                logger.lifecycle("Skipping local WebUI build (webui.skip=true)")
                return@onlyIf false
            }
            true
        }

        outputs.file(destZip)

        doLast {
            fun runCommand(vararg cmd: String, workDir: File) {
                logger.lifecycle("  Running: ${cmd.joinToString(" ")} (in $workDir)")
                val process = ProcessBuilder(*cmd)
                    .directory(workDir)
                    .inheritIO()
                    .start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw GradleException("Command failed with exit code $exitCode: ${cmd.joinToString(" ")}")
                }
            }

            // Step 1: Install dependencies if needed
            if (!file("$localWebUIPath/node_modules").exists()) {
                logger.lifecycle("Installing WebUI dependencies...")
                runCommand(pnpmCmd, "install", workDir = file(localWebUIPath))
            }

            // Step 2: Build WebUI
            logger.lifecycle("Building WebUI from $localWebUIPath...")
            runCommand(pnpmCmd, "run", "build", workDir = file(localWebUIPath))

            // Step 3: Package build output into WebUI.zip
            val destFile = file(destZip)
            destFile.parentFile.mkdirs()

            ant.invokeMethod(
                "zip",
                mapOf(
                    "destfile" to destZip,
                    "level" to "9",
                    "basedir" to webuiBuildDir,
                ),
            )

            logger.lifecycle("WebUI.zip built from local source: $destZip (${destFile.length() / 1024} KB)")
        }
    }

    // Keep the original downloadWebUI as a fallback for CI
    register<Download>("downloadWebUI") {
        src("https://github.com/Suwayomi/Suwayomi-WebUI-preview/releases/download/$webUIRevisionTag/Suwayomi-WebUI-$webUIRevisionTag.zip")
        dest("src/main/resources/WebUI.zip")

        fun shouldOverwrite(): Boolean {
            val zipPath = project.projectDir.absolutePath + "/src/main/resources/WebUI.zip"
            val zipFile = net.lingala.zip4j.ZipFile(zipPath)

            var shouldOverwrite = true
            if (zipFile.isValidZipFile) {
                val zipRevision =
                    zipFile.getInputStream(zipFile.getFileHeader("revision")).bufferedReader().use {
                        it.readText().trim()
                    }

                if (zipRevision == webUIRevisionTag) {
                    shouldOverwrite = false
                }
            }

            return shouldOverwrite
        }

        overwrite(shouldOverwrite())
        onlyIf {
            // Skip download if local build already produced the zip
            val zipFile = file("src/main/resources/WebUI.zip")
            if (zipFile.exists()) {
                logger.lifecycle("WebUI.zip already exists, skipping remote download")
                return@onlyIf false
            }
            true
        }
    }

    register("runElectron") {
        group = "application"
        finalizedBy(run)
        doFirst {
            application.applicationDefaultJvmArgs =
                listOf(
                    "-Dsuwayomi.tachidesk.config.server.webUIInterface=electron",
                    // Change this to the installed electron application
                    "-Dsuwayomi.tachidesk.config.server.electronPath=/usr/bin/electron",
                )
        }
    }

    runKtlintCheckOverMainSourceSet {
        mustRunAfter(generateJte)
    }

    compileKotlin {
        dependsOn(":server:server-config-generate:generateSettings")
    }

    processResources {
        dependsOn(":server:server-config-generate:generateSettings")
    }

    processTestResources {
        dependsOn(":server:server-config-generate:generateSettings")
    }
}
