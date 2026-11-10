#!/usr/bin/env bash
# Testes de tools/server-env/lib.sh e do override. Usam fronts e servidores falsos em diretórios temporários;
# o caso "real" só lê o servidor real e se pula quando ele ou o pin real não existem.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"
source "$ROOT/tools/lib/common.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Servidor real (só leitura), se existir: resolvido antes de apontar ZENY_SERVER_REPO para o falso.
real_srv="$(env -u ZENY_SERVER_REPO bash -c 'source "$1/tools/lib/common.sh" && server_repo' _ "$ROOT" 2>/dev/null)"

make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
export ZENY_SERVER_REPO="$srv"
unset ZENY_FRONT_ENV_FILE ZENY_HTTP_ADDRESS ZENY_HTTP_PORT

# fe FRONT FUNC ARGS... : roda FUNC de tools/server-env/lib.sh do front dado, com cwd neutro.
fe() {
  local front="$1"
  shift
  (cd / && bash -c 'source "$1/tools/server-env/lib.sh" && shift && "$@"' _ "$front" "$@")
}

# new_front DIR [COMMIT] : front falso com o pin (commitado) de COMMIT (padrão FAKE_C1) do servidor falso.
new_front() {
  local dir="$1" commit="${2:-$FAKE_C1}"
  make_fake_front "$dir"
  write_fake_pin "$dir" "$srv" "$commit"
  _fgit "$dir" add -A >/dev/null
  _fgit "$dir" commit -q --allow-empty -m "pin"
}

front="$tmp/front"
new_front "$front"
tag="${FAKE_C1:0:12}"

# --- árvore do commit fixado ---
out="$(fe "$front" ensure_server_tree)"
assert_eq "$front/.server/$FAKE_C1" "$out" "ensure_server_tree imprime o caminho da árvore"
assert_file "$front/.server/$FAKE_C1/docker/compose.yaml" "árvore extraída tem docker/compose.yaml"
assert_eq "$front/.server/$FAKE_C1" "$(fe "$front" server_tree_dir)" "server_tree_dir"
assert_eq "$front/.server/$FAKE_C1.override.yaml" "$(fe "$front" override_file)" "override_file fora da árvore"
assert_eq "$tag" "$(fe "$front" server_tag)" "server_tag são os 12 primeiros do commit"
m1="$(stat -c '%Y %i' "$front/.server/$FAKE_C1" "$front/.server/$FAKE_C1/docker/compose.yaml")"
sleep 1.1
fe "$front" ensure_server_tree >/dev/null
m2="$(stat -c '%Y %i' "$front/.server/$FAKE_C1" "$front/.server/$FAKE_C1/docker/compose.yaml")"
assert_eq "$m1" "$m2" "segunda chamada não reextrai (mtime e inode iguais)"
assert_eq "0" "$(find "$front/.server" -mindepth 1 -maxdepth 1 -name '.*' ! -name .gitignore | wc -l)" "sem temporários sobrando"

# --- worktree do front: estado no checkout principal ---
git -C "$front" worktree add -q "$front/.claude/worktrees/wt" -b wt >/dev/null 2>&1
wt="$front/.claude/worktrees/wt"
assert_eq "$front/.server/$FAKE_C1" "$(fe "$wt" server_tree_dir)" "server_tree_dir no worktree aponta para o principal"
assert_eq "$front/.server/$FAKE_C1.override.yaml" "$(fe "$wt" override_file)" "override_file no worktree aponta para o principal"
assert_eq "$front/.server/zeny-front.env" "$(fe "$wt" env_file)" "env_file no worktree aponta para o principal"
write_test_env "$front/.server/zeny-front.env"
fe "$wt" ensure_server_tree >/dev/null
git -C "$front" worktree remove --force "$wt"
assert_file "$front/.server/$FAKE_C1/docker/compose.yaml" "árvore sobrevive à remoção do worktree"
assert_file "$front/.server/zeny-front.env" ".env sobrevive à remoção do worktree"

# --- autoignore: front sem .gitignore fica limpo ---
front2="$tmp/front2"
new_front "$front2"
fe "$front2" ensure_server_tree >/dev/null
fe "$front2" render_override "$front2/.server/x.override.yaml" 127.0.0.1
write_test_env "$front2/.server/zeny-front.env"
fe "$front2" ensure_state_dir "$front2/build"
printf 'x\n' >"$front2/build/arquivo"
assert_eq "" "$(git -C "$front2" status --porcelain)" "git status do front limpo depois de .server/ e build/"
assert_eq "*" "$(cat "$front2/.server/.gitignore")" ".server/.gitignore com *"
assert_eq "*" "$(cat "$front2/build/.gitignore")" "build/.gitignore com *"

front3="$tmp/front3"
new_front "$front3"
mkdir -p "$front3/.server"
printf 'outro\n' >"$front3/.server/.gitignore"
fe "$front3" ensure_server_tree >/dev/null
assert_eq "outro" "$(cat "$front3/.server/.gitignore")" ".gitignore existente não é sobrescrito"

# --- contrato adulterado ---
front4="$tmp/front4"
new_front "$front4"
fe "$front4" ensure_server_tree >/dev/null
printf '# adulterado\n' >>"$front4/.server/$FAKE_C1/api/openapi.yaml"
assert_exit 3 "openapi.yaml alterado na árvore -> 3" -- fe "$front4" ensure_server_tree

# --- env_get sem executar nada ---
envf="$tmp/envget.env"
printf 'ZENY_HTTP_PORT=9099\n$(touch %s/pwned)\nOUTRA=a=b\r\n' "$tmp" >"$envf"
assert_eq "9099" "$(ZENY_FRONT_ENV_FILE="$envf" fe "$front" env_get ZENY_HTTP_PORT 8088)" "env_get lê o valor"
assert_eq "a=b" "$(ZENY_FRONT_ENV_FILE="$envf" fe "$front" env_get OUTRA)" "env_get com = no valor e CRLF"
assert_eq "8088" "$(ZENY_FRONT_ENV_FILE="$envf" fe "$front" env_get AUSENTE 8088)" "env_get usa o padrão"
assert_eq "8088" "$(ZENY_FRONT_ENV_FILE="$tmp/nao-existe.env" fe "$front" env_get ZENY_HTTP_PORT 8088)" "env_get sem arquivo usa o padrão"
assert_eq "no" "$([[ -e "$tmp/pwned" ]] && echo yes || echo no)" "env_get não executa linhas do env"

# --- render_override ---
fe "$front" render_override "$tmp/o.yaml" 0.0.0.0
o="$(cat "$tmp/o.yaml")"
assert_contains "$o" "image: zeny-hotel-manager:$tag" "override com a tag"
assert_contains "$o" '${ZENY_HTTP_ADDRESS:-0.0.0.0}' "override com o endereço padrão"
assert_not_contains "$o" "@" "sem marcadores restantes"

# --- compose config (Docker real, sem subir nada) ---
docker_ok=0
docker compose version >/dev/null 2>&1 && docker_ok=1

# cfg FRONT : compose config em JSON.
cfg() { fe "$1" compose config --format json; }

if ((docker_ok == 0)); then
  skip_case "docker compose indisponível: casos de compose config"
else
  front5="$tmp/front5"
  new_front "$front5"
  mkdir -p "$front5/.server"
  write_test_env "$front5/.server/zeny-front.env"
  j="$(cfg "$front5")"
  assert_eq "zeny-front" "$(jq -r .name <<<"$j")" "projeto compose zeny-front"
  assert_eq "zeny-hotel-manager:$tag" "$(jq -r .services.api.image <<<"$j")" "imagem do api com a tag"
  assert_eq "1" "$(jq '.services.proxy.ports | length' <<<"$j")" "um único mapeamento de porta"
  assert_eq "8088" "$(jq -r '.services.proxy.ports[0].published' <<<"$j")" "porta publicada 8088"
  assert_eq "127.0.0.1" "$(jq -r '.services.proxy.ports[0].host_ip' <<<"$j")" "host_ip padrão 127.0.0.1"
  assert_eq "http://localhost:8088" "$(jq -r .services.api.environment.ZENY_STORAGE_PUBLIC_ENDPOINT <<<"$j")" "endpoint público padrão"
  assert_contains "$(jq -r '.services.api.build.dockerfile' <<<"$j")" "Dockerfile" "build do api herdado (D1)"

  write_test_env "$front5/.server/zeny-front.env" ZENY_HTTP_ADDRESS=0.0.0.0 ZENY_HTTP_PORT=9099
  j="$(cfg "$front5")"
  assert_eq "0.0.0.0" "$(jq -r '.services.proxy.ports[0].host_ip' <<<"$j")" "ZENY_HTTP_ADDRESS do env vence"
  assert_eq "9099" "$(jq -r '.services.proxy.ports[0].published' <<<"$j")" "ZENY_HTTP_PORT do env vence"
  assert_eq "http://localhost:9099" "$(jq -r .services.api.environment.ZENY_STORAGE_PUBLIC_ENDPOINT <<<"$j")" "endpoint público segue a porta"

  # override do pacote (0.0.0.0) sem ZENY_HTTP_ADDRESS no env
  write_test_env "$front5/.server/zeny-front.env"
  fe "$front5" render_override "$tmp/pacote.override.yaml" 0.0.0.0
  j="$(cd / && docker compose -p zeny-front -f "$front5/.server/$FAKE_C1/docker/compose.yaml" -f "$tmp/pacote.override.yaml" \
    --env-file "$front5/.server/zeny-front.env" config --format json)"
  assert_eq "0.0.0.0" "$(jq -r '.services.proxy.ports[0].host_ip' <<<"$j")" "override do pacote publica em 0.0.0.0"

  # projeto não sobrescrevível por ambiente
  j="$(ZENY_FRONT_PROJECT=zeny cfg "$front5")"
  assert_eq "zeny-front" "$(jq -r .name <<<"$j")" "ZENY_FRONT_PROJECT do ambiente não muda o projeto"
fi

# --- real: compose do servidor verdadeiro (só leitura) ---
real_commit="$(sed -n 's/^commit=//p' "$ROOT/api/server-pin.properties" 2>/dev/null)"
if ((docker_ok == 0)) || [[ -z "$real_srv" || -z "$real_commit" ]] ||
  ! git -C "$real_srv" rev-parse --verify --end-of-options "$real_commit^{commit}" >/dev/null 2>&1; then
  skip_case "real: servidor ou pin real ausente (ou sem docker compose)"
else
  front6="$tmp/front6"
  make_fake_front "$front6" # copia o pin real do worktree
  mkdir -p "$front6/.server"
  write_test_env "$front6/.server/zeny-front.env"
  j="$(ZENY_SERVER_REPO="$real_srv" fe "$front6" compose config --format json 2>&1)"
  if ! jq -e . >/dev/null 2>&1 <<<"$j"; then
    _assert_fail "compose config do servidor real" "$j"
  else
    assert_eq "zeny-front" "$(jq -r .name <<<"$j")" "real: projeto zeny-front"
    assert_eq "zeny-hotel-manager:${real_commit:0:12}" "$(jq -r .services.api.image <<<"$j")" "real: imagem do api"
    assert_eq "1" "$(jq '.services.proxy.ports | length' <<<"$j")" "real: um único mapeamento"
    assert_eq "127.0.0.1" "$(jq -r '.services.proxy.ports[0].host_ip' <<<"$j")" "real: host_ip"
    assert_eq "8088" "$(jq -r '.services.proxy.ports[0].published' <<<"$j")" "real: porta"
    assert_eq "0" "$(jq -r '[.services[].image // empty | select(endswith(":dev"))] | length' <<<"$j")" "real: nenhuma imagem :dev"
  fi
fi

finish
