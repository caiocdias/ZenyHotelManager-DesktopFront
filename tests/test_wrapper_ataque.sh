#!/usr/bin/env bash
# Testes adversariais do wrapper do Gradle: clones do HEAD com várias configs de fim de linha
# (jar intacto, gradlew executável e sem CR, gradlew.bat em CRLF), blobs idênticos ao servidor,
# escopo exato das regras novas e servidor ausente (77, nunca 2).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tools/lib/common.sh"

# Isola o git de qualquer configuração global da máquina (autocrlf, eol, atributos).
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
_g() { git -c core.attributesFile=/dev/null "$@"; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

jar=gradle/wrapper/gradle-wrapper.jar
sha() { sha256sum "$1" | cut -d' ' -f1; }

# Clones do HEAD commitado (não da árvore de trabalho) com configs de fim de linha.
for cfg in "core.autocrlf=true" "core.autocrlf=input" "core.autocrlf=false" \
  "core.eol=crlf" "core.autocrlf=true core.eol=crlf"; do
  c="$tmp/clone-${cfg//[ =.]/_}"
  args=()
  for kv in $cfg; do args+=(-c "$kv"); done
  _g clone -q "${args[@]}" "$ROOT" "$c" 2>/dev/null
  assert_eq "$(git -C "$ROOT" cat-file blob "HEAD:$jar" | sha256sum | cut -d' ' -f1)" "$(sha "$c/$jar")" "[$cfg] jar do clone idêntico ao blob do HEAD"
  [[ -x "$c/gradlew" ]] || assert_eq "executável" "não executável" "[$cfg] gradlew do clone é executável"
  assert_eq "0" "$(grep -c $'\r' "$c/gradlew")" "[$cfg] gradlew do clone sem CR"
  assert_eq "0" "$(grep -vc $'\r$' "$c/gradlew.bat")" "[$cfg] gradlew.bat do clone com CR em toda linha"
  assert_eq "0" "$(grep -c $'\r\r' "$c/gradlew.bat")" "[$cfg] gradlew.bat sem CR duplicado"
  assert_eq "#!/bin/sh" "$(head -n1 "$c/gradlew")" "[$cfg] shebang do gradlew intacto"
  bash -n "$c/gradlew" 2>/dev/null || assert_eq "sintaxe ok" "sintaxe inválida" "[$cfg] gradlew do clone passa em bash -n"
  assert_eq "" "$(_g -C "$c" status --porcelain)" "[$cfg] clone sem alterações pendentes"
  # round trip: renormalizar não pode gerar diff (o CRLF da árvore não vaza para o índice)
  _g -C "$c" add --renormalize . 2>/dev/null
  assert_eq "" "$(_g -C "$c" diff --cached --name-only)" "[$cfg] add --renormalize não altera nada"
  assert_eq "100755" "$(_g -C "$c" ls-files -s gradlew | cut -d' ' -f1)" "[$cfg] gradlew continua 100755 no índice do clone"
done

# Blobs do índice do front: gradlew.bat sem CR; modos corretos.
assert_eq "0" "$(git -C "$ROOT" cat-file blob HEAD:gradlew.bat | grep -c $'\r')" "gradlew.bat no HEAD com LF (sem CR)"
assert_eq "0" "$(git -C "$ROOT" cat-file blob HEAD:gradlew | grep -c $'\r')" "gradlew no HEAD sem CR"
assert_eq "100644" "$(git -C "$ROOT" ls-files -s gradlew.bat | cut -d' ' -f1)" "gradlew.bat não executável no índice"
assert_eq "100644" "$(git -C "$ROOT" ls-files -s "$jar" | cut -d' ' -f1)" "jar não executável no índice"

# O jar é um zip íntegro (um CR convertido ou truncamento o quebraria).
if command -v unzip >/dev/null 2>&1; then
  assert_exit 0 "jar do wrapper passa em unzip -t" unzip -tq "$ROOT/$jar"
elif command -v python3 >/dev/null 2>&1; then
  assert_exit 0 "jar do wrapper passa em zipfile.testzip" python3 -I -c 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); sys.exit(1 if z.testzip() else 0)' "$ROOT/$jar"
fi

# Escopo das regras novas.
attr="$(_g -C "$ROOT" check-attr text eol -- sub/gradlew gradlew.bat.bak xgradlew gradlew.cmd sub/dir/x.bat sub/dir/x.jar a.BAT gradle/wrapper/gradle-wrapper.properties)"
assert_contains "$attr" "sub/gradlew: eol: lf" "gradlew em subdiretório também usa eol=lf (regra sem barra)"
assert_contains "$attr" "xgradlew: text: unspecified" "xgradlew não herda a regra do gradlew"
assert_contains "$attr" "gradlew.bat.bak: text: unspecified" "gradlew.bat.bak não herda a regra *.bat"
assert_contains "$attr" "gradlew.cmd: text: unspecified" "documenta: .cmd não é coberto"
assert_contains "$attr" "sub/dir/x.bat: eol: crlf" "*.bat em profundidade usa eol=crlf"
assert_contains "$attr" "sub/dir/x.jar: text: unset" "*.jar em profundidade é binário"
assert_contains "$attr" "a.BAT: eol: unspecified" "documenta: .BAT maiúsculo não é coberto"
assert_contains "$attr" "gradle-wrapper.properties: text: unspecified" "properties do wrapper sem regra de texto"
assert_not_contains "$(_g -C "$ROOT" check-attr eol -- gradlew)" "crlf" "gradlew não pega a regra *.bat"
assert_not_contains "$(_g -C "$ROOT" check-attr eol -- gradlew.bat)" "eol: lf" "gradlew.bat não pega a regra do gradlew"
# `binary` é macro: -diff -merge -text. Só contrato e jar podem ser text: unset.
unset_files="$(git -C "$ROOT" ls-files -z | xargs -0 git -c core.attributesFile=/dev/null -C "$ROOT" check-attr text -- | grep 'text: unset' | sed 's/: text: unset//' | sort | tr '\n' ' ')"
assert_eq "api/openapi.yaml $jar " "$unset_files" "só contrato e jar são text: unset"

# Servidor: no pin, o gradlew é executável e os blobs batem com os do front.
if ! (server_repo) >/dev/null 2>&1; then
  skip_case "repositório do servidor ausente; blobs do wrapper não comparados"
else
  pin="$(sed -n 's/^commit=//p' "$ROOT/api/server-pin.properties")"
  assert_contains "$(srv_git cat-file -p "$pin^{tree}" | grep -P '\tgradlew$')" "100755" "gradlew é executável no servidor no pin"
  for f in gradlew "$jar" gradle/wrapper/gradle-wrapper.properties; do
    assert_eq "$(srv_git rev-parse "$pin:$f")" "$(git -C "$ROOT" rev-parse "HEAD:$f")" "blob de $f no HEAD é o mesmo do servidor no pin"
  done
  assert_eq "$(srv_git show "$pin:gradlew.bat" | tr -d '\r' | sha256sum)" "$(git -C "$ROOT" cat-file blob HEAD:gradlew.bat | sha256sum)" "gradlew.bat no HEAD é o do servidor com CRLF trocado por LF"
  assert_eq "$(srv_git show "$pin:gradlew.bat" | sha256sum)" "$(sha256sum <"$ROOT/gradlew.bat")" "gradlew.bat da árvore é byte a byte o do servidor (CRLF)"
fi

# Servidor ausente: test_wrapper.sh pula com 77; nunca falha com 2 do die.
for ausente in "$tmp/nao-existe" "$tmp"; do
  out="$(ZENY_SERVER_REPO="$ausente" bash "$ROOT/tests/test_wrapper.sh" 2>&1)"
  code=$?
  assert_eq "77" "$code" "servidor ausente ($ausente): test_wrapper.sh sai com 77"
  assert_contains "$out" "PULADO:" "servidor ausente ($ausente): imprime PULADO:"
  assert_not_contains "$out" "FALHOU" "servidor ausente ($ausente): sem falhas, só pulo"
done

finish
