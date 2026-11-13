import java.util.jar.Manifest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":design"))
    implementation(project(":core"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    // Os dois nativos, não o do SO atual: a Task 8 empacota um único JAR para Linux x64 e Windows x64.
    implementation(compose.desktop.linux_x64)
    implementation(compose.desktop.windows_x64)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // Teste de UI só no classpath de teste: o JAR de produção não o carrega (como no design).
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "com.zeny.desktop.app.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
    // Testes não tocam o java.util.prefs nem o /tmp reais da máquina (como no core).
    val testTmpRoot = File(layout.buildDirectory.get().asFile, "tmp")
    val isolatedPrefs = File(testTmpRoot, "test-prefs")
    val isolatedTmp = File(testTmpRoot, "test-tmp")
    systemProperty("java.util.prefs.userRoot", isolatedPrefs.absolutePath)
    systemProperty("java.io.tmpdir", isolatedTmp.absolutePath)
    // O opt-in das verificações ao vivo é estado da tarefa: mudá-lo faz os testes rodarem de novo.
    inputs.property("zenyLiveRealtime", providers.environmentVariable("ZENY_LIVE_REALTIME").orElse(""))
    inputs.property("zenyLiveOutage", providers.environmentVariable("ZENY_LIVE_OUTAGE").orElse(""))
    doFirst {
        isolatedPrefs.mkdirs()
        isolatedTmp.mkdirs()
    }
}

val mergedServicesDir = layout.buildDirectory.dir("generated/merged-services")

// Une as linhas distintas de cada META-INF/services/* de todos os JARs do runtimeClasspath.
val mergeDesktopServices =
    tasks.register("mergeDesktopServices") {
        val runtime = configurations.runtimeClasspath
        val out = mergedServicesDir
        inputs.files(runtime)
        outputs.dir(out)
        doLast {
            val merged = sortedMapOf<String, MutableSet<String>>()
            runtime.get().files.filter { it.isFile && it.name.endsWith(".jar") }.forEach { dep ->
                ZipFile(dep).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                        .forEach { e ->
                            val lines =
                                zip
                                    .getInputStream(e)
                                    .bufferedReader()
                                    .readText()
                                    .lines()
                                    .map { it.substringBefore('#').trim() }
                                    .filter { it.isNotEmpty() }
                            merged.getOrPut(e.name) { linkedSetOf() }.addAll(lines)
                        }
                }
            }
            val root = out.get().asFile
            root.deleteRecursively()
            merged.forEach { (name, lines) ->
                val target = File(root, name)
                target.parentFile.mkdirs()
                target.writeText(lines.joinToString("\n", postfix = "\n"))
            }
        }
    }

val desktopJar =
    tasks.register<Jar>("desktopJar") {
        group = "build"
        description = "JAR único com o runtimeClasspath inteiro (Linux x64 e Windows x64)."
        archiveFileName = "zeny-desktop.jar"
        destinationDirectory = layout.buildDirectory.dir("libs")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        dependsOn(mergeDesktopServices)
        val runtime = configurations.runtimeClasspath
        from(sourceSets.main.get().output)
        from(mergedServicesDir)
        from(runtime.map { cp -> cp.files.map { if (it.isDirectory) it else zipTree(it) } }) {
            exclude("META-INF/services/**", "META-INF/MANIFEST.MF", "**/module-info.class")
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
        }
        manifest.attributes(
            "Main-Class" to "com.zeny.desktop.app.MainKt",
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
        doFirst {
            val multiRelease =
                runtime.get().files.filter { it.isFile && it.name.endsWith(".jar") }.any { dep ->
                    ZipFile(dep).use { zip ->
                        zip
                            .getEntry("META-INF/MANIFEST.MF")
                            ?.let {
                                Manifest(
                                    zip.getInputStream(it),
                                ).mainAttributes.getValue("Multi-Release") == "true"
                            }
                            ?: false
                    }
                }
            if (multiRelease) manifest.attributes("Multi-Release" to "true")
        }
    }

val desktopJarFile = desktopJar.flatMap { it.archiveFile }

// Os testes de inspeção leem o JAR que o build produz, não um que por acaso exista.
tasks.test {
    dependsOn(desktopJar)
    inputs.file(desktopJarFile)
    systemProperty("zeny.desktopJar", desktopJarFile.get().asFile.absolutePath)
}

val verifyDesktopJar =
    tasks.register("verifyDesktopJar") {
        group = "verification"
        description = "Confere o JAR único do app: Main-Class, nativos do Skia, sem assinaturas e serviços fundidos."
        val jar = desktopJarFile
        val runtime = configurations.runtimeClasspath
        inputs.file(jar)
        dependsOn(desktopJar)
        inputs.files(runtime)
        doLast {
            fun serviceLines(text: String) =
                text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

            val problems = mutableListOf<String>()
            val expected = sortedMapOf<String, MutableSet<String>>()
            runtime.get().files.filter { it.isFile && it.name.endsWith(".jar") }.forEach { dep ->
                ZipFile(dep).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                        .forEach { e ->
                            expected
                                .getOrPut(e.name) { linkedSetOf() }
                                .addAll(serviceLines(zip.getInputStream(e).bufferedReader().readText()))
                        }
                }
            }
            val signature = Regex("META-INF/[^/]+\\.(SF|DSA|RSA|EC)", RegexOption.IGNORE_CASE)
            ZipFile(jar.get().asFile).use { zip ->
                val names =
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toSet()
                val mainClass =
                    zip
                        .getEntry("META-INF/MANIFEST.MF")
                        ?.let { Manifest(zip.getInputStream(it)).mainAttributes.getValue("Main-Class") }
                if (mainClass.isNullOrBlank()) problems += "Main-Class ausente no manifesto"
                val nativeAccess =
                    zip
                        .getEntry("META-INF/MANIFEST.MF")
                        ?.let { Manifest(zip.getInputStream(it)).mainAttributes.getValue("Enable-Native-Access") }
                if (nativeAccess != "ALL-UNNAMED") problems += "Enable-Native-Access: ALL-UNNAMED ausente no manifesto"
                listOf("libskiko-linux-x64.so", "skiko-windows-x64.dll").forEach { lib ->
                    if (names.none { it == lib || it.endsWith("/$lib") }) problems += "nativo ausente: $lib"
                }
                names.filter { signature.matches(it) }.forEach { problems += "assinatura presente: $it" }
                names.filter { it.endsWith("module-info.class") }.forEach { problems += "module-info presente: $it" }
                expected.forEach { (name, lines) ->
                    val entry = zip.getEntry(name)
                    if (entry == null) {
                        problems += "serviço ausente: $name"
                    } else {
                        val actual = serviceLines(zip.getInputStream(entry).bufferedReader().readText()).toSet()
                        (lines - actual).forEach { problems += "serviço $name perdeu a linha: $it" }
                    }
                }
            }
            if (problems.isNotEmpty()) {
                throw GradleException("desktopJar inválido:\n" + problems.joinToString("\n") { " - $it" })
            }
        }
    }

tasks.named("check") { dependsOn(verifyDesktopJar) }
