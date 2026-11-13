// Travamento de dependências: o grafo resolvido de cada projeto fica em gradle.lockfile, e o classpath dos
// plugins do build em buildscript-gradle.lockfile; o osv-scanner da revisão
// final lê esses arquivos. O modo estrito falha o build para versão fora do lockfile, dependência nova ou
// configuração sem lock state. Versão menor no catálogo é ignorada em favor da travada, sem aviso; por isso
// regrave os lockfiles no mesmo commit que muda o catálogo ou um plugin: `./gradlew resolveAndLockAll --write-locks`.
buildscript {
    dependencyLocking {
        lockAllConfigurations()
        lockMode = LockMode.STRICT
    }
    // Correções de advisories no classpath dos plugins; versões, motivos e saída no catálogo.
    dependencies {
        classpath(platform(libs.jackson.bom))
        constraints {
            classpath(libs.guava) { because("GHSA-xxph-c9ww-hj94") }
            classpath(libs.handlebars) { because("GHSA-r4gv-qr8j-p3pg") }
        }
    }
}

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

    // O ktlint CLI traz o logback 1.3.16 (3 advisories Low); motivo e saída no catálogo.
    dependencies {
        constraints {
            add("ktlint", rootProject.libs.logback.classic) { because("GHSA-jhq6-gfmj-v8fx, GHSA-p47f-322f-whfh, GHSA-qqpg-mvqg-649v") }
            add("ktlint", rootProject.libs.logback.core) { because("GHSA-jhq6-gfmj-v8fx, GHSA-p47f-322f-whfh, GHSA-qqpg-mvqg-649v") }
        }
    }

    // O plugin do detekt liga ao `check` só a análise sem resolução de tipos; detektMain/detektTest
    // (com resolução de tipos) também entram, como no servidor; o detektSmokeTest do demo-seed idem.
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(tasks.matching { it.name == "detektMain" || it.name == "detektTest" || it.name == "detektSmokeTest" })
    }
}

// Mesmo travamento e a tarefa de regravação em todos os projetos, a raiz inclusive: `--write-locks` só
// grava o projeto cuja tarefa resolve as configurações. A raiz não aplica plugin a si mesma.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode = LockMode.STRICT
    }

    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Resolve configurações em tempo de execução")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "Rode com --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}
