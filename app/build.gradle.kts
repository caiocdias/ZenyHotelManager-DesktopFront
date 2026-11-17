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
    // Só nos testes (ADR 0008): extrair o texto da ficha ao vivo e provar o JAR; o `app` de produção não o declara.
    testImplementation(libs.pdfbox)
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
    inputs.property("zenyLiveReception", providers.environmentVariable("ZENY_LIVE_RECEPTION").orElse(""))
    inputs.property("zenyLiveOutage", providers.environmentVariable("ZENY_LIVE_OUTAGE").orElse(""))
    inputs.property("zenyLiveCheckIn", providers.environmentVariable("ZENY_LIVE_CHECKIN").orElse(""))
    inputs.property("zenyLiveWalkIn", providers.environmentVariable("ZENY_LIVE_WALKIN").orElse(""))
    inputs.property("zenyLiveStay", providers.environmentVariable("ZENY_LIVE_STAY").orElse(""))
    inputs.property("zenyLiveTraces", providers.environmentVariable("ZENY_LIVE_TRACES").orElse(""))
    inputs.property("zenyLiveCard", providers.environmentVariable("ZENY_LIVE_CARD").orElse(""))
    inputs.property("zenyLiveSearch", providers.environmentVariable("ZENY_LIVE_SEARCH").orElse(""))
    inputs.property("zenyLiveAvailability", providers.environmentVariable("ZENY_LIVE_AVAILABILITY").orElse(""))
    inputs.property("zenyLiveBooking", providers.environmentVariable("ZENY_LIVE_BOOKING").orElse(""))
    // Com o opt-in, a verificação fala com o servidor, que muda entre rodadas: nunca sai UP-TO-DATE.
    val liveRealtime = providers.environmentVariable("ZENY_LIVE_REALTIME")
    val liveReception = providers.environmentVariable("ZENY_LIVE_RECEPTION")
    val liveCheckIn = providers.environmentVariable("ZENY_LIVE_CHECKIN")
    val liveWalkIn = providers.environmentVariable("ZENY_LIVE_WALKIN")
    val liveStay = providers.environmentVariable("ZENY_LIVE_STAY")
    val liveTraces = providers.environmentVariable("ZENY_LIVE_TRACES")
    val liveCard = providers.environmentVariable("ZENY_LIVE_CARD")
    val liveSearch = providers.environmentVariable("ZENY_LIVE_SEARCH")
    val liveAvailability = providers.environmentVariable("ZENY_LIVE_AVAILABILITY")
    val liveBooking = providers.environmentVariable("ZENY_LIVE_BOOKING")
    outputs.upToDateWhen {
        liveRealtime.orNull != "1" && liveReception.orNull != "1" && liveCheckIn.orNull != "1" &&
            liveWalkIn.orNull != "1" && liveStay.orNull != "1" && liveTraces.orNull != "1" &&
            liveCard.orNull != "1" && liveSearch.orNull != "1" && liveAvailability.orNull != "1" &&
            liveBooking.orNull != "1"
    }
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

// Apache 2.0 §4(d): os NOTICE e LICENSE das bibliotecas listadas em desktop-jar-required.txt precisam estar no JAR.
// Com DuplicatesStrategy.EXCLUDE só o primeiro META-INF/NOTICE sobrevive; estes vão para uma pasta por artefato.
val noticeEntry = Regex("META-INF/(NOTICE|LICENSE)[^/]*", RegexOption.IGNORE_CASE)
val requiredLines =
    file("desktop-jar-required.txt")
        .readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
// Do mais longo ao mais curto: "x-io-1.0.jar" é de "x-io", não de "x".
val noticedArtifacts =
    requiredLines
        .filter { it.startsWith("notice:") }
        .map { it.removePrefix("notice:") }
        .sortedByDescending { it.length }
val requiredEntries = requiredLines.filterNot { it.startsWith("notice:") }
val mergedNoticesDir = layout.buildDirectory.dir("generated/merged-notices")

/** O artefato de aviso de um JAR do runtimeClasspath (`lib-3.0.8.jar` é `lib`), ou nulo se não for dos avisados. */
fun noticedArtifact(jar: File): String? =
    noticedArtifacts.firstOrNull { jar.name.startsWith("$it-") && jar.name.endsWith(".jar") }

val mergeDesktopNotices =
    tasks.register("mergeDesktopNotices") {
        val runtime = configurations.runtimeClasspath
        val out = mergedNoticesDir
        inputs.file("desktop-jar-required.txt")
        inputs.files(runtime)
        outputs.dir(out)
        doLast {
            val root = out.get().asFile
            root.deleteRecursively()
            runtime.get().files.filter { it.isFile }.forEach { dep ->
                val artifact = noticedArtifact(dep) ?: return@forEach
                ZipFile(dep).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && noticeEntry.matches(it.name) }
                        .forEach { e ->
                            val target =
                                File(
                                    root,
                                    "META-INF/third-party-notices/$artifact-${dep.nameWithoutExtension.removePrefix(
                                        "$artifact-",
                                    )}/${e.name.substringAfterLast('/')}",
                                )
                            target.parentFile.mkdirs()
                            target.writeBytes(zip.getInputStream(e).readBytes())
                        }
                }
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
        dependsOn(mergeDesktopServices, mergeDesktopNotices)
        val runtime = configurations.runtimeClasspath
        from(sourceSets.main.get().output)
        from(mergedServicesDir)
        from(mergedNoticesDir)
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
        description =
            "Confere o JAR único do app: Main-Class, nativos do Skia, entradas e avisos exigidos, sem assinaturas e serviços fundidos."
        val jar = desktopJarFile
        val runtime = configurations.runtimeClasspath
        inputs.file(jar)
        inputs.file("desktop-jar-required.txt")
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
            val expectedNotices = sortedSetOf<String>()
            runtime.get().files.filter { it.isFile }.forEach { dep ->
                val artifact = noticedArtifact(dep) ?: return@forEach
                ZipFile(dep).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && noticeEntry.matches(it.name) }
                        .forEach { e ->
                            val version = dep.nameWithoutExtension.removePrefix("$artifact-")
                            expectedNotices +=
                                "META-INF/third-party-notices/$artifact-$version/${e.name.substringAfterLast('/')}"
                        }
                }
            }
            noticedArtifacts.forEach { artifact ->
                if (expectedNotices.none { it.startsWith("META-INF/third-party-notices/$artifact-") }) {
                    problems += "o runtimeClasspath não traz NOTICE/LICENSE de $artifact"
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
                requiredEntries.filter { it !in names }.forEach { problems += "entrada exigida ausente: $it" }
                expectedNotices.filter { it !in names }.forEach { problems += "aviso de licença ausente: $it" }
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
