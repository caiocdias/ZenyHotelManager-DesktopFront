#!/usr/bin/env bash
# Testes do wrapper do Gradle: versão e hash da distribuição, executável no índice e
# cópia sem alteração do wrapper do servidor no commit do pin.
# Quando a rodada do M0 trocar o pin para um servidor com outro Gradle, a mesma rodada
# recopia o wrapper e atualiza este teste.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tools/lib/common.sh"

props="$ROOT/gradle/wrapper/gradle-wrapper.properties"
content="$(cat "$props" 2>/dev/null)"
assert_contains "$content" "distributionSha256Sum=dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f" "hash da distribuição do Gradle 9.8.1"
assert_contains "$content" "gradle-9.8.1-bin.zip" "distribuição Gradle 9.8.1"

assert_contains "$(git -C "$ROOT" ls-files -s gradlew)" "100755" "gradlew executável no índice"

files="gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties"
if ! (server_repo) >/dev/null 2>&1; then
  skip_case "repositório do servidor ausente; wrapper não comparado com o do pin"
else
  pin="$(sed -n 's/^commit=//p' "$ROOT/api/server-pin.properties")"
  for f in $files; do
    # árvore de trabalho: gradlew.bat do servidor tem CRLF e o eol=crlf o refaz aqui
    esperado="$(srv_git show "$pin:$f" | sha256sum | cut -d' ' -f1)"
    obtido="$(sha256sum "$ROOT/$f" 2>/dev/null | cut -d' ' -f1)"
    assert_eq "$esperado" "$obtido" "$f idêntico ao do servidor no pin"
  done
fi

finish
