#!/usr/bin/env bash
# Task 12 da E05: a tarefa smokeTest do demo-seed. Estático; não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

sem_comentario() { sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' "$1"; }
kts_file="$ROOT/demo-seed/build.gradle.kts"
kts="$(sem_comentario "$kts_file")"

assert_file "$ROOT/demo-seed/src/smokeTest/kotlin/com/zeny/desktop/seed/smoke/SmokeTest.kt" "SmokeTest.kt existe"
assert_contains "$kts" 'sourceSets.create("smokeTest")' "o source set smokeTest existe"
assert_contains "$kts" 'register<Test>("smokeTest")' "smokeTest é uma tarefa Test"
assert_contains "$kts" 'zeny.smoke.serverUrl' "-PserverUrl vira a propriedade zeny.smoke.serverUrl"

# Fora de check e de build: nenhuma dependência para a tarefa smokeTest.
assert_eq "" "$(grep -E 'dependsOn\(.*smokeTest|finalizedBy\(.*smokeTest|check *\{[^}]*smokeTest|build *\{[^}]*smokeTest' <<<"$kts" | grep -vi 'detekt\|ktlint')" "smokeTest não entra em check nem em build"

# O detekt do source set entra no check.
assert_contains "$(cat "$ROOT/build.gradle.kts" "$kts_file")" 'detektSmokeTest' "detektSmokeTest entra no check"

# A mensagem sai na execução (doFirst), não na configuração.
linha="$(grep -n 'Informe -PserverUrl' "$kts_file" | head -n1 | cut -d: -f1)"
if [[ -z "$linha" ]]; then
  _assert_fail "mensagem 'Informe -PserverUrl' ausente"
else
  antes="$(head -n "$linha" "$kts_file")"
  abre="$(grep -n 'doFirst' <<<"$antes" | tail -n1 | cut -d: -f1)"
  tarefa="$(grep -n 'register<Test>("smokeTest")' <<<"$antes" | tail -n1 | cut -d: -f1)"
  if [[ -z "$abre" || -z "$tarefa" || "$abre" -lt "$tarefa" ]]; then
    _assert_fail "a mensagem 'Informe -PserverUrl' não está num doFirst da tarefa smokeTest"
  fi
fi

if grep -Eiw 'application' <<<"$kts" >/dev/null; then
  _assert_fail "demo-seed/build.gradle.kts tem a palavra application em código ativo"
fi

# Nenhum arquivo versionado (nem o novo) atribui valor literal a variável de senha ou PIN do seed.
# Valores gerados em tempo de execução ($..., $(...)) e placeholders <...> são aceitos.
cd "$ROOT" || exit 1
achados="$(git ls-files --cached --others --exclude-standard -z | xargs -0 grep -nHE \
  'ZENY_SEED_[A-Z_]*(PASSWORD|PIN)[A-Z_]*=["'\'']?[^$<"'\''[:space:]]' 2>/dev/null | cut -d: -f1,2)"
assert_eq "" "$achados" "nenhum arquivo atribui valor literal a ZENY_SEED_*(PASSWORD|PIN)"

finish
