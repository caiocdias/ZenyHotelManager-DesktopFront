#!/usr/bin/env bash
# Ataque à Task 8 da M3 E02: a verificação ao vivo da disponibilidade e da cotação (LiveAvailabilityCheck) só lê,
# fica pulada sem o opt-in e, com ele, não pode sair verde por omissão. Estático: não chama o Gradle nem o servidor.
# As comparações ficam em LiveAvailabilityReaders.kt (provadas sem rede pela LiveAvailabilityReadersTest).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tests/lib/assert.sh"

# Só código ativo, num formato estável diante do ktlint: sem blocos /* */ nem KDoc, sem //, numa linha, sem espaço
# depois de "(" nem antes de ")", sem vírgula final antes de ")" e sem espaço antes do ponto de uma cadeia quebrada.
codigo_ativo() {
  perl -0pe 's{/\*.*?\*/}{ }gs' "$1" 2>/dev/null | sed -E 's@^\s*//.*$@@; s@\s//\s.*$@@' | tr '\n' ' ' | tr -s ' ' |
    sed -E 's/\( /(/g; s/ \)/)/g; s/,\)/)/g; s/ \.([A-Za-z])/.\1/g'
}

DIR="$ROOT/app/src/test/kotlin/com/zeny/desktop/app/reservations"
KT="$DIR/LiveAvailabilityCheck.kt"
RD="$DIR/LiveAvailabilityReaders.kt"
GRADLE="$ROOT/app/build.gradle.kts"
assert_file "$KT"
assert_file "$RD"
assert_file "$DIR/LiveAvailabilityReadersTest.kt"
kt="$(codigo_ativo "$KT")"
rd="$(codigo_ativo "$RD")"
gradle="$(codigo_ativo "$GRADLE")"
tudo="$kt $rd"

# --- 1. Opt-in no build, igual ao dos outros ----------------------------------------------------------------
assert_contains "$gradle" 'inputs.property("zenyLiveAvailability", providers.environmentVariable("ZENY_LIVE_AVAILABILITY").orElse(""))' \
  "app/build.gradle.kts: ZENY_LIVE_AVAILABILITY é entrada do tasks.test"
assert_contains "$gradle" 'val liveAvailability = providers.environmentVariable("ZENY_LIVE_AVAILABILITY")' \
  "app/build.gradle.kts: liveAvailability lê ZENY_LIVE_AVAILABILITY"
bloco="$(grep -oE 'outputs\.upToDateWhen \{[^}]*\}' <<<"$gradle")"
assert_contains "$bloco" 'liveAvailability.orNull != "1"' "app/build.gradle.kts: o upToDateWhen leva ZENY_LIVE_AVAILABILITY"
assert_contains "$bloco" 'liveSearch.orNull != "1"' "app/build.gradle.kts: o upToDateWhen manteve o ZENY_LIVE_SEARCH"

# --- 2. Gating: sem opt-in pula (antes de cada teste); com opt-in exige as três variáveis -------------------
assert_contains "$kt" '@BeforeTest fun requireOptIn() { assumeTrue(System.getenv("ZENY_LIVE_AVAILABILITY") == "1"' \
  "sem ZENY_LIVE_AVAILABILITY=1 cada teste pula (assumeTrue no @BeforeTest)"
for v in ZENY_LIVE_URL ZENY_LIVE_VIEWER_LOGIN ZENY_LIVE_VIEWER_PASSWORD; do
  assert_contains "$kt" "env(\"$v\")" "a verificação exige $v (falha se faltar)"
done
assert_contains "$kt" '?: fail("defina $name para a verificação real")' "env() falha citando só o NOME da variável"
n="$(grep -oE 'System\.getenv\(' <<<"$tudo" | wc -l)"
assert_eq "2" "$n" "System.getenv só no opt-in e no env()"
n="$(grep -oE '@Test' <<<"$kt" | wc -l)"
assert_eq "3" "$n" "as três verificações do brief"
# Nenhuma credencial vai à saída: o valor de env()/getenv nunca entra em println, mensagem ou string.
assert_not_contains "$tudo" 'println(env(' "nenhum println do valor de env()"
assert_not_contains "$tudo" 'println(System.getenv' "nenhum println do valor de getenv"
assert_not_contains "$tudo" '${env(' "nenhum valor de env() interpolado em texto"
assert_not_contains "$tudo" 'System.getenv("ZENY_LIVE_VIEWER' "a senha e o login só passam por env()"
assert_not_contains "$tudo" 'PASSWORD}' "nenhuma interpolação da senha"
assert_not_contains "$tudo" 'e.printStackTrace' "sem printStackTrace"
# A senha entra num único lugar (o login) e nenhuma variável a carrega até uma saída.
n="$(grep -oF 'env("ZENY_LIVE_VIEWER_PASSWORD")' <<<"$tudo" | wc -l)"
assert_eq "1" "$n" "a senha é lida uma só vez, direto no login"
# Todo println tem um literal como argumento; os únicos $ permitidos são contagens e o tempo medido.
total="$(grep -oF 'println(' <<<"$tudo" | wc -l)"
literais="$(grep -oE 'println\("[^"$]*(\$(\{(api\.offers\.size|unavailable\.size)\}|millis|TOO_MANY_ADULTS)[^"$]*)*"\)' <<<"$tudo" | wc -l)"
assert_eq "$total" "$literais" "todo println é literal, só com contagens e tempo em templates"
assert_not_contains "$tudo" 'System.out' "nenhuma saída direta fora do println"
assert_not_contains "$tudo" 'System.err' "nenhuma saída de erro direta"
assert_not_contains "$tudo" ' print(' "nenhum print sem quebra"

# --- 3. Só lê: nenhuma chamada de escrita do servidor ---------------------------------------------------------
assert_not_contains "$tudo" '.checkIn(' "não faz check-in"
assert_not_contains "$tudo" '.createReservation(' "não cria reserva"
assert_not_contains "$tudo" '.cancel' "não cancela"

# --- 4. As afirmações do brief ----------------------------------------------------------------------------------
assert_contains "$kt" 'private const val H2_LIMIT_MILLIS = 10_000L' "teto da H2: 10 s"
assert_contains "$kt" 'val started = System.nanoTime() runOnUiThread { calendar.start() }' \
  "a carga do calendário é medida em volta do start()"
assert_contains "$kt" 'millis <= H2_LIMIT_MILLIS' "o tempo medido é comparado ao teto"
assert_contains "$kt" 'H2: a carga do calendário levou' "estouro do teto cita a H2"
assert_contains "$kt" 'private const val WINDOW_DAYS = 30' "janela de 30 datas"
assert_contains "$kt" 'setOf("Standard", "Luxo", "Suíte")' "as três linhas do calendário"
assert_contains "$kt" 'core.reservations.availability(first, last)' "compara com a fachada"
assert_contains "$kt" 'assertCalendarShows(calendar, api, WINDOW_DAYS, ROOM_TYPE_NAMES)' "a comparação de todas as células"
assert_contains "$kt" 'runOnUiThread { calendar.reload() }' "reload() e nova comparação"
n="$(grep -oE 'assertShowsApi\(core, calendar\)' <<<"$kt" | wc -l)"
assert_eq "2" "$n" "o calendário é comparado na carga e de novo depois do reload()"
assert_contains "$kt" 'listOf(window, window),' "o reload() é medido na fonte: duas leituras da mesma janela"
n="$(grep -oE 'RecordingSource\(connection\.availabilitySource\(\)\)' <<<"$kt" | wc -l)"
assert_eq "3" "$n" "a fonte real é medida por um decorator, nos três testes"
assert_contains "$kt" 'private const val ARRIVAL_OFFSET_DAYS = 7L' "chegada em d+7"
assert_contains "$kt" 'private const val DEPARTURE_OFFSET_DAYS = 9L' "saída em d+9"
assert_contains "$kt" 'core.reservations.quoteStay(arrival, departure, 2, emptyList(), true)' "cotação de 2 adultos pela fachada"
assert_contains "$kt" 'core.reservations.quoteStay(arrival, departure, 4, emptyList(), true)' "cotação de 4 adultos pela fachada"
assert_contains "$kt" 'assertOffersMatch(quote.offers, api)' "as ofertas batem com a API"
assert_contains "$kt" 'texts.any { it.contains("Café da manhã") }' "o regime em português"
assert_contains "$kt" 'private const val TOO_MANY_ADULTS = "4"' "a recusa com 4 adultos"
assert_contains "$kt" 'it.contains("recebe até 3 adulto(s)")' "o texto da recusa por ocupação"
assert_contains "$kt" 'listOf("Standard", "Luxo")' "Standard e Luxo recusados"
assert_contains "$kt" 'assertRefusalsShown(api,' "cada recusa da API está na tela"
assert_contains "$kt" 'assertNoRawCode(api, setOf("OCCUPANCY_EXCEEDS_ROOM_TYPE"))' "nenhum nó com o código cru"
assert_contains "$kt" 'allTexts().any { it == "Indisponíveis" }' "a seção Indisponíveis aparece"
assert_contains "$kt" 'File("build/live/availability.png")' "captura da tela"
assert_contains "$kt" 'file.parentFile.mkdirs()' "o diretório da captura é criado"
assert_contains "$kt" 'ImageIO.write(image, "png", file)' "a captura é gravada em png"
assert_contains "$kt" 'file.length() > 0' "a captura não sai vazia"

# --- 5. A força das comparações (LiveAvailabilityReaders.kt) -----------------------------------------------------
assert_contains "$rd" 'assertEquals(expected, shown,' "cada célula é igual ao available da API"
assert_contains "$rd" 'api.roomTypes.forEach { type ->' "todos os tipos de UH da API"
assert_contains "$rd" 'calendar.dates.forEach { date ->' "todas as datas da janela"
assert_contains "$rd" 'assertTrue(numeric > 0,' "traços não batem com uma API sem dias"
assert_contains "$rd" 'assertEquals(names.size * windowDays, checked,' "o total de células conferidas"
assert_contains "$rd" '(listOf(offer.total) + offer.nights.map { it.total }).map(ReceptionText::money)' \
  "o dinheiro: total e cada noite, por ReceptionText.money"
assert_contains "$rd" 'found >= count,' "o dinheiro repetido é contado, não só procurado"
assert_contains "$rd" 'assertEquals(api.offers.size, shown.size,' "o mesmo número de ofertas"
assert_contains "$rd" 'api.offers.single { key(it) == key(onScreen) }' "as ofertas casam por tipo e tarifa, não pela ordem"
assert_contains "$rd" 'texts[index].startsWith("$name ·")' "a recusa casa com o nome do tipo"
assert_contains "$rd" 'allTexts().forEach { text ->' "o código cru é procurado em todos os nós"
assert_contains "$rd" 'SemanticsProperties.ContentDescription' "o código cru também em descrições"
assert_contains "$rd" 'onRoot(useUnmergedTree = true)' "os nós da árvore não mesclada"
assert_contains "$rd" 'rawCode.find(text) == null' "qualquer texto no formato de código cru"
assert_contains "$rd" 'delegate.availability(from, to)' "o decorator repassa a leitura da disponibilidade"
assert_contains "$rd" 'delegate.quote(arrival, departure, adults, childAges)' "o decorator repassa a cotação"

finish
