#!/usr/bin/env bash
# Ataque à Task 7 da M3 E01: a verificação ao vivo da busca de reservas (LiveReservationSearchCheck) só lê, fica
# pulada sem o opt-in e, com ele, não pode sair verde por omissão. Estático: não chama o Gradle nem o servidor.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

codigo_ativo() { sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' "$1" 2>/dev/null | tr '\n' ' ' | tr -s ' '; }

KT="$ROOT/app/src/test/kotlin/com/zeny/desktop/app/reservations/LiveReservationSearchCheck.kt"
GRADLE="$ROOT/app/build.gradle.kts"
SEED="$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed/data/FictitiousDocuments.kt"
assert_file "$KT"
kt="$(codigo_ativo "$KT")"
gradle="$(codigo_ativo "$GRADLE")"
seed="$(codigo_ativo "$SEED")"

# --- 1. Opt-in no build, igual ao dos outros ----------------------------------------------------------------
assert_contains "$gradle" 'inputs.property("zenyLiveSearch", providers.environmentVariable("ZENY_LIVE_SEARCH").orElse(""))' \
  "app/build.gradle.kts: ZENY_LIVE_SEARCH é entrada do tasks.test"
assert_contains "$gradle" 'val liveSearch = providers.environmentVariable("ZENY_LIVE_SEARCH")' \
  "app/build.gradle.kts: liveSearch lê ZENY_LIVE_SEARCH"
bloco="$(grep -oE 'outputs\.upToDateWhen \{[^}]*\}' <<<"$gradle")"
assert_contains "$bloco" 'liveSearch.orNull != "1"' "app/build.gradle.kts: o upToDateWhen leva ZENY_LIVE_SEARCH"
assert_contains "$bloco" 'liveCard.orNull != "1"' "app/build.gradle.kts: o upToDateWhen manteve o ZENY_LIVE_CARD"

# --- 2. Gating: sem opt-in pula (antes de cada teste); com opt-in exige as três variáveis -------------------
assert_contains "$kt" 'assumeTrue(System.getenv("ZENY_LIVE_SEARCH") == "1"' "sem ZENY_LIVE_SEARCH=1 cada teste pula"
for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD; do
  assert_contains "$kt" "env(\"$v\")" "a verificação exige $v (falha se faltar)"
done
assert_contains "$kt" '?: fail("defina $name para a verificação real")' "env() falha citando só o NOME da variável"
n="$(grep -oE 'System\.getenv\(' <<<"$kt" | wc -l)"
assert_eq "2" "$n" "System.getenv só no opt-in e no env()"
n="$(grep -oE '@Test' <<<"$kt" | wc -l)"
assert_eq "4" "$n" "as quatro verificações do brief"

# --- 3. Só lê: nenhuma chamada de escrita do servidor ---------------------------------------------------------
assert_not_contains "$kt" '.checkIn(' "não faz check-in"
assert_not_contains "$kt" '.createReservation(' "não cria reserva"
assert_not_contains "$kt" '.cancel' "não cancela"
assert_not_contains "$kt" 'e.printStackTrace' "sem printStackTrace"

# --- 4. A restauração dos fluxos capturados não depende do sucesso do teste -----------------------------------
assert_contains "$kt" 'val text = captured.stop() ran.getOrThrow()' \
  "o stop() roda antes de relançar a falha (System.out, System.err e o JUL raiz voltam em qualquer caso)"
assert_contains "$kt" 'System.setOut(oldOut) System.setErr(oldErr)' "stop() devolve System.out e System.err"
assert_contains "$kt" 'root.removeHandler(handler)' "stop() tira o handler do JUL raiz"

# --- 5. O CPF do G1 segue a regra de FictitiousDocuments (pesos 10 e 11, módulo 11, resto < 2 vira 0) --------
assert_contains "$kt" 'private const val G1_CPF_BASE = "202600101"' "base do G1 igual à do DemoCatalog (2026001 + 01)"
assert_contains "$kt" 'private const val CPF_FIRST_WEIGHT = 10' "primeiro peso 10"
assert_contains "$kt" 'private const val CPF_MODULUS = 11' "módulo 11"
assert_contains "$kt" 'digit(G1_CPF_BASE + d1, CPF_FIRST_WEIGHT + 1)' "segundo dígito com peso 11 sobre a base + d1"
assert_contains "$kt" 'if (it < 2) 0 else CPF_MODULUS - it' "resto menor que 2 vira 0"
assert_contains "$seed" 'val d1 = check(base9, 10)' "FictitiousDocuments: primeiro peso 10"
assert_contains "$seed" 'val d2 = check(base9 + d1, 11)' "FictitiousDocuments: segundo peso 11"
assert_contains "$seed" 'private const val MODULUS = 11' "FictitiousDocuments: módulo 11"
assert_contains "$seed" 'private const val MIN_WEIGHTED_REMAINDER = 2' "FictitiousDocuments: resto menor que 2 vira 0"
assert_contains "$(codigo_ativo "$ROOT/demo-seed/src/main/kotlin/com/zeny/desktop/seed/data/DemoCatalog.kt")" \
  'FictitiousDocuments.cpf("2026001${n.toString().padStart(2, '"'0'"')}")' "o catálogo monta o CPF do hóspede n assim"

# --- 6. As afirmações do brief ----------------------------------------------------------------------------------
assert_contains "$kt" '!it.departureOverdue' "a lista do dia sai sem a saída vencida"
assert_contains "$kt" 'DayList.IN_HOUSE' "compara com os hospedados do dia"
assert_contains "$kt" 'private const val SMALL_PAGE = 3' "páginas de 3"
assert_contains "$kt" 'assertEquals(ids.size, ids.toSet().size' "nenhum id repetido"
assert_contains "$kt" 'assertEquals(expected.size, ids.size' "o total bate com a lista do dia"
assert_contains "$kt" 'val masked = "CPF ***${cpf.takeLast(4)}"' "máscara CPF ***NNNN"
assert_contains "$kt" 'texts.none { ELEVEN_DIGITS.containsMatchIn(it) }' "nenhum nó com 11 dígitos"
assert_contains "$kt" 'File("build/live/reservation-search.png")' "captura da tela"

finish
