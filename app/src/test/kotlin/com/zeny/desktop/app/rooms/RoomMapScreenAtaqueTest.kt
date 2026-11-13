package com.zeny.desktop.app.rooms

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.design.theme.ZenyColors
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.OutOfOrderOverlayResponse
import com.zeny.desktop.sdk.model.RoomResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import com.zeny.desktop.sdk.model.RoomStatusResponse.ServiceFlag
import de.quati.kotlin.util.Option
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ataque à [RoomMapScreen]: cada estado do corpo, a barra do tempo real, a legenda que filtra, os chips de tipo, a
 * ordem dos andares, a atualização ao vivo de um ladrilho, o painel lateral, a LGPD e os textos sem código cru.
 */
@OptIn(ExperimentalTestApi::class)
class RoomMapScreenAtaqueTest {
    private val a = roomId(1)
    private val b = roomId(2)
    private val c = roomId(3)
    private val d = roomId(4)

    private fun network() = ApiError.Network(RuntimeException("sem rede"))

    private fun ComposeUiTest.map(source: RoomsSource) {
        setContent {
            val scope = rememberCoroutineScope()
            val state = remember { RoomMapState(source, scope) }
            ZenyTheme { RoomMapScreen(state) }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.loaded(
        source: RoomsSource,
        fake: FakeRoomsSource,
    ) {
        map(source)
        fake.emit(RealtimeEvent.Ready)
        waitForIdle()
    }

    private fun ComposeUiTest.loaded(source: FakeRoomsSource) = loaded(source, source)

    private fun ComposeUiTest.tile(id: String) = onNodeWithTag("room-tile-$id")

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.retryButtons() = onAllNodesWithText("Tentar de novo")

    /** Todos os textos da árvore semântica (sem fusão), com as descrições. */
    private fun ComposeUiTest.allTexts(): List<String> =
        onAllNodes(SemanticsMatcher("qualquer nó") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text } +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }

    private fun ComposeUiTest.top(text: String) = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun ComposeUiTest.left(
        text: String,
        inTile: String,
    ) = onNode(hasText(text) and hasAnyAncestor(hasTestTag("room-tile-$inTile")), useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
        .left

    /** A cor que mais aparece no chip de status de um ladrilho: o fundo, que vem do `tone`. */
    private fun ComposeUiTest.chipColor(
        id: String,
        label: String,
    ): Int {
        val img =
            onNode(hasText(label) and hasAnyAncestor(hasTestTag("room-tile-$id")), useUnmergedTree = true)
                .captureToImage()
                .toAwtImage()
        val counts = HashMap<Int, Int>()
        for (x in 0 until img.width) {
            for (y in 0 until img.height) counts.merge(img.getRGB(x, y), 1, Int::plus)
        }
        return counts.maxBy { it.value }.key
    }

    // ---------------------------------------------------------------- estados do corpo

    @Test
    fun loadingWithoutTilesShowsOnlyLoading() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            map(source)

            onNodeWithText("Carregando…").assertExists()
            onNodeWithText("Nenhuma UH cadastrada").assertDoesNotExist()
            onNodeWithText("Nenhuma UH com os filtros escolhidos").assertDoesNotExist()
            retryButtons().assertCountEquals(0)
            onNodeWithTag("map-error").assertDoesNotExist()
            tile(a).assertDoesNotExist()
        }

    @Test
    fun reloadAfterRetryKeepsTilesInsteadOfLoading() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            loaded(source)
            source.statusesError = network()
            source.emit(RealtimeEvent.Reset)
            waitForIdle()
            onNodeWithTag("map-error").assertExists()

            source.statusesError = null
            source.holdStatuses = true
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()

            // A recarga está em voo (loading) e os ladrilhos seguem à vista, sem o "Carregando…" no lugar deles.
            assertEquals(1, source.heldStatuses.size)
            onNodeWithText("Carregando…").assertDoesNotExist()
            onNodeWithTag("map-error").assertDoesNotExist()
            tile(a).assertExists()
            tile(b).assertExists()

            source.heldStatuses.forEach { it.complete(Unit) }
            waitForIdle()
            tile(a).assertExists()
            onNodeWithTag("map-error").assertDoesNotExist()
        }

    @Test
    fun errorWithoutTilesShowsSingleRetryThatCallsRetry() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.statusesError = network()
            loaded(source)
            val callsBefore = source.statusesCalls

            onNodeWithText(network().userMessage).assertExists()
            retryButtons().assertCountEquals(1)
            onNodeWithTag("map-error").assertDoesNotExist()
            onNodeWithText("Carregando…").assertDoesNotExist()
            onNodeWithText("Nenhuma UH cadastrada").assertDoesNotExist()

            source.holdStatuses = true
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()

            assertEquals(callsBefore + 1, source.statusesCalls)
            onNodeWithText("Carregando…").assertExists()
            onNodeWithText(network().userMessage).assertDoesNotExist()
        }

    @Test
    fun errorWithTilesHasOneRetryAndTilesStayClickable() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            loaded(source)
            source.statusesError = network()
            source.emit(RealtimeEvent.Reset)
            waitForIdle()

            // Só a faixa: o corpo não repete o ErrorState debaixo dela.
            retryButtons().assertCountEquals(1)
            onAllNodesWithText(network().userMessage).assertCountEquals(1)
            tile(a).assertHasClickAction().performClick()
            waitForIdle()
            onNodeWithText("UH 101").assertExists()
            onNodeWithTag("map-error").assertExists()
        }

    @Test
    fun errorWhileFiltersHideEverythingLeavesFiltersUsable() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            loaded(source)
            click("status-filter-INSPECTED")
            onNodeWithText("Nenhuma UH com os filtros escolhidos").assertExists()

            source.statusesError = network()
            source.emit(RealtimeEvent.Reset)
            waitForIdle()

            // O corpo mostra o erro, mas o filtro continua à vista e desmarcável: o usuário não fica sem saída.
            onNodeWithText(network().userMessage).assertExists()
            onNodeWithTag("status-filter-INSPECTED").assertIsSelected().assertHasClickAction()
            click("status-filter-INSPECTED")

            tile(a).assertExists()
            tile(b).assertExists()
            onNode(hasText(network().userMessage) and hasAnyAncestor(hasTestTag("map-error"))).assertExists()
            retryButtons().assertCountEquals(1)
        }

    @Test
    fun streamFailureWithTilesShowsBandAndRetrySubscribesAgain() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            loaded(source)
            assertEquals(1, source.subscriptions)

            source.end(ApiError.Problem(404, "NOT_FOUND", null, emptyList()))
            waitForIdle()

            onNode(hasText("O registro não foi encontrado.") and hasAnyAncestor(hasTestTag("map-error"))).assertExists()
            tile(a).assertExists()
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()

            assertEquals(2, source.subscriptions)
            onNodeWithTag("map-error").assertDoesNotExist()
            tile(a).assertExists()
        }

    @Test
    fun detailReadFailureShowsBandKeepsPanelAndRetryRereadsRegistry() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.roomError = serverErrorProblem()
            loaded(source)
            tile(a).performClick()
            waitForIdle()

            onNodeWithTag("map-error").assertExists()
            onNodeWithText("UH 101").assertExists()
            onNodeWithText("Observações", substring = true).assertDoesNotExist()

            source.roomError = null
            onNodeWithText("Tentar de novo").performClick()
            waitForIdle()

            assertEquals(listOf(a, a), source.roomCalls)
            onNodeWithTag("map-error").assertDoesNotExist()
            onNodeWithText("Observações: Vista para o mar").assertExists()
        }

    @Test
    fun noRoomsWithLegendFilterOnStillSaysNoRoomsRegistered() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            loaded(source)
            onNodeWithText("Nenhuma UH cadastrada").assertExists()

            // Hotel sem UH: marcar "Limpa: 0" na legenda não pode fazer a tela sugerir que há UHs escondidas.
            click("status-filter-CLEAN")

            onNodeWithText("Nenhuma UH cadastrada").assertExists()
            onNodeWithText("Nenhuma UH com os filtros escolhidos").assertDoesNotExist()
        }

    // ---------------------------------------------------------------- barra do tempo real

    @Test
    fun realtimeBarShowsOneLabelPerStatus() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            val labels = listOf("Conectando…", "Ao vivo", "Reconectando…")

            fun only(label: String) =
                labels.forEach { onAllNodesWithText(it).assertCountEquals(if (it == label) 1 else 0) }

            loaded(source)
            only("Conectando…")

            source.realtimeStatus.value = RealtimeStatus.Connecting
            waitForIdle()
            only("Conectando…")

            source.realtimeStatus.value = RealtimeStatus.Live
            waitForIdle()
            only("Ao vivo")

            source.realtimeStatus.value = RealtimeStatus.Reconnecting(attempt = 3, delayMillis = 4_000)
            waitForIdle()
            only("Reconectando…")

            source.realtimeStatus.value = RealtimeStatus.Live
            waitForIdle()
            only("Ao vivo")
        }

    @Test
    fun realtimeFailedShowsCatalogMessageWithoutCodeOrDetail() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            val failure = serverErrorProblem()
            loaded(source)

            source.realtimeStatus.value = RealtimeStatus.Failed(failure)
            waitForIdle()

            onNodeWithText("O servidor falhou ao processar o pedido. Tente de novo em instantes.").assertExists()
            onNodeWithText("Ao vivo").assertDoesNotExist()
            onNodeWithText("Conectando…").assertDoesNotExist()
            val leaks = allTexts().filter { "INTERNAL_ERROR" in it || "NullPointer" in it || "500" in it }
            assertTrue(leaks.isEmpty(), "código ou detalhe cru na tela: $leaks")
            tile(a).assertExists()
        }

    @Test
    fun incompatibleResponseErrorShowsVersionMessageOnly() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.statusesError = incompatibleResponse()
            loaded(source)

            onNodeWithText(VERSION_MESSAGE).assertExists()
            val leaks = allTexts().filter { "BLOCKED" in it || "Unknown enum" in it || "Exception" in it }
            assertTrue(leaks.isEmpty(), "causa crua na tela: $leaks")
        }

    // ---------------------------------------------------------------- legenda e filtros

    @Test
    fun legendCountsFollowTypeFilterButNotStatusFilter() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101", type = typeId(1)),
                roomStatus(b, "102", type = typeId(1), housekeeping = HousekeepingStatus.DIRTY),
                roomStatus(c, "201", type = typeId(2), housekeeping = HousekeepingStatus.DIRTY),
                roomStatus(d, "202", type = typeId(2), occupancy = Occupancy.OCCUPIED),
            )
            loaded(source)
            onNodeWithText("Suja: 2").assertExists()
            onNodeWithText("Limpa: 1").assertExists()
            onNodeWithText("Ocupada: 1").assertExists()

            click("status-filter-DIRTY")
            // O filtro por status não mexe nos contadores.
            onNodeWithText("Suja: 2").assertExists()
            onNodeWithText("Limpa: 1").assertExists()

            click("type-filter-${typeId(2)}")
            onNodeWithText("Suja: 1").assertExists()
            onNodeWithText("Limpa: 0").assertExists()
            onNodeWithText("Ocupada: 1").assertExists()
            tile(c).assertExists()
            tile(b).assertDoesNotExist()
        }

    @Test
    fun legendChipToggledTwiceShowsEverythingAgain() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101"),
                roomStatus(b, "102", housekeeping = HousekeepingStatus.DIRTY),
            )
            loaded(source)
            onNodeWithTag("status-filter-DIRTY").assertIsNotSelected()

            click("status-filter-DIRTY")
            onNodeWithTag("status-filter-DIRTY").assertIsSelected()
            tile(a).assertDoesNotExist()
            tile(b).assertExists()

            click("status-filter-DIRTY")
            onNodeWithTag("status-filter-DIRTY").assertIsNotSelected()
            tile(a).assertExists()
            tile(b).assertExists()
            onNodeWithText("Nenhuma UH com os filtros escolhidos").assertDoesNotExist()
        }

    @Test
    fun typeChipToggledTwiceShowsEverythingAgain() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101", type = typeId(1)), roomStatus(b, "102", type = typeId(2)))
            loaded(source)

            click("type-filter-${typeId(1)}")
            onNodeWithTag("type-filter-${typeId(1)}").assertIsSelected()
            tile(b).assertDoesNotExist()

            click("type-filter-${typeId(1)}")
            onNodeWithTag("type-filter-${typeId(1)}").assertIsNotSelected()
            tile(a).assertExists()
            tile(b).assertExists()
        }

    @Test
    fun vanishedTypeInFilterStaysAsChipAndCanBeUnchecked() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101", type = typeId(1)), roomStatus(b, "102", type = typeId(2)))
            loaded(source)
            click("type-filter-${typeId(2)}")
            tile(a).assertDoesNotExist()

            // O tipo Luxo some do catálogo, junto com a única UH dele.
            source.types.removeAll { it.id.toString() == typeId(2) }
            source.server.remove(b)
            source.emit(RealtimeEvent.Reset)
            waitForIdle()

            onNodeWithText("Nenhuma UH com os filtros escolhidos").assertExists()
            onNodeWithTag("type-filter-${typeId(2)}").assertIsSelected().assertHasClickAction()
            click("type-filter-${typeId(2)}")

            tile(a).assertExists()
            onNodeWithTag("type-filter-${typeId(2)}").assertDoesNotExist()
            onNodeWithText("Nenhuma UH com os filtros escolhidos").assertDoesNotExist()
        }

    // ---------------------------------------------------------------- quadro

    @Test
    fun floorsInNaturalOrderWithNoFloorLast() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(roomId(1), "S1", floor = null),
                roomStatus(roomId(2), "1010", floor = "10"),
                roomStatus(roomId(3), "201", floor = "2"),
                roomStatus(roomId(4), "101", floor = "1"),
                roomStatus(roomId(5), "S2", floor = "  "),
            )
            loaded(source)

            val tops = listOf("Andar 1", "Andar 2", "Andar 10", "Sem andar").map { top(it) }
            assertEquals(tops.sorted(), tops, "andares fora de ordem: $tops")
            onAllNodesWithText("Sem andar").assertCountEquals(1)
            // O andar em branco vai para "Sem andar", junto da UH sem andar.
            tile(roomId(5)).assertExists()
            assertTrue(top("Sem andar") < onNodeWithTag("room-tile-${roomId(5)}").fetchSemanticsNode().boundsInRoot.top)
        }

    @Test
    fun tilesInsideFloorInNaturalNumberOrder() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "10"), roomStatus(b, "2"), roomStatus(c, "1"))
            loaded(source)

            val xs = listOf(c to "1", b to "2", a to "10").map { (id, number) -> left(number, inTile = id) }
            assertEquals(xs.sorted(), xs, "UHs fora de ordem no andar: $xs")
        }

    @Test
    fun tileColorComesFromStatusTone() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101"),
                roomStatus(b, "102", housekeeping = HousekeepingStatus.DIRTY),
                roomStatus(c, "103", outOfOrder = OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER),
                roomStatus(d, "104", occupancy = Occupancy.OCCUPIED),
            )
            loaded(source)

            assertEquals(ZenyColors.Success.toArgb(), chipColor(a, "Limpa"))
            assertEquals(ZenyColors.Warning.toArgb(), chipColor(b, "Suja"))
            assertEquals(ZenyColors.Danger.toArgb(), chipColor(c, "Fora de ordem"))
            assertEquals(ZenyColors.Info.toArgb(), chipColor(d, "Ocupada · Limpa"))
        }

    @Test
    fun liveMessageUpdatesTileLabelColorAndLegend() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101"), roomStatus(b, "102", housekeeping = HousekeepingStatus.DIRTY))
            loaded(source)
            tile(b).assert(hasText("Suja"))
            onNodeWithText("Suja: 1").assertExists()

            source.put(roomStatus(b, "102", housekeeping = HousekeepingStatus.CLEAN, version = 2))
            source.message(b, version = 2)
            waitForIdle()

            tile(b).assert(hasText("Limpa"))
            tile(b).assert(hasText("Suja").not())
            assertEquals(ZenyColors.Success.toArgb(), chipColor(b, "Limpa"))
            onNodeWithText("Suja: 0").assertExists()
            onNodeWithText("Limpa: 2").assertExists()
        }

    @Test
    fun tileIsClickableAndSaysNumberAndStatusInText() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(a, "101").copy(inspectionPending = true),
                roomStatus(b, "102", outOfOrder = OutOfOrderOverlayResponse.Kind.OUT_OF_SERVICE),
                roomStatus(c, "103", housekeeping = HousekeepingStatus.INSPECTED),
            )
            loaded(source)

            tile(a).assertHasClickAction().assert(hasText("101")).assert(hasText("Limpa, aguardando inspeção"))
            tile(b).assertHasClickAction().assert(hasText("102")).assert(hasText("Fora de serviço"))
            tile(c).assertHasClickAction().assert(hasText("103")).assert(hasText("Inspecionada"))
        }

    // ---------------------------------------------------------------- painel

    @Test
    fun panelShowsEveryFieldOfAnOccupiedOutOfOrderRoomWithoutFloor() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(
                roomStatus(
                    a,
                    "S1",
                    floor = null,
                    type = typeId(2),
                    housekeeping = HousekeepingStatus.DIRTY,
                    occupancy = Occupancy.OCCUPIED,
                    outOfOrder = OutOfOrderOverlayResponse.Kind.OUT_OF_ORDER,
                ).copy(guestCount = Option.Some(3), serviceFlag = ServiceFlag.DO_NOT_DISTURB),
            )
            loaded(source)
            tile(a).performClick()
            waitForIdle()

            val panel = hasAnyAncestor(hasTestTag("room-detail"))
            listOf(
                "UH S1",
                "Andar: Sem andar",
                "Tipo: Luxo",
                "Observações: Vista para o mar",
                "Ocupação: Ocupada",
                "Hóspedes: 3",
                "Governança: Suja",
                "Sinalização: Não perturbe",
                "Fora de ordem: de 10/11/2026 a 12/11/2026",
                "Fechar",
            ).forEach { onNode(hasText(it) and panel, useUnmergedTree = true).assertExists() }
        }

    @Test
    fun occupiedWithoutGuestCountShowsNoGuestLine() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101", occupancy = Occupancy.OCCUPIED))
            loaded(source)
            tile(a).performClick()
            waitForIdle()

            onNodeWithText("Ocupação: Ocupada").assertExists()
            onAllNodesWithText("Hóspedes", substring = true).assertCountEquals(0)
        }

    @Test
    fun notesOnlyAfterRegistryIsRead() =
        runComposeUiTest {
            val fake = FakeRoomsSource().withRooms101And102()
            val held = HeldRoomSource(fake)
            loaded(held, fake)
            tile(a).performClick()
            waitForIdle()

            onNodeWithText("UH 101").assertExists()
            onNodeWithText("Observações", substring = true).assertDoesNotExist()

            held.heldRoom
                .single()
                .second
                .complete(Unit)
            waitForIdle()
            onNodeWithText("Observações: Vista para o mar").assertExists()
        }

    @Test
    fun registryForbiddenShowsPanelWithoutNotesAndWithoutError() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            source.roomError = forbiddenProblem()
            loaded(source)
            tile(a).performClick()
            waitForIdle()

            onNodeWithText("UH 101").assertExists()
            onNodeWithText("Governança: Limpa").assertExists()
            onNodeWithText("Observações", substring = true).assertDoesNotExist()
            onNodeWithTag("map-error").assertDoesNotExist()
            retryButtons().assertCountEquals(0)
        }

    @Test
    fun blankNotesAreNotShown() =
        runComposeUiTest {
            val fake = FakeRoomsSource().withRooms101And102()
            val source =
                object : RoomsSource by fake {
                    override suspend fun room(id: String): RoomResponse = fake.room(id).copy(notes = Option.Some("   "))
                }
            loaded(source, fake)
            tile(a).performClick()
            waitForIdle()

            onNodeWithText("UH 101").assertExists()
            onNodeWithText("Observações", substring = true).assertDoesNotExist()
        }

    @Test
    fun selectingAnotherTileReplacesThePanel() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            loaded(source)
            tile(a).performClick()
            waitForIdle()
            onNodeWithText("UH 101").assertExists()

            tile(b).performClick()
            waitForIdle()

            onAllNodesWithTag("room-detail").assertCountEquals(1)
            onNodeWithText("UH 102").assertExists()
            onNodeWithText("UH 101").assertDoesNotExist()
        }

    @Test
    fun closeThenReopenSameTile() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            loaded(source)
            tile(a).performClick()
            waitForIdle()
            onNodeWithText("Fechar").performClick()
            waitForIdle()
            onNodeWithTag("room-detail").assertDoesNotExist()

            tile(a).performClick()
            waitForIdle()
            onNodeWithText("UH 101").assertExists()
        }

    @Test
    fun panelFollowsLiveUpdateOfSelectedRoom() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            loaded(source)
            tile(a).performClick()
            waitForIdle()
            onNodeWithText("Governança: Limpa").assertExists()

            source.put(roomStatus(a, "101", housekeeping = HousekeepingStatus.DIRTY, version = 2))
            source.message(a, version = 2)
            waitForIdle()

            onNodeWithText("Governança: Suja").assertExists()
            onNodeWithText("Governança: Limpa").assertDoesNotExist()
        }

    @Test
    fun roomRemovedWithPanelOpenClosesPanel() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            loaded(source)
            tile(a).performClick()
            waitForIdle()

            source.server.remove(a)
            source.emit(RealtimeEvent.Reset)
            waitForIdle()

            tile(a).assertDoesNotExist()
            onNodeWithTag("room-detail").assertDoesNotExist()
            onNodeWithText("UH 101").assertDoesNotExist()
            tile(b).assertExists()
        }

    @Test
    fun roomDeactivatedWithPanelOpenClosesPanel() =
        runComposeUiTest {
            val source = FakeRoomsSource().withRooms101And102()
            loaded(source)
            tile(a).performClick()
            waitForIdle()

            source.put(roomStatus(a, "101", version = 2, active = false))
            source.message(a, version = 2)
            waitForIdle()

            tile(a).assertDoesNotExist()
            onNodeWithTag("room-detail").assertDoesNotExist()
        }

    // ---------------------------------------------------------------- LGPD e textos crus

    @Test
    fun noIdentifiersRawEnumsOrTimestampsAnywhereOnScreen() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.realtimeStatus.value = RealtimeStatus.Live
            source.put(
                roomStatus(a, "101", occupancy = Occupancy.OCCUPIED).copy(
                    guestCount = Option.Some(2),
                    serviceFlag = ServiceFlag.MAKE_UP_ROOM,
                ),
                roomStatus(b, "102", outOfOrder = OutOfOrderOverlayResponse.Kind.OUT_OF_SERVICE),
                roomStatus(c, "103", housekeeping = HousekeepingStatus.INSPECTED, floor = null),
            )
            loaded(source)
            tile(a).performClick()
            waitForIdle()
            onNodeWithText("Hóspedes: 2").assertExists()

            val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-")
            val rawEnum = Regex("\\b[A-Z]{3,}(_[A-Z]+)*\\b")
            val texts = allTexts()
            assertTrue(texts.isNotEmpty())
            texts.forEach { text ->
                assertTrue(!uuid.containsMatchIn(text), "identificador cru na tela: $text")
                assertTrue(!rawEnum.containsMatchIn(text), "código cru na tela: $text")
                assertTrue("2026-11-12T" !in text, "data/hora crua na tela: $text")
                assertTrue("2026-11-1" !in text, "data ISO crua na tela: $text")
            }
        }

    @Test
    fun detailPanelHasNoGuestDataBeyondGuestCount() =
        runComposeUiTest {
            val source = FakeRoomsSource()
            source.put(roomStatus(a, "101", occupancy = Occupancy.OCCUPIED).copy(guestCount = Option.Some(1)))
            loaded(source)
            tile(a).performClick()
            waitForIdle()

            val forbidden = listOf("Hóspede:", "Nome", "CPF", "Documento", "Reserva", "Check-in", "Check-out")
            val leaks = allTexts().filter { text -> forbidden.any { text.contains(it, ignoreCase = true) } }
            assertTrue(leaks.isEmpty(), "dado de hóspede ou reserva na tela: $leaks")
        }
}
