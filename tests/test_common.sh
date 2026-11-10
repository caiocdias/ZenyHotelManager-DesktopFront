#!/usr/bin/env bash
# Testes de tools/lib/common.sh. Só usa servidores falsos em diretórios temporários.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"
source "$ROOT/tools/lib/common.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_fake_server "$tmp/ZenyHotelManager"
srv="$tmp/ZenyHotelManager"
unset ZENY_SERVER_REPO

# Roda uma função de common.sh do front dado ($1), sem ZENY_SERVER_REPO, com cwd neutro.
in_front() {
  local front="$1"
  shift
  (cd / && env -u ZENY_SERVER_REPO bash -c 'source "$1/tools/lib/common.sh" && shift && "$@"' _ "$front" "$@")
}

# server_repo padrão, a partir de um worktree dentro do front falso
make_fake_front "$tmp/front"
git -C "$tmp/front" worktree add -q "$tmp/front/.claude/worktrees/wt" -b wt >/dev/null 2>&1
wt="$tmp/front/.claude/worktrees/wt"
assert_eq "$tmp/ZenyHotelManager" "$(in_front "$wt" server_repo)" "server_repo padrão a partir de worktree"
assert_eq "$wt" "$(in_front "$wt" front_root)" "front_root é o worktree do script, não o cwd"
assert_eq "$tmp/front" "$(in_front "$wt" front_main_checkout)" "front_main_checkout é o checkout principal"

# ZENY_SERVER_REPO inválido e com espaço
mkdir -p "$tmp/nao-git"
ZENY_SERVER_REPO="$tmp/nao-git" assert_exit 2 "ZENY_SERVER_REPO que não é git -> 2" -- server_repo
mkdir -p "$tmp/com espaco"
cp -a "$srv/." "$tmp/com espaco/"
assert_eq "$tmp/com espaco" "$(ZENY_SERVER_REPO="$tmp/com espaco" server_repo)" "ZENY_SERVER_REPO com espaço"

export ZENY_SERVER_REPO="$srv"

# srv_git só-leitura
assert_eq "$FAKE_C4" "$(srv_git rev-parse main)" "srv_git rev-parse main"
status_before="$(git -C "$srv" status --porcelain)"
wt_before="$(git -C "$srv" worktree list)"
assert_exit 2 "srv_git checkout recusado" -- srv_git checkout main
assert_contains "$ASSERT_OUT" "subcomando git não permitido no servidor: checkout" "mensagem do checkout"
assert_exit 2 "srv_git fetch recusado" -- srv_git fetch
assert_exit 2 "srv_git log recusado" -- srv_git log
assert_exit 2 "srv_git worktree recusado" -- srv_git worktree add x
assert_eq "$status_before" "$(git -C "$srv" status --porcelain)" "status do servidor inalterado"
assert_eq "$wt_before" "$(git -C "$srv" worktree list)" "worktrees do servidor inalterados"

# resolve_server_ref
assert_eq "$FAKE_C4" "$(resolve_server_ref main)" "resolve_server_ref main"
(cd "$tmp" && assert_exit 2 "REF com hífen -> 2" -- resolve_server_ref --output=x)
assert_eq "no" "$([[ -e "$tmp/x" || -e "$srv/x" ]] && echo yes || echo no)" "REF com hífen não cria arquivo"
assert_exit 2 "REF vazio -> 2" -- resolve_server_ref ''
assert_exit 2 "REF inexistente -> 2" -- resolve_server_ref nao-existe
assert_contains "$ASSERT_OUT" "commit não encontrado no servidor: nao-existe" "mensagem de REF inexistente"

# skip_case / finish
cat >"$tmp/aux_skip.sh" <<AUX
source "$ROOT/tests/lib/assert.sh"
skip_case "caso pulado"
finish
AUX
cat >"$tmp/aux_fail_skip.sh" <<AUX
source "$ROOT/tests/lib/assert.sh"
skip_case "caso pulado"
assert_eq a b "falha proposital"
finish
AUX
assert_exit 77 "só pulo -> 77" -- bash "$tmp/aux_skip.sh"
assert_exit 1 "falha + pulo -> 1" -- bash "$tmp/aux_fail_skip.sh"

# write_fake_pin / pin_get / short_commit
make_fake_front "$tmp/front2"
write_fake_pin "$tmp/front2" "$srv" "$FAKE_C3"
pin="$tmp/front2/api/server-pin.properties"
assert_eq "$(sha256sum "$tmp/front2/api/openapi.yaml" | cut -d' ' -f1)" "$(sed -n 's/^contract_sha256=//p' "$pin")" "contract_sha256 confere com a cópia"
assert_eq "$(srv_git show "$FAKE_C3:api/openapi.yaml")" "$(cat "$tmp/front2/api/openapi.yaml")" "cópia do contrato do commit"
assert_eq "commit=$FAKE_C3" "$(sed -n 1p "$pin")" "pin: commit na linha 1"
assert_eq "synced_at=2026-11-10T00:00:00Z" "$(sed -n 3p "$pin")" "pin: synced_at na linha 3"

assert_eq "$FAKE_C3" "$(in_front "$tmp/front2" pin_get commit)" "pin_get commit"
assert_exit 3 "pin_get chave ausente -> 3" -- in_front "$tmp/front2" pin_get inexistente
assert_exit 3 "pin_get arquivo ausente -> 3" -- in_front "$tmp/front" pin_get commit

short="$(short_commit "$FAKE_C4")"
assert_eq "12" "${#short}" "short_commit tem 12 caracteres"
assert_eq "${FAKE_C4:0:12}" "$short" "short_commit são os 12 primeiros"

finish
