#!/usr/bin/env bash
# Ataque de etapa à Task 9 da M3 E02: complementa test_live_seed_availability_ataque.sh com o que a trava da tarefa
# deixa passar. A verificação ao vivo escreve na demo (reserva em d+20, estadia mínima em d+25) e só desfaz se os
# marcadores do `finally` forem armados na ordem certa, e nenhuma interpolação pode pôr dado sensível numa mensagem.
# Estático: não chama o Gradle nem o servidor.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

# Mesmo formato estável diante do ktlint da trava da tarefa: só código ativo, numa linha, sem espaço depois de "("
# nem antes de ")", sem vírgula final antes de ")" e sem espaço antes do ponto de uma cadeia quebrada.
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

# assert_match TEXTO REGEX-ERE MSG: o regex (ERE) tem de casar com o código numa linha.
assert_match() {
  if ! grep -Eq -- "$2" <<<"$1"; then
    _assert_fail "$3" "não casa: $2"
  fi
}

KT="$ROOT/demo-seed/src/test/kotlin/com/zeny/desktop/seed/sdk/LiveAvailabilityBookingCheck.kt"
assert_file "$KT"
kt="$(codigo_ativo "$KT")"

# --- 1. A reserva de d+20 é desfeita em todo caminho ------------------------------------------------------------
# Os marcadores nascem antes do try: o finally os enxerga mesmo se o corpo falhar na primeira linha.
assert_contains "$kt" 'var createdId: Uuid? = null var cancelled = false try {' \
  "createdId e cancelled são declarados antes do try do teste da reserva"
# O id é guardado logo depois da criação, antes de qualquer leitura ou asserção que possa falhar.
assert_match "$kt" 'createBooking\(it\.requireIdempotencyKey\(\), null, request, it\.headers\) \}\.value\.reservations\.single\(\) createdId = created\.id println\(' \
  "createdId = created.id logo depois da criação, antes de qualquer asserção"
# `cancelled` só vira true DEPOIS do cancelamento: se o cancelamento do corpo falhar, o finally ainda tenta.
assert_contains "$kt" 'cancel(seed, created.id) cancelled = true assertEquals(before, available()' \
  "cancelled = true só depois do cancelamento, antes da releitura"
n="$(grep -oF 'cancelled = true' <<<"$kt" | wc -l)"
assert_eq "1" "$n" "cancelled recebe true uma única vez"
# Sem UH disponível o teste para antes de escrever.
assert_contains "$kt" 'if (before < 1) fail("NEED_SEED: sem UH $ROOM_TYPE_CODE disponível em d+$BOOKING_OFFSET_DAYS") val request = bookingRequest(' \
  "sem UH disponível o teste falha com NEED_SEED antes de criar a reserva"
n="$(grep -oF 'createBooking(' <<<"$kt" | wc -l)"
assert_eq "1" "$n" "uma única criação de reserva em todo o arquivo"
# A reserva é a de 1 noite, 1 adulto, do G1 fictício, pelo balcão (FRONT_DESK) do brief.
assert_contains "$kt" 'private const val GUEST_CPF_BASE = "202600101"' "o hóspede é o G1 fictício"
assert_contains "$kt" 'origin = BookingRequest.Origin.FRONT_DESK' "origem FRONT_DESK"
assert_contains "$kt" 'adults = 1,' "um adulto"
assert_contains "$kt" 'departure = BusinessDate(arrival.value.plusDays(1)).toApi()' "uma noite"
assert_contains "$kt" 'holderGuestId = holder' "o titular é o hóspede dado"

# --- 2. A restrição de d+25 só é retirada se foi gravada, e nunca apaga a que já existia -------------------------
assert_contains "$kt" 'var written = false try {' "written é declarado antes do try do teste da restrição"
n="$(grep -oF 'written = true' <<<"$kt" | wc -l)"
assert_eq "1" "$n" "written recebe true uma única vez"
# Ordem: guarda de restrição prévia -> written = true -> gravação. Com written = true acima da guarda, o finally
# apagaria a estadia mínima de outra pessoa; sem ele, a gravada ficaria na demo.
assert_match "$kt" 'if \(hasStored\(seed, planId, date\)\) \{ fail\([^}]*\} written = true val set = update\(date, RestrictionValuesDto\(minStay = Option\.Some\(MIN_STAY_NIGHTS\)\), emptyList\(\)\) seed\.executor\.execute \{ rates\.updateRatePlanRestrictions\(planId, set, it\.headers\) \}' \
  "guarda NEED_SEED, depois written = true, depois a gravação de minStay = MIN_STAY_NIGHTS"
# A escrita e a retirada cobrem só a data, geral, sem tipo, sem dias da semana: nada além de d+25 é tocado.
assert_contains "$kt" 'RestrictionUpdateRequest(clear = Option.Some(clear), from = Option.Some(date.toApi()), roomTypeIds = Option.Some(emptyList()), `set` = Option.Some(set), to = Option.Some(date.toApi()), weekdays = Option.Some(emptyList()))' \
  "a gravação e a retirada cobrem só from = to = a data, com tipo e dias da semana vazios, todos explícitos em Option.Some (ADR 0014)"
n="$(grep -oF 'updateRatePlanRestrictions(' <<<"$kt" | wc -l)"
assert_eq "2" "$n" "duas chamadas: a gravação e a retirada"
# A retirada remove só MIN_STAY e confere que a data ficou limpa, relendo.
assert_contains "$kt" 'val clear = update(date, RestrictionValuesDto(), listOf(RestrictionUpdateRequest.ClearItem.MIN_STAY))' \
  "a retirada limpa só MIN_STAY"
assert_contains "$kt" 'val left = hasStored(seed, planId, date) assertTrue(!left, "a data voltou sem restrição")' \
  "depois de retirar, relê a data e exige que esteja sem restrição"
assert_contains "$kt" 'private const val MIN_STAY_NIGHTS = 2' "a estadia mínima gravada é de 2 noites"
assert_match "$kt" 'quoteStay\(date, BusinessDate\(day\.plusDays\(1\)\), 2, emptyList\(\), includeUnavailable = true\)' \
  "a cotação é de 1 noite (menor que o mínimo de 2)"

# --- 3. Nenhuma interpolação põe credencial ou dado pessoal em saída ou em mensagem ------------------------------
# Toda `$nome` e `${expr}` do código ativo precisa estar nesta lista: constantes do arquivo, o id da reserva, a
# contagem e o código do tipo. Uma interpolação nova (a config, o CPF, um campo de SeedConfig) quebra aqui.
esperado='$before
$BOOKING_OFFSET_DAYS
$MIN_STAY_NIGHTS
$PLAN_CODE
$RESTRICTION_OFFSET_DAYS
$ROOM_TYPE_CODE
${created.id}
${item.roomTypeCode}
${unavailable.size}
${Uuid.random()}'
obtido="$(grep -oE '\$\{[^}]*\}|\$[A-Za-z_][A-Za-z_0-9]*' <<<"$kt" | sort -u)"
assert_eq "$(sort -u <<<"$esperado")" "$obtido" "as únicas interpolações são as conhecidas e seguras"
assert_not_contains "$kt" 'adminLogin' "o login do Administrador não é tocado"
assert_not_contains "$kt" 'Secret' "nenhum Secret é tocado"
# Lista fechada também para a config: as listas acima não pegam uma mensagem sem `$`, como
# `fail(config.managerPin.value)`. Toda ocorrência de `config.` tem de ser `config.server`, e são exatamente duas
# (a abertura da sessão do administrador, nos dois testes).
#
# M7 (M4 E01): contar `config.` deixava passar `config?.`, `config!!.`, `with(config)`, um alias, `config()` e um nome
# que só começa com `config.server`. A regra passa a olhar a palavra `config` inteira: tirando as formas conhecidas
# (`config.server` sem nada colado, `.open(config)`, `val config = config()`, `fun config(): SeedConfig` e
# `parsed.config` sem nada colado), não pode sobrar nenhuma.
config_violacoes() {
  local texto="$1" total servidor sobra
  total="$(grep -oF 'config.' <<<"$texto" | wc -l)"
  servidor="$(grep -oF 'config.server' <<<"$texto" | wc -l)"
  [[ "$total" == "$servidor" ]] || echo "proibido: config. além de config.server"
  servidor="$(grep -oP '\bconfig\.server(?![\w.?!])' <<<"$texto" | wc -l)"
  [[ "$servidor" == 2 ]] || echo "falta: config.server exatamente duas vezes (achou $servidor)"
  sobra="$(perl -pe 's/\bconfig\.server(?![\w.?!])//g; s/\.open\(config\)//g;
    s/\bval config = config\(\)(?![\w.?!])//g; s/\bfun config\(\): SeedConfig\b//g;
    s/\bparsed\.config(?![\w.?!(])//g' <<<"$texto" | grep -oP '\bconfig\b' | wc -l)"
  [[ "$sobra" == 0 ]] || echo "proibido: config fora das formas conhecidas ($sobra)"
}
assert_diferente() { [[ "$1" != "$2" ]] || _assert_fail "$3"; }
assert_eq "" "$(config_violacoes "$kt")" "toda ocorrência de config. é config.server, e são duas"
# Mutantes: cada um tem de reprovar a regra.
assert_diferente "" "$(config_violacoes "$kt fail(config.managerPin.value)")" \
  "mutante: fail(config.managerPin.value) sem interpolação é detectado"
assert_diferente "" "$(config_violacoes "${kt/config.server/config.managerPin.value}")" \
  "mutante: config.server trocado por config.managerPin.value é detectado"
assert_diferente "" "$(config_violacoes "${kt/config.server/x}")" "mutante: uma config.server a menos é detectado"
# M7 (M4 E01): contornos da contagem por `config.`: chamada segura, `!!`, `with`, um alias e um nome que só começa
# com `config.server`. Cada um lê um segredo da config sem escrever `config.` seguido do campo.
assert_diferente "" "$(config_violacoes "$kt fail(config?.managerPin?.value)")" \
  "mutante: fail(config?.managerPin?.value) é detectado"
assert_diferente "" "$(config_violacoes "$kt fail(config!!.managerPin.value)")" \
  "mutante: fail(config!!.managerPin.value) é detectado"
assert_diferente "" "$(config_violacoes "$kt with(config) { fail(managerPin.value) }")" \
  "mutante: with(config) { fail(managerPin.value) } é detectado"
assert_diferente "" "$(config_violacoes "$kt val c = config fail(c.managerPin.value)")" \
  "mutante: um alias da config é detectado"
assert_diferente "" "$(config_violacoes "${kt/config.server/config.serverPin}")" \
  "mutante: config.server trocado por config.serverPin é detectado"
assert_diferente "" "$(config_violacoes "${kt/.open(config)/.open(config).also { fail(config().managerPin.value) \}}")" \
  "mutante: config().managerPin depois da abertura é detectado"

finish
