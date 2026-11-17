#!/usr/bin/env bash
# Ataque à Task 7 da M3 E05 (testador de etapa): as verificações ao vivo que sustentam o roteiro do M3
# (LiveReservationSearchCheck: 19.1, 19.2 e 25; LiveBookingFlowCheck: 23.1; LiveReservationLifecycleCheck: 26). Os
# fatos que elas repetem como literais têm de ser os do código: os externalId e as situações do seed, os rótulos do
# ReceptionText, a base do CPF do Hóspede Doze, o período da C1, o separador dos alertas do statusText, as datas do
# roteiro e o texto do total novo. As duas novas da busca só leem; o opt-in vem antes de qualquer trabalho; nenhuma
# credencial, CPF inteiro ou nome sai em println ou mensagem; o total novo é o do servidor e o da tela, sem conta de
# preço no teste. Estático: não chama o Gradle nem o servidor. Cada trava é provada por mutação.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

D="app/src/test/kotlin/com/zeny/desktop/app/reservations"
SEARCH="$D/LiveReservationSearchCheck.kt"
BOOKING="$D/LiveBookingFlowCheck.kt"
LIFECYCLE="$D/LiveReservationLifecycleCheck.kt"
SEED="demo-seed/src/main/kotlin/com/zeny/desktop/seed"
APP="app/src/main/kotlin/com/zeny/desktop/app"
for f in "$SEARCH" "$BOOKING" "$LIFECYCLE" "$SEED/pending/PendingSeeder.kt" "$SEED/pending/FutureSeeder.kt" \
  "$SEED/data/DemoCatalog.kt" "$APP/reception/ReceptionText.kt" "$APP/reception/ReservationRows.kt" \
  "$APP/reservations/ReservationChangeState.kt" "$APP/reservations/ReservationSearchText.kt" "docs/demo/roteiro-m3.md"; do
  [[ -s "$ROOT/$f" ]] || { _assert_fail "arquivo lido pelo ataque existe: $f"; finish; }
done

codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}
antes() {
  local resto="${1#*"$2"}"
  [[ "$1" == *"$2"* && "$resto" == *"$3"* ]]
}
# corpo TEXTO INÍCIO FIM : o trecho do texto do INÍCIO até antes do FIM.
corpo() {
  local t="${1#*"$2"}"
  [[ "$1" == *"$2"* ]] || return 0
  printf '%s' "${t%%"$3"*}"
}

# --- Os fatos do código que as verificações repetem ------------------------------------------------------------
const_of() { grep -oE "const val $2 = \"?[A-Za-z0-9_ -]+\"?L?" "$ROOT/$1" | sed -E 's/.* = "?//; s/"?L?$//'; }
label_of() { grep -oE "\"$1\" to \"[^\"]+\"" "$ROOT/$APP/reception/ReceptionText.kt" | head -1 | sed -E 's/.* to "([^"]+)"/\1/'; }
plans="$(grep -oE 'Plan\("DEMO-FUT-[A-Z0-9]+", "[A-Z]+", [0-9]+, [0-9]+, "[A-Z_]+", cancelAfterCreate = (true|false)\)' "$ROOT/$SEED/pending/FutureSeeder.kt" |
  sed -E 's/Plan\("([^"]+)", "[A-Z]+", ([0-9]+), ([0-9]+), "[A-Z_]+", cancelAfterCreate = (true|false)\)/\1 \4 \2 \3/')"
# externalId situação-esperada logo depois do seed
expected_status="$(awk '{print $1, ($2 == "true" ? "CANCELLED" : "CONFIRMED")}' <<<"$plans"
  echo "$(const_of "$SEED/pending/PendingSeeder.kt" PRE_BOOKING_ID) PROVISIONAL"
  echo "$(const_of "$SEED/pending/PendingSeeder.kt" RESERVATION_ID) CONFIRMED")"
# Os pares "externalId" to "rótulo" esperados, um por linha, em ordem (o mapa é comparado como conjunto).
want_pairs="$(while read -r id st; do printf '"%s" to "%s"\n' "$id" "$(label_of "$st")"; done <<<"$expected_status" | sort)"
holder_n="$(const_of "$SEED/pending/FutureSeeder.kt" HOLDER_KEY | tr -d 'G')"
cpf_prefix="$(grep -oE 'FictitiousDocuments\.cpf\("[0-9]+' "$ROOT/$SEED/data/DemoCatalog.kt" | grep -oE '[0-9]+$')"
g12_base="$cpf_prefix$(printf '%02d' "$holder_n")"
read -r _ _ c1_a c1_d <<<"$(grep -E ' true ' <<<"$plans")"
in_house="$(label_of IN_HOUSE)"
cancelled_label="$(label_of CANCELLED)"
rows_fn="$(awk '/fun ReservationListItemResponse\.statusText\(\)/{on = 1} on {print} on && /joinToString/ {exit}' "$ROOT/$APP/reception/ReservationRows.kt")"
sep="$(grep -oE 'joinToString\("[^"]+"\)' <<<"$rows_fn" | sed -E 's/joinToString\("([^"]+)"\)/\1/')"
masked_prefix="$(label_of CPF) $(grep -oE 'HIDDEN = "[^"]+"' "$ROOT/$APP/reservations/ReservationSearchText.kt" | sed -E 's/.*"([^"]+)"/\1/')"
visible="$(grep -oE 'VISIBLE_DOCUMENT_CHARS = [0-9]+' "$ROOT/$APP/reservations/ReservationSearchText.kt" | grep -oE '[0-9]+$')"
change_done="$(grep -oE 'done = "Reserva \$\{reservation\.code\} alterada\. Novo total: \$\{ReceptionText\.money\(reservation\.total\)\}\."' "$ROOT/$APP/reservations/ReservationChangeState.kt")"
rot23="$(awk '/^\*\*23\.1\. /{on = 1} /^\*\*23\.2\. /{exit} on' "$ROOT/docs/demo/roteiro-m3.md")"
rot21="$(awk '/^## 20\. /{on = 1} /^## 22\. /{exit} on' "$ROOT/docs/demo/roteiro-m3.md")"
rot_change_day="$(grep -oE 'pela data operacional mais [0-9]+ dias' <<<"$rot23" | grep -oE '[0-9]+')"
rot_arrival="$(grep -oE 'em \*\*Chegada\*\*, digite a data operacional mais [0-9]+ dias' <<<"$rot21" | head -1 | grep -oE '[0-9]+')"
rot_departure="$(grep -oE 'em \*\*Saída\*\*, a data operacional mais [0-9]+ dias' <<<"$rot21" | head -1 | grep -oE '[0-9]+')"
for v in "expected_status:$expected_status" "g12_base:$g12_base" "c1:$c1_a-$c1_d" "sep:$sep" "in_house:$in_house" \
  "masked:$masked_prefix/$visible" "change_done:$change_done" "roteiro:$rot_arrival/$rot_departure/$rot_change_day"; do
  [[ -n "${v#*:}" && "${v#*:}" != *"/"/* && "${v#*:}" != "-" ]] || _assert_fail "li o fato do código ou do roteiro: ${v%%:*}" "$v"
done
assert_eq "5" "$(grep -c . <<<"$expected_status")" "li as cinco reservas do seed do M3"
assert_eq "$((rot_departure + 1))" "$rot_change_day" "roteiro: o 23.1 muda a saída do 21 para um dia depois"

# violacoes BUSCA RESERVA CICLO : uma linha por trava quebrada (nada = tudo certo); os três em código ativo.
violacoes() {
  local s="$1" b="$2" l="$3" doc cancel dep n
  tem() { [[ "$1" == *"$2"* ]] || echo "falta: $3"; }
  nao_tem() { [[ "$1" != *"$2"* ]] || echo "proibido: $3"; }

  # 1. O opt-in vem antes de qualquer trabalho: um @BeforeTest com o assumeTrue de cada classe, e só ele.
  for pair in "s:ZENY_LIVE_SEARCH" "b:ZENY_LIVE_BOOKING" "l:ZENY_LIVE_LIFECYCLE"; do
    local t="${pair%%:*}" v="${pair#*:}" txt
    case "$t" in s) txt="$s" ;; b) txt="$b" ;; l) txt="$l" ;; esac
    tem "$txt" "@BeforeTest fun requireOptIn() { assumeTrue(System.getenv(\"$v\") == \"1\"," "o opt-in $v no @BeforeTest"
    [[ "$(grep -oF '@BeforeTest' <<<"$txt" | wc -l)" == 1 && "$(grep -oF 'assumeTrue(' <<<"$txt" | wc -l)" == 1 ]] ||
      echo "proibido: outro @BeforeTest ou assumeTrue além do opt-in $v"
    nao_tem "$txt" 'assumeFalse(' "pular em vez de falhar ($v)"
    nao_tem "$txt" '@Disabled' "teste desligado ($v)"
  done

  # 2. As situações do seed (19.2): os externalId e os rótulos são os do código.
  local got_pairs
  got_pairs="$(grep -oE 'private val SEED_STATUS_LABELS = mapOf\([^)]*\)' <<<"$s" | grep -oE '"[^"]+" to "[^"]+"' | sort)"
  [[ "$got_pairs" == "$want_pairs" ]] || echo "falta: SEED_STATUS_LABELS = os externalId do seed com os rótulos do ReceptionText"
  tem "$s" "private const val G12_CPF_BASE = \"$g12_base\"" "a base do CPF do titular do seed ($g12_base)"
  tem "$s" 'val cpf = fictitiousCpf(G12_CPF_BASE)' "o CPF buscado é o do titular do seed"
  [[ "$(grep -oF "val masked = \"$masked_prefix\${cpf.takeLast($visible)}\"" <<<"$s" | wc -l)" == 2 ]] ||
    echo "falta: a máscara esperada é a da tela ($masked_prefix + $visible dígitos), nas duas buscas por documento"
  tem "$s" 'assertTrue(texts.none { it.contains(cpf) },' "nenhum nó da tela com o CPF inteiro"
  tem "$s" 'assertEquals(label, statusByCode[code],' "a situação de cada reserva do seed é a da tela"
  tem "$s" 'recording.documentCalls.get() > 0' "espera a busca por documento terminar na fonte"
  # 3. A cancelada (25): o período da C1, só a situação Cancelada, e falha (não pula) sem ela.
  tem "$s" "private const val CANCELLED_FROM_DAYS = ${c1_a}L" "o filtro começa na chegada da C1 (d+$c1_a)"
  tem "$s" "private const val CANCELLED_TO_DAYS = ${c1_d}L" "o filtro termina na saída da C1 (d+$c1_d)"
  tem "$s" 'private const val CANCELLED = "CANCELLED"' "a situação do contrato"
  tem "$s" 'search.statuses = setOf(CANCELLED)' "o filtro é só Cancelada"
  tem "$s" 'val externalId = "DEMO-FUT-C1"' "a cancelada é a C1"
  tem "$s" 'if (seed.status != CANCELLED) { fail("$NEED_DEMO_SEED:' "a C1 fora de Cancelada falha (não pula)"
  tem "$s" 'val others = rows.filter { it.status != cancelledLabel }' "toda linha do filtro mostra Cancelada"
  tem "$s" 'rows.any { it.code == seed.code },' "a C1 está no resultado"
  tem "$s" 'recording.calls.any { it.statuses == setOf(CANCELLED) }' "espera a busca filtrada terminar na fonte"
  # 4. O filtro Hospedada (19.1): o rótulo e o separador são os do código.
  tem "$s" "private const val IN_HOUSE_LABEL = \"$in_house\"" "o rótulo de hospedada é o do ReceptionText"
  tem "$s" "filterNot { it == IN_HOUSE_LABEL || it.startsWith(\"\$IN_HOUSE_LABEL$sep\") }" \
    "a situação admite o alerta depois do separador do statusText ([$sep])"
  # 5. As duas novas só leem; nada de CPF inteiro, nome ou credencial em texto.
  doc="$(corpo "$s" 'fun documentSearchFindsSeedReservationsOfGuestTwelve()' '@Test')"
  cancel="$(corpo "$s" 'fun cancelledFilterFindsSeedCancelled()' '@Test')"
  [[ -n "$doc" && -n "$cancel" ]] || echo "falta: as duas verificações do roteiro"
  for p in '.confirm(' '.cancel(' 'checkIn' 'createReservation' 'reactivate' 'ReservationChangeState' \
    'ReservationCancelState' 'NewReservationState' 'assumeTrue('; do
    nao_tem "$doc$cancel" "$p" "as duas novas só leem ($p)"
  done
  # (a máscara e o formato NNN.NNN.NNN-NN usam pedaços do CPF de propósito: só o CPF inteiro é proibido)
  if grep -qE '"[^"]*(\$cpf([^.A-Za-z_]|$)|\$\{cpf\}|\$\{?G12_CPF_BASE|\$\{?holder|\$\{env\()' <<<"$s"; then
    echo "proibido: CPF inteiro, titular ou credencial interpolado num texto"
  fi
  nao_tem "$doc$cancel" 'Hóspede Demonstração' "nome de hóspede nas verificações do roteiro"

  # 6. A mudança de saída (23.1): as datas do roteiro; o total novo da tela é o do servidor; sem conta de preço.
  tem "$b" "private const val LIFECYCLE_ARRIVAL_DAYS = ${rot_arrival}L" "a chegada da reserva é a do roteiro (d+$rot_arrival)"
  tem "$b" "private const val LIFECYCLE_DEPARTURE_DAYS = ${rot_departure}L" "a saída da reserva é a do roteiro (d+$rot_departure)"
  tem "$b" "private const val CHANGED_DEPARTURE_DAYS = ${rot_change_day}L" "a saída nova é a do roteiro (d+$rot_change_day)"
  tem "$b" 'confirmationOpens(screen, ports, created) departureChangeRaisesTotal(screen, ports, created, today.plusDays(CHANGED_DEPARTURE_DAYS))' \
    "a mudança de saída vem depois da confirmação em PDF (que confere o total antigo)"
  dep="$(corpo "$b" 'private fun DesktopComposeUiTest.departureChangeRaisesTotal(' 'private fun DesktopComposeUiTest.confirmationOpens(')"
  [[ -n "$dep" ]] || echo "falta: departureChangeRaisesTotal"
  antes "$dep" 'val totalBefore = BigDecimal(before.total)' 'runOnUiThread { change.confirm() }' ||
    echo "falta: o total anterior relido do servidor antes de salvar"
  tem "$dep" 'assertTrue(!change.inHouse,' "a reserva alterada é confirmada, não hospedada"
  tem "$dep" 'runOnUiThread { change.departureText = DateInput.format(newDeparture) }' "a saída muda pela tela"
  tem "$dep" 'assertSettledDone(change,' "a alteração termina sem erro"
  tem "$dep" 'assertEquals(newDeparture.toApi(), after.departure,' "a saída relida é a nova"
  tem "$dep" 'totalAfter > totalBefore,' "o total relido cresce"
  local tela="${change_done#done = }"
  tela="${tela//reservation.code/created.code}"
  tela="${tela//reservation.total/after.total}"
  tem "$dep" "assertEquals($tela, change.done," "o total que a tela mostra é o relido do servidor, no texto do ReservationChangeState"
  tem "$dep" 'assertEquals(historyBefore + 1, historyAfter,' "o histórico ganha uma entrada"
  for p in ' * ' '.multiply(' '.add(' '.plus(BigDecimal' 'nightly' 'rate' 'price' 'Money.parse'; do
    nao_tem "$dep" "$p" "conta de preço no teste ($p)"
  done
  [[ "$(grep -oF 'runOnUiThread { change.confirm() }' <<<"$dep" | wc -l)" == 1 ]] || echo "proibido: mais de uma alteração"

  # 7. Depósitos em falta vazia (26).
  tem "$l" 'assertEquals(0, counts[PendingList.DEPOSITS],' "Depósitos em falta vazia"
  antes "$l" 'val counts = PendingList.entries.associateWith { loadAll(screen, pending.table(it), it.label).size }' \
    'assertEquals(0, counts[PendingList.DEPOSITS],' || echo "falta: a contagem vem das quatro listas carregadas"
}

s="$(codigo_ativo "$ROOT/$SEARCH")"
b="$(codigo_ativo "$ROOT/$BOOKING")"
l="$(codigo_ativo "$ROOT/$LIFECYCLE")"
assert_eq "" "$(violacoes "$s" "$b" "$l")" "as verificações ao vivo da Task 7 cumprem as travas do roteiro do M3"
# O texto do total novo que a tela monta (o que a verificação compara) existe no código.
[[ -n "$change_done" ]] || _assert_fail "código: o ReservationChangeState monta \"Reserva … alterada. Novo total: …\""
assert_contains "$(cat "$ROOT/$LIFECYCLE")" 'que são do M4' "o comentário da lista de depósitos cita o M4"

# muta DESCRIÇÃO ANTIGO NOVO s|b|l : o mutante (primeira ocorrência trocada) tem de quebrar alguma trava.
muta() {
  local m_s="$s" m_b="$b" m_l="$l" ref
  case "$4" in s) ref="$m_s" ;; b) ref="$m_b" ;; l) ref="$m_l" ;; esac
  [[ "$ref" == *"$2"* ]] || { _assert_fail "mutação sem alvo: $1" "trecho: $2"; return; }
  case "$4" in
    s) m_s="${m_s/"$2"/"$3"}" ;; b) m_b="${m_b/"$2"/"$3"}" ;; l) m_l="${m_l/"$2"/"$3"}" ;;
  esac
  [[ -n "$(violacoes "$m_s" "$m_b" "$m_l")" ]] || _assert_fail "mutante não detectado: $1"
}
muta "opt-in da busca removido" 'System.getenv("ZENY_LIVE_SEARCH") == "1"' 'true' s
muta "pula sem a C1 cancelada" 'if (seed.status != CANCELLED) { fail(' 'if (seed.status != CANCELLED) { assumeTrue(false,' s
muta "rótulo da pré-reserva errado" '"DEMO-PEND-P1" to "Pré-reserva"' '"DEMO-PEND-P1" to "Confirmada"' s
muta "externalId inventado" '"DEMO-FUT-F2" to "Confirmada"' '"DEMO-FUT-F9" to "Confirmada"' s
muta "C1 esperada confirmada" '"DEMO-FUT-C1" to "Cancelada"' '"DEMO-FUT-C1" to "Confirmada"' s
muta "base do CPF de outro hóspede" "G12_CPF_BASE = \"$g12_base\"" 'G12_CPF_BASE = "202600111"' s
muta "máscara com 3 dígitos" 'val masked = "CPF ***${cpf.takeLast(4)}"' 'val masked = "CPF ***${cpf.takeLast(3)}"' s
muta "CPF inteiro numa mensagem" 'assertTrue(texts.any { it.contains(masked) }, "a tela não mostra $masked")' \
  'assertTrue(texts.any { it.contains(masked) }, "a tela não mostra $cpf")' s
muta "período da C1 errado" "CANCELLED_FROM_DAYS = ${c1_a}L" 'CANCELLED_FROM_DAYS = 27L' s
muta "filtro com outra situação" 'search.statuses = setOf(CANCELLED)' 'search.statuses = setOf(CANCELLED, "CONFIRMED")' s
muta "linha não cancelada aceita" 'val others = rows.filter { it.status != cancelledLabel }' 'val others = rows.filter { false }' s
muta "separador dos alertas duplicado errado" 'it.startsWith("$IN_HOUSE_LABEL · ")' 'it.startsWith("$IN_HOUSE_LABEL - ")' s
muta "busca de leitura que escreve" '{ recording.documentCalls.get() > 0 }' '{ recording.documentCalls.get() > 0 } runBlocking { confirmSource.confirm(code) }' s
muta "filtro Cancelada que escreve" 'search.statuses = setOf(CANCELLED)' 'search.statuses = setOf(CANCELLED) runBlocking { source.reactivate(seed.id) }' s
muta "saída nova fora do roteiro" "CHANGED_DEPARTURE_DAYS = ${rot_change_day}L" 'CHANGED_DEPARTURE_DAYS = 35L' b
muta "mudança antes do PDF" 'confirmationOpens(screen, ports, created) departureChangeRaisesTotal(' 'departureChangeRaisesTotal(' b
muta "total da tela sem o servidor" 'Novo total: ${ReceptionText.money(after.total)}.",' 'Novo total: ${ReceptionText.money(before.total)}.",' b
muta "conta de preço no teste" 'val totalAfter = BigDecimal(after.total)' 'val totalAfter = totalBefore.multiply(BigDecimal(4)).divide(BigDecimal(3))' b
muta "total menor aceito" 'totalAfter > totalBefore,' 'totalAfter >= totalBefore,' b
muta "histórico não conferido" 'assertEquals(historyBefore + 1, historyAfter,' 'assertEquals(historyAfter, historyAfter,' b
muta "depósitos não conferidos" 'assertEquals(0, counts[PendingList.DEPOSITS],' 'assertEquals(counts[PendingList.DEPOSITS], counts[PendingList.DEPOSITS],' l

finish
