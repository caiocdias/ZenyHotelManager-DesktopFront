package com.zeny.desktop.app.reception

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.BusinessDay
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.StayGuestResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val resA = Uuid.parse("21000000-0000-0000-0000-00000000000a")
private val resB = Uuid.parse("21000000-0000-0000-0000-00000000000b")
private val holderU = Uuid.parse("31000000-0000-0000-0000-000000000001")
private val guestOne = Uuid.parse("31000000-0000-0000-0000-000000000011")
private val guestTwo = Uuid.parse("31000000-0000-0000-0000-000000000012")
private val guestThree = Uuid.parse("31000000-0000-0000-0000-000000000013")
private val planU = Uuid.parse("41000000-0000-0000-0000-000000000001")

private fun sg(
    id: Uuid,
    primary: Boolean,
) = StayGuestResponse(guestId = id, primary = primary)

private fun detailOf(
    id: Uuid,
    code: String,
    guests: List<StayGuestResponse>,
) = ReservationDetailResponse(
    bookingCode = "B-1",
    holderGuestId = holderU,
    reservation =
        ReservationResponse(
            additionalPackagesTotal = "0.00",
            adults = 2,
            arrival = "2026-11-13",
            bookingId = Uuid.random(),
            cancellationTerms =
                CancellationTermsResponse(
                    24,
                    "Flexível",
                    penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                    policyId = Uuid.random(),
                    version = 1,
                ),
            childAges = emptyList(),
            code = code,
            createdAt = "2026-11-10T12:00:00Z",
            dayUse = false,
            departure = "2026-11-15",
            folioId = Uuid.random(),
            guaranteeTerms =
                GuaranteeTermsResponse(name = "Sem garantia", policyId = Uuid.random(), type = "NONE", version = 1),
            guests = guests,
            id = id,
            nightlyRates = emptyList(),
            penaltiesCharged = "0.00",
            ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
            ratePlanId = planU,
            roomTotal = "500.00",
            roomTypeId = Uuid.parse(typeId(1)),
            segment = "LEISURE",
            status = "CHECKED_IN",
            total = "500.00",
            version = 1,
        ),
    restrictionAlerts = emptyList(),
)

private fun threeGuests() = listOf(sg(guestOne, false), sg(guestTwo, true), sg(guestThree, false))

private fun source(
    guests: List<StayGuestResponse>,
    otherGuests: List<StayGuestResponse> = emptyList(),
) = FakeReceptionSource().apply {
    businessDay = BusinessDay(BusinessDate.parse("2026-11-13"), ZoneId.of("America/Sao_Paulo"))
    types += roomType(typeId(1), "Standard")
    reservations[resA.toString()] = detailOf(resA, "RA", guests)
    reservations[resB.toString()] = detailOf(resB, "RB", otherGuests)
    holders[holderU.toString()] = HolderSummary("Maria Souza", "CPF ***.***.***-34")
    holders[guestOne.toString()] = HolderSummary("Ana Lima", "CPF ***.***.***-11")
    holders[guestTwo.toString()] = HolderSummary("Bruno Lima", "RG ***22")
    holders[guestThree.toString()] = HolderSummary("Carla Lima", null)
    ratePlans[planU.toString()] = "Tarifa Balcão"
}

private fun problem(status: Int) = ApiError.Problem(status, null, null, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailGuestsAtaqueTest {
    private val a = resA.toString()
    private val b = resB.toString()

    private fun TestScope.opened(src: FakeReceptionSource) =
        ReservationDetailState(src, backgroundScope).also {
            it.open(a)
            runCurrent()
        }

    @Test
    fun keepsServerOrderEvenWhenReadsAnswerOutOfOrder() =
        runTest {
            val src = source(threeGuests())
            src.holdHolder = true
            val state = ReservationDetailState(src, backgroundScope)
            state.open(a)
            runCurrent()
            // Libera o titular primeiro e depois os hóspedes na ordem inversa da do servidor.
            for (id in listOf(holderU, guestThree, guestTwo, guestOne)) {
                src.heldHolders.filter { it.first == id.toString() }.forEach { it.second.complete(Unit) }
            }
            runCurrent()

            val names = assertNotNull(state.view).stayGuests.map { it.name to it.primary }
            assertEquals(listOf("Ana Lima" to false, "Bruno Lima" to true, "Carla Lima" to false), names)
        }

    @Test
    fun guestWithoutDocumentKeepsNullDocument() =
        runTest {
            val line = assertNotNull(opened(source(threeGuests())).view).stayGuests.last()

            assertEquals(GuestLine("Carla Lima", null, false), line)
        }

    @Test
    fun holderAlsoListedAsStayGuestShowsInBothAndReadsEachTime() =
        runTest {
            val src = source(listOf(sg(holderU, true), sg(guestOne, false)))
            val view = assertNotNull(opened(src).view)

            assertEquals("Maria Souza", view.holder)
            assertEquals(GuestLine("Maria Souza", "CPF ***.***.***-34", true), view.stayGuests.first())
            // Comportamento fixado: sem cache, o titular é lido duas vezes (uma para o cabeçalho, outra para a lista).
            assertEquals(2, src.holderCalls.count { it == holderU.toString() })
        }

    @Test
    fun emptyGuestListHasNoErrorAndNoExtraReads() =
        runTest {
            val src = source(emptyList())
            val view = assertNotNull(opened(src).view)

            assertTrue(view.stayGuests.isEmpty())
            assertNull(view.stayGuestsError)
            assertEquals(listOf(holderU.toString()), src.holderCalls)
        }

    @Test
    fun onlyForbiddenAndNotFoundBecomeDash() =
        runTest {
            val src = source(listOf(sg(guestOne, true)))
            src.holderErrors[guestOne.toString()] = problem(401)
            val view = assertNotNull(opened(src).view)

            assertTrue(view.stayGuests.isEmpty())
            assertNotNull(view.stayGuestsError)
        }

    @Test
    fun networkAndForeignFailuresOnAGuestOnlyFailTheSection() =
        runTest {
            for (failure in listOf<Exception>(ApiError.Network(RuntimeException("x")), IllegalStateException("boom"))) {
                val src = source(threeGuests())
                src.holderErrors[guestThree.toString()] = failure
                val state = opened(src)
                val view = assertNotNull(state.view)

                assertNull(state.error)
                assertEquals("RA", view.code)
                assertEquals("Tarifa Balcão", view.plan)
                assertTrue(view.stayGuests.isEmpty())
                assertNotNull(view.stayGuestsError)
            }
        }

    @Test
    fun oneGuestForbiddenDoesNotHideTheOthers() =
        runTest {
            val src = source(threeGuests())
            src.holderErrors[guestTwo.toString()] = problem(403)
            val view = assertNotNull(opened(src).view)

            assertEquals(
                listOf(
                    GuestLine("Ana Lima", "CPF ***.***.***-11", false),
                    GuestLine("—", null, true),
                    GuestLine("Carla Lima", null, false),
                ),
                view.stayGuests,
            )
        }

    @Test
    fun staleStayGuestsOfAnotherIdNeverLand() =
        runTest {
            val src = source(threeGuests(), otherGuests = emptyList())
            src.holdHolder = true
            val state = ReservationDetailState(src, backgroundScope)
            state.open(a)
            runCurrent()
            val staleGates = src.heldHolders.map { it.second }.toList()
            state.open(b)
            runCurrent()
            // A carga de B responde primeiro; a de A, atrasada, depois.
            src.heldHolders
                .map { it.second }
                .filter { it !in staleGates }
                .forEach { it.complete(Unit) }
            runCurrent()
            staleGates.forEach { it.complete(Unit) }
            runCurrent()

            val view = assertNotNull(state.view)
            assertEquals("RB", view.code)
            assertTrue(view.stayGuests.isEmpty())
            assertNull(state.error)
            assertEquals(resB, state.reservation?.reservation?.id)
        }

    @Test
    fun staleReservationOfAnotherIdNeverLandsEvenWhenItAnswersLast() =
        runTest {
            val src = source(threeGuests())
            src.holdReservation = true
            val state = ReservationDetailState(src, backgroundScope)
            state.open(a)
            runCurrent()
            state.open(b)
            runCurrent()
            val gates = src.heldReservations.toList()
            gates.single { it.first == b }.second.complete(Unit)
            runCurrent()
            gates.single { it.first == a }.second.complete(Unit)
            runCurrent()

            assertEquals(resB, state.reservation?.reservation?.id)
            assertEquals("RB", state.view?.code)
        }

    @Test
    fun closeWhileLoadingLeavesEverythingNullAndNoError() =
        runTest {
            val src = source(threeGuests())
            src.holdHolder = true
            val state = ReservationDetailState(src, backgroundScope)
            state.open(a)
            runCurrent()
            state.close()
            src.heldHolders.forEach { it.second.complete(Unit) }
            runCurrent()

            assertNull(state.reservation)
            assertNull(state.view)
            assertNull(state.error)
            assertFalse(state.loading)
        }

    @Test
    fun switchingIdMidLoadNeverSurfacesCancellationAsSectionError() =
        runTest {
            val src = source(threeGuests())
            src.holdHolder = true
            val state = ReservationDetailState(src, backgroundScope)
            state.open(a)
            runCurrent()
            state.open(b)
            runCurrent()
            src.heldHolders.forEach { it.second.complete(Unit) }
            runCurrent()

            val view = assertNotNull(state.view)
            assertNull(view.stayGuestsError)
            assertNull(state.error)
        }

    @Test
    fun reloadKeepsReservationAndViewWhenTheReloadFails() =
        runTest {
            val src = source(threeGuests())
            val state = opened(src)
            src.reservationError = problem(500)
            state.reload()
            runCurrent()

            assertNotNull(state.error)
            assertEquals(resA, state.reservation?.reservation?.id)
            assertEquals(3, state.view?.stayGuests?.size)
        }

    @Test
    fun reloadReplacesStayGuestsWithTheNewRead() =
        runTest {
            val src = source(threeGuests())
            val state = opened(src)
            src.reservations[a] = detailOf(resA, "RA", listOf(sg(guestTwo, true)))
            state.reload()
            runCurrent()

            assertEquals(listOf(GuestLine("Bruno Lima", "RG ***22", true)), state.view?.stayGuests)
            assertEquals(
                1,
                state.reservation
                    ?.reservation
                    ?.guests
                    ?.size,
            )
        }

    @Test
    fun toStringOfLinesViewAndStateLeaksNoPersonalData() =
        runTest {
            val src = source(threeGuests())
            src.holderErrors[guestThree.toString()] = problem(500)
            val state = opened(src)
            val withError = assertNotNull(state.view)
            val ok = assertNotNull(opened(source(threeGuests())).view)
            val text =
                withError.toString() + ok.toString() + state + ok.stayGuests.joinToString() +
                    GuestLine("Ana Lima", "CPF ***.***.***-11", true)

            for (secret in listOf("Ana", "Lima", "Bruno", "CPF", "RG ", "***", "Maria", "Souza", "31000000")) {
                assertFalse(secret in text, "vazou $secret em: $text")
            }
            assertEquals("GuestLine(primary=true)", GuestLine("Ana", "CPF 1", true).toString())
        }
}

@OptIn(ExperimentalTestApi::class)
class ReservationDetailGuestsScreenAtaqueTest {
    private fun ComposeUiTest.openWith(src: FakeReceptionSource) {
        src.pages[DayList.ARRIVALS to null] = Page(listOf(listItemA()), null)
        setContent {
            val scope = rememberCoroutineScope()
            val panel = remember { DayPanelState(src, scope, src.permissions) }
            val detail = remember { ReservationDetailState(src, scope) }
            ZenyTheme { ReceptionScreen(panel, detail) }
        }
        waitForIdle()
        onNodeWithTag("row-$resA").performClick()
        waitForIdle()
    }

    @Test
    fun forbiddenGuestShowsDashAndNoDocument() =
        runComposeUiTest {
            val src = source(threeGuests())
            src.holderErrors[guestTwo.toString()] = problem(403)
            openWith(src)

            onNodeWithText("—  (principal)").assertDoesNotExist()
            onNodeWithText("— (principal)").assertExists()
            onNodeWithText("Ana Lima · CPF ***.***.***-11").assertExists()
            onNodeWithText("Carla Lima").assertExists()
        }

    @Test
    fun semanticTreeHasOnlyMaskedDocumentsAndNoIds() =
        runComposeUiTest {
            openWith(source(threeGuests()))
            val tree = onRoot(useUnmergedTree = true).printToStringForTest()

            assertTrue("CPF ***.***.***-11" in tree)
            for (id in listOf(guestOne, guestTwo, guestThree, holderU)) {
                assertFalse(id.toString() in tree, "UUID $id na árvore")
            }
            assertFalse("31000000" in tree)
        }

    @Test
    fun sectionErrorShowsMessageRetryAndKeepsRestOfDetail() =
        runComposeUiTest {
            val src = source(threeGuests())
            src.holderErrors[guestOne.toString()] = problem(500)
            openWith(src)

            onNodeWithText("Hóspedes da estadia").assertExists()
            onNodeWithText("Tentar de novo").assertExists()
            onNodeWithText("Histórico").assertExists()
            onNodeWithText("Ana Lima · CPF ***.***.***-11").assertDoesNotExist()
        }
}

@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.SemanticsNodeInteraction.printToStringForTest(): String =
    dump(fetchSemanticsNode())

private fun dump(node: androidx.compose.ui.semantics.SemanticsNode): String =
    node.config.joinToString { "${it.key.name}=${it.value}" } + "\n" + node.children.joinToString("") { dump(it) }

private fun listItemA() =
    ReservationListItemResponse(
        adults = 2,
        arrival = "2026-11-13",
        arrivalOverdue = false,
        bookingCode = "B-1",
        bookingId = Uuid.random(),
        channel = "DIRECT",
        children = 0,
        code = "RA",
        dayUse = false,
        departure = "2026-11-15",
        departureOverdue = false,
        folioId = Uuid.random(),
        holderGuestId = holderU,
        id = resA,
        origin = "FRONT_DESK",
        ratePlanCategory = ReservationListItemResponse.RatePlanCategory.NORMAL,
        ratePlanId = Uuid.random(),
        roomAssignment = Option.Undefined,
        roomTypeId = Uuid.parse(typeId(1)),
        segment = "LEISURE",
        status = "CONFIRMED",
        total = "500.00",
        version = 1,
    )
