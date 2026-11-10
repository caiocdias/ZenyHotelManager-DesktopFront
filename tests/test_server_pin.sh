#!/usr/bin/env bash
# Testes de tools/server-pin.sh em front falso + servidor falso.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_fake_server "$tmp/server"
srv="$tmp/server"
make_fake_front "$tmp/front"
front="$tmp/front"
export ZENY_SERVER_REPO="$srv"

pin() { (cd / && bash "$front/tools/server-pin.sh" "$@"); }
snap() { printf '%s\n--\n%s\n' "$(git -C "$srv" status --porcelain)" "$(git -C "$srv" worktree list | cut -d' ' -f1)"; }
prop() { grep "^$1=" "$front/api/server-pin.properties" | cut -d= -f2-; }
same() { cmp -s "$1" "$2" || assert_eq "iguais" "diferentes" "$3"; }

before="$(snap)"

# Sem argumento fixa C4 (main)
assert_exit 0 "sem argumento fixa main" -- pin
assert_contains "$ASSERT_OUT" "Servidor fixado em ${FAKE_C4:0:12} (contrato sha256 $(sha256sum "$front/api/openapi.yaml" | cut -c1-12))" "mensagem de sucesso"
assert_eq "$FAKE_C4" "$(prop commit)" "commit completo de C4"
git -C "$srv" cat-file blob "$FAKE_C4:api/openapi.yaml" >"$tmp/c4.yaml"
same "$tmp/c4.yaml" "$front/api/openapi.yaml" "openapi.yaml byte a byte (C4)"
assert_eq "$(sha256sum "$front/api/openapi.yaml" | cut -d' ' -f1)" "$(prop contract_sha256)" "contract_sha256 confere"
assert_eq "commit contract_sha256 synced_at" "$(cut -d= -f1 "$front/api/server-pin.properties" | tr '\n' ' ' | sed 's/ $//')" "três chaves na ordem"
[[ "$(prop synced_at)" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || assert_eq "formato UTC" "$(prop synced_at)" "synced_at"
assert_eq "3" "$(wc -l <"$front/api/server-pin.properties" | tr -d ' ')" "pin tem 3 linhas"

# Com argumento fixa C1
assert_exit 0 "fixa C1" -- pin "$FAKE_C1"
assert_eq "$FAKE_C1" "$(prop commit)" "commit completo de C1"
git -C "$srv" cat-file blob "$FAKE_C1:api/openapi.yaml" >"$tmp/c1.yaml"
same "$tmp/c1.yaml" "$front/api/openapi.yaml" "openapi.yaml byte a byte (C1)"
assert_eq "$(sha256sum "$front/api/openapi.yaml" | cut -d' ' -f1)" "$(prop contract_sha256)" "contract_sha256 de C1"

# REF inexistente ou com hífen: exit 2, nada alterado
cp "$front/api/server-pin.properties" "$tmp/pin.bak"
cp "$front/api/openapi.yaml" "$tmp/yaml.bak"
assert_exit 2 "REF inexistente" -- pin refs/nao-existe
same "$tmp/pin.bak" "$front/api/server-pin.properties" "pin intacto após REF inexistente"
same "$tmp/yaml.bak" "$front/api/openapi.yaml" "openapi intacto após REF inexistente"
assert_exit 2 "REF começando com hífen" -- pin --foo
same "$tmp/pin.bak" "$front/api/server-pin.properties" "pin intacto após REF com hífen"

# Commit sem api/openapi.yaml: exit 2, nada alterado
sgit() { git -C "$srv" -c user.name=T -c user.email=t@e -c commit.gpgsign=false -c core.hooksPath=/dev/null "$@"; }
sgit rm -q api/openapi.yaml
sgit commit -q -m "sem contrato"
assert_exit 2 "commit sem contrato" -- pin HEAD
same "$tmp/pin.bak" "$front/api/server-pin.properties" "pin intacto sem contrato"
same "$tmp/yaml.bak" "$front/api/openapi.yaml" "openapi intacto sem contrato"
assert_eq "openapi.yaml
server-pin.properties" "$(ls "$front/api")" "sem temporários em api/"

# CRLF e sem newline final
mkdir -p "$srv/api"
printf 'openapi: 3.0.1\r\ninfo:\r\n  title: x\r\n  version: 1\r\npaths: {}' >"$srv/api/openapi.yaml"
sgit add api/openapi.yaml
sgit commit -q -m "CRLF sem newline final"
crlf="$(sgit rev-parse HEAD)"
assert_exit 0 "fixa contrato CRLF" -- pin "$crlf"
same "$srv/api/openapi.yaml" "$front/api/openapi.yaml" "CRLF e sem newline final byte a byte"
assert_eq "$(sha256sum "$front/api/openapi.yaml" | cut -d' ' -f1)" "$(prop contract_sha256)" "contract_sha256 do CRLF"

# O script não suja o servidor (status e worktrees iguais)
assert_eq "$before" "$(snap)" "status e worktree list do servidor iguais"

finish
