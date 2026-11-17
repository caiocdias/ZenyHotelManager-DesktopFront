#!/usr/bin/env bash
# Ataque à Task 5 da M3 E05 (testador de etapa): a verificação ao vivo da confirmação da pré-reserva
# (LivePreReservationConfirmCheck) fica pulada sem o opt-in, que é estado da tarefa de teste; falha sem credencial,
# não imprime credencial nem dado de hóspede; acha a pré-reserva do seed pelo externalId que o PendingSeeder usa e
# falha (não pula) sem ela; e tem as seis asserções do plano, com os textos que a tela de fato mostra. Estático: não
# chama o Gradle nem o servidor. Cada trava é provada por mutação: a cópia limpa passa e cada mutante falha.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

KT="app/src/test/kotlin/com/zeny/desktop/app/reservations/LivePreReservationConfirmCheck.kt"
GRADLE="app/build.gradle.kts"
PENDING_KT="demo-seed/src/main/kotlin/com/zeny/desktop/seed/pending/PendingSeeder.kt"
BOOKING_TEXT="app/src/main/kotlin/com/zeny/desktop/app/reservations/BookingText.kt"
CONFIRM_STATE="app/src/main/kotlin/com/zeny/desktop/app/reservations/ReservationConfirmState.kt"
for f in "$KT" "$GRADLE" "$PENDING_KT" "$BOOKING_TEXT" "$CONFIRM_STATE"; do
  [[ -s "$ROOT/$f" ]] || { _assert_fail "arquivo lido pelo ataque existe: $f"; finish; }
done

# Só código ativo, numa linha, num formato estável diante do ktlint (o mesmo de test_live_lifecycle_ataque.sh).
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

# antes TEXTO A B: A aparece no texto e B aparece depois dele.
antes() {
  local resto="${1#*"$2"}"
  [[ "$1" == *"$2"* && "$resto" == *"$3"* ]]
}

# Os fatos de fora da verificação, que ela precisa repetir igual: o externalId da pré-reserva no seed, o texto dos
# termos da garantia e o resumo da confirmação na tela.
seed_id="$(grep -oE 'const val PRE_BOOKING_ID = "[^"]+"' "$ROOT/$PENDING_KT" | sed -E 's/.*"([^"]+)"/\1/')"
card_label="$(grep -oE '"CARD" to "[^"]+"' "$ROOT/$BOOKING_TEXT" | head -1 | sed -E 's/.* to "([^"]+)"/\1/')"
[[ -n "$seed_id" && -n "$card_label" ]] || _assert_fail "li o externalId da pré-reserva e o rótulo do cartão" "$seed_id / $card_label"
assert_contains "$(cat "$ROOT/$BOOKING_TEXT")" 'return "Garantia exigida: $label$detail"' "código: os termos da garantia exigida"
assert_contains "$(cat "$ROOT/$CONFIRM_STATE")" 'done = "Reserva ${response.reservation.code} confirmada."' "código: o resumo da confirmação"

# violacoes KT GRADLE: imprime uma linha por trava quebrada (nada = tudo certo). KT e GRADLE são código ativo.
violacoes() {
  local kt="$1" gradle="$2" bloco total args main_fn
  tem() { [[ "$kt $gradle" == *"$1"* ]] || echo "falta: $2"; }
  nao_tem() { [[ "$kt" != *"$1"* ]] || echo "proibido: $2"; }

  # 1. Opt-in no build e no teste; pular só pelo opt-in.
  tem 'inputs.property("zenyLivePrereservation", providers.environmentVariable("ZENY_LIVE_PRERESERVATION").orElse(""))' \
    "ZENY_LIVE_PRERESERVATION é entrada do tasks.test"
  tem 'val livePrereservation = providers.environmentVariable("ZENY_LIVE_PRERESERVATION")' "o upToDateWhen lê o opt-in"
  bloco="$(grep -oE 'outputs\.upToDateWhen \{[^}]*\}' <<<"$gradle")"
  [[ "$bloco" == *'livePrereservation.orNull != "1"'* ]] || echo "falta: o upToDateWhen leva ZENY_LIVE_PRERESERVATION"
  tem '@BeforeTest fun requireOptIn() { assumeTrue(System.getenv("ZENY_LIVE_PRERESERVATION") == "1",' \
    "sem ZENY_LIVE_PRERESERVATION=1 o teste pula (assumeTrue no @BeforeTest)"
  [[ "$(grep -oE 'assumeTrue\(' <<<"$kt" | wc -l)" == 1 ]] || echo "proibido: assumeTrue além do opt-in (pular em vez de falhar)"
  for p in 'assumeFalse(' 'assumeThat(' '@Disabled' '@Ignore' 'abort('; do nao_tem "$p" "pular ou desligar ($p)"; done
  [[ "$(grep -oE '@Test' <<<"$kt" | wc -l)" == 1 ]] || echo "falta: uma verificação só"
  tem '@Test fun confirmingProvisionalLeavesPendingGuarantees()' "o nome do plano"

  # 2. Credenciais: só pelo env(), que falha citando só o nome; nada delas sai; a sessão sai no fim.
  for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD; do
    [[ "$(grep -oF "env(\"$v\")" <<<"$kt" | wc -l)" == 1 ]] || echo "falta: $v lida uma vez só, por env()"
  done
  tem '?: fail("defina $name para a verificação real")' "env() falha citando só o NOME da variável"
  [[ "$(grep -oE 'System\.getenv\(' <<<"$kt" | wc -l)" == 2 ]] || echo "proibido: System.getenv fora do opt-in e do env()"
  for p in '${env(' 'printStackTrace' 'System.out' 'System.err' ' print(' 'holderName' 'document'; do
    nao_tem "$p" "saída ou interpolação de $p"
  done
  total="$(grep -oF 'println(' <<<"$kt" | wc -l)"
  args="$(grep -oP 'println\("(?:[^"\\]|\\.)*"(?: \+ "(?:[^"\\]|\\.)*")*\)' <<<"$kt")"
  [[ "$(grep -c . <<<"$args")" == "$total" ]] || echo "proibido: println com argumento que não é texto literal"
  if grep -qiE 'env\(|login|password|holder|cpf|guest|\$\{?it\b|row\.' <<<"$args"; then
    echo "proibido: println com credencial ou dado de hóspede"
  fi
  tem 'runCatching { runBlocking { connection.logout() } } connection.close()' "a sessão sai e fecha no finally"
  tem 'assertIs<SessionState.Active>(connection.state.value, "a sessão da Recepcionista caiu")' "a sessão não caiu no meio"

  # 3. A pré-reserva do seed, pelo externalId do PendingSeeder: uma só e PROVISIONAL; senão falha (NEED_SEED).
  tem "private const val PRE_BOOKING = \"$seed_id\"" "o externalId é o do PendingSeeder ($seed_id)"
  tem 'ports.search.search(ReservationFilter(externalId = PRE_BOOKING), cursor, PAGE_LIMIT)' "a busca pelo externalId"
  tem '} while (cursor != null)' "todas as páginas da busca"
  tem 'if (found.isEmpty()) fail("$NO_PRE_BOOKING' "sem a pré-reserva, falha"
  tem 'assertEquals(1, found.size,' "exatamente uma (o seed não duplicou)"
  tem 'if (preBooking.status != PROVISIONAL) { fail(' "fora de PROVISIONAL, falha"
  tem '"NEED_SEED: recrie a demo; a pré-reserva do seed vale 48 h e é consumida por esta verificação"' "a mensagem do plano"

  # 4. As seis etapas, na ordem: nas garantias, a confirmação com os termos e o CARD, confirma, relê, sai da lista.
  main_fn="${kt#*fun confirmingProvisionalLeavesPendingGuarantees()}"
  main_fn="${main_fn%%private fun withScreen(*}"
  antes "$main_fn" 'val before = pendingGuaranteeRows(scope, ports)' 'before.firstOrNull { it.code == preBooking.code } ?: fail(' ||
    echo "falta: a pré-reserva conferida nas garantias antes de confirmar"
  antes "$main_fn" 'before.firstOrNull { it.code == preBooking.code }' 'openConfirmation(scope, ports, id)' ||
    echo "falta: a lista conferida antes da confirmação"
  antes "$main_fn" 'confirm(state, preBooking.code)' 'assertConfirmedWithGuarantee(ports, preBooking)' ||
    echo "falta: a reserva relida depois de confirmar"
  antes "$main_fn" 'assertConfirmedWithGuarantee(ports, preBooking)' 'val after = pendingGuaranteeRows(scope, ports)' ||
    echo "falta: a lista recarregada depois de reler"
  tem 'assertFalse(after.any { it.code == preBooking.code },' "a pré-reserva sai das garantias"
  tem 'assertEquals(id, listed.reservationId,' "a linha das garantias é a da pré-reserva"
  tem "private const val CARD_TERMS = \"Garantia exigida: $card_label\"" "os termos esperados são os da tela (Garantia exigida: $card_label)"
  tem 'assertTrue(terms.contains(CARD_TERMS),' "os termos da confirmação trazem o cartão"
  tem 'assertEquals(CARD, state.guarantee, "a garantia pré-escolhida da confirmação")' "a garantia nasce CARD"
  tem 'private const val CARD = "CARD"' "o meio CARD"
  tem 'assertEquals("Reserva $expectedCode confirmada.", state.done,' "o resumo é o da tela"
  tem 'assertNull(state.error, "a confirmação da $expectedCode falhou: ${state.error}")' "a confirmação não falhou"
  tem 'assertEquals(CONFIRMED, reservation.status,' "a situação relida é CONFIRMED"
  tem 'assertEquals(CARD, guarantee.method,' "a garantia relida é CARD"
  tem '(reservation.guarantee as? Option.Some)?.value,' "a garantia relida existe"
  [[ "$(grep -oF 'assertNull(table.error, "a lista \"$label\" falhou: ${table.error?.userMessage}")' <<<"$kt" | wc -l)" == 1 ]] ||
    echo "falta: a lista de garantias não termina com erro"
  tem '!table.hasMore -> { true }' "todas as páginas das garantias"
  tem 'scope.launch { table.loadMore() }' "pede a página seguinte das garantias"

  # 5. Só a confirmação escreve: nada de criar, cancelar, reativar ou alterar reserva.
  for p in 'createReservation' 'cancel(' 'reactivate' 'ReservationChangeState' 'ReservationCancelState' 'NewReservationState'; do
    nao_tem "$p" "escrita além da confirmação ($p)"
  done
  [[ "$(grep -oF 'state.confirm()' <<<"$kt" | wc -l)" == 1 ]] || echo "proibido: mais de uma confirmação"
}

kt="$(codigo_ativo "$ROOT/$KT")"
gradle="$(codigo_ativo "$ROOT/$GRADLE")"
achado="$(violacoes "$kt" "$gradle")"
assert_eq "" "$achado" "LivePreReservationConfirmCheck e app/build.gradle.kts cumprem as travas da Task 5"

# muta DESCRIÇÃO ANTIGO NOVO [gradle]: o mutante (primeira ocorrência trocada) tem de quebrar alguma trava.
muta() {
  local m_kt="$kt" m_gradle="$gradle"
  if [[ "${4:-}" == gradle ]]; then
    [[ "$m_gradle" == *"$2"* ]] || { _assert_fail "mutação sem alvo: $1" "trecho: $2"; return; }
    m_gradle="${m_gradle/"$2"/"$3"}"
  else
    [[ "$m_kt" == *"$2"* ]] || { _assert_fail "mutação sem alvo: $1" "trecho: $2"; return; }
    m_kt="${m_kt/"$2"/"$3"}"
  fi
  [[ -n "$(violacoes "$m_kt" "$m_gradle")" ]] || _assert_fail "mutante não detectado: $1"
}

muta "opt-in removido" 'System.getenv("ZENY_LIVE_PRERESERVATION") == "1"' 'true'
muta "opt-in fora das entradas" 'inputs.property("zenyLivePrereservation"' 'inputs.property("zenyLiveOutro"' gradle
muta "opt-in fora do upToDateWhen" 'livePrereservation.orNull != "1"' 'true' gradle
muta "senha não exigida" 'env("ZENY_LIVE_VIEWER_PASSWORD")' '"x"'
muta "login impresso" 'runBlocking { connection.login(' 'println("login ${env("ZENY_LIVE_VIEWER_LOGIN")}") runBlocking { connection.login('
muta "linha da lista impressa" 'println("LivePreReservationConfirmCheck: ${preBooking.code}' 'println("LivePreReservationConfirmCheck: ${listed.holder}'
muta "sem logout" 'runCatching { runBlocking { connection.logout() } }' 'runCatching { }'
muta "externalId errado" "private const val PRE_BOOKING = \"$seed_id\"" 'private const val PRE_BOOKING = "DEMO-SEED-P1"'
muta "pula sem a pré-reserva" 'if (found.isEmpty()) fail("$NO_PRE_BOOKING' 'if (found.isEmpty()) assumeTrue(false, "$NO_PRE_BOOKING'
muta "duplicada aceita" 'assertEquals(1, found.size,' 'assertEquals(found.size, found.size,'
muta "confirmada aceita como pré-reserva" 'if (preBooking.status != PROVISIONAL) { fail(' 'if (false) { fail('
muta "não confere as garantias antes" 'before.firstOrNull { it.code == preBooking.code } ?: fail(' 'before.firstOrNull { it.code == preBooking.code } ?: return@withScreen('
muta "termos não conferidos" 'assertTrue(terms.contains(CARD_TERMS),' 'assertTrue(true,'
muta "termos com outro texto" "private const val CARD_TERMS = \"Garantia exigida: $card_label\"" 'private const val CARD_TERMS = "Garantia: cartão"'
muta "garantia pré-escolhida não conferida" 'assertEquals(CARD, state.guarantee, "a garantia pré-escolhida da confirmação")' 'assertTrue(true, "a garantia pré-escolhida da confirmação")'
muta "resumo não conferido" 'assertEquals("Reserva $expectedCode confirmada.", state.done,' 'assertTrue(state.done != null,'
muta "situação relida não conferida" 'assertEquals(CONFIRMED, reservation.status,' 'assertTrue(true,'
muta "garantia relida não conferida" 'assertEquals(CARD, guarantee.method,' 'assertTrue(true,'
muta "continua nas garantias aceito" 'assertFalse(after.any { it.code == preBooking.code },' 'assertFalse(false,'
muta "lista lida antes de reler" 'assertConfirmedWithGuarantee(ports, preBooking) val after' 'val after'
muta "só a primeira página" 'scope.launch { table.loadMore() }' 'Unit'
muta "lista com erro aceita" 'assertNull(table.error, "a lista \"$label\" falhou: ${table.error?.userMessage}")' 'assertTrue(true)'
muta "escreve além de confirmar" 'runOnUiThread { state.confirm() }' 'runOnUiThread { state.confirm() } runBlocking { ports.confirm.cancel(id) }'

finish
