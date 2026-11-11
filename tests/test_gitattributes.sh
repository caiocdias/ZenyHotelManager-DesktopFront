#!/usr/bin/env bash
# Testes do .gitattributes: atributos esperados e checkout simulando o Windows
# (core.autocrlf=true) sem alterar o contrato nem converter scripts e configs para CRLF.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

# Isola o git de qualquer configuração global da máquina (autocrlf, eol, atributos).
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
_g() { git -c core.attributesFile=/dev/null "$@"; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# $1 = com .gitattributes (0|1). Monta em $tmp/repo-$1 o estado atual da árvore,
# commita e clona em $tmp/clone-$1 como no Windows (autocrlf gravado na config do clone).
_clone_windows() {
  local com="$1" repo="$tmp/repo-$1" clone="$tmp/clone-$1" f
  mkdir -p "$repo"
  _g -C "$repo" init -q
  while IFS= read -r -d '' f; do
    [[ "$f" == .gitattributes ]] && continue
    mkdir -p "$repo/$(dirname "$f")"
    cp -p "$ROOT/$f" "$repo/$f"
  done < <(git -C "$ROOT" ls-files -z)
  if [[ "$com" == 1 && -f "$ROOT/.gitattributes" ]]; then
    cp "$ROOT/.gitattributes" "$repo/.gitattributes"
  fi
  _g -C "$repo" add -A
  _g -C "$repo" -c user.name=Teste -c user.email=teste@example.com \
    -c commit.gpgsign=false -c core.hooksPath=/dev/null commit -q -m base
  _g clone -q -c core.autocrlf=true "$repo" "$clone" 2>/dev/null
}

# atributos
attr="$(_g -C "$ROOT" check-attr text eol -- api/openapi.yaml)"
assert_contains "$attr" "api/openapi.yaml: text: unset" "openapi.yaml sem normalização de texto"
for f in tools/server-drift.sh tools/server-env/gen-env.sh tests/lib/assert.sh \
  api/server-pin.properties tools/server-env/.env.example tools/server-env/compose.override.yaml gradlew \
  sdk/gradle.lockfile buildscript-gradle.lockfile settings-gradle.lockfile; do
  attr="$(_g -C "$ROOT" check-attr text eol -- "$f")"
  assert_contains "$attr" "$f: text: set" "$f é texto"
  assert_contains "$attr" "$f: eol: lf" "$f usa eol=lf"
done

attr="$(_g -C "$ROOT" check-attr text eol -- gradlew.bat)"
assert_contains "$attr" "gradlew.bat: eol: crlf" "gradlew.bat usa eol=crlf"
attr="$(_g -C "$ROOT" check-attr text eol -- gradle/wrapper/gradle-wrapper.jar)"
assert_contains "$attr" "gradle/wrapper/gradle-wrapper.jar: text: unset" "gradle-wrapper.jar é binário"

# checkout Windows
_clone_windows 1
c="$tmp/clone-1"
assert_eq "true" "$(_g -C "$c" config core.autocrlf)" "clone com core.autocrlf=true"
pin="$(sed -n 's/^contract_sha256=//p' "$ROOT/api/server-pin.properties")"
assert_eq "$pin" "$(sha256sum "$c/api/openapi.yaml" | cut -d' ' -f1)" "openapi.yaml do clone bate com o contract_sha256"
com_cr=""
while IFS= read -r -d '' f; do
  grep -q $'\r' "$f" && com_cr+="$f "
done < <(find "$c" -name .git -prune -o -name '*.sh' -type f -print0)
assert_eq "" "$com_cr" "nenhum *.sh do clone contém CR"
for f in api/server-pin.properties tools/server-env/.env.example tools/server-env/compose.override.yaml; do
  if grep -q $'\r' "$c/$f"; then
    assert_eq "sem CR" "com CR" "$f do clone sem CR"
  else
    assert_eq "sem CR" "sem CR" "$f do clone sem CR"
  fi
done
for f in sdk/gradle.lockfile app/gradle.lockfile buildscript-gradle.lockfile settings-gradle.lockfile; do
  if grep -q $'\r' "$c/$f"; then
    assert_eq "sem CR" "com CR" "$f do clone sem CR"
  else
    assert_eq "sem CR" "sem CR" "$f do clone sem CR"
  fi
done
assert_exit 0 "test_lockfiles.sh passa no clone Windows" -- bash "$c/tests/test_lockfiles.sh"
if grep -q $'\r' "$c/gradlew"; then
  assert_eq "sem CR" "com CR" "gradlew do clone sem CR"
else
  assert_eq "sem CR" "sem CR" "gradlew do clone sem CR"
fi
assert_eq "0" "$(grep -vc $'\r$' "$c/gradlew.bat")" "gradlew.bat do clone com CR em toda linha"
assert_eq "$(sha256sum "$ROOT/gradle/wrapper/gradle-wrapper.jar" | cut -d' ' -f1)" \
  "$(sha256sum "$c/gradle/wrapper/gradle-wrapper.jar" | cut -d' ' -f1)" "gradle-wrapper.jar do clone igual ao da árvore"
assert_eq "" "$(_g -C "$c" status --porcelain)" "clone sem alterações pendentes"

# controle negativo: sem .gitattributes, o autocrlf converte para CRLF
_clone_windows 0
if grep -q $'\r' "$tmp/clone-0/tools/server-drift.sh"; then
  assert_eq "com CR" "com CR" "controle negativo: sem .gitattributes há CR em server-drift.sh"
else
  assert_eq "com CR" "sem CR" "controle negativo: sem .gitattributes há CR em server-drift.sh"
fi

# controle negativo dos lockfiles: sem a regra, o CR aparece e o teste de lockfile falha
if grep -q $'\r' "$tmp/clone-0/sdk/gradle.lockfile"; then
  assert_eq "com CR" "com CR" "controle negativo: sem .gitattributes há CR em sdk/gradle.lockfile"
else
  assert_eq "com CR" "sem CR" "controle negativo: sem .gitattributes há CR em sdk/gradle.lockfile"
fi
# Sem a regra o próprio script também vem com CRLF; basta que a execução não termine em sucesso.
if bash "$tmp/clone-0/tests/test_lockfiles.sh" >/dev/null 2>&1; then
  assert_eq "falha" "passou" "controle negativo: test_lockfiles.sh falha no clone com CR"
else
  assert_eq "falha" "falha" "controle negativo: test_lockfiles.sh falha no clone com CR"
fi

finish
