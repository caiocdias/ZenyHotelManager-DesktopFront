#!/usr/bin/env bash
# Ataque à Task 1 da M1 E02: catálogo e lockfiles do core coerentes (ktor-client-java, coroutines-test) e
# cerca do detekt. Não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

cat="$(cat "$ROOT/gradle/libs.versions.toml")"
lock="$(cat "$ROOT/core/gradle.lockfile")"
ktor="$(sed -nE 's/^ktor *= *"([^"]+)".*/\1/p' <<<"$cat" | head -n1)"
assert_contains "$cat" 'ktor-client-java = { module = "io.ktor:ktor-client-java", version.ref = "ktor" }' "ktor-client-java usa version.ref = ktor"
assert_contains "$lock" "io.ktor:ktor-client-java:$ktor=compileClasspath,runtimeClasspath" "core trava ktor-client-java na versão do ktor ($ktor) em compile e runtime"
assert_contains "$lock" "io.ktor:ktor-client-mock:$ktor=testCompileClasspath,testRuntimeClasspath" "ktor-client-mock só nos testes"

coro_cat="$(sed -nE 's/^kotlinx-coroutines-test *=.*version *= *"([^"]+)".*/\1/p' <<<"$cat")"
coro_core="$(sed -nE 's/^org\.jetbrains\.kotlinx:kotlinx-coroutines-core:([^=]+)=.*runtimeClasspath.*/\1/p' <<<"$lock" | head -n1)"
assert_eq "1.11.0" "$coro_cat" "catálogo fixa kotlinx-coroutines-test em 1.11.0"
assert_eq "$coro_core" "$coro_cat" "kotlinx-coroutines-test igual ao kotlinx-coroutines-core resolvido no core"
assert_contains "$lock" "org.jetbrains.kotlinx:kotlinx-coroutines-test:$coro_cat=testCompileClasspath,testRuntimeClasspath" "coroutines-test travado só nos testes do core"

# O engine do JDK não puxa engines concorrentes.
assert_not_contains "$lock" 'ktor-client-okhttp' "core não resolve engine OkHttp"
assert_not_contains "$lock" 'ktor-client-cio' "core não resolve engine CIO"

# A cerca cobre subpacotes por curinga.
detekt_yml="$(cat "$ROOT/config/detekt/detekt.yml")"
assert_contains "$detekt_yml" "value: 'io.ktor.client.*'" "detekt barra io.ktor.client.*"
assert_contains "$detekt_yml" "value: 'de.quati.ogen.client.*'" "detekt barra de.quati.ogen.client.*"

finish
