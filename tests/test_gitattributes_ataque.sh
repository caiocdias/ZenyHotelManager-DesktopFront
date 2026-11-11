#!/usr/bin/env bash
# Testes adversariais do .gitattributes: contrato byte a byte (CRLF, sem newline final),
# .sh em profundidade, core.eol/autocrlf, precedência e escopo exato das regras.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

# Isola o git de qualquer configuração global da máquina (autocrlf, eol, atributos).
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
_g() { git -c core.attributesFile=/dev/null "$@"; }
_gc() {
  local d="$1"
  shift
  _g -C "$d" -c user.name=Teste -c user.email=teste@example.com \
    -c commit.gpgsign=false -c core.hooksPath=/dev/null "$@"
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Repositório de ataque: .gitattributes real + arquivos com CRLF e sem newline final.
# $1 = nome; $2.. = configs "-c k=v" aplicadas ao commit e ao clone.
repo="$tmp/repo"
mkdir -p "$repo/api" "$repo/tests/lib" "$repo/a/b/c" "$repo/tools/server-env" "$repo/outro/api"
_g -C "$repo" init -q
cp "$ROOT/.gitattributes" "$repo/.gitattributes"
# Contrato com CRLF e sem newline final (bytes que não podem mudar).
printf 'openapi: 3.0.1\r\ninfo:\r\n  title: x\r\n\r\n  version: 1\r\nfim' >"$repo/api/openapi.yaml"
printf 'openapi: 3.0.1\r\nsem-attr' >"$repo/outro/api/openapi.yaml"
printf '#!/usr/bin/env bash\r\necho oi\r\n' >"$repo/tests/lib/x.sh"
printf '#!/usr/bin/env bash\r\necho fundo\r\n' >"$repo/a/b/c/fundo.sh"
printf 'commit=abc\r\n' >"$repo/api/server-pin.properties"
printf 'A=1\r\n' >"$repo/tools/server-env/.env.example"
printf 'name: x\r\n' >"$repo/tools/server-env/compose.override.yaml"
printf 'x\r\n' >"$repo/tools/server-env/outro.env.example"
printf '#!/bin/sh\r\n' >"$repo/tools/MAIUSCULO.SH"
_g -C "$repo" add -A 2>/dev/null
_gc "$repo" commit -q -m base

raw_sha() { sha256sum "$1" | cut -d' ' -f1; }
orig_sha="$(printf 'openapi: 3.0.1\r\ninfo:\r\n  title: x\r\n\r\n  version: 1\r\nfim' | sha256sum | cut -d' ' -f1)"

# O blob commitado do contrato é o byte a byte original (sem converter CRLF no checkin).
blob_sha="$(_g -C "$repo" cat-file blob HEAD:api/openapi.yaml | sha256sum | cut -d' ' -f1)"
assert_eq "$orig_sha" "$blob_sha" "checkin com .gitattributes não altera o contrato CRLF sem newline final"

# Scripts e configs com CRLF entram normalizados para LF (text eol=lf), em qualquer profundidade.
for f in tests/lib/x.sh a/b/c/fundo.sh api/server-pin.properties tools/server-env/.env.example \
  tools/server-env/compose.override.yaml; do
  if _g -C "$repo" cat-file blob "HEAD:$f" | grep -q $'\r'; then
    assert_eq "blob sem CR" "blob com CR" "$f commitado com CRLF é normalizado para LF"
  fi
done

# Clones com diferentes configs de fim de linha do Windows/Mac/Linux.
for cfg in "core.autocrlf=true" "core.autocrlf=input" "core.autocrlf=false" \
  "core.eol=crlf" "core.eol=crlf core.autocrlf=true" "core.autocrlf=true core.eol=native"; do
  c="$tmp/clone-${cfg//[ =.]/_}"
  args=()
  for kv in $cfg; do args+=(-c "$kv"); done
  _g clone -q "${args[@]}" "$repo" "$c" 2>/dev/null
  assert_eq "$orig_sha" "$(raw_sha "$c/api/openapi.yaml")" "[$cfg] contrato preservado byte a byte no clone"
  # CR no contrato: o arquivo precisa continuar com CRLF e sem newline final.
  assert_eq "fim" "$(tail -c 3 "$c/api/openapi.yaml")" "[$cfg] contrato sem newline final preservado"
  for f in tests/lib/x.sh a/b/c/fundo.sh api/server-pin.properties tools/server-env/.env.example \
    tools/server-env/compose.override.yaml; do
    if grep -q $'\r' "$c/$f"; then
      assert_eq "sem CR" "com CR" "[$cfg] $f sem CR no clone"
    fi
  done
  assert_eq "" "$(_g -C "$c" status --porcelain)" "[$cfg] clone sem alterações pendentes"
done

# Checkin no Windows: com autocrlf=true, um contrato CRLF editado não pode virar LF no blob.
w="$tmp/wincheckin"
_g clone -q -c core.autocrlf=true "$repo" "$w" 2>/dev/null
printf 'openapi: 3.0.1\r\nnovo\r\n' >"$w/api/openapi.yaml"
_g -C "$w" add api/openapi.yaml 2>/dev/null
novo="$(printf 'openapi: 3.0.1\r\nnovo\r\n' | sha256sum | cut -d' ' -f1)"
assert_eq "$novo" "$(_g -C "$w" cat-file blob :api/openapi.yaml | sha256sum | cut -d' ' -f1)" \
  "autocrlf=true no checkin não converte o contrato CRLF"

# Escopo: as regras não vazam para caminhos parecidos (âncora do api/openapi.yaml, outras extensões).
attr="$(_g -C "$repo" check-attr text eol -- outro/api/openapi.yaml tools/server-env/outro.env.example)"
assert_not_contains "$attr" "text: unset" "openapi.yaml de outro diretório não é contrato"
assert_not_contains "$attr" "eol: lf" "sufixo parecido com .env.example não herda eol=lf"
# *.sh é sensível a caixa: documenta que .SH não é coberto (sem regra, sem surpresa).
attr="$(_g -C "$repo" check-attr eol -- tools/MAIUSCULO.SH)"
assert_contains "$attr" "eol: unspecified" "documenta: .SH maiúsculo não é coberto pela regra *.sh"

# Precedência: *.sh (text eol=lf) não pode atingir o contrato nem o contrário.
attr="$(_g -C "$repo" check-attr text eol -- api/openapi.yaml)"
assert_contains "$attr" "api/openapi.yaml: eol: unspecified" "contrato não tem eol (só -text)"
attr="$(_g -C "$repo" check-attr text eol -- a/b/c/fundo.sh tests/lib/x.sh)"
assert_contains "$attr" "a/b/c/fundo.sh: eol: lf" "*.sh em profundidade usa eol=lf"
assert_contains "$attr" "tests/lib/x.sh: text: set" "tests/lib/*.sh é texto"

# O .gitattributes real: sem text=auto, sem atributos fora da lista, toda regra de arquivo existe.
assert_not_contains "$(grep -v '^#' "$ROOT/.gitattributes")" "text=auto" "sem * text=auto"
assert_eq "" "$(grep -nE '^\*[[:space:]]' "$ROOT/.gitattributes")" "sem regra global '*'"
assert_eq "" "$(grep -nE '[[:space:]]+$' "$ROOT/.gitattributes")" "sem espaço no fim das linhas"
assert_eq "" "$(grep -c $'\r' "$ROOT/.gitattributes" | grep -v '^0$')" ".gitattributes sem CR"
assert_eq "" "$(tail -c1 "$ROOT/.gitattributes" | od -An -c | grep -v '\\n')" ".gitattributes termina com newline"
com_attr=""
while IFS= read -r -d '' f; do
  out="$(_g -C "$ROOT" check-attr -a -- "$f" | sed 's/^[^:]*: //')"
  [[ -n "$out" ]] && com_attr+="$f "
done < <(git -C "$ROOT" ls-files -z)
esperado=""
while IFS= read -r -d '' f; do
  case "$f" in
    *.sh | *.lockfile | gradlew | *.bat | *.jar | api/openapi.yaml | api/server-pin.properties | tools/server-env/.env.example | tools/server-env/compose.override.yaml)
      esperado+="$f " ;;
  esac
done < <(git -C "$ROOT" ls-files -z)
assert_eq "$esperado" "$com_attr" "só os arquivos da lista têm atributos no repositório real"
# Toda regra não-sh aponta para arquivo rastreado (sem regra órfã por erro de digitação).
while read -r pat _; do
  [[ -z "$pat" || "$pat" == \#* || "$pat" == \*.sh ]] && continue
  git -C "$ROOT" ls-files --error-unmatch -- "$pat" >/dev/null 2>&1 || assert_eq "rastreado" "ausente" "regra órfã: $pat"
done <"$ROOT/.gitattributes"

# Nenhum blob rastreado tem CR (text eol=lf não pode ter alterado nada, e nada entrou com CRLF).
com_cr=""
while IFS= read -r -d '' f; do
  [[ "$f" == api/openapi.yaml ]] && continue
  # binário (text: unset) não entra na checagem de CR
  [[ "$(_g -C "$ROOT" check-attr text -- "$f")" == *"text: unset" ]] && continue
  git -C "$ROOT" cat-file blob "HEAD:$f" | grep -q $'\r' && com_cr+="$f "
done < <(git -C "$ROOT" ls-files -z)
assert_eq "" "$com_cr" "nenhum blob do HEAD (exceto contrato e binários) com CR"

finish
