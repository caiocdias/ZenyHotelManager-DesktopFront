#!/usr/bin/env bash
# Ataque à Task 7 da M2 E04: a verificação ao vivo da troca de UH e do desfazer check-in (LiveStayActionsCheck)
# escreve no servidor e gasta tentativas de PIN do aprovador. Estático: não chama o Gradle nem o servidor.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

codigo_ativo() { sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' "$1" 2>/dev/null | tr '\n' ' ' | tr -s ' '; }

KT="$ROOT/app/src/test/kotlin/com/zeny/desktop/app/reception/LiveStayActionsCheck.kt"
GRADLE="$ROOT/app/build.gradle.kts"
assert_file "$KT"
kt="$(codigo_ativo "$KT")"
gradle="$(codigo_ativo "$GRADLE")"

# --- 1. Opt-in no build, igual ao de ZENY_LIVE_WALKIN -------------------------------------------------------
assert_contains "$gradle" 'inputs.property("zenyLiveStay", providers.environmentVariable("ZENY_LIVE_STAY").orElse(""))' \
  "app/build.gradle.kts: ZENY_LIVE_STAY é entrada do tasks.test"
assert_contains "$gradle" 'val liveStay = providers.environmentVariable("ZENY_LIVE_STAY")' \
  "app/build.gradle.kts: liveStay lê ZENY_LIVE_STAY"
bloco="$(grep -oE 'outputs\.upToDateWhen \{[^}]*\}' <<<"$gradle")"
assert_contains "$bloco" 'liveStay.orNull != "1"' "app/build.gradle.kts: o upToDateWhen leva ZENY_LIVE_STAY"
assert_contains "$bloco" 'liveWalkIn.orNull != "1"' "app/build.gradle.kts: o upToDateWhen manteve o ZENY_LIVE_WALKIN"

# --- 2. Gating: sem opt-in pula; com opt-in exige as cinco variáveis, sem imprimi-las ----------------------
assert_contains "$kt" 'assumeTrue(System.getenv("ZENY_LIVE_STAY") == "1"' "sem ZENY_LIVE_STAY=1 a verificação pula"
for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD ZENY_LIVE_APPROVER_LOGIN ZENY_LIVE_APPROVER_PIN; do
  assert_contains "$kt" "env(\"$v\")" "a verificação exige $v (falha se faltar)"
done
assert_contains "$kt" '?: fail("defina $name para a verificação real")' "env() falha citando só o NOME da variável"
# só o env() lê o ambiente além do opt-in
n="$(grep -oE 'System\.getenv\(' <<<"$kt" | wc -l)"
assert_eq "2" "$n" "System.getenv só no opt-in e no env()"

# --- 3. Higiene de segredo: nenhuma saída interpola senha ou PIN --------------------------------------------
# Mensagens (println, fail, check, assert*) não citam a senha, o PIN certo, nem o errado.
for s in 'approverPin' 'wrongPin' 'VIEWER_PASSWORD'; do
  vazamento="$(grep -oE '(println|fail|checkNotNull|assert[A-Za-z]*)\([^;]*"[^"]*\$\{?'"$s"'[^"]*"' <<<"$kt" | wc -l)"
  assert_eq "0" "$vazamento" "nenhuma saída interpola $s"
done
assert_not_contains "$kt" 'approvals.pin}' "nenhuma interpolação do PIN digitado"
assert_not_contains "$kt" 'e.printStackTrace' "sem printStackTrace"

# --- 4. PIN errado: derivado do certo, diferente, e uma única tentativa -------------------------------------
assert_contains "$kt" 'wrongPin != approverPin && wrongPin.length == approverPin.length' \
  "o PIN errado é garantidamente diferente do certo e do mesmo tamanho"
n="$(grep -oE 'approvals\.pin = wrongPin' <<<"$kt" | wc -l)"
assert_eq "1" "$n" "só uma tentativa com PIN errado (cada uma conta para o bloqueio do aprovador)"
n="$(grep -oE 'approvals\.pin = approverPin' <<<"$kt" | wc -l)"
assert_eq "1" "$n" "uma tentativa com o PIN certo"
assert_not_contains "$kt" 'repeat(' "nenhum laço de tentativas"

# --- 5. Escolha determinística e falha com contagens --------------------------------------------------------
n="$(grep -oE 'sortedBy \{ it\.code \}' <<<"$kt" | wc -l)"
assert_eq "2" "$n" "as duas verificações ordenam as hospedadas por código antes de escolher"
assert_contains "$kt" 'UHs candidatas por hospedada=$roomsPerStay' "troca: sem candidata falha com as contagens"
assert_contains "$kt" 'com check-in hoje=${seeds.size}' "desfazer: sem candidata falha com as contagens"

# --- 6. Afirmações fiéis ao brief ---------------------------------------------------------------------------
assert_contains "$kt" 'RoomDisplayStatus.DIRTY' "troca: origem vaga e suja"
assert_contains "$kt" 'RoomDisplayStatus.OCCUPIED' "troca: destino ocupado"
assert_contains "$kt" 'MOVE_TITLE = "Troca de UH"' "histórico: Troca de UH"
assert_contains "$kt" 'move.reason = "GUEST_REQUEST"' "troca com motivo GUEST_REQUEST"
assert_contains "$kt" 'move.tariff = "KEEP"' "troca com tarifa KEEP"
assert_contains "$kt" 'assertEquals(CONFIRMED, runBlocking { source.reservation(row.id) }.reservation.status)' "desfazer: CONFIRMED"
assert_contains "$kt" 'assertFalse(state.done' "desfazer: com PIN errado não conclui"
assert_contains "$kt" 'checkedInOn == today.value.toString()' "desfazer: check-in igual à data operacional"
assert_contains "$kt" 'capture("room-move")' "captura room-move"
assert_contains "$kt" 'capture("undo-check-in")' "captura undo-check-in"

finish
