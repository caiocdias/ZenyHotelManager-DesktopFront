#!/usr/bin/env bash
# Testes adversariais de tools/server-drift.sh (servidor falso e front falso em diretório temporário).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'chmod -R u+rwx "$tmp" 2>/dev/null; rm -rf "$tmp"' EXIT

make_fake_server "$tmp/srv"
srv="$tmp/srv"
export ZENY_SERVER_REPO="$srv"
unset ZENY_DRIFT_BASE ZENY_DRIFT_TARGET
C1="$FAKE_C1" C2="$FAKE_C2" C3="$FAKE_C3" C4="$FAKE_C4"

oasdiff_image="${OASDIFF_IMAGE:-tufin/oasdiff:v1.33.0@sha256:6263a96dd2ef0726c54e21fea9b8e1607eac4841add0079324b424c1f52b819c}"
have_image=0
docker image inspect "$oasdiff_image" >/dev/null 2>&1 && have_image=1
run_real() { # ESPERADO MSG CMD...
  local expected="$1" msg="$2"
  shift 2
  if ((have_image)); then
    assert_exit "$expected" "$msg" -- "$@"
  else
    ASSERT_OUT=""
    skip_case "$msg (imagem do oasdiff ausente)"
  fi
}

# commit_srv MSG : commita tudo no servidor falso (preparação do teste) e devolve o SHA em $sha.
commit_srv() {
  _fgit "$srv" add -A >/dev/null
  _fgit "$srv" commit -q --allow-empty -m "$1"
  sha="$(_fgit "$srv" rev-parse HEAD)"
}

# --- commits extras no servidor falso (a partir de C4) ---
cp "$srv/api/openapi.yaml" "$tmp/c4.yaml"
# D1: só docker/ muda (contrato igual)
printf 'x: 1\n' >>"$srv/docker/compose.yaml"
printf 'ZENY_X=\n' >"$srv/docker/novo.env"
commit_srv "D1: docker/ muda"
D1="$sha"
# D2: nome com espaço em docker/
printf 'y\n' >"$srv/docker/a b.yaml"
commit_srv "D2: arquivo com espaço"
D2="$sha"
# V2a / V2b: mudança breaking só fora de /api/v1
{
  cat "$tmp/c4.yaml"
  cat <<'YAML'
  /api/v2/velho:
    get:
      operationId: velho
      responses:
        '200':
          description: ok
YAML
} >"$srv/api/openapi.yaml"
commit_srv "V2a: acrescenta /api/v2/velho"
V2A="$sha"
cp "$tmp/c4.yaml" "$srv/api/openapi.yaml"
commit_srv "V2b: remove /api/v2/velho"
V2B="$sha"
# M: spec malformada; G: spec boa de novo
printf 'openapi: [\n  isto: x: {{\n' >"$srv/api/openapi.yaml"
commit_srv "M: spec malformada"
BAD="$sha"
cp "$tmp/c4.yaml" "$srv/api/openapi.yaml"
commit_srv "G: spec boa de novo"
GOOD="$sha"
# N: sem api/openapi.yaml
git -C "$srv" rm -q -f api/openapi.yaml
commit_srv "N: sem contrato"
NOSPEC="$sha"
mkdir -p "$srv/api"
cp "$tmp/c4.yaml" "$srv/api/openapi.yaml"
commit_srv "R: contrato volta"
BACK="$sha"
# tag anotada em C3
_fgit "$srv" tag -a -m anotada tag-c3 "$C3"

# --- fronts falsos ---
mkfront() { # NOME COMMIT : front com pin do servidor falso; define $f
  f="$tmp/$1"
  make_fake_front "$f"
  write_fake_pin "$f" "$srv" "$2"
}
mkfront fa "$C1"
drift="$f/tools/server-drift.sh"

# Temporários: o script usa mktemp -d; TMPDIR aponta para um diretório do teste.
mkdir "$tmp/tmpd"
export TMPDIR="$tmp/tmpd"
tmp_clean() { # MSG
  local left
  left="$(ls -A "$tmp/tmpd")"
  assert_eq "" "$left" "sem temporários sobrando: $1"
}
no_stray() { # MSG : nenhum arquivo malicioso criado
  local p
  for p in "$tmp/x" "$tmp/pwned" "$f/x" "$f/pwned" "$srv/x" "$srv/pwned" "$ROOT/x" "$ROOT/pwned"; do
    [[ ! -e "$p" ]] || assert_eq "inexistente" "existe: $p" "$1"
  done
}

snapshot() {
  git -C "$srv" for-each-ref --format='%(refname) %(objectname)'
  git -C "$srv" --no-optional-locks status --porcelain
  find "$srv" -printf '%p %s %T@\n' | sort
}
before="$(snapshot)"

# ---------------- stubs próprios ----------------
stub_path="$(install_docker_stub "$tmp/stub")"
stublog="$tmp/stub/docker.log"

# Daemon parado: inspect imprime erro e sai com 1.
mkdir -p "$tmp/dead/bin"
cat >"$tmp/dead/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock" >&2
exit 1
STUB
chmod +x "$tmp/dead/bin/docker"
dead_path="$tmp/dead/bin:$PATH"

# Docker que imprime lixo e sai com código de erro.
mkdir -p "$tmp/junk/bin"
cat >"$tmp/junk/bin/docker" <<'STUB'
#!/usr/bin/env bash
[[ "$1" == image ]] && exit 0
printf 'Resultado: breaking (código 12)\n\xff lixo\n'
exit "${JUNK_EXIT:-0}"
STUB
chmod +x "$tmp/junk/bin/docker"
junk_path="$tmp/junk/bin:$PATH"

# ---------------- classificação de erro do Docker/oasdiff: sempre 2 ----------------
for code in 2 3 10 11 12 101 102 125 126 127 130 137 139 255; do
  : >"$stublog"
  STUB_DOCKER_EXIT=$code STUB_IMAGE_PRESENT=1 PATH="$stub_path" \
    assert_exit 2 "docker run exit $code -> 2 (não 3/10/11/12)" -- "$drift" --target "$C3"
  assert_contains "$ASSERT_OUT" "oasdiff falhou (exit $code)" "mensagem do exit $code"
  assert_not_contains "$ASSERT_OUT" "Resultado:" "sem linha de resultado no erro $code"
  tmp_clean "docker exit $code"
done
: >"$stublog"
STUB_DOCKER_EXIT=0 STUB_IMAGE_PRESENT=1 PATH="$stub_path" assert_exit 11 "stub run exit 0 -> 11" -- "$drift" --target "$C3"
assert_contains "$ASSERT_OUT" "Resultado: aditivo (código 11)" "resultado do stub 0"
STUB_DOCKER_EXIT=1 STUB_IMAGE_PRESENT=1 PATH="$stub_path" assert_exit 12 "stub run exit 1 -> 12" -- "$drift" --target "$C3"
assert_contains "$ASSERT_OUT" "Resultado: breaking (código 12)" "resultado do stub 1"
tmp_clean "stub 0/1"

for code in 2 125 137; do
  JUNK_EXIT=$code PATH="$junk_path" assert_exit 2 "docker com lixo na saída e exit $code -> 2" -- "$drift" --target "$C3"
  tmp_clean "lixo $code"
done

PATH="$dead_path" assert_exit 2 "daemon parado -> 2" -- "$drift" --target "$C3"
assert_contains "$ASSERT_OUT" "imagem do oasdiff indisponível" "mensagem de imagem (daemon parado)"
assert_contains "$ASSERT_OUT" "Cannot connect to the Docker daemon" "causa do daemon repassada"
assert_contains "$ASSERT_OUT" "docker pull" "orienta o pull"
assert_not_contains "$ASSERT_OUT" "Resultado:" "sem resultado com daemon parado"
tmp_clean "daemon parado"

# Imagem ausente nunca provoca pull nem run; contrato idêntico (10) nem consulta o Docker.
: >"$stublog"
STUB_IMAGE_PRESENT=0 PATH="$stub_path" assert_exit 2 "imagem ausente -> 2" -- "$drift" --target "$C4"
assert_not_contains "$(cat "$stublog")" "pull" "nunca faz pull"
assert_not_contains "$(cat "$stublog")" " run " "nunca faz run sem a imagem"
: >"$stublog"
STUB_IMAGE_PRESENT=0 PATH="$dead_path" assert_exit 10 "contrato idêntico não depende do Docker" -- "$drift" --target "$C2"
STUB_IMAGE_PRESENT=0 PATH="$stub_path" assert_exit 10 "contrato idêntico não chama o Docker" -- "$drift" --target "$C2"
assert_eq "" "$(cat "$stublog")" "docker não chamado no 10"

# Argumentos do docker run
: >"$stublog"
STUB_DOCKER_EXIT=0 STUB_IMAGE_PRESENT=1 PATH="$stub_path" assert_exit 11 "stub para inspecionar argumentos" -- "$drift" --target "$C3"
log="$(cat "$stublog")"
assert_contains "$log" "image inspect $oasdiff_image" "inspect com a imagem fixada"
assert_contains "$log" "run --rm --network none -v " "run --rm --network none"
assert_contains "$log" ":/specs:ro -w /specs $oasdiff_image breaking base/api/openapi.yaml api/openapi.yaml" "montagem ro, workdir e contratos"
assert_contains "$log" "--match-path ^/api/v1(/|\$) --fail-on ERR --allow-external-refs=false --format text --lang pt-br" "opções do oasdiff"
assert_not_contains "$log" "pull" "sem pull"
assert_not_contains "$log" "$srv" "o servidor nunca é montado"
OASDIFF_IMAGE="exemplo/outra:1" STUB_DOCKER_EXIT=0 STUB_IMAGE_PRESENT=1 PATH="$stub_path" assert_exit 11 "OASDIFF_IMAGE sobrescreve" -- "$drift" --target "$C3"
assert_contains "$(cat "$stublog")" "image inspect exemplo/outra:1" "usa a imagem sobrescrita"
tmp_clean "argumentos"

# ---------------- REFs maliciosos ----------------
mkdir -p "$tmp/cwd"
bads=("--output=$tmp/x" "-o" "-oX" "--exec=touch $tmp/pwned" "--foo" "-" "--" "main;touch $tmp/pwned"
  "\$(touch $tmp/pwned)" "\`touch $tmp/pwned\`" "main
touch" "../../x" "$tmp/x" "HEAD:api/openapi.yaml" "nao existe" "refs/heads/inexistente" "$C1:api")
for bad in "${bads[@]}"; do
  assert_exit 2 "--target malicioso recusado: ${bad//$'\n'/\\n}" -- "$drift" --target "$bad"
  no_stray "--target ${bad//$'\n'/\\n}"
  assert_exit 2 "--base malicioso recusado: ${bad//$'\n'/\\n}" -- "$drift" --base "$bad" --target "$C2"
  no_stray "--base ${bad//$'\n'/\\n}"
  ZENY_DRIFT_TARGET="$bad" assert_exit 2 "ZENY_DRIFT_TARGET malicioso: ${bad//$'\n'/\\n}" -- "$drift"
  no_stray "env alvo ${bad//$'\n'/\\n}"
  ZENY_DRIFT_BASE="$bad" assert_exit 2 "ZENY_DRIFT_BASE malicioso: ${bad//$'\n'/\\n}" -- "$drift" --target "$C2"
  no_stray "env base ${bad//$'\n'/\\n}"
done
assert_exit 2 "--base --target (valor é outra opção) -> 2" -- "$drift" --base --target "$C2"
assert_exit 2 "--target --base -> 2" -- "$drift" --target --base "$C1"
tmp_clean "refs maliciosos"

# ---------------- uso ----------------
assert_exit 2 "--target sem valor -> 2" -- "$drift" --target
assert_contains "$ASSERT_OUT" "exige um valor" "mensagem do valor faltando"
assert_exit 2 "--base sem valor -> 2" -- "$drift" --base
assert_exit 2 "--base C1 --target sem valor -> 2" -- "$drift" --base "$C1" --target
assert_exit 2 "opção desconhecida -> 2" -- "$drift" --nada
assert_exit 2 "argumento posicional -> 2" -- "$drift" "$C2"
assert_exit 2 "--target=REF (forma com igual) -> 2" -- "$drift" "--target=$C2"
assert_exit 2 "opção desconhecida depois de válida -> 2" -- "$drift" --target "$C2" --foo
assert_not_contains "$ASSERT_OUT" "Resultado:" "sem resultado em erro de uso"
assert_exit 0 "-h -> 0" -- "$drift" -h
for c in 0 10 11 12 2 3; do assert_contains "$ASSERT_OUT" "$c " "ajuda cita o código $c"; done
assert_exit 0 "--help ignora o servidor" -- env ZENY_SERVER_REPO=/nao/existe "$drift" --help

# Repositório do servidor inválido: 2, mesmo com pin presente
ZENY_SERVER_REPO=/nao/existe assert_exit 2 "servidor inexistente -> 2" -- "$drift" --target "$C2"
ZENY_SERVER_REPO="$tmp/cwd" assert_exit 2 "servidor não-git -> 2" -- "$drift" --target "$C2"
ZENY_SERVER_REPO="$srv/api" assert_exit 2 "subdiretório do servidor -> 2" -- "$drift" --target "$C2"
ZENY_SERVER_REPO=/nao/existe assert_exit 2 "servidor inexistente e --base -> 2" -- "$drift" --base "$C1" --target "$C2"
tmp_clean "uso"

# ---------------- REFs válidos de várias formas ----------------
assert_exit 10 "SHA abreviado como alvo" -- "$drift" --target "${C2:0:10}"
assert_exit 10 "C4~2 como alvo" -- "$drift" --target "$C4~2"
assert_exit 0 "alvo igual à base por outro nome (C3 via tag anotada)" -- "$drift" --base "$C3" --target tag-c3
assert_contains "$ASSERT_OUT" "(tag-c3)" "primeira linha mostra a REF do alvo"
assert_contains "$ASSERT_OUT" "base ${C3:0:12} × alvo ${C3:0:12}" "tag anotada resolvida para o commit"
assert_exit 0 "main como alvo e base = HEAD do servidor" -- "$drift" --base "$BACK" --target main
assert_eq "Pré-voo: base ${BACK:0:12} × alvo ${BACK:0:12} (main)" "$(head -1 <<<"$ASSERT_OUT")" "primeira linha exata"
assert_eq "Resultado: igual (código 0)" "$(tail -1 <<<"$ASSERT_OUT")" "última linha exata"
assert_exit 10 "C1 -> C2" -- "$drift" --target "$C2"
assert_eq "Resultado: avançou sem mudança no contrato (código 10)" "$(tail -1 <<<"$ASSERT_OUT")" "última linha do 10"
assert_eq "Pré-voo: base ${C1:0:12} × alvo ${C2:0:12} ($C2)" "$(head -1 <<<"$ASSERT_OUT")" "primeira linha do 10"

# ---------------- pin ----------------
pinned() { # NOME : refaz front com pin de C1; define f e pdrift
  mkfront "$1" "$C1"
  pdrift="$f/tools/server-drift.sh"
}
pinned p1
assert_exit 0 "pin íntegro, alvo = pin -> 0" -- "$pdrift" --target "$C1"
printf '#' >>"$f/api/openapi.yaml"
assert_exit 3 "byte alterado -> 3" -- "$pdrift" --target "$C2"
assert_not_contains "$ASSERT_OUT" "Resultado:" "sem resultado no 3"
assert_exit 10 "byte alterado, mas --base explícito -> sem 3" -- "$pdrift" --base "$C1" --target "$C2"
ZENY_DRIFT_BASE="$C1" assert_exit 10 "byte alterado, mas ZENY_DRIFT_BASE explícito -> sem 3" -- "$pdrift" --target "$C2"
assert_exit 3 "byte alterado, alvo = pin -> 3" -- "$pdrift" --target "$C1"
pinned p2
head -c -1 "$f/api/openapi.yaml" >"$tmp/trunc" && cp "$tmp/trunc" "$f/api/openapi.yaml"
assert_exit 3 "byte removido -> 3" -- "$pdrift" --target "$C2"
pinned p3
rm "$f/api/openapi.yaml"
assert_exit 3 "contrato local ausente -> 3" -- "$pdrift" --target "$C2"
assert_exit 10 "contrato local ausente com --base -> sem 3" -- "$pdrift" --base "$C1" --target "$C2"
pinned p4
grep -v '^commit=' "$f/api/server-pin.properties" >"$tmp/pin.new" && cp "$tmp/pin.new" "$f/api/server-pin.properties"
assert_exit 3 "pin sem commit -> 3" -- "$pdrift" --target "$C2"
assert_exit 10 "pin sem commit, mas --base -> sem 3" -- "$pdrift" --base "$C1" --target "$C2"
pinned p5
: >"$f/api/server-pin.properties"
assert_exit 3 "pin vazio -> 3" -- "$pdrift" --target "$C2"
pinned p6
sed -i 's/^contract_sha256=.*/contract_sha256=/' "$f/api/server-pin.properties"
assert_exit 3 "contract_sha256 vazio -> 3" -- "$pdrift" --target "$C2"
pinned p7
sed -i 's/^commit=.*/commit=/' "$f/api/server-pin.properties"
assert_exit 3 "commit= vazio no pin (incompleto) -> 3" -- "$pdrift" --target "$C2"
pinned p8
sed -i 's/^commit=.*/commit=0123456789012345678901234567890123456789/' "$f/api/server-pin.properties"
assert_exit 2 "pin com commit inexistente -> 2" -- "$pdrift" --target "$C2"
pinned p9
sed -i "s|^commit=.*|commit=--output=$tmp/x|" "$f/api/server-pin.properties"
assert_exit 2 "pin com commit começando por hífen -> 2" -- "$pdrift" --target "$C2"
no_stray "pin malicioso"
pinned p10
sed -i 's/$/\r/' "$f/api/server-pin.properties"
assert_exit 10 "pin com CRLF -> 10" -- "$pdrift" --target "$C2"
pinned p11
rm "$f/api/server-pin.properties"
assert_exit 3 "sem pin -> 3" -- "$pdrift" --target "$C2"
assert_exit 3 "sem pin, alvo = main -> 3" -- "$pdrift"
assert_exit 10 "sem pin, --base explícito -> sem 3" -- "$pdrift" --base "$C1" --target "$C2"
ZENY_DRIFT_BASE="$C1" assert_exit 10 "sem pin, ZENY_DRIFT_BASE -> sem 3" -- "$pdrift" --target "$C2"
assert_exit 0 "sem pin, --base = alvo -> 0" -- "$pdrift" --base "$C2" --target "$C2"
pinned p12
rm -rf "$f/api"
assert_exit 3 "sem diretório api/ -> 3" -- "$pdrift" --target "$C2"
assert_exit 10 "sem api/, com --base -> sem 3" -- "$pdrift" --base "$C1" --target "$C2"
pinned p13
assert_exit 10 "cwd diferente da raiz do front" -- bash -c "cd / && '$pdrift' --target '$C2'"
pinned p14
printf '#' >>"$f/api/openapi.yaml"
assert_exit 3 "pin inconsistente e alvo inexistente -> 3 (pin vem antes)" -- "$pdrift" --target nao-existe
tmp_clean "pin"

# ---------------- precedência opção x variável ----------------
ZENY_DRIFT_TARGET="$C4" assert_exit 10 "--target vence ZENY_DRIFT_TARGET" -- "$drift" --target "$C2"
ZENY_DRIFT_TARGET="$C2" assert_exit 10 "ZENY_DRIFT_TARGET sozinho vale" -- "$drift"
ZENY_DRIFT_BASE="$C4" assert_exit 10 "--base vence ZENY_DRIFT_BASE" -- "$drift" --base "$C1" --target "$C2"
ZENY_DRIFT_BASE="$C1" ZENY_DRIFT_TARGET="$C1" assert_exit 0 "as duas variáveis" -- "$drift"
ZENY_DRIFT_BASE="" ZENY_DRIFT_TARGET="" assert_exit 10 "variáveis vazias = ausentes (pin e main, --target dado)" -- "$drift" --target "$C2"
run_real 11 "--base/--target vencem as variáveis (11, não 12)" env ZENY_DRIFT_BASE="$C3" ZENY_DRIFT_TARGET="$C4" "$drift" --base "$C1" --target "$C3"
help="$("$drift" --help 2>&1)"
if ! grep -Eiq 'preced|sobrep|prevalec|vence|prioridade|tem preferência' <<<"$help"; then
  assert_eq "ajuda documenta a precedência opção x ZENY_DRIFT_*" "ajuda sem menção a precedência" "precedência documentada"
fi
assert_exit 2 "--target '' -> 2 (valor vazio explícito não cai no padrão)" -- "$drift" --target ""
assert_exit 2 "--base '' com pin válido -> 2 (não cai no pin)" -- "$drift" --base "" --target "$C2"
tmp_clean "precedência"

# ---------------- lista de docker/ ----------------
STUB_DOCKER_EXIT=0 STUB_IMAGE_PRESENT=1 PATH="$stub_path" assert_exit 10 "só docker/ muda -> 10" -- "$drift" --base "$C4" --target "$D1"
assert_contains "$ASSERT_OUT" "Ambiente do servidor alterado:" "cabeçalho da lista"
assert_contains "$ASSERT_OUT" "  docker/compose.yaml" "lista compose"
assert_contains "$ASSERT_OUT" "  docker/novo.env" "lista novo.env"
assert_not_contains "$ASSERT_OUT" "sem mudanças" "não diz sem mudanças"
assert_not_contains "$ASSERT_OUT" "README.md" "só docker/ na lista"
assert_eq "Resultado: avançou sem mudança no contrato (código 10)" "$(tail -1 <<<"$ASSERT_OUT")" "última linha com docker/ alterado"
assert_exit 0 "base = alvo = D1: sem lista" -- "$drift" --base "$D1" --target "$D1"
assert_not_contains "$ASSERT_OUT" "Ambiente do servidor" "igual não lista docker/"
assert_exit 10 "C1 -> C2 sem docker/" -- "$drift" --base "$C1" --target "$C2"
assert_contains "$ASSERT_OUT" "Ambiente do servidor sem mudanças." "C1->C2 sem mudança de docker/"
assert_exit 10 "D1 -> C4 (volta)" -- "$drift" --base "$D1" --target "$C4"
assert_contains "$ASSERT_OUT" "docker/novo.env" "mudança também listada na volta"
assert_exit 10 "nome com espaço em docker/" -- "$drift" --base "$D1" --target "$D2"
assert_contains "$ASSERT_OUT" "  docker/a b.yaml" "nome com espaço listado inteiro"
tmp_clean "docker/"

# ---------------- contratos ausentes ----------------
assert_exit 2 "alvo sem api/openapi.yaml -> 2" -- "$drift" --base "$C4" --target "$NOSPEC"
assert_contains "$ASSERT_OUT" "não tem api/openapi.yaml" "mensagem de alvo sem contrato"
assert_exit 2 "base sem api/openapi.yaml -> 2" -- "$drift" --base "$NOSPEC" --target "$BACK"
assert_not_contains "$ASSERT_OUT" "Resultado:" "sem resultado quando falta contrato"
tmp_clean "contrato ausente"

# ---------------- oasdiff real ----------------
run_real 11 "real: C1 -> C3 aditivo" "$drift" --base "$C1" --target "$C3"
tmp_clean "real aditivo"
run_real 12 "real: C3 -> C4 breaking" "$drift" --base "$C3" --target "$C4"
tmp_clean "real breaking"
run_real 12 "real: C3 -> C1 (retrocesso remove /things) é breaking" "$drift" --base "$C3" --target "$C1"
run_real 11 "real: C4 -> C3 (volta a ter meta/version) é aditivo" "$drift" --base "$C4" --target "$C3"
run_real 11 "real: breaking só em /api/v2 não conta (match-path)" "$drift" --base "$V2A" --target "$V2B"
run_real 2 "real: spec malformada no alvo -> 2" "$drift" --base "$C4" --target "$BAD"
assert_not_contains "$ASSERT_OUT" "Resultado:" "sem resultado com spec malformada"
tmp_clean "real malformada alvo"
run_real 2 "real: spec malformada na base -> 2" "$drift" --base "$BAD" --target "$GOOD"
tmp_clean "real malformada base"
assert_exit 0 "mesmo commit malformado -> 0 (nada a comparar)" -- "$drift" --base "$BAD" --target "$BAD"
assert_exit 10 "commits distintos, contrato idêntico em bytes -> 10" -- "$drift" --base "$C4" --target "$GOOD"
mkfront fr "$C1"
run_real 11 "real: pin C1 -> C3 -> 11" "$f/tools/server-drift.sh" --target "$C3"
run_real 12 "real: pin C1 -> main (R) -> 12" "$f/tools/server-drift.sh"
tmp_clean "real pin"

# ---------------- só leitura no servidor ----------------
assert_eq "" "$(diff <(printf '%s\n' "$before") <(snapshot))" "servidor falso intacto (refs, status, arquivos, tamanhos e mtimes)"
assert_eq "" "$(git -C "$srv" --no-optional-locks status --porcelain)" "servidor sem mudanças no working tree"
tmp_clean "final"

finish
