#!/usr/bin/env bash
# Ataque à Task 8 da M3 E02, por mutação: remove cada garantia da verificação ao vivo da disponibilidade numa cópia
# e exige que tests/test_live_availability_ataque.sh falhe. A cópia sem defeito passa (controle). Não chama o Gradle.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

D="app/src/test/kotlin/com/zeny/desktop/app/reservations"
CHECK="$D/LiveAvailabilityCheck.kt"
READERS="$D/LiveAvailabilityReaders.kt"
GRADLE="app/build.gradle.kts"

copia="$(mktemp -d)"
trap 'rm -rf "$copia"' EXIT

# Igual ao da trava: o código ativo numa linha, em formato estável. As mutações são escritas nesse formato.
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

nova_copia() {
  rm -rf "${copia:?}"/*
  mkdir -p "$copia/tests/lib" "$copia/$D"
  cp "$ROOT"/tests/lib/* "$copia/tests/lib/"
  cp "$ROOT/tests/test_live_availability_ataque.sh" "$copia/tests/"
  cp "$ROOT/$D/LiveAvailabilityReadersTest.kt" "$copia/$D/"
  codigo_ativo "$ROOT/$CHECK" >"$copia/$CHECK"
  codigo_ativo "$ROOT/$READERS" >"$copia/$READERS"
  codigo_ativo "$ROOT/$GRADLE" >"$copia/$GRADLE"
}

# troca ARQUIVO ANTIGO NOVO: troca a primeira ocorrência literal; falha se o trecho não existe (mutação inútil).
troca() {
  local arquivo="$copia/$1" conteudo
  conteudo="$(<"$arquivo")"
  if [[ "$conteudo" != *"$2"* ]]; then
    _assert_fail "mutação sem alvo" "trecho não encontrado em $1: $2"
    return 1
  fi
  printf '%s\n' "${conteudo/"$2"/"$3"}" >"$arquivo"
}

roda() { bash "$copia/tests/test_live_availability_ataque.sh" >/dev/null 2>&1; }

# muta DESCRIÇÃO ARQUIVO ANTIGO NOVO: o script, sobre a cópia mutada, tem de sair com 1.
muta() {
  nova_copia
  troca "$2" "$3" "$4" || return 0
  roda
  local code=$?
  [[ "$code" == 1 ]] || _assert_fail "$1" "o script deveria falhar (exit 1) e saiu com $code"
}

nova_copia
roda || _assert_fail "controle: a cópia sem defeito passa" "o script falhou sobre a cópia limpa"

# Opt-in e credenciais.
muta "opt-in removido" "$CHECK" 'System.getenv("ZENY_LIVE_AVAILABILITY") == "1"' 'true'
muta "senha não exigida" "$CHECK" 'env("ZENY_LIVE_VIEWER_PASSWORD")' '"x"'
muta "login não exigido" "$CHECK" 'env("ZENY_LIVE_VIEWER_LOGIN")' '"x"'
muta "url não exigida" "$CHECK" 'env("ZENY_LIVE_URL")' '"http://x"'
muta "env() não falha" "$CHECK" '?: fail("defina $name para a verificação real")' '?: ""'
muta "senha impressa" "$CHECK" 'println("LiveAvailabilityCheck: o calendário' 'println(env("ZENY_LIVE_VIEWER_PASSWORD")); println("LiveAvailabilityCheck: o calendário'
muta "senha interpolada" "$CHECK" '"a cotação falhou: ${quote.error}")' '"a cotação falhou: ${env("ZENY_LIVE_VIEWER_PASSWORD")}")'
muta "gradle: sem a entrada" "$GRADLE" 'inputs.property("zenyLiveAvailability", providers.environmentVariable("ZENY_LIVE_AVAILABILITY").orElse(""))' ''
muta "gradle: fora do upToDateWhen" "$GRADLE" ' && liveAvailability.orNull != "1"' ''
muta "gradle: variável errada" "$GRADLE" 'val liveAvailability = providers.environmentVariable("ZENY_LIVE_AVAILABILITY")' 'val liveAvailability = providers.environmentVariable("OUTRA")'

# Só lê.
muta "escreve: check-in" "$CHECK" 'assertIs<SessionState.Active>' 'core.reservations.checkIn(); assertIs<SessionState.Active>'

# Tempo, janela e reload.
muta "teto H2 afrouxado" "$CHECK" 'H2_LIMIT_MILLIS = 10_000L' 'H2_LIMIT_MILLIS = 100_000L'
muta "teto H2 não afirmado" "$CHECK" 'millis <= H2_LIMIT_MILLIS' 'true'
muta "tempo medido fora da carga" "$CHECK" 'val started = System.nanoTime() runOnUiThread { calendar.start() }' 'runOnUiThread { calendar.start() } val started = System.nanoTime()'
muta "janela diferente de 30" "$CHECK" 'WINDOW_DAYS = 30' 'WINDOW_DAYS = 7'
muta "linhas do calendário" "$CHECK" 'setOf("Standard", "Luxo", "Suíte")' 'setOf("Standard", "Luxo")'
muta "reload não chamado" "$CHECK" 'runOnUiThread { calendar.reload() }' 'runOnUiThread { }'
muta "reload não medido na fonte" "$CHECK" 'listOf(window, window),' 'listOf(window),'
muta "sem decorator" "$CHECK" 'RecordingSource(connection.availabilitySource())' 'connection.availabilitySource()'
muta "janela da API errada" "$CHECK" 'core.reservations.availability(first, last)' 'core.reservations.availability(first, first)'
muta "células não comparadas" "$CHECK" 'assertCalendarShows(calendar, api, WINDOW_DAYS, ROOM_TYPE_NAMES)' 'Unit'

# Cotação e recusa.
muta "chegada fora de d+7" "$CHECK" 'ARRIVAL_OFFSET_DAYS = 7L' 'ARRIVAL_OFFSET_DAYS = 1L'
muta "saída fora de d+9" "$CHECK" 'DEPARTURE_OFFSET_DAYS = 9L' 'DEPARTURE_OFFSET_DAYS = 2L'
muta "ofertas não comparadas" "$CHECK" 'assertOffersMatch(quote.offers, api)' 'Unit'
muta "regime não exigido" "$CHECK" 'texts.any { it.contains("Café da manhã") }' 'true'
muta "4 adultos trocado" "$CHECK" 'quoteStay(arrival, departure, 4, emptyList(), true)' 'quoteStay(arrival, departure, 3, emptyList(), true)'
muta "texto da recusa afrouxado" "$CHECK" 'it.contains("recebe até 3 adulto(s)")' 'true'
muta "Luxo não exigido" "$CHECK" 'listOf("Standard", "Luxo")' 'listOf("Standard")'
muta "recusas da API não conferidas" "$CHECK" 'assertRefusalsShown(api,' 'skipRefusals(api,'
muta "código cru não procurado" "$CHECK" 'assertNoRawCode(api, setOf("OCCUPANCY_EXCEEDS_ROOM_TYPE"))' 'Unit'
muta "Indisponíveis não exigido" "$CHECK" 'allTexts().any { it == "Indisponíveis" }' 'true'

# Captura.
muta "captura em outro caminho" "$CHECK" 'File("build/live/availability.png")' 'File("build/live/x.png")'
muta "diretório da captura não criado" "$CHECK" 'file.parentFile.mkdirs()' ''
muta "captura não gravada" "$CHECK" 'ImageIO.write(image, "png", file)' 'true'
muta "captura vazia aceita" "$CHECK" 'file.length() > 0' 'true'

# Força das comparações.
muta "traços batem com traços" "$READERS" 'assertTrue(numeric > 0,' 'assertTrue(true || numeric > 0,'
muta "célula não comparada" "$READERS" 'assertEquals(expected, shown,' 'assertEquals(shown, shown,'
muta "só algumas células" "$READERS" 'calendar.dates.forEach { date ->' 'calendar.dates.take(5).forEach { date ->'
muta "só um tipo de UH" "$READERS" 'api.roomTypes.forEach { type ->' 'api.roomTypes.take(1).forEach { type ->'
muta "contagem de células" "$READERS" 'assertEquals(names.size * windowDays, checked,' 'assertEquals(checked, checked,'
muta "dinheiro repetido só procurado" "$READERS" 'found >= count,' 'found >= 1,'
muta "noites fora do dinheiro" "$READERS" '(listOf(offer.total) + offer.nights.map { it.total }).map(ReceptionText::money)' 'listOf(offer.total).map(ReceptionText::money)'
muta "total fora do dinheiro" "$READERS" '(listOf(offer.total) + offer.nights.map { it.total }).map(ReceptionText::money)' 'offer.nights.map { it.total }.map(ReceptionText::money)'
muta "número de ofertas" "$READERS" 'assertEquals(api.offers.size, shown.size,' 'assertEquals(shown.size, shown.size,'
muta "ofertas casadas pela posição" "$READERS" 'api.offers.single { key(it) == key(onScreen) }' 'api.offers[index]'
muta "recusa sem o nome do tipo" "$READERS" 'texts[index].startsWith("$name ·")' 'true'
muta "descrições não lidas" "$READERS" 'SemanticsProperties.ContentDescription' 'SemanticsProperties.Text'
muta "árvore não mesclada não lida" "$READERS" 'onRoot(useUnmergedTree = true)' 'onRoot()'
muta "formato de código cru" "$READERS" 'rawCode.find(text) == null' 'true'
muta "decorator não repassa a leitura" "$READERS" 'delegate.availability(from, to)' 'AvailabilityResponse(from = "", roomTypes = emptyList(), to = "")'

# Segredo: a senha numa variável que chega ao println, println de argumento não literal, template fora da lista.
muta "senha numa variável impressa" "$CHECK" 'assertIs<SessionState.Active>' 'val p = env("ZENY_LIVE_VIEWER_PASSWORD") println(p) assertIs<SessionState.Active>'
muta "println de argumento não literal" "$CHECK" 'assertIs<SessionState.Active>' 'println(connection.toString()) assertIs<SessionState.Active>'
muta "println com template fora da lista" "$CHECK" '${api.offers.size} oferta(s)' '${connection} oferta(s)'
muta "saída direta no System.out" "$CHECK" 'assertIs<SessionState.Active>' 'System.out.write(1) assertIs<SessionState.Active>'

# Comentários não valem: a garantia só em bloco/KDoc é a garantia removida.
muta "opt-in só em bloco de comentário" "$CHECK" 'System.getenv("ZENY_LIVE_AVAILABILITY") == "1"' 'true /* System.getenv("ZENY_LIVE_AVAILABILITY") == "1" */'
muta "afirmação só em KDoc" "$READERS" 'assertTrue(numeric > 0' '/** assertTrue(numeric > 0 */ assertTrue(true'

finish
