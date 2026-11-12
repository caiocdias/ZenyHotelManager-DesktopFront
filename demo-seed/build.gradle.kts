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
