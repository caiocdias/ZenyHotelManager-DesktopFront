plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(project(":sdk"))

    implementation(libs.ktor.client.java)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    // Testes não tocam o java.util.prefs nem o /tmp reais da máquina.
    val testTmpRoot = File(layout.buildDirectory.get().asFile, "tmp")
    val isolatedPrefs = File(testTmpRoot, "test-prefs")
    val isolatedTmp = File(testTmpRoot, "test-tmp")
    systemProperty("java.util.prefs.userRoot", isolatedPrefs.absolutePath)
    systemProperty("java.io.tmpdir", isolatedTmp.absolutePath)
    // O opt-in das verificações ao vivo é estado da tarefa: mudá-lo faz os testes rodarem de novo.
    inputs.property("zenyLiveRealtime", providers.environmentVariable("ZENY_LIVE_REALTIME").orElse(""))
    doFirst {
        isolatedPrefs.mkdirs()
        isolatedTmp.mkdirs()
    }
}
