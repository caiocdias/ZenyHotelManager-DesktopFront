#!/usr/bin/env bash
# Testes de tools/server-env/gen-env.sh e do .env.example do front.
# O caso "real" só lê o servidor real e se pula quando ele ou o pin real não existem.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"
source "$ROOT/tests/lib/fixtures.sh"
source "$ROOT/tools/lib/common.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

GEN="$ROOT/tools/server-env/gen-env.sh"
EX="$ROOT/tools/server-env/.env.example"
SECRETS=(ZENY_ENCRYPTION_KEYS ZENY_JWT_KEYS ZENY_ADMIN_PASSWORD ZENY_STORAGE_ROOT_PASSWORD ZENY_STORAGE_SECRET_KEY)

# val FILE KEY : valor da última linha KEY= do arquivo.
val() { awk -v k="$2" 'index($0, k "=") == 1 { v = substr($0, length(k) + 2) } END { print v }' "$1"; }

# others FILE : linhas que não são dos cinco segredos.
others() {
  local re
  re="$(IFS='|'; echo "${SECRETS[*]}")"
  grep -vE "^($re)=" "$1"
}

# names : nomes de variáveis (KEY=) na ordem do arquivo lido do stdin.
names() { sed -n 's/^\([A-Z][A-Z0-9_]*\)=.*/\1/p'; }

assert_file "$EX" ".env.example existe"
[[ -f "$GEN" && -x "$GEN" ]] || _assert_fail "gen-env.sh existe e é executável"
[[ -x "$EX" ]] && _assert_fail ".env.example não tem bit de execução"

# --- modelo ---
assert_eq "8088" "$(val "$EX" ZENY_HTTP_PORT)" "porta padrão 8088"
for k in ZENY_HTTP_ADDRESS ZENY_STORAGE_PUBLIC_ENDPOINT ZENY_ENCRYPTION_KEYS ZENY_JWT_KEYS \
  ZENY_ADMIN_PASSWORD ZENY_STORAGE_ROOT_PASSWORD ZENY_STORAGE_SECRET_KEY ZENY_FCM_CREDENTIALS; do
  assert_eq "1" "$(grep -c "^$k=\$" "$EX")" "$k aparece uma vez, vazio, no modelo"
done

# --- geração ---
d1="$tmp/a"
mkdir -p "$d1"
assert_exit 0 "gera o .env" -- bash "$GEN" "$d1/.env"
out1="$ASSERT_OUT"
assert_contains "$out1" "Gerado $d1/.env (segredos:" "mensagem de geração"
for k in "${SECRETS[@]}"; do
  [[ -n "$(val "$d1/.env" "$k")" ]] || _assert_fail "$k preenchido"
  assert_contains "$out1" "$k" "mensagem lista $k"
done
assert_eq "600" "$(stat -c %a "$d1/.env")" "modo 600"
assert_eq "$(others "$EX")" "$(others "$d1/.env")" "demais linhas iguais ao modelo"
assert_eq "$(wc -l <"$EX")" "$(wc -l <"$d1/.env")" "mesmo número de linhas"

# formato dos segredos
jwt="$(val "$d1/.env" ZENY_JWT_KEYS)"
assert_eq "dev:" "${jwt:0:4}" "JWT com prefixo dev:"
bits="$(printf '%s' "${jwt#dev:}" | base64 -d 2>/dev/null | openssl pkey -inform DER -noout -text 2>/dev/null | head -1)"
assert_contains "$bits" "3072 bit" "JWT é RSA 3072"
enc="$(val "$d1/.env" ZENY_ENCRYPTION_KEYS)"
assert_eq "dev:" "${enc:0:4}" "cifragem com prefixo dev:"
assert_eq "32" "$(printf '%s' "${enc#dev:}" | base64 -d 2>/dev/null | wc -c)" "chave de cifragem com 32 bytes"

# saída sem segredos
for k in "${SECRETS[@]}"; do
  assert_not_contains "$out1" "$(val "$d1/.env" "$k")" "saída não contém o valor de $k"
done

# sem --force
cp "$d1/.env" "$tmp/first.env"
assert_exit 2 "DEST existente sem --force" -- bash "$GEN" "$d1/.env"
cmp -s "$d1/.env" "$tmp/first.env" || _assert_fail "DEST intacto sem --force"
assert_exit 0 "com --force" -- bash "$GEN" --force "$d1/.env"
out2="$ASSERT_OUT"
for k in "${SECRETS[@]}"; do
  [[ "$(val "$d1/.env" "$k")" != "$(val "$tmp/first.env" "$k")" ]] || _assert_fail "$k difere na segunda geração"
  assert_not_contains "$out2" "$(val "$d1/.env" "$k")" "saída do --force não contém $k"
done
assert_eq "600" "$(stat -c %a "$d1/.env")" "modo 600 após --force"

# diretório inexistente
assert_exit 2 "diretório de DEST inexistente" -- bash "$GEN" "$tmp/nao/existe/.env"
[[ -e "$tmp/nao" ]] && _assert_fail "não cria diretórios"

# --address e --public-endpoint
d2="$tmp/b"
mkdir -p "$d2"
assert_exit 0 "com --address e --public-endpoint" -- bash "$GEN" --address 0.0.0.0 --public-endpoint http://192.168.0.20:8088 "$d2/.env"
assert_eq "0.0.0.0" "$(val "$d2/.env" ZENY_HTTP_ADDRESS)" "ZENY_HTTP_ADDRESS gravado"
assert_eq "http://192.168.0.20:8088" "$(val "$d2/.env" ZENY_STORAGE_PUBLIC_ENDPOINT)" "ZENY_STORAGE_PUBLIC_ENDPOINT gravado"
assert_eq "1" "$(grep -c '^ZENY_HTTP_ADDRESS=' "$d2/.env")" "uma linha de ZENY_HTTP_ADDRESS"

# valor com / & | \ não quebra a troca
d3="$tmp/c"
mkdir -p "$d3"
assert_exit 0 "endpoint com caracteres especiais" -- bash "$GEN" --public-endpoint 'http://a/b&c|d\e' "$d3/.env"
assert_eq 'http://a/b&c|d\e' "$(val "$d3/.env" ZENY_STORAGE_PUBLIC_ENDPOINT)" "valor literal"

# uso incorreto
assert_exit 2 "sem DEST" -- bash "$GEN"
assert_exit 2 "opção desconhecida" -- bash "$GEN" --nada "$tmp/x.env"

# autocontido: fora de qualquer repositório, sem git no PATH
iso="$tmp/iso"
mkdir -p "$iso/pkg" "$iso/bin"
cp "$GEN" "$EX" "$iso/pkg/"
for c in bash openssl base64 tr awk mktemp chmod mv cat dirname rm cp stat head wc grep sed date printf env; do
  p="$(command -v "$c")"
  [[ "$p" == /* ]] && ln -sf "$p" "$iso/bin/$c"
done
assert_exit 0 "fora de repositório, sem git" -- bash -c 'cd "$1/pkg" && PATH="$1/bin" bash ./gen-env.sh "$1/pkg/.env"' _ "$iso"
assert_file "$iso/pkg/.env" ".env gerado fora do repositório"

# --- real: mesmas variáveis, na mesma ordem, do servidor no commit do pin ---
real_commit="$(env -u ZENY_SERVER_REPO bash -c 'source "$1/tools/lib/common.sh" && pin_get commit' _ "$ROOT" 2>/dev/null)"
srv_example="$(env -u ZENY_SERVER_REPO bash -c 'source "$1/tools/lib/common.sh" && srv_git show "$2:docker/.env.example"' _ "$ROOT" "$real_commit" 2>/dev/null)"
if [[ -n "$real_commit" && -n "$srv_example" ]]; then
  assert_eq "$(names <<<"$srv_example")" "$(names <"$EX")" "variáveis do front iguais às do servidor no pin, na mesma ordem"
else
  skip_case "servidor ou pin real ausente: ordem das variáveis do .env.example"
fi

finish
