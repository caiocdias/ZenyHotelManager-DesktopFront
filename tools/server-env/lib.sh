#!/usr/bin/env bash
# Biblioteca do ambiente do servidor no front (projeto compose zeny-front). Para `source`.
# Estado de máquina (árvore extraída, override, .env) fica no checkout principal do front.

_SERVER_ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$_SERVER_ENV_DIR/../lib/common.sh"

readonly ZENY_FRONT_PROJECT=zeny-front

server_tag() {
  short_commit "$(pin_get commit)"
}

server_tree_dir() {
  local commit main
  commit="$(pin_get commit)" || exit "$?"
  main="$(front_main_checkout)" || exit "$?"
  printf '%s/.server/%s\n' "$main" "$commit"
}

override_file() {
  printf '%s.override.yaml\n' "$(server_tree_dir)"
}

# ensure_state_dir DIR : cria DIR e o faz se autoignorar (sem sobrescrever um .gitignore existente).
ensure_state_dir() {
  mkdir -p "$1" || die 1 "não foi possível criar $1"
  [[ -e "$1/.gitignore" ]] || printf '*\n' >"$1/.gitignore"
}

# ensure_server_tree : extrai o commit fixado (uma vez), confere o contrato e imprime o caminho.
ensure_server_tree() {
  local tree commit state tmp expected actual
  tree="$(server_tree_dir)" || exit "$?"
  commit="$(pin_get commit)" || exit "$?"
  state="$(dirname "$tree")"
  ensure_state_dir "$state"
  if [[ ! -d "$tree" ]]; then
    tmp="$(mktemp -d "$state/.extract.XXXXXX")" || die 1 "não foi possível criar diretório temporário em $state"
    srv_git archive --format=tar "$commit" | tar -x -C "$tmp"
    if [[ "${PIPESTATUS[0]}" != 0 || "${PIPESTATUS[1]}" != 0 ]]; then
      rm -rf "$tmp"
      die 2 "falha ao extrair o commit $commit do servidor"
    fi
    # mktemp cria 0700; a árvore segue o umask, pois os containers leem os bind mounts com outros uids.
    chmod "$(printf '%o' $((0777 & ~$(umask))))" "$tmp"
    # -T: nunca entra num destino existente (extração concorrente); nesse caso descarta o temporário.
    if ! mv -T "$tmp" "$tree" 2>/dev/null; then
      rm -rf "$tmp"
      [[ -d "$tree" ]] || die 1 "falha ao instalar a árvore $tree"
    fi
  fi
  expected="$(pin_get contract_sha256)" || exit "$?"
  actual="$(sha256sum "$tree/api/openapi.yaml" 2>/dev/null | cut -d' ' -f1)"
  [[ "$actual" == "$expected" ]] ||
    die 3 "api/openapi.yaml da árvore $tree não confere com contract_sha256 do pin"
  printf '%s\n' "$tree"
}

# _replace_all TEXT MARKER VALUE : troca MARKER por VALUE literalmente (sem interpretar & nem barras).
_replace_all() {
  local text="$1" marker="$2" value="$3" out=""
  while [[ "$text" == *"$marker"* ]]; do
    out+="${text%%"$marker"*}$value"
    text="${text#*"$marker"}"
  done
  printf '%s' "$out$text"
}

# render_override DEST DEFAULT_ADDRESS
render_override() {
  local dest="$1" address="$2" tag content
  tag="$(server_tag)" || exit "$?"
  content="$(<"$_SERVER_ENV_DIR/compose.override.yaml")"
  content="$(_replace_all "$content" @SERVER_TAG@ "$tag")"
  content="$(_replace_all "$content" @DEFAULT_ADDRESS@ "$address")"
  printf '%s\n' "$content" >"$dest"
}

env_file() {
  if [[ -n "${ZENY_FRONT_ENV_FILE:-}" ]]; then
    printf '%s\n' "$ZENY_FRONT_ENV_FILE"
  else
    printf '%s/.server/zeny-front.env\n' "$(front_main_checkout)"
  fi
}

# env_get KEY [DEFAULT] : lê KEY do env_file com awk (nunca executa o arquivo).
env_get() {
  local key="$1" default="${2:-}" file value
  file="$(env_file)" || exit "$?"
  if [[ -f "$file" ]]; then
    value="$(awk -v k="$key" '
      { sub(/\r$/, "") }
      index($0, k "=") == 1 { v = "F" substr($0, length(k) + 2) }
      END { if (v != "") print v }
    ' "$file")"
    if [[ "$value" == F* ]]; then
      printf '%s\n' "${value#F}"
      return 0
    fi
  fi
  printf '%s\n' "$default"
}

# compose ARGS... : docker compose do projeto zeny-front sobre a árvore do commit fixado.
compose() {
  local tree override envf
  tree="$(ensure_server_tree)" || exit "$?"
  override="$(override_file)" || exit "$?"
  render_override "$override" 127.0.0.1 || exit "$?"
  envf="$(env_file)" || exit "$?"
  docker compose -p "$ZENY_FRONT_PROJECT" \
    -f "$tree/docker/compose.yaml" -f "$override" \
    --env-file "$envf" "$@"
}
