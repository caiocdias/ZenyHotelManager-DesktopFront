#!/usr/bin/env bash
# Biblioteca comum dos scripts do front. Para `source`; não executa nada ao ser carregado.
# O repositório do servidor é SÓ LEITURA: todo acesso passa por srv_git.

_COMMON_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# die CODE MSG...
die() {
  local code="$1"
  shift
  printf 'erro: %s\n' "$*" >&2
  exit "$code"
}

# Raiz do worktree do front em que este script está (independe do cwd).
front_root() {
  git -C "$_COMMON_SH_DIR" rev-parse --show-toplevel
}

# Checkout principal do front: diretório pai do git-common-dir.
front_main_checkout() {
  local common
  common="$(git -C "$_COMMON_SH_DIR" rev-parse --path-format=absolute --git-common-dir)" ||
    die 2 "não foi possível localizar o checkout principal do front"
  dirname "$common"
}

# Caminho absoluto do repositório do servidor.
server_repo() {
  local candidate dir top
  if [[ -n "${ZENY_SERVER_REPO:-}" ]]; then
    candidate="$ZENY_SERVER_REPO"
  else
    candidate="$(front_main_checkout)/../ZenyHotelManager" || exit "$?"
  fi
  dir="$(cd "$candidate" 2>/dev/null && pwd -P)" ||
    die 2 "repositório do servidor não encontrado: $candidate"
  top="$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null)"
  [[ "$top" == "$dir" ]] || die 2 "não é um repositório git: $dir"
  printf '%s\n' "$dir"
}

# srv_git ARGS... : git no servidor, só com subcomandos de leitura.
srv_git() {
  local sub="${1:-}" repo
  case "$sub" in
    rev-parse | cat-file | show | archive | diff) ;;
    *) die 2 "subcomando git não permitido no servidor: $sub" ;;
  esac
  repo="$(server_repo)" || exit "$?"
  shift
  git -C "$repo" "$sub" "$@"
}

# resolve_server_ref REF : devolve o SHA de 40 caracteres do commit.
resolve_server_ref() {
  local ref="${1:-}" sha
  [[ -n "$ref" ]] || die 2 "referência do servidor vazia"
  [[ "$ref" != -* ]] || die 2 "referência do servidor inválida: $ref"
  sha="$(srv_git rev-parse --verify --end-of-options "$ref^{commit}" 2>/dev/null)" ||
    die 2 "commit não encontrado no servidor: $ref"
  printf '%s\n' "$sha"
}

pin_file() {
  printf '%s/api/server-pin.properties\n' "$(front_root)"
}

# pin_get KEY : valor de KEY no pin; exit 3 se arquivo ou chave faltarem.
pin_get() {
  local key="$1" file k v
  file="$(pin_file)" || exit "$?"
  [[ -f "$file" ]] || die 3 "pin do servidor não encontrado: $file"
  while IFS='=' read -r k v || [[ -n "$k" ]]; do
    if [[ "$k" == "$key" ]]; then
      printf '%s\n' "$v"
      return 0
    fi
  done <"$file"
  die 3 "chave ausente no pin do servidor: $key"
}

# Os 12 primeiros caracteres do SHA.
short_commit() {
  printf '%s\n' "${1:0:12}"
}
