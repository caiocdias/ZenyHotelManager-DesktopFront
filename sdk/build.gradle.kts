plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ogen)
}

kotlin {
    jvmToolchain(25)
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
}

ogen {
    utilPackageName("com.zeny.desktop.sdk.util")
    add(packageName = "com.zeny.desktop.sdk") {
        specFile("$rootDir/api/openapi.yaml")
        model { postfix = "" }
        clientKtor {}
    }
}

dependencies {
    api(libs.ogen.core)
    api(libs.ogen.client.ktor)
    api(libs.quati.kotlin.util)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
}

tasks.named("ogenGenerate") {
    inputs.file("$rootDir/api/openapi.yaml").withPropertyName("contrato")
}
