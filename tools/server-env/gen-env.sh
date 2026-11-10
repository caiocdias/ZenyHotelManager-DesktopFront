#!/usr/bin/env bash
# Gera o .env do ambiente do servidor a partir do .env.example vizinho, preenchendo os segredos.
# Autocontido: roda também no pacote do Windows, dentro do Alpine (bash, openssl, base64 do busybox).
# Uso: gen-env.sh [--force] [--address ADDR] [--public-endpoint URL] DEST
set -u

usage() {
  echo "Uso: gen-env.sh [--force] [--address ADDR] [--public-endpoint URL] DEST" >&2
  exit 2
}

fail() {
  echo "gen-env: $1" >&2
  exit "${2:-2}"
}

force=0
address=""
have_address=0
endpoint=""
have_endpoint=0
dest=""
while (($# > 0)); do
  case "$1" in
    --force) force=1 ;;
    --address)
      (($# >= 2)) || usage
      address="$2"
      have_address=1
      shift
      ;;
    --public-endpoint)
      (($# >= 2)) || usage
      endpoint="$2"
      have_endpoint=1
      shift
      ;;
    -*) usage ;;
    *)
      [[ -z "$dest" ]] || usage
      dest="$1"
      ;;
  esac
  shift
done
[[ -n "$dest" ]] || usage

# Quebra de linha no valor criaria linhas novas (injeção de variáveis) no .env.
nl=$'\n'
cr=$'\r'
for v in "$address" "$endpoint"; do
  if [[ "$v" == *"$nl"* || "$v" == *"$cr"* ]]; then
    fail "valor com quebra de linha recusado"
  fi
done

template="$(dirname "$0")/.env.example"
[[ -f "$template" ]] || fail "modelo não encontrado: $template" 3
[[ -d "$(dirname "$dest")" ]] || fail "diretório de destino inexistente: $(dirname "$dest")"
if [[ -e "$dest" || -L "$dest" ]]; then
  [[ -f "$dest" ]] || fail "$dest existe e não é um arquivo regular"
  [[ "$force" == 1 ]] || fail "$dest já existe (use --force para sobrescrever)"
fi

umask 077
tmpfile="$(mktemp "$dest.XXXXXX")" || fail "não foi possível criar arquivo temporário junto de $dest" 3
claimed=
trap 'rm -f "$tmpfile"; [[ -z "$claimed" ]] || rm -f "$claimed"' EXIT

# Os geradores abaixo são os do README do servidor.
enc="dev:$(openssl rand -base64 32)" || fail "openssl falhou" 3
jwt_der="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n')"
[[ -n "$jwt_der" ]] || fail "openssl falhou ao gerar a chave RSA" 3
jwt="dev:$jwt_der"
admin="$(openssl rand -base64 18)" || fail "openssl falhou" 3
root_pw="$(openssl rand -base64 24)" || fail "openssl falhou" 3
secret_key="$(openssl rand -base64 24)" || fail "openssl falhou" 3

# Valores entram por ENVIRON (literais; awk -v interpretaria barras invertidas) e a troca é pela
# chave inteira, sem o valor em padrão de sed.
GEN_ENCRYPTION_KEYS="$enc" GEN_JWT_KEYS="$jwt" GEN_ADMIN_PASSWORD="$admin" \
  GEN_STORAGE_ROOT_PASSWORD="$root_pw" GEN_STORAGE_SECRET_KEY="$secret_key" \
  GEN_HTTP_ADDRESS="$address" GEN_HAVE_ADDRESS="$have_address" \
  GEN_PUBLIC_ENDPOINT="$endpoint" GEN_HAVE_ENDPOINT="$have_endpoint" \
  awk '
    BEGIN {
      v["ZENY_ENCRYPTION_KEYS"] = ENVIRON["GEN_ENCRYPTION_KEYS"]
      v["ZENY_JWT_KEYS"] = ENVIRON["GEN_JWT_KEYS"]
      v["ZENY_ADMIN_PASSWORD"] = ENVIRON["GEN_ADMIN_PASSWORD"]
      v["ZENY_STORAGE_ROOT_PASSWORD"] = ENVIRON["GEN_STORAGE_ROOT_PASSWORD"]
      v["ZENY_STORAGE_SECRET_KEY"] = ENVIRON["GEN_STORAGE_SECRET_KEY"]
      if (ENVIRON["GEN_HAVE_ADDRESS"] == "1") v["ZENY_HTTP_ADDRESS"] = ENVIRON["GEN_HTTP_ADDRESS"]
      if (ENVIRON["GEN_HAVE_ENDPOINT"] == "1") v["ZENY_STORAGE_PUBLIC_ENDPOINT"] = ENVIRON["GEN_PUBLIC_ENDPOINT"]
    }
    {
      i = index($0, "=")
      if (i > 1 && substr($0, 1, i - 1) in v) {
        print substr($0, 1, i) v[substr($0, 1, i - 1)]
      } else {
        print
      }
    }
  ' "$template" >"$tmpfile" || fail "falha ao montar o arquivo" 3

if ! chmod 600 "$tmpfile" 2>/dev/null; then
  echo "gen-env: aviso: não foi possível aplicar chmod 600 em $dest (sem permissões POSIX?)" >&2
fi
if [[ "$force" != 1 ]]; then
  # Criação exclusiva (noclobber = O_EXCL): se outra execução publicou antes, esta sai com 2.
  if ! (set -o noclobber; : >"$dest") 2>/dev/null; then
    fail "$dest já existe (use --force para sobrescrever)"
  fi
  claimed="$dest"
fi
mv -f "$tmpfile" "$dest" || fail "não foi possível gravar $dest" 3
trap - EXIT

echo "Gerado $dest (segredos: ZENY_ENCRYPTION_KEYS, ZENY_JWT_KEYS, ZENY_ADMIN_PASSWORD, ZENY_STORAGE_ROOT_PASSWORD, ZENY_STORAGE_SECRET_KEY)"
