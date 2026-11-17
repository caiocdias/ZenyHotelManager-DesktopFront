#!/usr/bin/env bash
# Ataque à Task 9 da M3 E02: a verificação ao vivo do "outro cliente" (LiveAvailabilityBookingCheck) escreve e desfaz
# na demo. Ela fica pulada sem o opt-in, não imprime credencial, usa externalId DEMO-E02- e desfaz num finally.
# Estático: não chama o Gradle nem o servidor.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

# Só código ativo, num formato estável diante do ktlint: sem blocos /* */ nem KDoc, sem //, numa linha, sem espaço
# depois de "(" nem antes de ")", sem vírgula final antes de ")" e sem espaço antes do ponto de uma cadeia quebrada.
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

KT="$ROOT/demo-seed/src/test/kotlin/com/zeny/desktop/seed/sdk/LiveAvailabilityBookingCheck.kt"
assert_file "$KT"
kt="$(codigo_ativo "$KT")"

# --- 1. Opt-in: sem ele cada teste pula ----------------------------------------------------------------------
assert_contains "$kt" 'assumeTrue(System.getenv("ZENY_SEED_SERVER") != null && System.getenv("ZENY_SEED_LIVE_AVAILABILITY") == "1")' \
  "sem ZENY_SEED_LIVE_AVAILABILITY=1 a verificação pula"
n="$(grep -oF 'optedIn()' <<<"$kt" | wc -l)"
assert_eq "3" "$n" "optedIn() é definido uma vez e chamado nos dois testes"
n="$(grep -oE '@Test' <<<"$kt" | wc -l)"
assert_eq "2" "$n" "os dois testes do brief"
assert_contains "$kt" 'SeedArgs.parse(emptyList(), System.getenv())' "as credenciais vêm só do SeedArgs"

# --- 2. Nenhuma credencial vai à saída --------------------------------------------------------------------------
n="$(grep -oE 'System\.getenv\(' <<<"$kt" | wc -l)"
assert_eq "3" "$n" "System.getenv só no opt-in (duas) e no SeedArgs"
assert_not_contains "$kt" 'println(System.getenv' "nenhum println do valor de getenv"
assert_not_contains "$kt" 'config.admin' "nenhum campo de credencial do SeedConfig"
assert_not_contains "$kt" 'Password' "nenhuma senha tocada"
assert_not_contains "$kt" 'printStackTrace' "sem printStackTrace"
assert_not_contains "$kt" 'System.out' "nenhuma saída direta fora do println"
assert_not_contains "$kt" 'System.err' "nenhuma saída de erro direta"
total="$(grep -oF 'println(' <<<"$kt" | wc -l)"
literais="$(grep -oE 'println\("[^"]*"\)' <<<"$kt" | wc -l)"
assert_eq "$total" "$literais" "todo println é um único literal"

# --- 3. Escreve e desfaz ------------------------------------------------------------------------------------------
assert_contains "$kt" 'Option.Some("DEMO-E02-${Uuid.random()}")' "o externalId é DEMO-E02-<uuid>"
assert_not_contains "$kt" 'DEMO-SEED-' "não usa o externalId do semeador"
assert_not_contains "$kt" 'ReservationSpec' "não usa o ReservationSpec"
assert_contains "$kt" 'private const val BOOKING_OFFSET_DAYS = 20L' "reserva em d+20"
assert_contains "$kt" 'private const val RESTRICTION_OFFSET_DAYS = 25L' "restrição em d+25"
assert_contains "$kt" 'private const val MIN_STAY_NIGHTS = 2' "estadia mínima de 2 noites"
assert_contains "$kt" 'ReservationCancelRequest.Reason.HOTEL_REQUEST, waivePenalty = false' "cancelamento por HOTEL_REQUEST sem dispensar multa"
assert_not_contains "$kt" 'If-Match' "o cancelamento não usa If-Match"
assert_contains "$kt" 'api.cancelReservation(id, it.requireIdempotencyKey(), null, body, it.headers)' "cancelamento com Idempotency-Key própria"
# O finally cancela se a criação passou e o cancelamento não.
assert_contains "$kt" 'finally { try { val id = createdId if (id != null && !cancelled) cancel(admin.seed, id) } finally { admin.shutDown() } }' \
  "o finally cancela a reserva criada e ainda não cancelada"
# O finally retira a restrição com clear = [MIN_STAY] e confere.
assert_contains "$kt" 'finally { try { if (written) clearMinStay(admin.seed) } finally { admin.shutDown() } }' \
  "o finally retira a restrição gravada"
assert_contains "$kt" 'listOf(RestrictionUpdateRequest.ClearItem.MIN_STAY)' "o clear é [MIN_STAY]"
assert_contains "$kt" 'assertTrue(!left, "a data voltou sem restrição")' "confere que a data voltou sem restrição"
assert_contains "$kt" 'NEED_SEED: há restrição em d+$RESTRICTION_OFFSET_DAYS no $PLAN_CODE' "o NEED_SEED diz como limpar"

# --- 4. As afirmações do brief --------------------------------------------------------------------------------
assert_contains "$kt" 'assertEquals(before - 1, available()' "a reserva reduz a disponibilidade em 1"
assert_contains "$kt" 'assertEquals(before, available()' "o cancelamento devolve a disponibilidade"
assert_contains "$kt" 'seed.core.reservations.availability(date, date)' "a disponibilidade vem da fachada"
assert_contains "$kt" 'quoteStay(date, BusinessDate(day.plusDays(1)), 2, emptyList(), includeUnavailable = true)' "cotação pela fachada"
assert_contains "$kt" 'it.code == "MIN_STAY_NOT_MET"' "o motivo MIN_STAY_NOT_MET"
assert_contains "$kt" 'ErrorCatalog.isShowableDetail(reason.message)' "a mensagem é texto de pessoa"
assert_contains "$kt" 'reason.message.contains("exige ao menos $MIN_STAY_NIGHTS noite(s)")' "a mensagem diz a regra em português"

finish
