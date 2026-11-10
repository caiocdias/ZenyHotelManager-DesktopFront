#!/usr/bin/env bash
# Testes de tools/server-env/package.sh e save-images.sh. Servidor e front falsos, docker stub.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT STUB_IMAGE_PRESENT STUB_DOCKER_EXIT

stubpath="$(install_docker_stub "$tmp/stub")"
dlog="$tmp/stub/docker.log"
tag="${FAKE_C1:0:12}"

front="$tmp/front"
make_fake_front "$front"
write_fake_pin "$front" "$srv" "$FAKE_C1"
_fgit "$front" add -A >/dev/null
_fgit "$front" commit -q --allow-empty -m "pin"

# Segredos de máquina que jamais podem entrar no pacote.
mkdir -p "$front/.server"
write_test_env "$front/.server/zeny-front.env"
write_test_env "$front/tools/server-env/.env"
mkdir -p "$front/build/outro"
echo preservar >"$front/build/outro/arquivo.txt"

pkg="$front/build/demo-package"
assert_exit 0 "package.sh sai com 0" -- bash "$front/tools/server-env/package.sh"
assert_contains "$ASSERT_OUT" "$pkg" "imprime o caminho do pacote"

# --- conteúdo ---
assert_file "$pkg/server/docker/compose.yaml" "server/docker/compose.yaml"
assert_eq "$(git -C "$srv" show "$FAKE_C1:docker/compose.yaml")" "$(cat "$pkg/server/docker/compose.yaml")" "compose.yaml igual ao do commit"
assert_file "$pkg/compose.override.yaml" "compose.override.yaml"
assert_file "$pkg/.env.example" ".env.example"
assert_file "$pkg/gen-env.sh" "gen-env.sh"
assert_file "$pkg/server-pin.properties" "server-pin.properties"
assert_eq "$(cat "$front/api/server-pin.properties")" "$(cat "$pkg/server-pin.properties")" "pin copiado"
assert_file "$pkg/LEIA-ME.md" "LEIA-ME.md"
assert_file "$pkg/app/LEIA-ME.txt" "app/LEIA-ME.txt"
assert_contains "$(cat "$pkg/app/LEIA-ME.txt")" "M1 E07" "app/LEIA-ME.txt cita M1 E07"
assert_file "$pkg/images/LEIA-ME.txt" "images/LEIA-ME.txt"
assert_contains "$(cat "$pkg/images/LEIA-ME.txt")" "save-images.sh" "images/LEIA-ME.txt cita save-images.sh"
assert_eq "1" "$([[ -x "$pkg/gen-env.sh" ]] && echo 1 || echo 0)" "gen-env.sh executável"

leia="$(cat "$pkg/LEIA-ME.md")"
if grep -qE '@[A-Z_]+@' "$pkg/LEIA-ME.md"; then
  _assert_fail "LEIA-ME.md sem marcador @...@"
fi
assert_contains "$leia" "$FAKE_C1" "LEIA-ME.md contém o SHA do commit"
assert_contains "$leia" "zeny-hotel-manager:$tag" "LEIA-ME.md contém a imagem com a tag"

ov="$(cat "$pkg/compose.override.yaml")"
assert_contains "$ov" "zeny-hotel-manager:$tag" "override com a imagem do commit"
assert_contains "$ov" "ZENY_HTTP_ADDRESS:-0.0.0.0}" "override com endereço padrão 0.0.0.0"
assert_not_contains "$ov" "@" "override sem marcador"

# --- nenhum .env ---
assert_eq "" "$(find "$pkg" \( -name '.env' -o -name '*.env' \))" "pacote sem .env nem *.env"

# --- git status limpo sem .gitignore ---
assert_file "$front/build/.gitignore" "build autoignorado"
assert_eq "" "$(git -C "$front" status --porcelain -- build .server)" "git status do front limpo (build e .server)"

# --- segunda execução ---
echo lixo >"$pkg/estranho.txt"
assert_exit 0 "segunda execução" -- bash "$front/tools/server-env/package.sh"
assert_eq "0" "$([[ -e "$pkg/estranho.txt" ]] && echo 1 || echo 0)" "arquivo estranho apagado"
assert_file "$front/build/outro/arquivo.txt" "build/outro intacto"
assert_file "$pkg/LEIA-ME.md" "pacote recriado"

# --- gen-env do pacote + compose real ---
if ! docker compose version >/dev/null 2>&1; then
  skip_case "docker compose indisponível: pacote com compose config"
else
  work="$(mktemp -d)"
  (cd "$work" && assert_exit 0 "gen-env do pacote fora de git" -- bash "$pkg/gen-env.sh" "$work/.env")
  cfg="$(cd "$work" && docker compose -p zeny-front \
    -f "$pkg/server/docker/compose.yaml" -f "$pkg/compose.override.yaml" \
    --env-file "$work/.env" config --format json 2>&1)"
  if command -v python3 >/dev/null 2>&1; then
    hostip="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["proxy"]["ports"][0]["host_ip"])' <<<"$cfg" 2>&1)"
    endpoint="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["api"]["environment"]["ZENY_STORAGE_PUBLIC_ENDPOINT"])' <<<"$cfg" 2>&1)"
    assert_eq "0.0.0.0" "$hostip" "host_ip do pacote é 0.0.0.0"
    assert_eq "http://localhost:8088" "$endpoint" "endpoint público padrão"
  else
    assert_contains "$cfg" '"host_ip": "0.0.0.0"' "host_ip do pacote é 0.0.0.0"
    assert_contains "$cfg" '"ZENY_STORAGE_PUBLIC_ENDPOINT": "http://localhost:8088"' "endpoint público padrão"
  fi
  rm -rf "$work"
fi

# --- save-images.sh ---
: >"$dlog"
out="$tmp/imagens.tar"
STUB_IMAGE_PRESENT=1 assert_exit 0 "save-images com imagens presentes" -- \
  env PATH="$stubpath" STUB_IMAGE_PRESENT=1 bash "$front/tools/server-env/save-images.sh" "$out"
log="$(cat "$dlog")"
assert_eq "1" "$(grep -c ' save -o ' "$dlog")" "um único docker save"
saveline="$(grep ' save -o ' "$dlog")"
assert_contains "$saveline" "save -o $out" "save grava em OUT"
for img in "zeny-hotel-manager:$tag" postgres:18 pgsty/minio:RELEASE.2026-08-04T00-00-00Z caddy:2.11-alpine; do
  assert_contains "$saveline" "$img" "save inclui $img"
done
assert_not_contains "$saveline" ":dev" "save sem :dev"
assert_eq "1" "$(grep -o 'pgsty/minio' <<<"$saveline" | wc -l)" "imagens duplicadas removidas"

: >"$dlog"
assert_exit 2 "save-images com imagem ausente sai com 2" -- \
  env PATH="$stubpath" STUB_IMAGE_PRESENT=0 bash "$front/tools/server-env/save-images.sh" "$out"
assert_contains "$ASSERT_OUT" "up.sh --build" "dica de up.sh --build"
assert_eq "0" "$(grep -c ' save ' "$dlog")" "nenhum save quando falta imagem"

finish
