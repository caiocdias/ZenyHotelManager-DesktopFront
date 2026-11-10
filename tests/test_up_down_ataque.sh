#!/usr/bin/env bash
# Testes adversariais de tools/server-env/up.sh e down.sh (Task 6).
# Só stubs de docker/curl e fronts falsos. O único uso do docker real é `docker compose config -q`
# (via stub, no lugar de `down`), que só lê arquivos e nunca fala com o daemon.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT ZENY_UP_TIMEOUT STUB_IMAGE_PRESENT \
  STUB_CURL_BODY STUB_DOCKER_EXIT STUB_CURL_EXIT COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES \
  STUB_FAIL_ON STUB_STRICT_ENV STUB_REAL_CONFIG

REAL_DOCKER="$(command -v docker || true)"
export REAL_DOCKER

# Stub próprio: registra argv, cwd e as variáveis COMPOSE_* que chegam ao docker.
#   STUB_IMAGE_PRESENT (padrão 0)   STUB_FAIL_ON=build|up|down|<subcomando> (sai 3 só nele)
#   STUB_STRICT_ENV=1  : `--env-file X` inexistente falha como no docker real
#   STUB_REAL_CONFIG=1 : `compose -f ... down` vira `docker compose ... config -q` REAL (nunca down real)
stubdir="$tmp/stub"
mkdir -p "$stubdir/bin"
dlog="$stubdir/docker.log"
clog="$stubdir/curl.log"
cat >"$stubdir/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf 'cwd=%s %s\n' "$(pwd)" "$*" >>"$STUB_LOG_DIR/docker.log"
printf 'env COMPOSE_FILE=[%s] COMPOSE_PROJECT_NAME=[%s]\n' "${COMPOSE_FILE:-}" "${COMPOSE_PROJECT_NAME:-}" >>"$STUB_LOG_DIR/docker.env.log"
if [[ "${1:-}" == image && "${2:-}" == inspect ]]; then
  [[ "${STUB_IMAGE_PRESENT:-0}" == 1 ]] && exit 0
  exit 1
fi
args=("$@")
hasf=0 envfile="" verb=""
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[i]}" in
    -f) hasf=1 ;;
    --env-file) envfile="${args[i + 1]}" ;;
    build | up | down) [[ -z "$verb" ]] && verb="${args[i]}" ;;
  esac
done
if [[ "${STUB_STRICT_ENV:-0}" == 1 && -n "$envfile" && ! -f "$envfile" ]]; then
  echo "couldn't find env file: $envfile" >&2
  exit 1
fi
if [[ "${STUB_REAL_CONFIG:-0}" == 1 && "$verb" == down && "$hasf" == 1 ]]; then
  new=()
  for a in "${args[@]}"; do
    [[ "$a" == down ]] && break
    new+=("$a")
  done
  exec "$REAL_DOCKER" "${new[@]}" config -q
fi
[[ -n "$verb" && "${STUB_FAIL_ON:-}" == "$verb" ]] && exit 3
exit "${STUB_DOCKER_EXIT:-0}"
STUB
cat >"$stubdir/bin/curl" <<'STUB'
#!/usr/bin/env bash
printf 'cwd=%s %s\n' "$(pwd)" "$*" >>"$STUB_LOG_DIR/curl.log"
[[ -n "${STUB_CURL_BODY:-}" ]] && printf '%s' "$STUB_CURL_BODY"
exit "${STUB_CURL_EXIT:-0}"
STUB
chmod +x "$stubdir/bin/docker" "$stubdir/bin/curl"
export STUB_LOG_DIR="$stubdir"
stubpath="$stubdir/bin:$PATH"
elog="$stubdir/docker.env.log"

new_front() {
  make_fake_front "$1"
  write_fake_pin "$1" "$srv" "$FAKE_C1"
  _fgit "$1" add -A >/dev/null
  _fgit "$1" commit -q --allow-empty -m "pin"
}

run_script() {
  local front="$1" script="$2"
  shift 2
  (cd / && PATH="$stubpath" bash "$front/tools/server-env/$script" "$@")
}

reset_logs() { : >"$dlog"; : >"$clog"; : >"$elog"; }

# Cada caso usa um front novo, para não herdar árvore extraída nem env file.
front="$tmp/front"
new_front "$front"
envf="$front/.server/zeny-front.env"
write_test_env_in() { mkdir -p "$(dirname "$1")"; write_test_env "$@"; }
write_test_env_in "$envf"
export STUB_CURL_BODY='{"status":"UP"}' STUB_IMAGE_PRESENT=1

# Prepara a árvore extraída (uma subida normal) e zera os logs.
reset_logs
run_script "$front" up.sh >/dev/null 2>&1
assert_eq "yes" "$([[ -d "$front/.server/$FAKE_C1" ]] && echo yes || echo no)" "pré-condição: árvore extraída"

# ---------------------------------------------------------------------------
# 1. Opção desconhecida: exit 2, mensagem de uso em pt-BR, nenhuma chamada ao docker.
# ---------------------------------------------------------------------------
f_opt="$tmp/front_opt"
new_front "$f_opt"
write_test_env_in "$f_opt/.server/zeny-front.env"
for a in "--foo" "-x" "build" "--build=1" "-v"; do
  reset_logs
  assert_exit 2 "up.sh $a sai com 2" -- run_script "$f_opt" up.sh "$a"
  assert_contains "$ASSERT_OUT" "uso:" "up.sh $a: mensagem de uso em pt-BR"
  assert_eq "" "$(cat "$dlog")" "up.sh $a: nenhuma chamada ao docker"
done
reset_logs
assert_exit 2 "up.sh --build --foo sai com 2" -- run_script "$f_opt" up.sh --build --foo
assert_eq "" "$(cat "$dlog")" "up.sh --build --foo: nenhuma chamada ao docker (nada de build antes de validar)"
for a in "--foo" "-x" "-V" "--volume" "--volumes=1" "up"; do
  reset_logs
  assert_exit 2 "down.sh $a sai com 2" -- run_script "$front" down.sh "$a"
  assert_contains "$ASSERT_OUT" "uso:" "down.sh $a: mensagem de uso em pt-BR"
  assert_eq "" "$(cat "$dlog")" "down.sh $a: nenhuma chamada ao docker"
done
reset_logs
assert_exit 2 "down.sh -v --foo sai com 2 e não derruba nada" -- run_script "$front" down.sh -v --foo
assert_eq "" "$(cat "$dlog")" "down.sh -v --foo: nenhuma chamada ao docker"

# ---------------------------------------------------------------------------
# 2. down sem -v nunca remove volumes.
# ---------------------------------------------------------------------------
reset_logs
assert_exit 0 "down sem -v" -- run_script "$front" down.sh
assert_not_contains "$(cat "$dlog")" " -v" "down com árvore não passa -v"
assert_not_contains "$(cat "$dlog")" "--volumes" "down com árvore não passa --volumes"
assert_not_contains "$(cat "$dlog")" "volume" "down com árvore não toca em volume"
assert_contains "$ASSERT_OUT" "preservados" "down sem -v avisa que os volumes foram preservados"
assert_not_contains "$ASSERT_OUT" "com os volumes" "down sem -v não diz que removeu volumes"

f_nt="$tmp/front_nt"
new_front "$f_nt"
reset_logs
assert_exit 0 "down sem árvore e sem -v" -- run_script "$f_nt" down.sh
assert_not_contains "$(cat "$dlog")" " -v" "down sem árvore não passa -v"
assert_not_contains "$(cat "$dlog")" "volume" "down sem árvore não toca em volume"

reset_logs
assert_exit 0 "down -v -v" -- run_script "$front" down.sh -v -v
assert_eq "1" "$(grep -c ' -v' "$dlog")" "down -v -v passa -v uma vez"
reset_logs
assert_exit 0 "down --volumes" -- run_script "$front" down.sh --volumes
assert_contains "$(cat "$dlog")" "down -v" "--volumes vira down -v"
assert_contains "$ASSERT_OUT" "com os volumes" "down -v avisa a remoção dos volumes"

# ---------------------------------------------------------------------------
# 3. Isolamento com variáveis hostis.
# ---------------------------------------------------------------------------
check_isolation() { # LOG MSG
  local bad=0 l
  while IFS= read -r l; do
    [[ -z "$l" ]] && continue
    if [[ "$l" == *" compose "* ]]; then
      [[ "$l" == *" -p zeny-front "* ]] || bad=1
      [[ "$l" == *"-p zeny "* ]] && bad=1
      [[ "$l" == *"--project-name"* ]] && bad=1
    fi
    [[ "$l" == *":dev"* ]] && bad=1
    [[ "$l" == *"zeny-hotel-manager:dev"* ]] && bad=1
  done <"$1"
  assert_eq "0" "$bad" "$2"
}

f_h="$tmp/front_hostil"
new_front "$f_h"
write_test_env_in "$f_h/.server/zeny-front.env"
reset_logs
(
  export COMPOSE_PROJECT_NAME=zeny ZENY_FRONT_PROJECT=zeny COMPOSE_FILE=/nao/existe/compose.yaml COMPOSE_PROFILES=dev
  STUB_IMAGE_PRESENT=0 run_script "$f_h" up.sh >/dev/null 2>&1
  run_script "$f_h" down.sh >/dev/null 2>&1
  run_script "$f_h" down.sh -v >/dev/null 2>&1
)
assert_contains "$(cat "$dlog")" "-p zeny-front " "variáveis hostis: ainda chama o docker com -p zeny-front"
check_isolation "$dlog" "variáveis hostis (COMPOSE_PROJECT_NAME/ZENY_FRONT_PROJECT) não trocam o projeto"
assert_not_contains "$(cat "$dlog")" "/nao/existe" "COMPOSE_FILE hostil não aparece no argv"

# Com árvore: -f explícito vence COMPOSE_FILE; todo compose tem -f do compose do commit fixado.
while IFS= read -r l; do
  [[ "$l" == *" compose "* && "$l" != *"image inspect"* ]] || continue
  [[ "$l" == *"-f $f_h/.server/$FAKE_C1/docker/compose.yaml -f $f_h/.server/$FAKE_C1.override.yaml "* ]] ||
    _assert_fail "com árvore, compose usa -f da árvore do checkout principal" "linha: $l"
done <"$dlog"

# Sem árvore: o diretório vazio existe para que nenhum compose.yaml entre; COMPOSE_FILE no
# ambiente derrota esse propósito (o docker lê COMPOSE_FILE quando não há -f).
f_h2="$tmp/front_hostil2"
new_front "$f_h2"
reset_logs
COMPOSE_FILE=/nao/existe/compose.yaml run_script "$f_h2" down.sh >/dev/null 2>&1
assert_contains "$(cat "$dlog")" "-p zeny-front down" "pré-condição: down sem árvore chegou ao docker"
assert_not_contains "$(cat "$elog")" "COMPOSE_FILE=[/nao/existe" \
  "DEFEITO: down sem árvore deixa COMPOSE_FILE do ambiente chegar ao docker (derrota o diretório vazio)"

# Qualquer cwd diferente de /: o cwd do usuário (com compose.yaml hostil) não pode entrar.
f_h3="$tmp/front_hostil3"
new_front "$f_h3"
mkdir -p "$tmp/cwd_hostil"
printf 'name: zeny\nservices: {}\n' >"$tmp/cwd_hostil/compose.yaml"
reset_logs
(cd "$tmp/cwd_hostil" && PATH="$stubpath" bash "$f_h3/tools/server-env/down.sh" >/dev/null 2>&1)
assert_not_contains "$(cat "$dlog")" "cwd=$tmp/cwd_hostil " "down sem árvore não roda no cwd do usuário (compose.yaml hostil)"
assert_eq "no" "$([[ -d "$f_h3/.server/$FAKE_C1" ]] && echo yes || echo no)" "down sem árvore não extrai árvore"

# ---------------------------------------------------------------------------
# 4. down não pode depender do env file nem de segredos.
# ---------------------------------------------------------------------------
# 4a. Comportamento do docker real: `config` com --env-file inexistente e com env vazio falha.
if [[ -n "$REAL_DOCKER" ]] && "$REAL_DOCKER" compose version >/dev/null 2>&1; then
  tree="$front/.server/$FAKE_C1"
  ov="$front/.server/$FAKE_C1.override.yaml"
  cd "$tmp" || exit 1
  out="$("$REAL_DOCKER" compose -p zeny-front -f "$tree/docker/compose.yaml" -f "$ov" --env-file "$tmp/nao-existe.env" config -q 2>&1)"
  assert_contains "$out" "env file" "premissa real: --env-file inexistente é erro do docker compose"
  : >"$tmp/vazio.env"
  out="$("$REAL_DOCKER" compose -p zeny-front -f "$tree/docker/compose.yaml" -f "$ov" --env-file "$tmp/vazio.env" config -q 2>&1)"
  assert_contains "$out" "required variable" "premissa real: env file sem segredos falha em \${X:?} do compose do servidor"
else
  skip_case "docker compose real indisponível: premissas de interpolação não verificadas"
fi

# 4b. down com árvore e SEM env file (stub estrito, como o docker real).
mv "$envf" "$envf.bak"
reset_logs
STUB_STRICT_ENV=1 assert_exit 0 "DEFEITO: down com árvore extraída e sem .env deve funcionar" -- run_script "$front" down.sh
assert_contains "$(cat "$dlog")" "down" "down chegou a chamar o docker sem .env"
reset_logs
STUB_STRICT_ENV=1 assert_exit 0 "DEFEITO: down -v com árvore extraída e sem .env deve funcionar" -- run_script "$front" down.sh -v
# o env file de ZENY_FRONT_ENV_FILE inexistente também
reset_logs
ZENY_FRONT_ENV_FILE="$tmp/outro-inexistente.env" STUB_STRICT_ENV=1 \
  assert_exit 0 "DEFEITO: down com ZENY_FRONT_ENV_FILE inexistente deve funcionar" -- run_script "$front" down.sh
mv "$envf.bak" "$envf"

# 4c. Mesma coisa com o loader real do compose (config -q no lugar do down), env vazio e ausente.
if [[ -n "$REAL_DOCKER" ]] && "$REAL_DOCKER" compose version >/dev/null 2>&1; then
  mv "$envf" "$envf.bak"
  reset_logs
  STUB_REAL_CONFIG=1 assert_exit 0 "DEFEITO (docker real): down sem .env não pode falhar no carregamento do compose" -- run_script "$front" down.sh
  : >"$envf"
  reset_logs
  STUB_REAL_CONFIG=1 assert_exit 0 "DEFEITO (docker real): down com .env sem segredos não pode falhar em \${X:?}" -- run_script "$front" down.sh
  mv "$envf.bak" "$envf"
  reset_logs
  STUB_REAL_CONFIG=1 assert_exit 0 "controle (docker real): com .env completo o carregamento do compose passa" -- run_script "$front" down.sh
fi

# 4d. Sem árvore e sem env file: down funciona (não depende de nada).
f_ne="$tmp/front_noenv"
new_front "$f_ne"
reset_logs
STUB_STRICT_ENV=1 assert_exit 0 "down sem árvore e sem .env funciona" -- run_script "$f_ne" down.sh
assert_not_contains "$(cat "$dlog")" "--env-file" "down sem árvore não passa --env-file"

# ---------------------------------------------------------------------------
# 5. Readiness: timeout, valores inválidos, resposta aninhada.
# ---------------------------------------------------------------------------
f_r="$tmp/front_ready"
new_front "$f_r"
write_test_env_in "$f_r/.server/zeny-front.env"

t0=$SECONDS
reset_logs
STUB_CURL_BODY='{"status":"DOWN"}' ZENY_UP_TIMEOUT=6 assert_exit 1 "timeout 6 s com readiness DOWN" -- run_script "$f_r" up.sh
dt=$((SECONDS - t0))
assert_eq "1" "$((dt >= 5 && dt <= 9 ? 1 : 0))" "ZENY_UP_TIMEOUT=6 espera cerca de 6 s (levou ${dt}s)"
assert_eq "1" "$(($(wc -l <"$clog") >= 2 ? 1 : 0))" "consulta mais de uma vez antes de desistir"
assert_contains "$ASSERT_OUT" "6s" "mensagem de timeout cita o tempo"
assert_contains "$ASSERT_OUT" "erro:" "mensagem de timeout em pt-BR com prefixo erro:"

# timeout 0: uma consulta e sai com 1; UP imediato com timeout 0 ainda passa.
reset_logs
t0=$SECONDS
STUB_CURL_BODY='{"status":"DOWN"}' ZENY_UP_TIMEOUT=0 assert_exit 1 "timeout 0 com DOWN" -- run_script "$f_r" up.sh
assert_eq "1" "$((SECONDS - t0 <= 3 ? 1 : 0))" "timeout 0 não dorme"
reset_logs
ZENY_UP_TIMEOUT=0 assert_exit 0 "timeout 0 com UP imediato sobe" -- run_script "$f_r" up.sh

# valores inválidos: nunca laço infinito (limite de 20 s por `timeout`).
for v in abc -5 1.5 " " "0x10"; do
  reset_logs
  t0=$SECONDS
  STUB_CURL_BODY='{"status":"DOWN"}' ZENY_UP_TIMEOUT="$v" assert_exit 1 "ZENY_UP_TIMEOUT='$v' termina com falha (sem laço infinito)" -- \
    timeout 20 env PATH="$stubpath" bash "$f_r/tools/server-env/up.sh"
  dt=$((SECONDS - t0))
  assert_eq "1" "$((dt < 19 ? 1 : 0))" "ZENY_UP_TIMEOUT='$v' não fica preso (levou ${dt}s)"
done

# falha de curl (conexão recusada) também respeita o tempo e mostra pt-BR.
reset_logs
STUB_CURL_BODY='' STUB_CURL_EXIT=7 ZENY_UP_TIMEOUT=1 assert_exit 1 "curl falhando estoura o tempo" -- run_script "$f_r" up.sh
assert_contains "$ASSERT_OUT" "veja os logs: docker compose -p zeny-front logs api" "dica de logs em pt-BR"

# Resposta com status raiz DOWN e componente aninhado UP não é pronto.
reset_logs
STUB_CURL_BODY='{"status":"DOWN","components":{"db":{"status":"UP"},"readinessState":{"status":"DOWN"}}}' ZENY_UP_TIMEOUT=1 \
  assert_exit 1 "DEFEITO: status raiz DOWN com componente aninhado UP não é readiness" -- run_script "$f_r" up.sh
assert_not_contains "$ASSERT_OUT" "no ar em" "DEFEITO: não declara 'no ar' com status raiz DOWN"
# Status que apenas começa com UP não conta.
reset_logs
STUB_CURL_BODY='{"status":"UPDATING"}' ZENY_UP_TIMEOUT=1 assert_exit 1 "UPDATING não é UP" -- run_script "$f_r" up.sh
reset_logs
STUB_CURL_BODY='{"status":"OUT_OF_SERVICE"}' ZENY_UP_TIMEOUT=1 assert_exit 1 "OUT_OF_SERVICE não é UP" -- run_script "$f_r" up.sh
# Resposta de erro (HTML/texto) que cita UP fora do JSON não conta.
reset_logs
STUB_CURL_BODY='status UP' ZENY_UP_TIMEOUT=1 assert_exit 1 "texto 'status UP' não é readiness" -- run_script "$f_r" up.sh

# ---------------------------------------------------------------------------
# 6. Falha de build/up propagada, sem esperar readiness.
# ---------------------------------------------------------------------------
reset_logs
STUB_IMAGE_PRESENT=0 STUB_FAIL_ON=build assert_exit 1 "build falho propaga exit 1" -- run_script "$f_r" up.sh
assert_not_contains "$(cat "$dlog")" "up -d" "build falho: não chama up -d"
assert_eq "" "$(cat "$clog")" "build falho: não consulta readiness"
assert_contains "$ASSERT_OUT" "falha ao construir" "build falho: mensagem em pt-BR"
assert_not_contains "$ASSERT_OUT" "no ar em" "build falho: não declara sucesso"

reset_logs
STUB_IMAGE_PRESENT=1 STUB_FAIL_ON=build assert_exit 1 "--build com build falho" -- run_script "$f_r" up.sh --build
assert_eq "" "$(cat "$clog")" "--build falho: não consulta readiness"
assert_not_contains "$(cat "$dlog")" "up -d" "--build falho: não chama up -d"

reset_logs
STUB_IMAGE_PRESENT=1 STUB_FAIL_ON=up assert_exit 1 "up -d falho propaga exit 1" -- run_script "$f_r" up.sh
assert_eq "" "$(cat "$clog")" "up -d falho: não consulta readiness"
assert_contains "$ASSERT_OUT" "falha ao subir" "up -d falho: mensagem em pt-BR"
assert_not_contains "$ASSERT_OUT" "no ar em" "up -d falho: não declara sucesso"

reset_logs
STUB_IMAGE_PRESENT=0 STUB_DOCKER_EXIT=3 assert_exit 1 "docker falhando em tudo propaga falha" -- run_script "$f_r" up.sh
assert_eq "" "$(cat "$clog")" "docker falhando: não consulta readiness"

# down falho não imprime sucesso (com e sem árvore).
reset_logs
STUB_FAIL_ON=down assert_exit 1 "down falho com árvore sai com 1" -- run_script "$front" down.sh
assert_not_contains "$ASSERT_OUT" "removido" "down falho com árvore: não diz que removeu"
assert_contains "$ASSERT_OUT" "falha ao derrubar" "down falho com árvore: mensagem em pt-BR"
reset_logs
STUB_FAIL_ON=down assert_exit 1 "down falho sem árvore sai com 1" -- run_script "$f_nt" down.sh -v
assert_not_contains "$ASSERT_OUT" "removido" "down falho sem árvore: não diz que removeu"

# ---------------------------------------------------------------------------
# 7. Host e porta vêm do env file sem executá-lo.
# ---------------------------------------------------------------------------
pwn="$tmp/PWNED"
f_e="$tmp/front_env"
new_front "$f_e"
fenv="$f_e/.server/zeny-front.env"
write_test_env_in "$fenv" "ZENY_HTTP_PORT=\$(touch $pwn)" "ZENY_HTTP_ADDRESS=\`touch $pwn\`" "EXTRA=\$(touch $pwn); touch $pwn"
reset_logs
ZENY_UP_TIMEOUT=1 run_script "$f_e" up.sh >/dev/null 2>&1
assert_eq "no" "$([[ -e "$pwn" ]] && echo yes || echo no)" "up.sh não executa o conteúdo do env file"
assert_eq "no" "$([[ -e "$pwn" ]] && echo yes || echo no)" "(repetido após leitura de porta e endereço) nada executado"

# O env do ambiente do usuário (ZENY_HTTP_PORT exportada) não substitui o env file.
write_test_env_in "$fenv" ZENY_HTTP_PORT=9099
reset_logs
ZENY_HTTP_PORT=1111 run_script "$f_e" up.sh >/dev/null 2>&1
assert_contains "$(cat "$clog")" "http://127.0.0.1:9099/" "a porta vem do env file, não da variável exportada"

# Última ocorrência vence; CRLF no env file.
write_test_env_in "$fenv" ZENY_HTTP_PORT=7001 ZENY_HTTP_PORT=7002
reset_logs
run_script "$f_e" up.sh >/dev/null 2>&1
assert_contains "$(cat "$clog")" "http://127.0.0.1:7002/" "porta duplicada: vale a última (como o compose)"
printf 'ZENY_HTTP_PORT=7003\r\nZENY_HTTP_ADDRESS=192.168.1.9\r\n' >"$fenv"
reset_logs
run_script "$f_e" up.sh >/dev/null 2>&1
assert_contains "$(cat "$clog")" "http://192.168.1.9:7003/" "env file com CRLF: sem \\r na URL"

# Porta no env file vazia: usa o padrão 8088.
write_test_env_in "$fenv" ZENY_HTTP_PORT=
reset_logs
run_script "$f_e" up.sh >/dev/null 2>&1
assert_contains "$(cat "$clog")" "http://127.0.0.1:8088/" "DEFEITO: porta vazia no env file (ZENY_HTTP_PORT=) deve usar 8088, como o compose"

# Variáveis do ambiente do usuário não substituem os valores do env file nem a URL.
write_test_env_in "$fenv"
reset_logs
ZENY_HTTP_ADDRESS=10.9.9.9 run_script "$f_e" up.sh >/dev/null 2>&1
assert_contains "$(cat "$clog")" "http://127.0.0.1:8088/" "ZENY_HTTP_ADDRESS exportada não vence o env file"

# ---------------------------------------------------------------------------
# 8. up.sh e o checkout: git status do checkout principal fica limpo; env file fora do Git.
# ---------------------------------------------------------------------------
f_g="$tmp/front_git"
new_front "$f_g"
write_test_env_in "$f_g/.server/zeny-front.env"
run_script "$f_g" up.sh >/dev/null 2>&1
assert_eq "" "$(_fgit "$f_g" status --porcelain)" "git status do checkout vazio depois de up.sh"
run_script "$f_g" down.sh -v >/dev/null 2>&1
assert_eq "" "$(_fgit "$f_g" status --porcelain)" "git status do checkout vazio depois de down.sh"

# Os scripts de entrada têm bit de execução.
for s in up.sh down.sh; do
  assert_eq "yes" "$([[ -x "$ROOT/tools/server-env/$s" ]] && echo yes || echo no)" "$s é executável"
done

# Mensagens: nenhuma mensagem de erro em inglês nos caminhos de erro.
reset_logs
out="$(run_script "$f_ne" up.sh 2>&1)"
assert_contains "$out" "arquivo de ambiente não encontrado" "erro sem env file em pt-BR"

finish
