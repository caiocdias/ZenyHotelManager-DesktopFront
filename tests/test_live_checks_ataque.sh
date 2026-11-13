#!/usr/bin/env bash
# Ataque à Task 10 da M1 E06: as verificações ao vivo (LiveRealtimeCheck, LiveRoomMapCheck) não podem sair
# verdes sem rodar nem vazar segredo pelo ambiente da máquina. Estático: não chama o Gradle nem o servidor.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

# Código ativo de um .kt/.kts: sem comentários de linha, numa linha só (chamadas quebradas em várias linhas
# também casam).
codigo_ativo() { sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' "$1" 2>/dev/null | tr '\n' ' ' | tr -s ' '; }

# --- 1. O curl das verificações não lê o ~/.curlrc ----------------------------------------------------------
# Sem -q (ou --disable) como PRIMEIRO argumento, o `curl -K -` lê também o ~/.curlrc (e o de CURL_HOME e
# XDG_CONFIG_HOME). Um `verbose` lá põe "> Authorization: Bearer <token>" no stderr, que o HousekeeperCurl
# repassa na mensagem do `check` quando o curl sai com código != 0; um `trace`/`trace-ascii` grava o corpo do
# login, com a senha, num arquivo.
mapfile -t fontes < <(grep -rlE 'ProcessBuilder' "$ROOT/app/src/test" "$ROOT/core/src/test" 2>/dev/null | sort)
chamadas_curl=0
for f in "${fontes[@]}"; do
  rel="${f#"$ROOT"/}"
  codigo="$(codigo_ativo "$f")"
  total="$(grep -oE 'ProcessBuilder\( *"curl" *[,)]' <<<"$codigo" | wc -l)"
  ((total == 0)) && continue
  chamadas_curl=$((chamadas_curl + total))
  sem_rc="$(grep -oE 'ProcessBuilder\( *"curl" *, *"(-q|--disable)" *[,)]' <<<"$codigo" | wc -l)"
  assert_eq "$total" "$sem_rc" \
    "$rel: todo ProcessBuilder(\"curl\", ...) passa -q (ou --disable) como 1º argumento, para não ler o ~/.curlrc"
done
((chamadas_curl > 0)) || skip_case "nenhum ProcessBuilder(\"curl\", ...) nas fontes de teste do app e do core"

# --- 2. O opt-in faz parte do estado da tarefa de teste -----------------------------------------------------
# A variável de ambiente não é entrada do `tasks.test`: depois de uma rodada sem o opt-in (pulada),
# `ZENY_LIVE_REALTIME=1 ./gradlew :core:test --tests '*LiveRealtimeCheck*'` sem --rerun fica UP-TO-DATE e sai
# BUILD SUCCESSFUL sem rodar a verificação (mesmo sem credencial, quando deveria falhar). E `--rerun` vale só para
# a tarefa logo antes dele: `./gradlew :core:test --tests A :app:test --tests B --rerun` deixa :core:test em dia.
raiz="$(codigo_ativo "$ROOT/build.gradle.kts")"
# Quantas vezes o código ativo do build (módulo + raiz) cita a variável; 0 = o Gradle não a vê.
cita() { grep -o "$2" <<<"$(codigo_ativo "$ROOT/$1/build.gradle.kts") $raiz" | wc -l; }
for modulo in core app; do
  n="$(cita "$modulo" ZENY_LIVE_REALTIME)"
  assert_eq "sim" "$( ((n > 0)) && echo sim || echo não)" \
    "$modulo: o build leva ZENY_LIVE_REALTIME em conta no tasks.test (entrada ou upToDateWhen), senão o opt-in não faz a verificação rodar"
done
n="$(cita app ZENY_LIVE_OUTAGE)"
assert_eq "sim" "$( ((n > 0)) && echo sim || echo não)" \
  "app: o build leva ZENY_LIVE_OUTAGE em conta no tasks.test, senão a queda do api fica UP-TO-DATE depois de uma rodada pulada"

finish
