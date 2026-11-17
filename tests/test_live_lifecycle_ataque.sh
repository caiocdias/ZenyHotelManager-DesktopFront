#!/usr/bin/env bash
# Ataque à Task 13 da M3 E04: a verificação ao vivo do cancelamento, da reativação e das pendências
# (LiveReservationLifecycleCheck) fica pulada sem o opt-in, falha sem credencial, não imprime credencial nem dado de
# hóspede, tem as asserções que provam os três critérios de aceite e a limpeza só cancela ensaio marcado, nunca uma
# reserva do seed. Estático: não chama o Gradle nem o servidor. Cada trava é provada por mutação numa cópia: a cópia
# limpa passa e cada mutante falha.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

KT="app/src/test/kotlin/com/zeny/desktop/app/reservations/LiveReservationLifecycleCheck.kt"
GRADLE="app/build.gradle.kts"
assert_file "$ROOT/$KT" "a verificação ao vivo da E04 existe"
assert_file "$ROOT/$GRADLE" "o build do app existe"

# Só código ativo, numa linha, num formato estável diante do ktlint (o mesmo de test_live_booking_ataque.sh).
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

# antes KT A B: A aparece no código e antes da primeira ocorrência de B depois dele.
antes() {
  local resto="${1#*"$2"}"
  [[ "$1" == *"$2"* && "$resto" == *"$3"* ]]
}

# violacoes KT GRADLE: imprime uma linha por trava quebrada (nada = tudo certo). KT e GRADLE são código ativo.
violacoes() {
  local kt="$1" gradle="$2" bloco total args cancel_fn cleanup_fn
  tem() { [[ "$kt $gradle" == *"$1"* ]] || echo "falta: $2"; }
  nao_tem() { [[ "$kt" != *"$1"* ]] || echo "proibido: $2"; }

  # 1. Opt-in no build e no teste; pular só pelo opt-in.
  tem 'inputs.property("zenyLiveLifecycle", providers.environmentVariable("ZENY_LIVE_LIFECYCLE").orElse(""))' \
    "ZENY_LIVE_LIFECYCLE é entrada do tasks.test"
  bloco="$(grep -oE 'outputs\.upToDateWhen \{[^}]*\}' <<<"$gradle")"
  [[ "$bloco" == *'liveLifecycle.orNull != "1"'* ]] || echo "falta: o upToDateWhen leva ZENY_LIVE_LIFECYCLE"
  tem 'val liveLifecycle = providers.environmentVariable("ZENY_LIVE_LIFECYCLE")' "o upToDateWhen lê ZENY_LIVE_LIFECYCLE"
  tem '@BeforeTest fun requireOptIn() { assumeTrue(System.getenv("ZENY_LIVE_LIFECYCLE") == "1"' \
    "sem ZENY_LIVE_LIFECYCLE=1 cada teste pula (assumeTrue no @BeforeTest)"
  [[ "$(grep -oE 'assumeTrue\(' <<<"$kt" | wc -l)" == 1 ]] || echo "proibido: assumeTrue além do opt-in (pular em vez de falhar)"
  nao_tem 'assumeFalse(' "assumeFalse (pular em vez de falhar)"
  nao_tem 'assumeThat(' "assumeThat (pular em vez de falhar)"
  nao_tem '@Disabled' "teste desligado"
  nao_tem '@Ignore' "teste desligado"
  [[ "$(grep -oE '@Test' <<<"$kt" | wc -l)" == 3 ]] || echo "falta: as três verificações"
  tem '@Test fun cancelAndReactivate()' "critérios 1 e 2"
  tem '@Test fun pendingListsLoad()' "critério 3"
  tem 'fun rehearsalCleanup()' "a limpeza dos ensaios"

  # 2. Credenciais: só pelo env(), que falha citando só o nome; nada delas sai.
  for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD ZENY_LIVE_APPROVER_LOGIN ZENY_LIVE_APPROVER_PIN; do
    [[ "$(grep -oF "env(\"$v\")" <<<"$kt" | grep -vc 'getenv')" == 1 ]] || echo "falta: $v lida uma vez só, por env()"
  done
  tem '?: fail("defina $name para a verificação real")' "env() falha citando só o NOME da variável"
  [[ "$(grep -oE 'System\.getenv\(' <<<"$kt" | wc -l)" == 2 ]] || echo "proibido: System.getenv fora do opt-in e do env()"
  tem 'override fun toString(): String = "Approver(***)"' "o toString do Gerente não mostra nada"
  [[ "$(grep -oE 'approver\.pin' <<<"$kt" | wc -l)" == 1 ]] || echo "proibido: o PIN do Gerente usado fora do diálogo"
  tem 'screen.approvals.pin = approver.pin' "o PIN vai só para o diálogo de aprovação"
  for p in '${env(' '$approver' '${approver' 'printStackTrace' 'System.out' 'System.err' ' print(' 'g1Cpf()}' \
    '${requestsOf' '${state.notes' '${reservation.specialRequests'; do
    nao_tem "$p" "saída ou interpolação de $p"
  done
  # Todo println: só literais e templates; nenhum com credencial, documento, titular, observação ou pedidos.
  total="$(grep -oF 'println(' <<<"$kt" | wc -l)"
  args="$(grep -oP 'println\("(?:[^"\\]|\\.)*"(?: \+ "(?:[^"\\]|\\.)*")*\)' <<<"$kt")"
  [[ "$(grep -c . <<<"$args")" == "$total" ]] || echo "proibido: println com argumento que não é texto literal"
  if grep -qiE 'approver|env\(|pin\b|\.pin|login|password|holder|cpf|picker|specialRequests|notes|REHEARSAL|requestsOf' <<<"$args"; then
    echo "proibido: println com credencial ou dado de hóspede"
  fi

  # 3. Critério 1: os termos antes de confirmar (com a captura) e a multa do servidor, lançada e sem isenção.
  cancel_fn="${kt#*fun DesktopComposeUiTest.cancelWithPenalty(}"
  cancel_fn="${cancel_fn%%fun ComposeUiTest.reactivateWithApproval(*}"
  antes "$cancel_fn" 'terms.contains(ONE_NIGHT_PENALTY),' 'confirmCancel(' ||
    echo "falta: os termos com a multa conferidos antes de confirmar"
  antes "$cancel_fn" 'capture("reservation-cancel", "cancel-dialog")' 'confirmCancel(' ||
    echo "falta: a captura do diálogo antes de confirmar"
  tem 'private const val ONE_NIGHT_PENALTY = "multa de 1 diária(s)"' "os termos esperados são os da política do BALCAO"
  tem 'assertFalse(state.waivePenalty, "o cancelamento do ensaio não pode isentar a multa")' "o ensaio não isenta"
  tem 'assertEquals(CANCELLED, reservation.status, "a situação relida de ${created.code} depois do cancelamento")' \
    "a situação relida é CANCELLED"
  tem 'amount.signum() > 0,' "a multa do servidor é maior que zero"
  tem 'assertFalse(penalty.waived,' "a multa não veio isenta"
  tem '(penalty.postingId as? Option.Some)?.value != null,' "a multa foi lançada na conta"
  tem 'val expected = "$PENALTY_POSTED${ReceptionText.money(penalty.amount)}."' "o texto esperado sai do valor do servidor"
  tem 'assertTrue(done.contains(expected),' "o resumo traz a multa lançada"
  tem 'private const val PENALTY_POSTED = "Multa lançada na conta: "' "o texto da multa lançada"

  # 4. Critério 2: a reativação pede o Gerente e volta a CONFIRMED.
  tem 'assertEquals(REACTIVATE_PERMISSION, required.permission,' "a aprovação pedida é a da reativação"
  tem 'private const val REACTIVATE_PERMISSION = "reservations.reservations.reactivate"' "a permissão da reativação"
  tem 'screen.approvals.pending ?: fail(' "sem pedido de aprovação, falha"
  tem 'assertEquals("Reserva ${created.code} reativada.", state.done,' "o resumo da reativação"
  tem 'assertEquals(CONFIRMED, reservation.status, "a situação relida de ${created.code} depois da reativação")' \
    "a situação relida é CONFIRMED"
  tem 'rows.firstOrNull { it.code == created.code } ?: fail(' "a reativada está nos no-shows previstos"

  # 5. Critério 3: as listas carregam sem erro, cada pendência do seed está lá, uma vez só.
  # Duas conferências: a de cada lista carregada (loadAll) e a da realocação esperada (awaitReallocation).
  [[ "$(grep -oF 'assertNull(table.error, "a lista \"$label\" falhou: ${table.error?.userMessage}")' <<<"$kt" |
    wc -l)" == 2 ]] || echo "falta: nenhuma lista termina com erro (no loadAll e na realocação)"
  tem 'PendingList.entries.associateWith { loadAll(screen, pending.table(it), it.label).size }' "as quatro listas carregam"
  tem 'guarantees.firstOrNull { it.code == preBooking.code } ?: fail(' "a pré-reserva do seed está nas garantias"
  tem 'assertEquals(REALLOCATED_DETAIL, reallocation.detail,' "a R1 aparece na realocação"
  tem 'assertEquals(1, found.size,' "o seed não duplicou: exatamente uma de cada"
  tem 'private const val PRE_BOOKING = "DEMO-PEND-P1"' "a pré-reserva do seed"
  tem 'private const val REALLOCATED = "DEMO-PEND-R1"' "a reserva realocada do seed"

  # 6. Limpeza: só ensaio confirmado com a marca, fora do seed, sem isenção; e o ensaio do próprio teste.
  tem 'private const val REHEARSAL = "ENSAIO E04"' "a marca do ensaio desta etapa"
  tem 'private const val REHEARSAL_MARK = "ENSAIO E0"' "a marca que a limpeza procura"
  tem 'private const val SEED_PREFIX = "DEMO-"' "o prefixo das reservas do seed"
  tem 'runOnUiThread { state.specialRequests = REHEARSAL }' "o ensaio nasce marcado"
  cleanup_fn="${kt#*fun rehearsalCleanup()}"
  cleanup_fn="${cleanup_fn%%fun DesktopComposeUiTest.createToday(*}"
  [[ "$cleanup_fn" == *'val seed = before.filter { externalId(it)?.startsWith(SEED_PREFIX) == true }'* ]] ||
    echo "falta: a limpeza separa as reservas do seed"
  [[ "$cleanup_fn" == *'val candidates = before - seed.toSet()'* ]] || echo "falta: o seed fica fora dos candidatos"
  [[ "$cleanup_fn" == *'if (reservation.status == CONFIRMED && requestsOf(reservation).startsWith(REHEARSAL_MARK))'* ]] ||
    echo "falta: só cancela confirmada com a marca"
  [[ "$cleanup_fn" == *'touched.isEmpty(),'* ]] || echo "falta: nenhuma sem a marca saiu das confirmadas"
  [[ "$cleanup_fn" == *'assertFalse(externalId(row)?.startsWith(SEED_PREFIX) == true, "o cancelado $code é do seed")'* ]] ||
    echo "falta: nenhum cancelado é do seed"
  tem 'ports.search.searchByDocument(query, setOf(CONFIRMED), cursor, PAGE_LIMIT)' "a lista do G1 é só de confirmadas"
  tem 'runOnUiThread { state.reason = OTHER }' "a limpeza cancela com OTHER"
  tem 'assertFalse(state.waivePenalty, "o cancelamento de ${rehearsal.code} não pode isentar a multa")' \
    "a limpeza não isenta"
  tem 'if (status == CONFIRMED) { cancelRehearsal(screen, ports, created)' \
    "o finally só cancela o ensaio do próprio teste se ainda confirmado"
  tem 'cleanup.exceptionOrNull()?.let(failure::addSuppressed)' "a falha da limpeza entra como suprimida"
}

kt="$(codigo_ativo "$ROOT/$KT")"
gradle="$(codigo_ativo "$ROOT/$GRADLE")"
achado="$(violacoes "$kt" "$gradle")"
assert_eq "" "$achado" "LiveReservationLifecycleCheck e app/build.gradle.kts cumprem as travas da E04"

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

muta "opt-in removido" 'System.getenv("ZENY_LIVE_LIFECYCLE") == "1"' 'true'
muta "opt-in fora das entradas" 'inputs.property("zenyLiveLifecycle"' 'inputs.property("zenyLiveOutro"' gradle
muta "opt-in fora do upToDateWhen" 'liveLifecycle.orNull != "1"' 'true' gradle
muta "senha não exigida" 'env("ZENY_LIVE_VIEWER_PASSWORD")' '"x"'
muta "PIN não exigido" 'env("ZENY_LIVE_APPROVER_PIN")' '"1234"'
muta "PIN impresso" 'runOnUiThread { screen.approvals.confirm() }' \
  'println("pin ${approver.pin}") runOnUiThread { screen.approvals.confirm() }'
muta "Gerente no toString" '"Approver(***)"' '"Approver($login)"'
muta "pedidos impressos" 'println("LiveReservationLifecycleCheck: limpeza: ' \
  'println("LiveReservationLifecycleCheck: ${requestsOf(reservation)} limpeza: '
muta "pula em vez de falhar" 'fail("NEED_SEED: o CPF fictício do G1' 'assumeTrue(false, "NEED_SEED: o CPF fictício do G1'
muta "termos conferidos só depois" 'terms.contains(ONE_NIGHT_PENALTY),' 'true,'
muta "multa zero aceita" 'amount.signum() > 0,' 'amount.signum() >= 0,'
muta "multa isenta aceita" 'assertFalse(penalty.waived,' 'assertTrue(true || penalty.waived,'
muta "multa sem lançamento aceita" '(penalty.postingId as? Option.Some)?.value != null,' 'true,'
muta "resumo da multa não conferido" 'assertTrue(done.contains(expected),' 'assertTrue(true,'
muta "texto esperado calculado no front" 'val expected = "$PENALTY_POSTED${ReceptionText.money(penalty.amount)}."' \
  'val expected = "$PENALTY_POSTED"'
muta "reativação sem aprovação aceita" 'assertEquals(REACTIVATE_PERMISSION, required.permission,' 'assertTrue(true,'
muta "reativada não relida" \
  'assertEquals(CONFIRMED, reservation.status, "a situação relida de ${created.code} depois da reativação")' \
  'assertTrue(true, "a situação relida de ${created.code} depois da reativação")'
muta "lista com erro aceita" 'assertNull(table.error, "a lista \"$label\" falhou: ${table.error?.userMessage}")' \
  'assertTrue(true, "a lista \"$label\" falhou: ${table.error?.userMessage}")'
muta "seed duplicado aceito" 'assertEquals(1, found.size,' 'assertEquals(found.size, found.size,'
muta "limpeza toca o seed" 'val candidates = before - seed.toSet()' 'val candidates = before'
muta "limpeza sem marca" \
  'if (reservation.status == CONFIRMED && requestsOf(reservation).startsWith(REHEARSAL_MARK))' \
  'if (reservation.status == CONFIRMED)'
muta "marca vazia" 'private const val REHEARSAL_MARK = "ENSAIO E0"' 'private const val REHEARSAL_MARK = ""'
muta "prefixo do seed errado" 'private const val SEED_PREFIX = "DEMO-"' 'private const val SEED_PREFIX = "DEMO-SEED-"'
muta "limpeza isenta" 'runOnUiThread { state.reason = OTHER }' 'runOnUiThread { state.reason = OTHER; state.waivePenalty = true }'
muta "finally cancela sempre" 'if (status == CONFIRMED) { cancelRehearsal(screen, ports, created)' \
  'if (true) { cancelRehearsal(screen, ports, created)'

finish
