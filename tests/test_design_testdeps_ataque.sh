#!/usr/bin/env bash
# Ataque à Task 1 da M1 E03: dependências de teste de UI do design só nos classpaths de teste (supply chain),
# nunca currentOs, e o JAR do app sem classes de teste. Lê lockfiles e o JAR; não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

lock="$ROOT/design/gradle.lockfile"
assert_file "$lock" "design/gradle.lockfile existe"

# Configurações onde a coordenada (prefixo g:a:) aparece, uma por linha.
configs_de() { grep -E "^$1:" "$lock" | cut -d= -f2 | tr ',' '\n' | sort -u; }
# Configurações que não são de teste (testCompileClasspath, testRuntimeClasspath, composeHotReloadDevTest...).
fora_de_teste() { configs_de "$1" | grep -vE '^(composeHotReloadDev)?[Tt]est[A-Za-z]*$'; }

deps_de_teste=(
  'org.jetbrains.compose.ui:ui-test'
  'org.jetbrains.compose.ui:ui-test-desktop'
  'junit:junit'
  'org.hamcrest:hamcrest-core'
  'org.jetbrains.kotlinx:kotlinx-coroutines-test'
  'org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm'
  'org.jetbrains.skiko:skiko-awt-runtime-windows-x64'
  'org.jetbrains.compose.desktop:desktop-jvm-windows-x64'
)
for c in "${deps_de_teste[@]}"; do
  assert_contains "$(configs_de "$c")" "testRuntimeClasspath" "design: $c travado no testRuntimeClasspath"
  assert_eq "" "$(fora_de_teste "$c")" "design: $c só em classpaths de teste"
done

# Os nativos de Linux entram no teste, e o runtime de produção do design não carrega nenhum nativo do Skia.
assert_contains "$(configs_de 'org.jetbrains.skiko:skiko-awt-runtime-linux-x64')" "testRuntimeClasspath" "design: nativo linux no teste"
for cfg in compileClasspath runtimeClasspath; do
  prod="$(grep -E "^[^#].*[=,]$cfg(,|$)" "$lock" | cut -d= -f1)"
  assert_not_contains "$prod" "ui-test" "design: $cfg sem ui-test"
  assert_not_contains "$prod" "junit:junit" "design: $cfg sem junit4"
  assert_not_contains "$prod" "hamcrest" "design: $cfg sem hamcrest"
  assert_not_contains "$prod" "skiko-awt-runtime" "design: $cfg sem nativos do Skia"
  assert_not_contains "$prod" "desktop-jvm-" "design: $cfg sem nativos do Compose"
done

# Os outros módulos não herdam o ui-test nem o junit4 (nem o app, que embarca o JAR).
for m in sdk core app demo-seed; do
  assert_eq "0" "$(grep -cE '^(org\.jetbrains\.compose\.ui:ui-test|junit:junit|org\.hamcrest)' "$ROOT/$m/gradle.lockfile")" "$m: lockfile sem ui-test/junit4/hamcrest"
done

# Nenhum currentOs em nenhum *.gradle.kts (comentários não contam).
achados=""
while IFS= read -r f; do
  if grep -vE '^\s*//' "$f" | grep -q 'currentOs'; then achados+="${f#"$ROOT"/} "; fi
done < <(find "$ROOT" -name '*.gradle.kts' -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.claude/*' | sort)
assert_eq "" "$achados" "nenhum *.gradle.kts usa currentOs"

# O design declara o ui-test e os nativos só como testImplementation.
dk="$(cat "$ROOT/design/build.gradle.kts")"
assert_eq "0" "$(grep -E 'desktop\.(linux|windows)_x64|uiTest' <<<"$dk" | grep -vc '^\s*testImplementation')" "design: uiTest e nativos só em testImplementation"

# O JAR do app não ganhou classes de teste de UI, junit, hamcrest nem coroutines-test.
jar="$ROOT/app/build/libs/zeny-desktop.jar"
if [[ ! -f "$jar" ]] || ! command -v zipinfo >/dev/null; then
  skip_case "zeny-desktop.jar ou zipinfo ausente (rode ./gradlew :app:desktopJar)"
else
  names="$(zipinfo -1 "$jar")"
  assert_eq "0" "$(grep -cE '^(androidx/compose/ui/test/|org/junit/|junit/|org/hamcrest/|kotlinx/coroutines/test/)' <<<"$names")" "JAR sem classes de teste de UI, junit, hamcrest ou coroutines-test"
fi

finish
