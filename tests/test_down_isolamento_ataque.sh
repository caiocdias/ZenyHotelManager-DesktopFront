#!/usr/bin/env bash
# Ataque da M0 E04 (escrita destrutiva): tools/server-env/down.sh, com e sem -v, chama o docker uma única vez e com o
# argv EXATO `compose -p zeny-front down [-v]`, mesmo com COMPOSE_*/ZENY_FRONT_PROJECT hostis no ambiente. O
# test_up_down*.sh confere o projeto por substring; aqui a igualdade também barra o que apagaria outra coisa sem
# trocar o projeto: `--rmi` (imagens, inclusive a zeny-hotel-manager:<commit> que o servidor reaproveita),
# `--remove-orphans`, `-f` de outro compose ou uma segunda chamada (`docker volume rm`, `docker image rm`, `prune`).
# Só stub de docker; nunca o docker real.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

script="${DOWN_SCRIPT:-$ROOT/tools/server-env/down.sh}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat >"$tmp/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$STUB_LOG_DIR/argv.log"
printf 'COMPOSE_FILE=[%s] COMPOSE_PROFILES=[%s]\n' "${COMPOSE_FILE:-}" "${COMPOSE_PROFILES:-}" >>"$STUB_LOG_DIR/env.log"
exit 0
STUB
chmod +x "$tmp/bin/docker"
export STUB_LOG_DIR="$tmp"

roda_down() { # roda_down [ARGS...] : down.sh com o ambiente hostil, de um cwd que tem compose.yaml de outro projeto
  : >"$tmp/argv.log"
  : >"$tmp/env.log"
  mkdir -p "$tmp/cwd"
  printf 'name: zeny\nservices: {}\n' >"$tmp/cwd/compose.yaml"
  (
    cd "$tmp/cwd" || exit 9
    export COMPOSE_PROJECT_NAME=zeny ZENY_FRONT_PROJECT=zeny COMPOSE_FILE="$tmp/cwd/compose.yaml" \
      COMPOSE_PROFILES=dev
    PATH="$tmp/bin:$PATH" bash "$script" "$@"
  ) >"$tmp/out.txt" 2>&1
}

for caso in "-v|compose -p zeny-front down -v" "--volumes|compose -p zeny-front down -v" "|compose -p zeny-front down"; do
  arg="${caso%%|*}"
  esperado="${caso#*|}"
  if [[ -n "$arg" ]]; then roda_down "$arg"; else roda_down; fi
  code=$?
  nome="down.sh ${arg:-(sem -v)} com ambiente hostil"
  assert_eq "0" "$code" "$nome sai 0" "saída: $(cat "$tmp/out.txt")"
  assert_eq "$esperado" "$(cat "$tmp/argv.log")" "$nome: uma única chamada ao docker, com o argv exato"
  assert_eq "COMPOSE_FILE=[] COMPOSE_PROFILES=[]" "$(cat "$tmp/env.log")" \
    "$nome: COMPOSE_FILE e COMPOSE_PROFILES não chegam ao docker"
  assert_contains "$(cat "$tmp/out.txt")" "projeto zeny-front removido" "$nome: avisa o projeto zeny-front"
done

finish
