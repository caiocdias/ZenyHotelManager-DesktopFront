#!/usr/bin/env bash
# Ataque da etapa M0 E02 a docker/gate do servidor: save-images.sh e package.sh só podem olhar docker/compose.yaml.
# Complementa tests/test_package.sh com asserções ancoradas (docker/gate, compose.gate, zeny-gate, ...) e outros compose na árvore.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT STUB_IMAGE_PRESENT STUB_DOCKER_EXIT
stubpath="$(install_docker_stub "$tmp/stub")"
dlog="$tmp/stub/docker.log"

# Servidor com: gate cujo image é IDÊNTICO a uma do compose principal, imagens exclusivas do gate, e outros compose
# em docker/ (override, prod) com image: próprias.
srv="$tmp/ZenyHotelManager"
make_fake_server "$srv"
mkdir -p "$srv/docker/gate/fnrh-mock/mappings"
cat >"$srv/docker/gate/compose.gate.yaml" <<'YAML'
name: zeny-gate
services:
  db:
    image: postgres:18
  api:
    image: zeny-hotel-manager:gate
  fnrh-mock:
    image: "wiremock/wiremock:3.13.1"   # mock
YAML
printf 'image: so-do-readme:1\n' >"$srv/docker/gate/README.md"
printf '#!/usr/bin/env bash\necho rodando-gate\n' >"$srv/docker/gate/run-gate.sh"
chmod +x "$srv/docker/gate/run-gate.sh"
printf '{}\n' >"$srv/docker/gate/fnrh-mock/mappings/a.json"
printf 'services:\n  api:\n    image: override-intruso:1\n' >"$srv/docker/compose.override.yaml"
printf 'services:\n  api:\n    image: prod-intruso:1\n' >"$srv/docker/compose.prod.yaml"
_fgit "$srv" add -A >/dev/null
_fgit "$srv" commit -q -m "gate e outros compose"
cg="$(_fgit "$srv" rev-parse HEAD)"
f="$tmp/front"
make_fake_front "$f"
write_fake_pin "$f" "$srv" "$cg"
_fgit "$f" add -A >/dev/null
_fgit "$f" commit -q --allow-empty -m "pin"
make_fake_jars "$f"
export ZENY_SERVER_REPO="$srv"

# 1. save-images: só as quatro imagens de docker/compose.yaml, na ordem, sem nada dos outros compose.
: >"$dlog"
assert_exit 0 "save-images com gate e outros compose" -- \
  env PATH="$stubpath" STUB_IMAGE_PRESENT=1 bash "$f/tools/server-env/save-images.sh" "$tmp/o.tar"
saveline="$(grep ' save -o ' "$dlog")"
assert_contains "$saveline" "save -o $tmp/o.tar zeny-hotel-manager:${cg:0:12} postgres:18 pgsty/minio:RELEASE.2026-08-04T00-00-00Z caddy:2.11-alpine" "exatamente as quatro imagens do compose principal, na ordem"
assert_eq "1" "$(grep -o 'postgres:18' <<<"$saveline" | wc -l)" "postgres:18 do gate não duplica a do compose principal"
for bad in zeny-hotel-manager:gate wiremock so-do-readme override-intruso prod-intruso; do
  assert_not_contains "$saveline" "$bad" "save-images não inclui $bad"
done
assert_eq "4" "$(wc -w <<<"${saveline#*save -o $tmp/o.tar }")" "quatro imagens no save"

# 2. Imagem ausente: o erro lista só imagens do compose principal; nenhuma consulta (inspect) às do gate.
: >"$dlog"
assert_exit 2 "save-images com imagens ausentes e gate na árvore" -- \
  env PATH="$stubpath" STUB_IMAGE_PRESENT=0 bash "$f/tools/server-env/save-images.sh" "$tmp/o2.tar"
for bad in zeny-hotel-manager:gate wiremock override-intruso prod-intruso; do
  assert_not_contains "$ASSERT_OUT" "$bad" "erro de imagem ausente não cita $bad"
  assert_not_contains "$(cat "$dlog")" "$bad" "docker não é consultado sobre $bad"
done
assert_contains "$ASSERT_OUT" "zeny-hotel-manager:${cg:0:12}" "erro cita a imagem do servidor fixado"

# 3. package.sh: gate vem do git archive, mas nada fora de server/ o referencia nem o torna executável pelo fluxo.
assert_exit 0 "package.sh com gate e outros compose" -- bash "$f/tools/server-env/package.sh"
pkg="$f/build/demo-package"
assert_file "$pkg/server/docker/gate/compose.gate.yaml" "gate no pacote só dentro de server/"
anchored='docker/gate|compose\.gate|zeny-gate|run-gate|gate-env|m5-roteiro|fnrh-mock|wiremock'
hits="$(cd "$pkg" && grep -rIlE "$anchored" . --exclude-dir=server || true)"
assert_eq "" "$hits" "nenhum arquivo do pacote fora de server/ referencia o gate"
assert_not_contains "$(cat "$pkg/compose.override.yaml")" "gate" "override sem gate"
assert_not_contains "$(cat "$pkg/server-pin.properties" "$pkg/VERSAO.txt")" "gate" "pin e VERSAO sem gate"
svc="$(grep -E '^  [a-z-]+:$' "$pkg/compose.override.yaml" | tr -d ' :' | tr '\n' ' ')"
assert_eq "api proxy " "$svc" "override só declara api e proxy"
assert_eq "0" "$(grep -cE 'docker/gate|run-gate|compose\.gate|-f [^ ]*gate' "$pkg/LEIA-ME.md")" "LEIA-ME sem comando que rode o gate"
assert_eq "" "$(find "$pkg" \( -name '.env' -o -name '*.env' \) -print)" "pacote sem .env"

finish
