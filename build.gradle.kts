plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ogen) apply false
}

// Lint oficial (`./gradlew ktlintCheck detekt`) nos cinco módulos. A raiz não aplica plugin a si mesma.
subprojects {
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    }

    // detektMain/detektTest recebem os diretórios do source set, que incluem o código gerado pelo ogen.
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        exclude { it.file.path.contains("/build/generated/") }
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version = rootProject.libs.versions.ktlint.cli
        // O código gerado do sdk (ogen) fica fora; o escrito à mão, testes incluídos, é linted.
        filter {
            exclude { it.file.path.contains("/build/generated/") }
        }
    }

    // O plugin do detekt liga ao `check` só a análise sem resolução de tipos; detektMain/detektTest
    // (com resolução de tipos) também entram, como no servidor.
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(tasks.matching { it.name == "detektMain" || it.name == "detektTest" })
    }
}
