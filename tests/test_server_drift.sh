#!/usr/bin/env bash
# Testes de tools/server-drift.sh. Só usa servidor falso e front falso em diretórios temporários.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_fake_server "$tmp/srv"
export ZENY_SERVER_REPO="$tmp/srv"
unset ZENY_DRIFT_BASE ZENY_DRIFT_TARGET
# make_fake_front copia api/ (pin real); write_fake_pin o sobrescreve com o pin do servidor falso.
make_fake_front "$tmp/front"
write_fake_pin "$tmp/front" "$tmp/srv" "$FAKE_C1"
drift="$tmp/front/tools/server-drift.sh"

snapshot() {
  git -C "$tmp/srv" for-each-ref --format='%(refname) %(objectname)'
  git -C "$tmp/srv" status --porcelain
  find "$tmp/srv" -path "$tmp/srv/.git" -prune -o -print | sort
}
before="$(snapshot)"

oasdiff_image="${OASDIFF_IMAGE:-tufin/oasdiff:v1.33.0@sha256:6263a96dd2ef0726c54e21fea9b8e1607eac4841add0079324b424c1f52b819c}"
have_image=0
docker image inspect "$oasdiff_image" >/dev/null 2>&1 && have_image=1

# run_real ESPERADO MSG CMD... : caso que usa o Docker real; pula se a imagem não está no cache.
run_real() {
  local expected="$1" msg="$2"
  shift 2
  if ((have_image)); then
    assert_exit "$expected" "$msg" -- "$@"
  else
    ASSERT_OUT=""
    skip_case "$msg (imagem do oasdiff ausente)"
  fi
}

# Casos que dispensam o oasdiff
assert_exit 0 "alvo = base -> 0" -- "$drift" --target "$FAKE_C1"
assert_contains "$ASSERT_OUT" "Resultado: igual (código 0)" "resultado igual"
assert_contains "$ASSERT_OUT" "Pré-voo: base ${FAKE_C1:0:12} × alvo ${FAKE_C1:0:12} ($FAKE_C1)" "primeira linha"
assert_exit 10 "C2 (só README) -> 10" -- "$drift" --target "$FAKE_C2"
assert_contains "$ASSERT_OUT" "Resultado: avançou sem mudança no contrato (código 10)" "resultado 10"
assert_contains "$ASSERT_OUT" "Ambiente do servidor sem mudanças." "docker/ intacto"

# Casos com o oasdiff real
run_real 11 "C3 aditivo -> 11" "$drift" --target "$FAKE_C3"
assert_contains "$ASSERT_OUT" "Resultado: aditivo (código 11)" "resultado aditivo"
run_real 12 "C4 breaking -> 12" "$drift" --target "$FAKE_C4"
assert_contains "$ASSERT_OUT" "Resultado: breaking (código 12)" "resultado breaking"
run_real 12 "sem alvo (main = C4) -> 12" "$drift"
ZENY_DRIFT_BASE="$FAKE_C3" ZENY_DRIFT_TARGET="$FAKE_C4" run_real 12 "env C3 -> C4 -> 12" "$drift"
run_real 11 "--base C1 --target C3 -> 11" "$drift" --base "$FAKE_C1" --target "$FAKE_C3"

# Pin inconsistente
make_fake_front "$tmp/front2"
write_fake_pin "$tmp/front2" "$tmp/srv" "$FAKE_C1"
printf '# x\n' >>"$tmp/front2/api/openapi.yaml"
assert_exit 3 "openapi local alterado -> 3" -- "$tmp/front2/tools/server-drift.sh" --target "$FAKE_C2"
write_fake_pin "$tmp/front2" "$tmp/srv" "$FAKE_C1"
grep -v '^contract_sha256=' "$tmp/front2/api/server-pin.properties" >"$tmp/pin.new"
cp "$tmp/pin.new" "$tmp/front2/api/server-pin.properties"
assert_exit 3 "pin sem contract_sha256 -> 3" -- "$tmp/front2/tools/server-drift.sh" --target "$FAKE_C2"
rm "$tmp/front2/api/server-pin.properties"
assert_exit 3 "sem pin -> 3" -- "$tmp/front2/tools/server-drift.sh" --target "$FAKE_C2"
run_real 11 "--base explícito sem pin -> 11" "$tmp/front2/tools/server-drift.sh" --base "$FAKE_C1" --target "$FAKE_C3"
assert_exit 10 "--base explícito sem pin, C1 -> C2 -> 10" -- "$tmp/front2/tools/server-drift.sh" --base "$FAKE_C1" --target "$FAKE_C2"

# Entrada inválida
assert_exit 2 "alvo inexistente -> 2" -- "$drift" --target nao-existe
assert_exit 2 "--target --output=x -> 2" -- "$drift" --target --output=x
if [[ -e "$tmp/x" || -e "$tmp/front/x" || -e "$tmp/srv/x" ]]; then
  assert_eq "ausente" "presente" "x não pode existir"
fi
assert_exit 2 "opção desconhecida -> 2" -- "$drift" --foo
assert_exit 0 "--help -> 0" -- "$drift" --help
assert_contains "$ASSERT_OUT" "12" "help lista os códigos"

# Docker falso: nunca chega a 11 ou 12
stub_path="$(install_docker_stub "$tmp/stub")"
STUB_DOCKER_EXIT=125 STUB_IMAGE_PRESENT=1 PATH="$stub_path" assert_exit 2 "docker run 125 -> 2" -- "$drift" --target "$FAKE_C3"
assert_contains "$ASSERT_OUT" "oasdiff falhou (exit 125)" "mensagem do exit 125"
: >"$tmp/stub/docker.log"
STUB_DOCKER_EXIT=1 PATH="$stub_path" assert_exit 2 "imagem ausente (inspect exit 1) -> 2" -- "$drift" --target "$FAKE_C3"
assert_contains "$ASSERT_OUT" "imagem do oasdiff indisponível" "mensagem de imagem"
assert_not_contains "$(cat "$tmp/stub/docker.log")" " run " "sem docker run quando a imagem falta"

# O servidor falso não mudou até aqui
assert_eq "$before" "$(snapshot)" "servidor falso intacto"

# Spec malformada no alvo (real): commit extra no servidor falso, feito pelo próprio teste
printf 'openapi: [\n  isto: não é yaml: {{\n' >"$tmp/srv/api/openapi.yaml"
_fgit "$tmp/srv" add api/openapi.yaml
_fgit "$tmp/srv" commit -q -m "C5: spec malformada"
bad="$(_fgit "$tmp/srv" rev-parse HEAD)"
run_real 2 "spec malformada -> 2" "$drift" --target "$bad"

finish
