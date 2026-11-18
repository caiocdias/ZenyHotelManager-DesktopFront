package com.zeny.desktop.app.reservations

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.zeny.desktop.app.reception.ReceptionText
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.AvailabilityResponse
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.QuoteUnavailableResponse
import de.quati.kotlin.util.Option
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * As leituras e as comparações da `LiveAvailabilityCheck`, sem rede: recebem a resposta da API como dado, para
 * a `LiveAvailabilityReadersTest` provar, com um servidor de mentira, que elas pegam defeito e que leem certo os
 * nós fora da área visível (o erro de medição da primeira rodada ao vivo da E01).
 */

private const val NO_DAY = "—"
private val rawCode = Regex("""\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b""")

/** Uma chamada ao servidor registrada na fonte: o que foi pedido e quanto levou. */
internal data class RecordedCall(
    val operation: String,
    val request: String,
    val nanos: Long,
)

/** Decorator que registra cada chamada da fonte real: a medida é feita na fonte, não pelo que a tela mostra. */
internal class RecordingSource(
    private val delegate: AvailabilitySource,
) : AvailabilitySource {
    private val log = CopyOnWriteArrayList<RecordedCall>()

    fun calls(operation: String): List<RecordedCall> = log.filter { it.operation == operation }

    private suspend fun <T> record(
        operation: String,
        request: String,
        block: suspend () -> T,
    ): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            log += RecordedCall(operation, request, System.nanoTime() - started)
        }
    }

    override suspend fun businessDay(): BusinessDay = record("businessDay", "") { delegate.businessDay() }

    override suspend fun availability(
        from: BusinessDate,
        to: BusinessDate,
    ): AvailabilityResponse =
        record("availability", "${from.toApi()}..${to.toApi()}") { delegate.availability(from, to) }

    override suspend fun quote(
        arrival: BusinessDate,
        departure: BusinessDate,
        adults: Int,
        childAges: List<Int>,
    ): QuoteResponse =
        record("quote", "${arrival.toApi()}..${departure.toApi()} adults=$adults children=$childAges") {
            delegate.quote(arrival, departure, adults, childAges)
        }
}

/** O texto do nó de [tag] (árvore mesclada); o nó existe mesmo fora da área visível, e a leitura não depende dela. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.nodeText(tag: String): String =
    onNodeWithTag(tag)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.Text)
        .orEmpty()
        .joinToString(" ") { it.text }

/** Todo texto que a tela diz, nas árvores mesclada e não mesclada: texto, campo editável, descrições e erro. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.allTexts(): List<String> {
    fun texts(node: SemanticsNode): List<String> {
        val config = node.config
        val own =
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(
                    config.getOrNull(SemanticsProperties.EditableText)?.text,
                    config.getOrNull(SemanticsProperties.StateDescription),
                    config.getOrNull(SemanticsProperties.Error),
                ) + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        return own + node.children.flatMap(::texts)
    }
    return texts(onRoot(useUnmergedTree = true).fetchSemanticsNode()) + texts(onRoot().fetchSemanticsNode())
}

/**
 * Cada célula da tela (todas, inclusive as fora da área visível) mostra o `available` da leitura nova da API; o
 * calendário tem [windowDays] datas a partir do dia operacional e as linhas dos tipos da API, com [names].
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertCalendarShows(
    calendar: AvailabilityState,
    api: AvailabilityResponse,
    windowDays: Int,
    names: Set<String>,
) {
    val first = checkNotNull(calendar.from) { "o calendário não leu o dia operacional" }
    assertEquals(calendar.businessDay, first, "o calendário não começa na data operacional")
    assertEquals(windowDays, calendar.dates.size, "o calendário não tem $windowDays datas")
    assertEquals(names, calendar.rows.map { it.name }.toSet(), "os tipos de UH do calendário")
    assertEquals(names.size, calendar.rows.size, "o calendário não tem ${names.size} linhas")
    assertEquals(
        api.roomTypes.map { it.roomTypeId.toString() }.toSet(),
        calendar.rows.map { it.roomTypeId }.toSet(),
        "os tipos de UH do calendário não são os da API",
    )
    assertEquals(calendar.rows.size, api.roomTypes.size, "o número de tipos de UH não bate com a API")
    var checked = 0
    api.roomTypes.forEach { type ->
        val byDate = type.days.associateBy { it.date }
        var numeric = 0
        calendar.dates.forEach { date ->
            val expected = byDate[date.toApi()]?.available?.toString() ?: NO_DAY
            val shown = nodeText("availability-cell-${type.roomTypeId}-${date.toApi()}")
            assertEquals(expected, shown, "${type.name} em ${date.toApi()}: a célula não bate com a API")
            if (expected != NO_DAY) numeric++
            checked++
        }
        assertTrue(numeric > 0, "${type.name}: a API não trouxe dia algum na janela (traços só batem com traços)")
    }
    assertEquals(names.size * windowDays, checked, "células conferidas")
}

private fun occurrences(
    text: String,
    part: String,
): Int = text.windowed(part.length).count { it == part }

/**
 * As ofertas na tela ([shown], na ordem da tela) batem com as da API, na quantidade e no dinheiro (total e cada
 * noite, por [ReceptionText.money]); casam por tipo e tarifa, não pela posição, porque a ordem do servidor entre
 * duas chamadas não é garantia.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertOffersMatch(
    shown: List<QuoteOfferResponse>,
    api: QuoteResponse,
) {
    assertEquals(api.offers.size, shown.size, "o número de ofertas não bate com a API")

    fun key(offer: QuoteOfferResponse) = offer.roomTypeId to offer.ratePlanId
    assertEquals(
        api.offers.size,
        api.offers
            .map(::key)
            .toSet()
            .size,
        "a API repetiu tipo e tarifa entre as ofertas",
    )
    assertEquals(api.offers.map(::key).toSet(), shown.map(::key).toSet(), "as ofertas da tela não são as da API")
    shown.forEachIndexed { index, onScreen ->
        val offer = api.offers.single { key(it) == key(onScreen) }
        val text = nodeText("quote-offer-$index")
        val expected = (listOf(offer.total) + offer.nights.map { it.total }).map(ReceptionText::money)
        expected.groupingBy { it }.eachCount().forEach { (money, count) ->
            val found = occurrences(text, money)
            assertTrue(
                found >= count,
                "oferta $index (${offer.roomTypeName}): $money x$count, achei $found em \"$text\"",
            )
        }
        if (offer.mealPlan == "BREAKFAST") {
            assertTrue(text.contains("Café da manhã"), "oferta $index: BREAKFAST não aparece como Café da manhã")
        }
    }
    assertEquals(0, onAllNodesWithTag("quote-offer-${api.offers.size}").fetchSemanticsNodes().size, "oferta a mais")
}

/** As combinações recusadas da API. */
internal fun QuoteResponse.refused(): List<QuoteUnavailableResponse> =
    ((unavailable as? Option.Some)?.value).orEmpty().filterNotNull()

/**
 * Cada combinação recusada pela API tem um nó na tela, que começa pelo nome do tipo e traz o texto de cada motivo;
 * há tantos nós quanto recusas. Devolve o texto dos nós.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertRefusalsShown(
    api: QuoteResponse,
    typeNames: Map<String, String>,
): List<String> {
    val refused = api.refused()
    val texts = refused.indices.map { nodeText("quote-unavailable-$it") }
    assertEquals(0, onAllNodesWithTag("quote-unavailable-${refused.size}").fetchSemanticsNodes().size, "recusa a mais")
    val free = texts.indices.toMutableSet()
    refused.forEach { item ->
        val name = typeNames[item.roomTypeId.toString()] ?: item.roomTypeCode
        val match =
            free.firstOrNull { index ->
                texts[index].startsWith("$name ·") &&
                    item.reasons.all { texts[index].contains(AvailabilityText.refusal(it)) }
            }
        assertTrue(match != null, "a recusa de $name (${item.ratePlanCode}) não está na tela: $texts")
        free.remove(match)
    }
    return texts
}

/** Nenhum nó da tela traz um código de erro cru: nem os da API, nem nada com o formato `PALAVRA_PALAVRA`. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertNoRawCode(
    api: QuoteResponse,
    extra: Set<String>,
) {
    val codes = api.refused().flatMap { item -> item.reasons.map { it.code } }.toSet() + extra
    allTexts().forEach { text ->
        codes.forEach { assertTrue(!text.contains(it), "há nó com o código cru $it: \"$text\"") }
        assertTrue(rawCode.find(text) == null, "há nó com texto no formato de código cru: \"$text\"")
    }
}
