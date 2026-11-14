package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.QuoteOfferResponse
import com.zeny.desktop.sdk.model.QuoteResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val atkStd = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val atkLux = Uuid.parse("10000000-0000-0000-0000-000000000002")
private val atk101 = Uuid.parse("50000000-0000-0000-0000-000000000101")
private val atk102 = Uuid.parse("50000000-0000-0000-0000-000000000102")
private val atk103 = Uuid.parse("50000000-0000-0000-0000-000000000103")
private val atk201 = Uuid.parse("50000000-0000-0000-0000-000000000201")

private const val ATK_NIGHT = "O walk-in é de pernoite: a saída precisa ser depois de hoje."
private const val ATK_NIGHTS = "A estadia vai de 1 a 365 noites."
private const val ATK_EMPTY = "Informe a saída (dd/mm/aaaa)."
private const val ATK_BAD_DATE = "Data inválida. Use dd/mm/aaaa."
private const val ATK_ADULTS = "Informe de 1 a 40 adultos."
private const val ATK_AGE = "A idade de cada criança vai de 0 a 17 anos."
private const val ATK_NO_STATUS = "Sem permissão para ver o status das UHs."

private fun atkOffer(
    type: Uuid,
    name: String,
) = QuoteOfferResponse(
    accommodationTotal = "300.00",
    additionalPackagesTotal = "0.00",
    availableUnits = 2,
    cancellationPolicyId = Uuid.random(),
    category = QuoteOfferResponse.Category.NORMAL,
    guaranteePolicyId = Uuid.random(),
    includedPackagesTotal = "0.00",
    mealPlan = "BREAKFAST",
    nights = emptyList(),
    ratePlanCode = "BAR",
    ratePlanId = Uuid.random(),
    ratePlanName = "Balcão",
    roomTotal = "300.00",
    roomTypeCode = "STD",
    roomTypeId = type,
    roomTypeName = name,
    total = "300.00",
)

private fun atkQuote(vararg offers: QuoteOfferResponse) =
    QuoteResponse(
        adults = 1,
        arrival = "2026-11-13",
        channel = "DIRECT",
        childAges = emptyList(),
        departure = "2026-11-15",
        nights = 2,
        offers = offers.toList(),
    )

@Suppress("LongParameterList") // fixture
private fun atkStatus(
    room: Uuid,
    number: String,
    type: Uuid = atkStd,
    ready: Boolean = true,
    active: Boolean = true,
    occupancy: RoomStatusResponse.Occupancy = RoomStatusResponse.Occupancy.VACANT,
) = RoomStatusResponse(
    active = active,
    changedAt = "2026-11-12T10:00:00Z",
    housekeepingStatus = RoomStatusResponse.HousekeepingStatus.CLEAN,
    inspectionPending = false,
    number = number,
    occupancy = occupancy,
    readyForCheckIn = ready,
    roomId = room,
    roomTypeId = type,
    serviceFlag = RoomStatusResponse.ServiceFlag.NONE,
    version = 1,
)

private fun atkProblem(
    status: Int,
    code: String?,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

/** Ataques ao `WalkInState`: validação, corridas, cancelamento, filtro de UHs e ordenação. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalkInStateAttackTest {
    private val standard = atkOffer(atkStd, "Standard")
    private val luxury = atkOffer(atkLux, "Luxo")

    private fun source() =
        FakeReceptionSource().apply {
            stayQuote = atkQuote(standard, luxury)
            roomStatusList +=
                listOf(
                    atkStatus(atk103, "103"),
                    atkStatus(atk101, "101"),
                    atkStatus(atk102, "102", ready = false),
                    atkStatus(atk201, "201", type = atkLux),
                )
        }

    private fun TestScope.started(source: FakeReceptionSource): WalkInState =
        WalkInState(source, backgroundScope, setOf("reservations.availability.read"), { null }, {}).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.quoted(
        source: FakeReceptionSource,
        departure: String = "15/11/2026",
    ): WalkInState =
        started(source).also {
            it.departureText = departure
            it.quote()
            runCurrent()
        }

    private fun TestScope.rejects(
        departure: String,
        adults: Int,
        ages: List<Int>,
        expected: String,
    ) {
        val source = source()
        val state = started(source)
        state.departureText = departure
        state.adults = adults
        state.childAges = ages
        state.quote()
        runCurrent()
        assertEquals(expected, state.error, "$departure/$adults/$ages")
        assertTrue(source.quoteStayCalls.isEmpty(), "$departure/$adults/$ages chamou a cotação")
        assertFalse(state.quoting)
        assertFalse(state.noOffers)
    }

    @Test
    fun unreadableDepartureShowsDateMessageAndEmptyShowsPrompt() =
        runTest {
            rejects("32/13/2026", 1, emptyList(), ATK_BAD_DATE)
            rejects("amanhã", 1, emptyList(), ATK_BAD_DATE)
            rejects("   ", 1, emptyList(), ATK_EMPTY)
        }

    @Test
    fun departureEqualOrBeforeArrivalShowsOvernightText() =
        runTest {
            rejects("13/11/2026", 1, emptyList(), ATK_NIGHT)
            rejects("12/11/2026", 1, emptyList(), ATK_NIGHT)
            rejects("01/01/2020", 1, emptyList(), ATK_NIGHT)
        }

    @Test
    fun nights365AreQuotedAnd366AreNot() =
        runTest {
            val source = source()
            val state = quoted(source, "13/11/2027")
            assertEquals(1, source.quoteStayCalls.size)
            assertEquals(BusinessDate.parse("2027-11-13"), source.quoteStayCalls.single().departure)
            assertNull(state.error)
            rejects("14/11/2027", 1, emptyList(), ATK_NIGHTS)
        }

    @Test
    fun adultsAndAgesOutOfRangeShowTheirTexts() =
        runTest {
            rejects("15/11/2026", 0, emptyList(), ATK_ADULTS)
            rejects("15/11/2026", 41, emptyList(), ATK_ADULTS)
            rejects("15/11/2026", -3, emptyList(), ATK_ADULTS)
            rejects("15/11/2026", 1, listOf(18), ATK_AGE)
            rejects("15/11/2026", 1, listOf(-1), ATK_AGE)
            rejects("15/11/2026", 1, listOf(3, 5, 18), ATK_AGE)
        }

    @Test
    fun boundaryAdultsAndAgesAreAccepted() =
        runTest {
            val source = source()
            val state = started(source)
            state.departureText = "15/11/2026"
            state.adults = 1
            state.childAges = listOf(0)
            state.quote()
            runCurrent()
            state.adults = 40
            state.childAges = listOf(17)
            state.quote()
            runCurrent()

            val sent = source.quoteStayCalls.map { it.adults to it.childAges }
            assertEquals(listOf(1 to listOf(0), 40 to listOf(17)), sent)
            assertNull(state.error)
        }

    @Test
    fun validQuoteClearsPreviousValidationError() =
        runTest {
            val source = source()
            val state = started(source)
            state.departureText = "13/11/2026"
            state.quote()
            runCurrent()
            assertEquals(ATK_NIGHT, state.error)
            state.departureText = "15/11/2026"
            state.quote()
            runCurrent()

            assertNull(state.error)
            assertEquals(2, state.offers.size)
        }

    @Test
    fun quoteBeforeBusinessDayArrivesDoesNotQuote() =
        runTest {
            val source = source().apply { holdBusinessDay = true }
            val state = started(source)
            state.departureText = "15/11/2026"
            state.quote()
            runCurrent()

            assertNull(state.arrival)
            assertFalse(state.canQuote)
            assertTrue(source.quoteStayCalls.isEmpty())
            source.heldBusinessDays.forEach { it.complete(Unit) }
            runCurrent()
            assertTrue(state.canQuote)
        }

    @Test
    fun businessDayFailureDoesNotShowStaleOrEmptyOffers() =
        runTest {
            val source = source().apply { businessDayError = RuntimeException("rede caiu") }
            val state = started(source)

            assertNull(state.arrival)
            assertTrue(state.error != null)
            assertTrue(state.offers.isEmpty())
            state.chooseOffer(standard)
            runCurrent()
            assertEquals(0, source.roomStatusesCalls)
            assertNull(state.offer)
        }

    @Test
    fun staleQuoteFailureDoesNotShowErrorWhenNewestSucceeds() =
        runTest {
            val source = source().apply { holdQuoteStay = true }
            val state = started(source)
            state.departureText = "15/11/2026"
            source.stayQuoteError = atkProblem(500, null)
            state.quote()
            runCurrent()
            source.stayQuoteError = null
            source.stayQuote = atkQuote(luxury)
            state.quote()
            runCurrent()

            source.heldQuoteStays[1].complete(Unit)
            runCurrent()
            source.heldQuoteStays[0].complete(Unit)
            runCurrent()

            assertEquals(listOf(luxury), state.offers)
            assertNull(state.error)
            assertFalse(state.quoting)
        }

    @Test
    fun staleQuoteSuccessDoesNotOverwriteNewestEmptyResult() =
        runTest {
            val source = source().apply { holdQuoteStay = true }
            val state = started(source)
            state.departureText = "15/11/2026"
            state.quote()
            runCurrent()
            source.stayQuote = atkQuote()
            state.quote()
            runCurrent()

            source.heldQuoteStays[1].complete(Unit)
            runCurrent()
            source.heldQuoteStays[0].complete(Unit)
            runCurrent()

            assertTrue(state.offers.isEmpty())
            assertTrue(state.noOffers)
            assertFalse(state.stepGuestEnabled)
        }

    @Test
    fun editingStayWhileQuotingIgnoresLateResultAndStopsSpinner() =
        runTest {
            val source = source().apply { holdQuoteStay = true }
            val state = started(source)
            state.departureText = "15/11/2026"
            state.quote()
            runCurrent()
            assertTrue(state.quoting)
            state.adults = 2
            assertFalse(state.quoting)
            source.heldQuoteStays.forEach { it.complete(Unit) }
            runCurrent()

            assertTrue(state.offers.isEmpty())
            assertFalse(state.noOffers)
            assertNull(state.error)
        }

    @Test
    fun quoteErrorThenEditClearsNothingButNewQuoteRecovers() =
        runTest {
            val source = source().apply { stayQuoteError = atkProblem(400, "STAY_INVALID") }
            val state = quoted(source)
            assertTrue(state.error != null)
            source.stayQuoteError = null
            state.quote()
            runCurrent()

            assertNull(state.error)
            assertEquals(2, state.offers.size)
        }

    @Test
    fun editingStayClearsNoOffersFlag() =
        runTest {
            val source = source().apply { stayQuote = atkQuote() }
            val state = quoted(source)
            assertTrue(state.noOffers)
            state.departureText = "16/11/2026"

            assertFalse(state.noOffers)
        }

    @Test
    fun chooseOfferAsksAssignedRoomsUpToDepartureMinusOne() =
        runTest {
            val source = source()
            val state = quoted(source, "16/11/2026")
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(
                listOf(BusinessDate.parse("2026-11-13") to BusinessDate.parse("2026-11-15")),
                source.assignedRoomsCalls,
            )
            assertEquals(1, source.roomStatusesCalls)
        }

    @Test
    fun chooseOfferReadsStatusesAndAssignedInParallel() =
        runTest {
            val source = source().apply { holdAssignedRooms = true }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(1, source.roomStatusesCalls)
            assertEquals(1, source.heldAssignedRooms.size)
            assertTrue(state.loadingRooms)
            assertTrue(state.rooms.isEmpty())
            source.heldAssignedRooms.single().complete(Unit)
            runCurrent()
            assertFalse(state.loadingRooms)
            assertEquals(listOf("101", "103"), state.rooms.map { it.number })
        }

    @Test
    fun roomsNeverShownUnfilteredWhileOrAfterAssignedRoomsFail() =
        runTest {
            val source =
                source().apply {
                    holdAssignedRooms = true
                    assignedRoomsError = RuntimeException("rede caiu")
                }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()
            assertTrue(state.rooms.isEmpty())
            source.heldAssignedRooms.single().complete(Unit)
            runCurrent()

            assertTrue(state.rooms.isEmpty())
            assertTrue(state.error != null)
            assertFalse(state.noRooms)
            state.chooseRoom(WalkInRoom(atk101.toString(), "101"))
            assertNull(state.room)
            assertFalse(state.stepGuestEnabled)
        }

    @Test
    fun assignedRoomsForbiddenDoesNotUseStatusPermissionText() =
        runTest {
            val source = source().apply { assignedRoomsError = atkProblem(403, "FORBIDDEN") }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(ActionText.error(atkProblem(403, "FORBIDDEN")), state.error)
        }

    @Test
    fun statusFailureWinsWhenBothReadsFail() =
        runTest {
            val source =
                source().apply {
                    roomStatusesError = atkProblem(403, "FORBIDDEN")
                    assignedRoomsError = RuntimeException("rede caiu")
                }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(ATK_NO_STATUS, state.error)
            assertTrue(state.rooms.isEmpty())
        }

    @Test
    fun statusFailureOtherThan403ShowsGenericText() =
        runTest {
            val source = source().apply { roomStatusesError = atkProblem(500, null) }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(ActionText.error(atkProblem(500, null)), state.error)
            assertTrue(state.rooms.isEmpty())
        }

    @Test
    fun onlyReadyVacantActiveRoomsOfOfferTypeSurvive() =
        runTest {
            val source =
                source().apply {
                    roomStatusList.clear()
                    roomStatusList +=
                        listOf(
                            atkStatus(Uuid.random(), "301"),
                            atkStatus(Uuid.random(), "302", ready = false),
                            atkStatus(Uuid.random(), "303", active = false),
                            atkStatus(Uuid.random(), "304", occupancy = RoomStatusResponse.Occupancy.OCCUPIED),
                            atkStatus(Uuid.random(), "305", type = atkLux),
                            atkStatus(Uuid.random(), "306", active = false, ready = true, type = atkLux),
                        )
                }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(listOf("301"), state.rooms.map { it.number })
        }

    @Test
    fun assignedRoomOfAnotherTypeDoesNotHideRoomsOfThisType() =
        runTest {
            val source = source().apply { assignedRooms = setOf(atk201.toString()) }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(listOf("101", "103"), state.rooms.map { it.number })
            state.chooseOffer(luxury)
            runCurrent()
            assertTrue(state.rooms.isEmpty())
            assertTrue(state.noRooms)
        }

    @Test
    fun roomsAreSortedAsTextSoTenTenComesBeforeOneOhTwo() =
        runTest {
            val source =
                source().apply {
                    roomStatusList.clear()
                    roomStatusList +=
                        listOf(
                            atkStatus(Uuid.random(), "102"),
                            atkStatus(Uuid.random(), "1010"),
                            atkStatus(Uuid.random(), "99"),
                            atkStatus(Uuid.random(), "101"),
                        )
                }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            // Comportamento atual: ordem por texto. "1010" aparece entre "101" e "102" e "99" por último.
            assertEquals(listOf("101", "1010", "102", "99"), state.rooms.map { it.number })
        }

    @Test
    fun staleOfferFailureDoesNotShowErrorWhenNewestSucceeds() =
        runTest {
            val source = source().apply { holdAssignedRooms = true }
            val state = quoted(source)
            source.assignedRoomsError = atkProblem(500, null)
            state.chooseOffer(standard)
            runCurrent()
            source.assignedRoomsError = null
            state.chooseOffer(luxury)
            runCurrent()

            source.heldAssignedRooms[1].complete(Unit)
            runCurrent()
            source.heldAssignedRooms[0].complete(Unit)
            runCurrent()

            assertNull(state.error)
            assertEquals(luxury, state.offer)
            assertEquals(listOf("201"), state.rooms.map { it.number })
            assertFalse(state.loadingRooms)
        }

    @Test
    fun choosingAnotherOfferDropsRoomAndClearsPreviousError() =
        runTest {
            val source = source()
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()
            state.chooseRoom(state.rooms.first())
            assertTrue(state.stepGuestEnabled)
            state.chooseOffer(luxury)

            assertNull(state.room)
            assertFalse(state.stepGuestEnabled)
            runCurrent()
            assertEquals(listOf("201"), state.rooms.map { it.number })
        }

    @Test
    fun editingStayWhileLoadingRoomsIgnoresLateRooms() =
        runTest {
            val source = source().apply { holdAssignedRooms = true }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()
            state.childAges = listOf(5)
            assertFalse(state.loadingRooms)
            source.heldAssignedRooms.forEach { it.complete(Unit) }
            runCurrent()

            assertTrue(state.rooms.isEmpty())
            assertNull(state.offer)
            assertFalse(state.noRooms)
            assertNull(state.error)
        }

    @Test
    fun requotingWhileLoadingRoomsIgnoresLateRooms() =
        runTest {
            val source = source().apply { holdAssignedRooms = true }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()
            state.quote()
            runCurrent()
            source.heldAssignedRooms.forEach { it.complete(Unit) }
            runCurrent()

            assertTrue(state.rooms.isEmpty())
            assertNull(state.offer)
            assertNull(state.room)
            assertFalse(state.stepGuestEnabled)
            assertEquals(2, state.offers.size)
        }

    @Test
    fun chooseOfferBeforeQuoteDoesNothing() =
        runTest {
            val source = source()
            val state = started(source)
            state.chooseOffer(standard)
            runCurrent()

            assertNull(state.offer)
            assertEquals(0, source.roomStatusesCalls)
            assertTrue(source.assignedRoomsCalls.isEmpty())
        }

    @Test
    fun chooseRoomOutsideTheListIsIgnored() =
        runTest {
            val state = quoted(source())
            state.chooseOffer(standard)
            runCurrent()
            state.chooseRoom(WalkInRoom(atk102.toString(), "102"))
            assertNull(state.room)
            state.chooseRoom(WalkInRoom(atk201.toString(), "201"))
            assertNull(state.room)
        }

    @Test
    fun closeDuringStartDoesNotSetArrivalOrError() =
        runTest {
            val source = source().apply { holdBusinessDay = true }
            val state = started(source)
            state.close()
            runCurrent()
            source.heldBusinessDays.forEach { it.complete(Unit) }
            runCurrent()

            assertNull(state.arrival)
            assertNull(state.error)
        }

    @Test
    fun closeDuringChooseOfferDoesNotApplyRoomsOrError() =
        runTest {
            val source = source().apply { holdAssignedRooms = true }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()
            state.close()
            runCurrent()
            source.heldAssignedRooms.forEach { it.complete(Unit) }
            runCurrent()

            assertTrue(state.rooms.isEmpty())
            assertFalse(state.noRooms)
            assertNull(state.error)
            assertNull(state.room)
        }

    @Test
    fun closeThenEveryActionIsNoOp() =
        runTest {
            val source = source()
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()
            state.chooseRoom(state.rooms.first())
            val calls = source.quoteStayCalls.size
            val statusCalls = source.roomStatusesCalls
            state.close()
            runCurrent()

            state.departureText = "16/11/2026"
            state.adults = 3
            state.childAges = listOf(4)
            state.quote()
            state.chooseOffer(luxury)
            state.chooseRoom(WalkInRoom(atk103.toString(), "103"))
            state.start()
            runCurrent()

            assertEquals("15/11/2026", state.departureText)
            assertEquals(1, state.adults)
            assertTrue(state.childAges.isEmpty())
            assertEquals(calls, source.quoteStayCalls.size)
            assertEquals(statusCalls, source.roomStatusesCalls)
            assertEquals(standard, state.offer)
            assertEquals("101", state.room?.number)
        }

    @Test
    fun closeDuringQuoteLeavesNoErrorNoOffersAndNoFlag() =
        runTest {
            val source = source().apply { holdQuoteStay = true }
            val state = started(source)
            state.departureText = "15/11/2026"
            state.quote()
            runCurrent()
            state.close()
            runCurrent()
            source.heldQuoteStays.forEach { it.complete(Unit) }
            runCurrent()

            assertTrue(state.offers.isEmpty())
            assertFalse(state.noOffers)
            assertNull(state.error)
        }

    @Test
    fun cancelledQuoteOfOwnStateIsNeverAnError() =
        runTest {
            val source = source().apply { holdQuoteStay = true }
            val state = started(source)
            state.departureText = "15/11/2026"
            state.quote()
            runCurrent()
            state.departureText = "16/11/2026"
            runCurrent()

            assertNull(state.error)
            state.quote()
            runCurrent()
            assertEquals(2, source.quoteStayCalls.size)
        }
}
