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
}

compose.desktop {
    application {
        mainClass = "com.zeny.desktop.app.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
}
