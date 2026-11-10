#!/usr/bin/env bash
# Testes de tools/server-env/up.sh e down.sh. Só stubs de docker/curl e fronts falsos.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT ZENY_UP_TIMEOUT STUB_IMAGE_PRESENT STUB_CURL_BODY STUB_DOCKER_EXIT STUB_CURL_EXIT

stubpath="$(install_docker_stub "$tmp/stub")"
dlog="$tmp/stub/docker.log"
clog="$tmp/stub/curl.log"

# new_front DIR : front falso com o pin de FAKE_C1.
new_front() {
  make_fake_front "$1"
  write_fake_pin "$1" "$srv" "$FAKE_C1"
  _fgit "$1" add -A >/dev/null
  _fgit "$1" commit -q --allow-empty -m "pin"
}

# run_script FRONT SCRIPT ARGS... : roda o script do front com o PATH dos stubs, cwd neutro.
run_script() {
  local front="$1" script="$2"
  shift 2
  (cd / && PATH="$stubpath" bash "$front/tools/server-env/$script" "$@")
}

reset_logs() { : >"$dlog"; : >"$clog"; }

front="$tmp/front"
new_front "$front"
envf="$front/.server/zeny-front.env"

# --- sem env file ---
reset_logs
assert_exit 2 "up sem env file sai com 2" -- run_script "$front" up.sh
assert_contains "$ASSERT_OUT" "gen-env.sh" "mensagem cita gen-env.sh"
assert_contains "$ASSERT_OUT" "$envf" "mensagem cita o caminho do env file"
assert_eq "" "$(cat "$dlog")" "nenhuma chamada ao docker sem env file"
assert_file "$front/.server/.gitignore" ".server nasce autoignorado"

# --- build conforme a imagem ---
write_test_env "$envf"
export STUB_CURL_BODY='{"status":"UP"}'

reset_logs
STUB_IMAGE_PRESENT=0 assert_exit 0 "up com imagem ausente" -- run_script "$front" up.sh
log="$(cat "$dlog")"
assert_contains "$log" "image inspect zeny-hotel-manager:${FAKE_C1:0:12}" "inspeciona a imagem do commit fixado"
assert_contains "$log" "build api" "imagem ausente constrói"
b="$(grep -n 'build api' "$dlog" | head -1 | cut -d: -f1)"
u="$(grep -n 'up -d' "$dlog" | head -1 | cut -d: -f1)"
assert_eq "1" "$((b < u ? 1 : 0))" "build vem antes de up -d"

reset_logs
STUB_IMAGE_PRESENT=1 assert_exit 0 "up com imagem presente" -- run_script "$front" up.sh
log="$(cat "$dlog")"
assert_not_contains "$log" "build" "imagem presente não constrói"
assert_contains "$log" "up -d" "sobe os serviços"

reset_logs
STUB_IMAGE_PRESENT=1 assert_exit 0 "up --build" -- run_script "$front" up.sh --build
assert_contains "$(cat "$dlog")" "build api" "--build constrói mesmo com imagem"
assert_contains "$ASSERT_OUT" "zeny-front no ar em http://127.0.0.1:8088 (servidor ${FAKE_C1:0:12})" "mensagem de sucesso"

# --- URL de readiness ---
reset_logs
STUB_IMAGE_PRESENT=1 run_script "$front" up.sh >/dev/null 2>&1
assert_contains "$(cat "$clog")" "http://127.0.0.1:8088/actuator/health/readiness" "URL padrão"

write_test_env "$envf" ZENY_HTTP_PORT=9099
reset_logs
STUB_IMAGE_PRESENT=1 run_script "$front" up.sh >/dev/null 2>&1
assert_contains "$(cat "$clog")" "http://127.0.0.1:9099/actuator/health/readiness" "porta do env"

write_test_env "$envf" ZENY_HTTP_ADDRESS=0.0.0.0
reset_logs
STUB_IMAGE_PRESENT=1 run_script "$front" up.sh >/dev/null 2>&1
assert_contains "$(cat "$clog")" "http://127.0.0.1:8088/" "0.0.0.0 consulta 127.0.0.1"

write_test_env "$envf" ZENY_HTTP_ADDRESS=192.168.0.20
reset_logs
STUB_IMAGE_PRESENT=1 run_script "$front" up.sh >/dev/null 2>&1
assert_contains "$(cat "$clog")" "http://192.168.0.20:8088/" "endereço próprio consulta o próprio endereço"

# --- timeout ---
write_test_env "$envf"
reset_logs
STUB_IMAGE_PRESENT=1 STUB_CURL_BODY='{"status":"DOWN"}' ZENY_UP_TIMEOUT=1 \
  assert_exit 1 "readiness DOWN estoura o tempo" -- run_script "$front" up.sh
assert_contains "$ASSERT_OUT" '"status":"DOWN"' "mostra a última resposta"
assert_contains "$ASSERT_OUT" "docker compose -p zeny-front logs api" "dica de logs"

# --- down ---
reset_logs
assert_exit 0 "down" -- run_script "$front" down.sh
log="$(cat "$dlog")"
assert_contains "$log" " down" "down chama compose down"
assert_not_contains "$log" "down -v" "down sem -v"
reset_logs
assert_exit 0 "down -v" -- run_script "$front" down.sh -v
assert_contains "$(cat "$dlog")" "down -v" "down -v"

# --- down sem árvore extraída ---
front2="$tmp/front2"
new_front "$front2"
reset_logs
assert_exit 0 "down sem árvore" -- run_script "$front2" down.sh
log="$(cat "$dlog")"
assert_contains "$log" "-p zeny-front down" "sem árvore usa -p zeny-front down"
assert_not_contains "$log" "cwd=$front2 " "cwd não é o do front"
assert_eq "no" "$([[ -d "$front2/.server/$FAKE_C1" ]] && echo yes || echo no)" "down não extrai a árvore"
reset_logs
assert_exit 0 "down -v sem árvore" -- run_script "$front2" down.sh --volumes
assert_contains "$(cat "$dlog")" "-p zeny-front down -v" "sem árvore -v"

# --- isolamento: só zeny-front ---
reset_logs
STUB_IMAGE_PRESENT=0 run_script "$front" up.sh >/dev/null 2>&1
run_script "$front" down.sh -v >/dev/null 2>&1
bad=0
while IFS= read -r l; do
  if [[ "$l" == *" compose "* ]]; then
    [[ "$l" == *"-p zeny-front "* ]] || bad=1
    [[ "$l" == *"-p zeny "* ]] && bad=1
  fi
  [[ "$l" == *":dev"* ]] && bad=1
done <"$dlog"
assert_eq "0" "$bad" "toda chamada compose usa -p zeny-front; nada de -p zeny nem :dev"

finish
