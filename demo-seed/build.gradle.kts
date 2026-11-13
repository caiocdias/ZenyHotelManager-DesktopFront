import java.util.jar.Manifest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
}

// Teste de fumaça contra um servidor de verdade (`./gradlew smokeTest -PserverUrl=http://127.0.0.1:8088`, com os
// segredos nas variáveis ZENY_SEED_*). Fica fora de `check` e de `build`; só o detekt do source set entra no `check`.
val smokeTestSourceSet =
    sourceSets.create("smokeTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += output + compileClasspath
    }

configurations["smokeTestImplementation"].extendsFrom(configurations.implementation.get())
configurations["smokeTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    "smokeTestImplementation"(libs.kotlin.test.junit5)
}

tasks.register<Test>("smokeTest") {
    group = "verification"
    description = "Semeia e confere um servidor real (-PserverUrl=...); segredos pelas variáveis ZENY_SEED_*."
    testClassesDirs = smokeTestSourceSet.output.classesDirs
    classpath = smokeTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    val serverUrl = providers.gradleProperty("serverUrl")
    doFirst {
        val url = serverUrl.orNull ?: throw GradleException("Informe -PserverUrl=http://endereço:porta")
        systemProperty("zeny.smoke.serverUrl", url)
    }
}

val seedMainClass = "com.zeny.desktop.seed.MainKt"

// `./gradlew :demo-seed:run --args="--help"`; sem o plugin de execução, que fica só no app (`./gradlew :app:run`).
tasks.register<JavaExec>("run") {
    group = "run"
    description = "Roda o demo-seed (segredos pelas variáveis ZENY_SEED_*)."
    mainClass = seedMainClass
    classpath = sourceSets.main.get().runtimeClasspath
}

// O JAR executável é o seedJar; o das classes soltas fica com outro nome para não ser confundido com ele.
tasks.jar {
    archiveClassifier = "plain"
}

val mergedServicesDir = layout.buildDirectory.dir("generated/merged-services")

// Une as linhas distintas de cada META-INF/services/* de todos os JARs do runtimeClasspath.
val mergeSeedServices =
    tasks.register("mergeSeedServices") {
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

val seedJar =
    tasks.register<Jar>("seedJar") {
        group = "build"
        description = "JAR único do demo-seed com o runtimeClasspath inteiro."
        archiveFileName = "demo-seed.jar"
        destinationDirectory = layout.buildDirectory.dir("libs")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        dependsOn(mergeSeedServices)
        val runtime = configurations.runtimeClasspath
        from(sourceSets.main.get().output)
        from(mergedServicesDir)
        from(runtime.map { cp -> cp.files.map { if (it.isDirectory) it else zipTree(it) } }) {
            exclude("META-INF/services/**", "META-INF/MANIFEST.MF", "**/module-info.class")
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
        }
        manifest.attributes("Main-Class" to seedMainClass)
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

tasks.assemble { dependsOn(seedJar) }
