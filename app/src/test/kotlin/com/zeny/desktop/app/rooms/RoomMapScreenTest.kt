package com.zeny.desktop.app.rooms

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.OutOfOrderOverlayResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RoomMapScreenTest {
    private val a = roomId(1)
    private val b = roomId(2)
    private val c = roomId(3)
    private val d = roomId(4)

    private fun network() = ApiError.Network(RuntimeException("sem rede"))

    private fun ComposeUiTest.map(source: FakeRoomsSource) {
        setContent {
            val scope = rememberCoroutineScope()
            val state = remember { RoomMapState(source, scope) }
            ZenyTheme { RoomMapScreen(state) }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.ready(source: FakeRoomsSource) {
        source.emit(RealtimeEvent.Ready)
        waitForIdle()
    }

    private fun ComposeUiTest.tile(id: String) = onNodeWithTag("room-tile-$id")

    @Test
    fun showsFloorsTilesAndLegend() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.realtimeStatus.value = RealtimeStatus.Live
            source.put(
                roomStatus(a, "101", floor = "1"),
                roomStatus(b, "102", floor = "1", housekeeping = HousekeepingStatus.DIRTY),
                roomStatus(c, "201", floor = "2", type = typeId(2), occupancy = Occupancy.OCCUPIED),
                roomStatus(d, "S1", floor = null, outOfOrder = OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER),
            )
            map(source)

            onNodeWithText("Mapa de UHs").assertExists()
            onNodeWithText("Carregando…").assertExists()

            ready(source)

            onNodeWithText("Carregando…").assertDoesNotExist()
            onNodeWithText("Ao vivo").assertExists()
            onNodeWithText("Andar 1").assertExists()
            onNodeWithText("Andar 2").assertExists()
            onNodeWithText("Sem andar").assertExists()
            tile(a).assert(hasText("101")).assert(hasText("Limpa"))
            tile(b).assert(hasText("102")).assert(hasText("Suja"))
            tile(c).assert(hasText("201")).assert(hasText("Ocupada · Limpa"))
            tile(d).assert(hasText("S1")).assert(hasText("Fora de ordem"))
            onNodeWithText("Limpa: 1").assertExists()
            onNodeWithText("Suja: 1").assertExists()
            onNodeWithText("Ocupada: 1").assertExists()
            onNodeWithText("Fora de ordem: 1").assertExists()
            onNodeWithText("Inspecionada: 0").assertExists()
            onNodeWithTag("type-filter-${typeId(1)}").assert(hasText("Standard"))
            onNodeWithTag("type-filter-${typeId(2)}").assert(hasText("Luxo"))
        }

    @Test
    fun clickOpensDetailAndCloses() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101", floor = "1").copy(inspectionPending = true),
                roomStatus(b, "102", floor = "1", outOfOrder = OutOfOrderOverlayResponse.Kind.OUT_OF_SERVICE),
            )
            map(source)
            ready(source)

            onNodeWithTag("room-detail").assertDoesNotExist()
            tile(a).performClick()
            waitForIdle()

            onNodeWithText("UH 101").assertExists()
            onNodeWithText("Andar: 1").assertExists()
            onNodeWithText("Tipo: Standard").assertExists()
            onNodeWithText("Observações: Vista para o mar").assertExists()
            onNodeWithText("Ocupação: Vaga").assertExists()
            onNodeWithText("Governança: Limpa, aguardando inspeção").assertExists()
            onNodeWithText("Sinalização: Nenhuma").assertExists()

            onNodeWithText("Fechar").performClick()
            waitForIdle()
            onNodeWithTag("room-detail").assertDoesNotExist()

            tile(b).performClick()
            waitForIdle()
            onNodeWithText("Fora de serviço: de 10/11/2026 a 12/11/2026").assertExists()
        }

    @Test
    fun filterHidesTiles() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101", type = typeId(1)),
                roomStatus(b, "102", type = typeId(2), housekeeping = HousekeepingStatus.DIRTY),
                roomStatus(c, "103", type = typeId(2)),
            )
            map(source)
            ready(source)

            onNodeWithTag("type-filter-${typeId(2)}").performClick()
            waitForIdle()
            tile(a).assertDoesNotExist()
            tile(b).assertExists()
            tile(c).assertExists()

            onNodeWithTag("status-filter-DIRTY").performClick()
            waitForIdle()
            tile(b).assertExists()
            tile(c).assertDoesNotExist()

            onNodeWithTag("type-filter-${typeId(2)}").performClick()
            onNodeWithTag("type-filter-${typeId(1)}").performClick()
            waitForIdle()
            tile(a).assertDoesNotExist()
            tile(b).assertDoesNotExist()
            onNodeWithText("Nenhuma UH com os filtros escolhidos").assertExists()
            onNodeWithText("Nenhuma UH cadastrada").assertDoesNotExist()
        }

    @Test
    fun reconnectingBannerShown() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101"))
            source.realtimeStatus.value = RealtimeStatus.Live
            map(source)
            ready(source)
            onNodeWithText("Ao vivo").assertExists()

            source.realtimeStatus.value = RealtimeStatus.Reconnecting(attempt = 1, delayMillis = 1_000)
            waitForIdle()
            onNodeWithText("Reconectando…").assertExists()
            onNodeWithText("Ao vivo").assertDoesNotExist()
            tile(a).assertExists()

            source.realtimeStatus.value = RealtimeStatus.Failed(network())
            waitForIdle()
            onNodeWithText(network().userMessage).assertExists()
            onNodeWithText("Reconectando…").assertDoesNotExist()
        }

    @Test
    fun errorShowsRetry() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101"))
            source.statusesError = network()
            map(source)
            ready(source)

            onNodeWithText(network().userMessage).assertExists()
            tile(a).assertDoesNotExist()

            source.statusesError = null
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()

            onNodeWithText("Tentar de novo").assertDoesNotExist()
            tile(a).assertExists()
        }

    @Test
    fun emptyStateMessage() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            map(source)
            ready(source)

            onNodeWithText("Nenhuma UH cadastrada").assertExists()
            onNodeWithText("Carregando…").assertDoesNotExist()
        }

    @Test
    fun errorWithTilesKeepsMap() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101"))
            map(source)
            ready(source)
            tile(a).assertExists()

            source.statusesError = network()
            source.emit(RealtimeEvent.Reset)
            waitForIdle()

            val inBand = hasAnyAncestor(hasTestTag("map-error"))
            onNode(hasText(network().userMessage) and inBand).assertExists()
            onNode(hasText("Tentar de novo") and inBand).assertExists()
            tile(a).assertExists()

            source.statusesError = null
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()
            onNodeWithTag("map-error").assertDoesNotExist()
            tile(a).assertExists()
        }

    @Test
    fun noGuestDataOnScreen() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101", occupancy = Occupancy.OCCUPIED).copy(guestCount = Option.Some(2)),
                roomStatus(b, "102").copy(guestCount = Option.Some(0)),
            )
            map(source)
            ready(source)

            tile(a).performClick()
            waitForIdle()
            onNodeWithText("Hóspedes: 2").assertExists()
            val allowed =
                listOf(
                    "UH ",
                    "Andar: ",
                    "Tipo: ",
                    "Observações: ",
                    "Ocupação: ",
                    "Hóspedes: ",
                    "Governança: ",
                    "Sinalização: ",
                    "Fora de ordem: ",
                    "Fora de serviço: ",
                    "Fechar",
                )
            val texts =
                onAllNodes(hasAnyAncestor(hasTestTag("room-detail")), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .flatMap { node ->
                        node.config
                            .getOrNull(SemanticsProperties.Text)
                            .orEmpty()
                            .map { it.text }
                    }
            assertTrue(texts.isNotEmpty())
            texts.forEach { text ->
                assertTrue(allowed.any { text.startsWith(it) }, "texto fora do permitido no detalhe: $text")
            }

            onNodeWithText("Fechar").performClick()
            tile(b).performClick()
            waitForIdle()
            onAllNodesWithText("Hóspedes", substring = true).assertCountEquals(0)
        }

    /** Todo texto da árvore semântica (não as tags), para conferir que nada cru chega ao usuário. */
    private fun ComposeUiTest.screenTexts(): List<String> =
        onAllNodes(SemanticsMatcher("qualquer nó") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                val config = node.config
                config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    listOfNotNull(config.getOrNull(SemanticsProperties.StateDescription))
            }

    @Test
    fun forbiddenTypesShowNeutralLabelsNotIds() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.roomTypesError = ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList())
            source.roomError = ApiError.Problem(403, "PERMISSION_DENIED", null, emptyList())
            source.put(roomStatus(a, "101", type = typeId(1)), roomStatus(b, "102", type = typeId(2)))
            map(source)
            ready(source)

            onNodeWithTag("type-filter-${typeId(1)}").assert(hasText("Tipo 1"))
            onNodeWithTag("type-filter-${typeId(2)}").assert(hasText("Tipo 2"))
            tile(b).performClick()
            waitForIdle()
            onNodeWithText("Tipo: Tipo 2").assertExists()

            val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            val texts = screenTexts()
            assertTrue(texts.isNotEmpty())
            texts.forEach { assertTrue(!uuid.containsMatchIn(it), "id cru na tela: $it") }

            onNodeWithText("Fechar").performClick()
            onNodeWithTag("type-filter-${typeId(2)}").performClick()
            waitForIdle()
            tile(a).assertDoesNotExist()
            tile(b).assertExists()
        }

    @Test
    fun tileIsButtonAndOpenOneIsSelected() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101"), roomStatus(b, "102"))
            map(source)
            ready(source)

            val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            tile(a).assert(button).assertIsNotSelected()
            tile(b).assert(button).assertIsNotSelected()

            tile(a).performClick()
            waitForIdle()
            tile(a).assertIsSelected()
            tile(b).assertIsNotSelected()

            tile(b).performClick()
            waitForIdle()
            tile(a).assertIsNotSelected()
            tile(b).assertIsSelected()

            onNodeWithText("Fechar").performClick()
            waitForIdle()
            tile(b).assertIsNotSelected()
        }

    @Test
    fun syncedMapLosingSubscriptionShowsDisconnectedWithRetry() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101"))
            source.realtimeStatus.value = RealtimeStatus.Live
            map(source)
            ready(source)
            onNodeWithText("Desconectado").assertDoesNotExist()

            // Um `Stop` fora do fim da sessão: a assinatura acaba sem erro e o tempo real volta ao repouso.
            source.realtimeStatus.value = RealtimeStatus.Idle
            source.end()
            waitForIdle()

            val inBar = hasAnyAncestor(hasTestTag("realtime-bar"))
            onNode(hasText("Desconectado") and inBar).assertExists()
            onNodeWithText("Conectando…").assertDoesNotExist()
            tile(a).assertExists()
            onNode(hasText("Tentar de novo") and inBar).performClick()
            waitForIdle()

            onNodeWithText("Desconectado").assertDoesNotExist()
            onNodeWithText("Conectando…").assertExists()
            assertEquals(2, source.subscriptions)
            assertEquals(1, source.activeSubscriptions)
        }
}
