plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    // Só do design (ADR 0008): a API pública do design não expõe tipos do PDFBox.
    implementation(libs.pdfbox)

    testImplementation(libs.kotlin.test.junit5)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation(compose.desktop.linux_x64)
    testImplementation(compose.desktop.windows_x64)
}

// Pacote que só roda na tarefa noDiskTest, em JVM própria com tmpdir e user.home isolados.
val noDiskPackage = "com.zeny.desktop.design.pdf.nodisk.*"

tasks.test {
    useJUnitPlatform()
    filter { excludeTestsMatching(noDiskPackage) }
    // Testes não tocam o java.util.prefs nem o /tmp reais da máquina (como no app).
    val testTmpRoot = File(layout.buildDirectory.get().asFile, "tmp")
    val isolatedPrefs = File(testTmpRoot, "test-prefs")
    val isolatedTmp = File(testTmpRoot, "test-tmp")
    systemProperty("java.util.prefs.userRoot", isolatedPrefs.absolutePath)
    systemProperty("java.io.tmpdir", isolatedTmp.absolutePath)
    doFirst {
        isolatedPrefs.mkdirs()
        isolatedTmp.mkdirs()
    }
}

// ADR 0009: o ciclo do PDF (gerar, carregar, renderizar, fechar) não cria arquivo. Mesmo conjunto de fontes
// de teste (sem configuração nova nos lockfiles), filtrado pelo pacote nodisk, numa JVM que parte com
// java.io.tmpdir e user.home em pastas novas do build.
val noDiskTest =
    tasks.register<Test>("noDiskTest") {
        group = "verification"
        description = "Confere que o PDFBox do design não grava em java.io.tmpdir nem em user.home."
        val testSources = sourceSets.test.get()
        testClassesDirs = testSources.output.classesDirs
        classpath = testSources.runtimeClasspath
        useJUnitPlatform()
        filter { includeTestsMatching(noDiskPackage) }
        val isolatedRoot = File(layout.buildDirectory.get().asFile, "tmp/no-disk-test")
        val isolatedTmp = File(isolatedRoot, "tmp")
        val isolatedHome = File(isolatedRoot, "home")
        jvmArgs("-Djava.io.tmpdir=${isolatedTmp.absolutePath}", "-Duser.home=${isolatedHome.absolutePath}")
        doFirst {
            isolatedRoot.deleteRecursively()
            isolatedTmp.mkdirs()
            isolatedHome.mkdirs()
        }
    }

tasks.check { dependsOn(noDiskTest) }
