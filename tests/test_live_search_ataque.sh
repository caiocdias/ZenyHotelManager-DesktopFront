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
# As quatro do brief da E01 e as duas do roteiro do M3 (M3 E05, Task 7: o 19.2 e o 25).
assert_eq "6" "$n" "as quatro verificações do brief e as duas do roteiro do M3"
for t in documentSearchFindsSeedReservationsOfGuestTwelve cancelledFilterFindsSeedCancelled; do
  assert_contains "$kt" "@Test fun $t() =" "a verificação $t é um @Test"
done

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
assert_contains "$kt" 'root.level = Level.ALL' "a captura põe o JUL raiz em ALL"
assert_contains "$kt" 'root.level = oldLevel' "stop() restaura o nível do JUL raiz"
assert_contains "$kt" 'generateSequence(record.thrown) { it.cause }' "o handler percorre a cadeia de causas"
assert_contains "$kt" 'runCatching { captured.start()' "captured.start() e o controle ficam dentro do runCatching"
assert_contains "$kt" 'search.fromText = "" search.toText = "" search.name = G1_HOLDER' \
  "a busca por nome limpa o período (o do start() pode não alcançar o G1)"
assert_contains "$kt" 'busca por nome do G1 não achou reservas' "busca por nome vazia falha com mensagem clara"

# --- 5. O CPF do G1 segue a regra de FictitiousDocuments (pesos 10 e 11, módulo 11, resto < 2 vira 0) --------
assert_contains "$kt" 'private const val G1_CPF_BASE = "202600101"' "base do G1 igual à do DemoCatalog (2026001 + 01)"
assert_contains "$kt" 'private const val CPF_FIRST_WEIGHT = 10' "primeiro peso 10"
assert_contains "$kt" 'private const val CPF_MODULUS = 11' "módulo 11"
# Desde a M3 E05 (Task 7), a base é parâmetro, com o G1 por padrão (o G12 é a mesma regra).
assert_contains "$kt" 'private fun fictitiousCpf(baseDigits: String = G1_CPF_BASE): String' "a base padrão é a do G1"
assert_contains "$kt" 'val d1 = digit(baseDigits, CPF_FIRST_WEIGHT)' "primeiro dígito com peso 10 sobre a base"
assert_contains "$kt" 'digit(baseDigits + d1, CPF_FIRST_WEIGHT + 1)' "segundo dígito com peso 11 sobre a base + d1"
assert_contains "$kt" 'return "$baseDigits$d1$d2"' "o CPF é a base e os dois dígitos"
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
assert_contains "$kt" 'texts.none { it.contains(cpf) }' "nenhum nó com os 11 dígitos do CPF do G1"
assert_not_contains "$kt" 'ELEVEN_DIGITS' "sem varredura genérica de 11 dígitos"
assert_contains "$kt" 'busca pelo documento do G1 não achou reservas' "resultado vazio falha com mensagem clara"
assert_contains "$kt" 'expected.size > SMALL_PAGE' "pagesDoNotRepeat exige mais de 3 hospedados (pré-condição do seed)"
assert_contains "$kt" 'loaded.pages >= MIN_PAGES' "pagesDoNotRepeat exige 2 páginas ou mais"
assert_contains "$kt" 'ReservationSearchSource by inner' "a medição de páginas decora a fonte real (delega o resto)"
assert_contains "$kt" 'RecordingSource(connection.reservationSearchSource())' "filteredRows mede pela fonte decorada"
assert_contains "$kt" 'calls += SearchCall(cursor, limit, it.items.size, filter.statuses)' \
  "cada search registra cursor, limit, linhas e as situações do filtro"
assert_contains "$kt" 'source.calls.filter { it.rows > 0 }' "página = chamada que trouxe linhas"
assert_contains "$kt" 'source.calls.clear() search.search()' "só contam as chamadas da busca filtrada, não as do start()"
assert_contains "$kt" 'loaded.calls.all { it.limit == SMALL_PAGE }' "toda chamada com limit = SMALL_PAGE"
assert_contains "$kt" 'assertNull(loaded.calls.first().cursor' "a 1ª chamada sem cursor"
assert_contains "$kt" 'loaded.calls.drop(1).all { it.cursor != null }' "as seguintes com cursor"
assert_not_contains "$kt" 'sizes' "sem medição por tamanhos da tabela (o DataTable pagina sozinho)"
assert_contains "$kt" 'assertTrue(expected.isNotEmpty(), "$NEED_SEED' "periodAndStatusFilter exige hospedados (pré-condição do seed)"
assert_contains "$kt" 'private const val PAGE_LIMIT = 200' "limit da lista do dia no teto do servidor (PageParams.MAX_LIMIT = 200)"
assert_contains "$kt" 'URLEncoder.encode(G1_HOLDER, Charsets.UTF_8)' "o nome também na forma de URL"
assert_contains "$kt" 'encoded.replace("+", "%20")' "o nome com %20"
assert_contains "$kt" 'cpf.takeLast(2)}"' "o CPF também formatado NNN.NNN.NNN-NN"
assert_contains "$kt" 'a captura não pegou a linha de controle de $path' "controle positivo da captura"
assert_contains "$kt" 'Logger.getLogger("zeny.live.control").warning(' "controle positivo pelo JUL"
assert_contains "$kt" 'System.err.println("stderr $CONTROL_MARKER")' "controle positivo por System.err"
assert_contains "$kt" 'File("build/live/reservation-search.png")' "captura da tela"

finish
