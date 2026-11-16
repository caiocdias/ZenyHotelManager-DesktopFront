#!/usr/bin/env bash
# Ataque à Task 8 da M3 E02: a verificação ao vivo da disponibilidade e da cotação (LiveAvailabilityCheck) só lê,
# fica pulada sem o opt-in e, com ele, não pode sair verde por omissão. Estático: não chama o Gradle nem o servidor.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

codigo_ativo() { sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' "$1" 2>/dev/null | tr '\n' ' ' | tr -s ' '; }

KT="$ROOT/app/src/test/kotlin/com/zeny/desktop/app/reservations/LiveAvailabilityCheck.kt"
GRADLE="$ROOT/app/build.gradle.kts"
assert_file "$KT"
kt="$(codigo_ativo "$KT")"
gradle="$(codigo_ativo "$GRADLE")"

# --- 1. Opt-in no build, igual ao dos outros ----------------------------------------------------------------
assert_contains "$gradle" 'inputs.property("zenyLiveAvailability", providers.environmentVariable("ZENY_LIVE_AVAILABILITY").orElse(""))' \
  "app/build.gradle.kts: ZENY_LIVE_AVAILABILITY é entrada do tasks.test"
assert_contains "$gradle" 'val liveAvailability = providers.environmentVariable("ZENY_LIVE_AVAILABILITY")' \
  "app/build.gradle.kts: liveAvailability lê ZENY_LIVE_AVAILABILITY"
bloco="$(grep -oE 'outputs\.upToDateWhen \{[^}]*\}' <<<"$gradle")"
assert_contains "$bloco" 'liveAvailability.orNull != "1"' "app/build.gradle.kts: o upToDateWhen leva ZENY_LIVE_AVAILABILITY"
assert_contains "$bloco" 'liveSearch.orNull != "1"' "app/build.gradle.kts: o upToDateWhen manteve o ZENY_LIVE_SEARCH"

# --- 2. Gating: sem opt-in pula (antes de cada teste); com opt-in exige as três variáveis -------------------
assert_contains "$kt" 'System.getenv("ZENY_LIVE_AVAILABILITY") == "1"' "sem ZENY_LIVE_AVAILABILITY=1 cada teste pula"
assert_contains "$kt" 'assumeTrue(' "o opt-in usa assumeTrue"
for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD; do
  assert_contains "$kt" "env(\"$v\")" "a verificação exige $v (falha se faltar)"
done
assert_contains "$kt" '?: fail("defina $name para a verificação real")' "env() falha citando só o NOME da variável"
n="$(grep -oE 'System\.getenv\(' <<<"$kt" | wc -l)"
assert_eq "2" "$n" "System.getenv só no opt-in e no env()"
n="$(grep -oE '@Test' <<<"$kt" | wc -l)"
assert_eq "3" "$n" "as três verificações do brief"

# --- 3. Só lê: nenhuma chamada de escrita do servidor ---------------------------------------------------------
assert_not_contains "$kt" '.checkIn(' "não faz check-in"
assert_not_contains "$kt" '.createReservation(' "não cria reserva"
assert_not_contains "$kt" '.cancel' "não cancela"
assert_not_contains "$kt" 'e.printStackTrace' "sem printStackTrace"

# --- 4. As afirmações do brief ----------------------------------------------------------------------------------
assert_contains "$kt" 'private const val H2_LIMIT_MILLIS = 10_000L' "teto da H2: 10 s"
assert_contains "$kt" 'System.nanoTime()' "a carga do calendário é medida"
assert_contains "$kt" 'H2: a carga do calendário levou' "estouro do teto cita a H2"
assert_contains "$kt" 'private const val WINDOW_DAYS = 30' "janela de 30 datas"
assert_contains "$kt" 'setOf("Standard", "Luxo", "Suíte")' "as três linhas do calendário"
assert_contains "$kt" 'core.reservations.availability(first, last)' "compara com a fachada"
assert_contains "$kt" 'runOnUiThread { calendar.reload() }' "reload() e nova comparação"
assert_contains "$kt" 'private const val ARRIVAL_OFFSET_DAYS = 7L' "chegada em d+7"
assert_contains "$kt" 'private const val DEPARTURE_OFFSET_DAYS = 9L' "saída em d+9"
assert_contains "$kt" 'core.reservations.quoteStay(arrival, departure, 2, emptyList(), true)' "cotação de 2 adultos pela fachada"
assert_contains "$kt" 'ReceptionText.money(offer.total)' "o total da oferta formatado"
assert_contains "$kt" 'ReceptionText.money(it.total)' "o valor de cada noite formatado"
assert_contains "$kt" 'assertEquals(api.offers.size, quote.offers.size' "o número de ofertas bate"
assert_contains "$kt" 'Café da manhã' "o regime em português"
assert_contains "$kt" 'private const val TOO_MANY_ADULTS = "4"' "a recusa com 4 adultos"
assert_contains "$kt" 'recebe até 3 adulto(s)' "o texto da recusa por ocupação"
assert_contains "$kt" 'it.contains("OCCUPANCY_EXCEEDS_ROOM_TYPE")' "nenhum nó com o código cru"
assert_contains "$kt" 'File("build/live/availability.png")' "captura da tela"

finish
