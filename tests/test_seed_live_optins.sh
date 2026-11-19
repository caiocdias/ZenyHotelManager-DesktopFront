#!/usr/bin/env bash
# M4 E05, C-M8: cada variável de ambiente de opt-in ao vivo que os testes do demo-seed leem (ZENY_*LIVE* e o
# ZENY_SEED_SERVER que as acompanha) é inputs.property da tarefa test: mudar o opt-in invalida o resultado em cache.
# Estático; não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

kts_file="$ROOT/demo-seed/build.gradle.kts"
tests_dir="$ROOT/demo-seed/src/test"

# Código ativo da tarefa test: do `tasks.test {` ao primeiro `}` na coluna 0, sem comentários de linha.
bloco="$(awk '/^tasks\.test[[:space:]]*\{/ {dentro=1} dentro {print} dentro && /^\}/ {exit}' "$kts_file" |
  sed -E 's@^[[:space:]]*//.*$@@; s@[[:space:]]//[[:space:]].*$@@')"
assert_contains "$bloco" 'useJUnitPlatform()' "achou o bloco tasks.test do demo-seed"

mapfile -t optins < <(grep -rhoE 'getenv\("ZENY_[[:alnum:]_]*(LIVE|SERVER)[[:alnum:]_]*"\)' "$tests_dir" |
  sed -E 's/^getenv\("//; s/"\)$//' | sort -u)
if ((${#optins[@]} == 0)); then
  _assert_fail "nenhum opt-in ao vivo encontrado nos testes do demo-seed (o padrão da busca envelheceu?)"
fi

for nome in "${optins[@]}"; do
  linha="$(grep -E "inputs\.property\(.*environmentVariable\(\"$nome\"\)" <<<"$bloco")"
  if [[ -z "$linha" ]]; then
    _assert_fail "$nome não é inputs.property da tarefa test do demo-seed"
  else
    assert_contains "$linha" '.orElse("")' "$nome tem valor padrão vazio (variável ausente não quebra a configuração)"
  fi
done

# Nada de inputs.property sobre credencial: senha e PIN não entram no estado da tarefa.
assert_eq "" "$(grep -E 'inputs\.property\(.*(PASSWORD|PIN)' <<<"$bloco")" "nenhum inputs.property lê senha ou PIN"

finish
