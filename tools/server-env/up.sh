#!/usr/bin/env bash
# Sobe o servidor fixado no pin como projeto compose zeny-front e espera o readiness.
# Uso: up.sh [--build]   (ZENY_UP_TIMEOUT: segundos de espera, padrão 600)
set -u
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

force_build=0
for arg in "$@"; do
  case "$arg" in
    --build) force_build=1 ;;
    *) die 2 "uso: up.sh [--build]" ;;
  esac
done

timeout="${ZENY_UP_TIMEOUT:-600}"
[[ "$timeout" =~ ^[0-9]+$ ]] || die 2 "ZENY_UP_TIMEOUT deve ser um número inteiro de segundos"

main="$(front_main_checkout)" || exit "$?"
ensure_state_dir "$main/.server"

envf="$(env_file)" || exit "$?"
[[ -f "$envf" ]] || die 2 "arquivo de ambiente não encontrado: $envf. Gere-o com: tools/server-env/gen-env.sh $envf"

tag="$(server_tag)" || exit "$?"
if ((force_build)) || ! docker image inspect "zeny-hotel-manager:$tag" >/dev/null 2>&1; then
  compose build api || die 1 "falha ao construir a imagem zeny-hotel-manager:$tag"
fi
compose up -d || die 1 "falha ao subir o projeto $ZENY_FRONT_PROJECT"

port="$(env_get ZENY_HTTP_PORT 8088)"
port="${port:-8088}"
address="$(env_get ZENY_HTTP_ADDRESS)"
case "$address" in
  "" | 127.0.0.1 | 0.0.0.0) host=127.0.0.1 ;;
  *) host="$address" ;;
esac
url="http://$host:$port"

deadline=$((SECONDS + timeout))
last=""
while :; do
  last="$(curl -fsS --max-time 5 "$url/actuator/health/readiness")"
  compact="${last//[[:space:]]/}"
  # Só o status de topo conta: o corpo tem de começar com {"status":"UP".
  if [[ "$compact" == '{"status":"UP"'* ]]; then
    printf '%s no ar em %s (servidor %s)\n' "$ZENY_FRONT_PROJECT" "$url" "$tag"
    exit 0
  fi
  remaining=$((deadline - SECONDS))
  ((remaining > 0)) || break
  sleep $((remaining < 5 ? remaining : 5))
done
printf 'erro: o servidor não ficou pronto em %ss. Última resposta: %s\n' "$timeout" "$last" >&2
printf 'veja os logs: docker compose -p %s logs api\n' "$ZENY_FRONT_PROJECT" >&2
exit 1
