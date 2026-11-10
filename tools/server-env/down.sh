#!/usr/bin/env bash
# Derruba o projeto compose zeny-front. Com -v/--volumes remove também os volumes dele.
# Uso: down.sh [-v|--volumes]
set -u
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

args=()
for arg in "$@"; do
  case "$arg" in
    -v | --volumes) args=(-v) ;;
    *) die 2 "uso: down.sh [-v|--volumes]" ;;
  esac
done

# Só o projeto, de um diretório vazio e sem COMPOSE_FILE/COMPOSE_PROFILES: derrubar não depende
# de segredos, do .env nem da árvore extraída.
empty="$(mktemp -d)" || die 1 "não foi possível criar diretório temporário"
(cd "$empty" && env -u COMPOSE_FILE -u COMPOSE_PROFILES docker compose -p "$ZENY_FRONT_PROJECT" down "${args[@]}")
code=$?
rmdir "$empty"
((code == 0)) || die 1 "falha ao derrubar o projeto $ZENY_FRONT_PROJECT"
if ((${#args[@]})); then
  printf 'projeto %s removido, com os volumes\n' "$ZENY_FRONT_PROJECT"
else
  printf 'projeto %s removido (volumes preservados)\n' "$ZENY_FRONT_PROJECT"
fi
