#!/usr/bin/env bash
# Pré-voo de defasagem: compara o contrato do servidor entre dois commits e classifica a mudança.
# Só leitura no servidor (toda chamada passa por srv_git).
#
# Uso: tools/server-drift.sh [--base REF] [--target REF]
#   --base REF     commit de partida (ou ZENY_DRIFT_BASE). Padrão: `commit` do pin
#                  (api/server-pin.properties). Com --base o pin não é lido nem conferido.
#   --target REF   commit de chegada (ou ZENY_DRIFT_TARGET). Padrão: main do servidor.
#   -h, --help     mostra esta ajuda.
#
# Códigos de saída:
#   0   igual: base e alvo são o mesmo commit
#   10  o servidor avançou, contrato idêntico
#   11  o servidor avançou com mudança compatível no contrato (aditivo: oasdiff sem ERR em /api/v1)
#   12  breaking: oasdiff com ERR em /api/v1
#   2   erro de uso ou de ambiente (repositório, REF, Docker, oasdiff)
#   3   pin inconsistente: ausente, incompleto, ou api/openapi.yaml local com hash diferente de
#       contract_sha256 (só verificado quando a base é a do pin)
#
# Variáveis: ZENY_SERVER_REPO, OASDIFF_IMAGE.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$ROOT/lib/common.sh"

OASDIFF_IMAGE="${OASDIFF_IMAGE:-tufin/oasdiff:v1.33.0@sha256:6263a96dd2ef0726c54e21fea9b8e1607eac4841add0079324b424c1f52b819c}"

usage() {
  sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

base_ref="${ZENY_DRIFT_BASE:-}"
target_ref="${ZENY_DRIFT_TARGET:-}"
while (($# > 0)); do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --base | --target)
      (($# >= 2)) || die 2 "opção $1 exige um valor"
      if [[ "$1" == --base ]]; then base_ref="$2"; else target_ref="$2"; fi
      shift 2
      ;;
    *) die 2 "opção desconhecida: $1 (veja --help)" ;;
  esac
done
target_ref="${target_ref:-main}"

# Base padrão = pin; só então o pin é conferido.
if [[ -z "$base_ref" ]]; then
  base_ref="$(pin_get commit)" || exit "$?"
  expected_sha="$(pin_get contract_sha256)" || exit "$?"
  local_contract="$(front_root)/api/openapi.yaml"
  [[ -f "$local_contract" ]] || die 3 "contrato local não encontrado: $local_contract"
  actual_sha="$(sha256sum "$local_contract" | cut -d' ' -f1)"
  [[ "$actual_sha" == "$expected_sha" ]] ||
    die 3 "api/openapi.yaml difere do pin (contract_sha256 esperado $expected_sha, obtido $actual_sha)"
fi

base="$(resolve_server_ref "$base_ref")" || exit "$?"
target="$(resolve_server_ref "$target_ref")" || exit "$?"

printf 'Pré-voo: base %s × alvo %s (%s)\n' "$(short_commit "$base")" "$(short_commit "$target")" "$target_ref"

finish_with() { # CODE LABEL
  printf 'Resultado: %s (código %s)\n' "$2" "$1"
  exit "$1"
}

if [[ "$base" == "$target" ]]; then
  finish_with 0 igual
fi

changed="$(srv_git diff --name-only "$base" "$target" -- docker/)" || exit 2
if [[ -n "$changed" ]]; then
  printf 'Ambiente do servidor alterado:\n'
  printf '  %s\n' $changed
  printf 'Revise tools/server-env/ na próxima rodada.\n'
else
  printf 'Ambiente do servidor sem mudanças.\n'
fi

specs="$(mktemp -d)" || die 2 "não foi possível criar diretório temporário"
trap 'rm -rf "$specs"' EXIT
mkdir -p "$specs/base/api" "$specs/api"
srv_git show "$base:api/openapi.yaml" >"$specs/base/api/openapi.yaml" 2>/dev/null ||
  die 2 "a base $(short_commit "$base") não tem api/openapi.yaml"
srv_git show "$target:api/openapi.yaml" >"$specs/api/openapi.yaml" 2>/dev/null ||
  die 2 "o alvo $(short_commit "$target") não tem api/openapi.yaml"

if cmp -s "$specs/base/api/openapi.yaml" "$specs/api/openapi.yaml"; then
  finish_with 10 "avançou sem mudança no contrato"
fi

inspect_err="$(docker image inspect "$OASDIFF_IMAGE" 2>&1 >/dev/null)" ||
  die 2 "imagem do oasdiff indisponível (${inspect_err:-docker image inspect falhou}); com internet: docker pull \"$OASDIFF_IMAGE\""

chmod -R a+rX "$specs"
docker run --rm --network none -v "$specs:/specs:ro" -w /specs "$OASDIFF_IMAGE" breaking \
  base/api/openapi.yaml api/openapi.yaml \
  --match-path '^/api/v1(/|$)' \
  --fail-on ERR \
  --allow-external-refs=false \
  --format text \
  --lang pt-br
code=$?
case "$code" in
  0) finish_with 11 aditivo ;;
  1) finish_with 12 breaking ;;
  *) die 2 "oasdiff falhou (exit $code)" ;;
esac
