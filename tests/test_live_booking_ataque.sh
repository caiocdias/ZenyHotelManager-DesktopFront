#!/usr/bin/env bash
# Ataque à Task 11 da M3 E03: a verificação ao vivo do ciclo da reserva (LiveBookingFlowCheck) fica pulada sem o
# opt-in, falha sem credencial, não imprime credencial nem dado de hóspede e tem as asserções que derrubam os erros
# que a etapa teme (criar duas vezes, não mandar If-Match, não recarregar no 412, PDF de outra reserva). Estático: não
# chama o Gradle nem o servidor. Cada trava é provada por mutação numa cópia: a cópia limpa passa e cada mutante falha.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

KT="app/src/test/kotlin/com/zeny/desktop/app/reservations/LiveBookingFlowCheck.kt"
GRADLE="app/build.gradle.kts"
assert_file "$ROOT/$KT" "a verificação ao vivo da E03 existe"
assert_file "$ROOT/$GRADLE" "o build do app existe"

# Só código ativo, numa linha, num formato estável diante do ktlint (o mesmo de test_live_availability_ataque.sh).
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

# violacoes KT GRADLE: imprime uma linha por trava quebrada (nada = tudo certo). KT e GRADLE são código ativo.
violacoes() {
  local kt="$1" gradle="$2" bloco total args
  tem() { [[ "$kt $gradle" == *"$1"* ]] || echo "falta: $2"; }
  nao_tem() { [[ "$kt" != *"$1"* ]] || echo "proibido: $2"; }

  # 1. Opt-in no build e no teste.
  tem 'inputs.property("zenyLiveBooking", providers.environmentVariable("ZENY_LIVE_BOOKING").orElse(""))' \
    "ZENY_LIVE_BOOKING é entrada do tasks.test"
  bloco="$(grep -oE 'outputs\.upToDateWhen \{[^}]*\}' <<<"$gradle")"
  [[ "$bloco" == *'liveBooking.orNull != "1"'* ]] || echo "falta: o upToDateWhen leva ZENY_LIVE_BOOKING"
  tem '@BeforeTest fun requireOptIn() { assumeTrue(System.getenv("ZENY_LIVE_BOOKING") == "1"' \
    "sem ZENY_LIVE_BOOKING=1 cada teste pula (assumeTrue no @BeforeTest)"
  [[ "$(grep -oE 'assumeTrue\(' <<<"$kt" | wc -l)" == 1 ]] || echo "proibido: assumeTrue além do opt-in (pular em vez de falhar)"
  nao_tem 'assumeFalse(' "assumeFalse (pular em vez de falhar)"
  nao_tem 'assumeThat(' "assumeThat (pular em vez de falhar)"
  nao_tem '@Disabled' "teste desligado"
  nao_tem '@Ignore' "teste ignorado"
  nao_tem 'abort(' "abort (pular em vez de falhar)"
  [[ "$(grep -oE '@Test' <<<"$kt" | wc -l)" == 3 ]] || echo "falta: as três verificações"

  # 2. Credenciais: só pelo env(), que falha citando só o nome; nada delas sai.
  for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD ZENY_LIVE_APPROVER_LOGIN ZENY_LIVE_APPROVER_PIN; do
    [[ "$(grep -oF "env(\"$v\")" <<<"$kt" | grep -vc 'getenv')" == 1 ]] || echo "falta: $v lida uma vez só, por env()"
  done
  tem '?: fail("defina $name para a verificação real")' "env() falha citando só o NOME da variável"
  [[ "$(grep -oE 'System\.getenv\(' <<<"$kt" | wc -l)" == 2 ]] || echo "proibido: System.getenv fora do opt-in e do env()"
  tem 'override fun toString(): String = "Approver(***)"' "o toString do Gerente não mostra nada"
  [[ "$(grep -oE 'approver\.pin' <<<"$kt" | wc -l)" == 1 ]] || echo "proibido: o PIN do Gerente usado fora do diálogo"
  tem 'screen.approvals.pin = approver.pin' "o PIN vai só para o diálogo de aprovação"
  for p in '${env(' '$approver' '${approver' 'printStackTrace' 'System.out' 'System.err' ' print(' 'g1Cpf()}'; do
    nao_tem "$p" "saída ou interpolação de $p"
  done
  # Todo println: só literais e templates; nenhum com credencial, documento, titular ou pedidos especiais.
  total="$(grep -oF 'println(' <<<"$kt" | wc -l)"
  args="$(grep -oP 'println\("(?:[^"\\]|\\.)*"(?: \+ "(?:[^"\\]|\\.)*")*\)' <<<"$kt")"
  [[ "$(grep -c . <<<"$args")" == "$total" ]] || echo "proibido: println com argumento que não é texto literal"
  if grep -qiE 'approver|env\(|pin|login|password|holder|cpf|picker|specialRequests|REHEARSAL' <<<"$args"; then
    echo "proibido: println com credencial ou dado de hóspede"
  fi

  # 3. Criada uma vez só (busca do titular e disponibilidade), com o total do servidor.
  tem 'before.size + 1, after.size,' "a busca do G1 tem exatamente uma reserva a mais"
  tem 'assertEquals(setOf(done.reservationId), after - before,' "a reserva a mais é a criada"
  tem 'availableBefore - 1, availableAfter,' "o STD tem exatamente uma unidade a menos"
  tem 'assertEquals(1, found.size,' "a busca pelo código acha uma linha só"
  tem 'assertEquals(ReceptionText.money(offer.total), done.total,' "o total mostrado é o do servidor"
  tem 'assertNull(screen.approvals.pending, "a reserva sem diária' "sem diária negociada não pede aprovação"
  tem 'percent > RECEPTIONIST_CEILING,' "o desconto pedido passa do teto da Recepcionista"
  tem 'assertTrue(night.overridden,' "toda noite ficou com a diária negociada"
  tem 'compareTo(BigDecimal.ONE),' "a diária gravada é a digitada"

  # 4. If-Match e 412.
  tem 'assertNull(second.done,' "a alteração com a versão velha não pode concluir (If-Match)"
  tem 'assertEquals(STALE_NOTICE, second.notice,' "o 412 mostra o aviso"
  tem 'assertEquals(segment, second.segment,' "depois do 412 o segmento volta ao do servidor"
  tem 'second.specialRequests == REHEARSAL_A,' "depois do 412 os pedidos são os da outra alteração"
  tem 'assertNull(second.notice,' "a refeita usa o ETag novo (sem outro 412)"

  # 5. Hospedado: a saída muda, o histórico registra e a saída volta.
  tem 'assertTrue(change.inHouse,' "a reserva abre como hospedada"
  tem 'assertEquals(extended.toApi(), reservation.departure,' "a saída relida é a estendida"
  tem 'historyBefore.size + 1, history.size,' "o histórico ganha uma entrada"
  tem 'assertEquals(original.toApi(), reservation.departure,' "a saída volta à original"

  # 6. A confirmação em PDF é da reserva criada e não grava nada sozinha.
  tem 'reservationId = created.id,' "o PDF pedido é o da reserva criada"
  tem 'kind = PdfKind.CONFIRMATION' "o visualizador é o da confirmação"
  tem 'assertEquals("$CONFIRMATION_TITLE${created.code}", card.title,' "o título cita o código da criada"
  tem 'assertTrue(text.contains(code),' "o texto do PDF traz o código"
  tem 'assertTrue(text.contains(arrival),' "o texto do PDF traz a chegada"
  tem 'assertTrue(text.contains(departure),' "o texto do PDF traz a saída"
  tem 'assertTrue(text.contains(digits),' "o texto do PDF traz o total relido"
  tem 'SaveLocationChooser { null }' "nenhum Salvar como… no ensaio"
}

kt="$(codigo_ativo "$ROOT/$KT")"
gradle="$(codigo_ativo "$ROOT/$GRADLE")"
achado="$(violacoes "$kt" "$gradle")"
assert_eq "" "$achado" "LiveBookingFlowCheck e app/build.gradle.kts cumprem as travas da E03"

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

muta "opt-in removido" 'System.getenv("ZENY_LIVE_BOOKING") == "1"' 'true'
muta "opt-in fora das entradas" 'inputs.property("zenyLiveBooking"' 'inputs.property("zenyLiveOutro"' gradle
muta "opt-in fora do upToDateWhen" 'liveBooking.orNull != "1"' 'true' gradle
muta "assumeThat no lugar de falhar" 'fail("NEED_SEED: nenhum hospedado aceitou' 'assumeThat(false) fail("NEED_SEED: nenhum hospedado aceitou'
muta "assumeFalse no lugar de falhar" 'fail("NEED_SEED: nenhum hospedado aceitou' 'assumeFalse(true) fail("NEED_SEED: nenhum hospedado aceitou'
muta "teste desligado com @Disabled" '@BeforeTest fun requireOptIn()' '@Disabled @BeforeTest fun requireOptIn()'
muta "teste desligado com @Ignore" '@BeforeTest fun requireOptIn()' '@Ignore @BeforeTest fun requireOptIn()'
muta "abort no lugar de falhar" 'fail("NEED_SEED: nenhum hospedado aceitou' 'abort("NEED_SEED: nenhum hospedado aceitou'
muta "senha não exigida" 'env("ZENY_LIVE_VIEWER_PASSWORD")' '"x"'
muta "PIN não exigido" 'env("ZENY_LIVE_APPROVER_PIN")' '"1234"'
muta "PIN impresso" 'runOnUiThread { screen.approvals.confirm() }' \
  'println("pin ${approver.pin}") runOnUiThread { screen.approvals.confirm() }'
muta "Gerente no toString" '"Approver(***)"' '"Approver($login)"'
muta "titular impresso" 'println("LiveBookingFlowCheck: desconto' 'println("titular $holderId; desconto'
muta "pula em vez de falhar" 'fail("NEED_SEED: nenhum hospedado aceitou' 'assumeTrue(false, "NEED_SEED: nenhum hospedado aceitou'
muta "criação dupla aceita" 'before.size + 1, after.size,' 'after.size, after.size,'
muta "unidade a menos não conferida" 'availableBefore - 1, availableAfter,' 'availableAfter, availableAfter,'
muta "If-Match não conferido" 'assertNull(second.done,' 'assertTrue(true || second.done == null,'
muta "412 sem recarga conferida" 'assertEquals(segment, second.segment,' 'assertEquals(second.segment, second.segment,'
muta "ETag novo não conferido" 'assertNull(second.notice,' 'assertTrue(true || second.notice == null,'
muta "PDF de outra reserva" 'reservationId = created.id,' 'reservationId = otherId,'
muta "código do PDF não conferido" 'assertTrue(text.contains(code),' 'assertTrue(true,'
muta "diária gravada não conferida" 'compareTo(BigDecimal.ONE),' 'compareTo(BigDecimal(night.roomAmount)),'

finish
