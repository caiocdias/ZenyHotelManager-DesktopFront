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

private val typeStd = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val typeLux = Uuid.parse("10000000-0000-0000-0000-000000000002")
private val room101 = Uuid.parse("50000000-0000-0000-0000-000000000101")
private val room102 = Uuid.parse("50000000-0000-0000-0000-000000000102")
private val room103 = Uuid.parse("50000000-0000-0000-0000-000000000103")
private val room201 = Uuid.parse("50000000-0000-0000-0000-000000000201")

private const val NIGHT_TEXT = "O walk-in é de pernoite: a saída precisa ser depois de hoje."
private const val NO_STATUS_TEXT = "Sem permissão para ver o status das UHs."
private const val NO_READY_TEXT =
    "Nenhuma UH desse tipo está pronta e livre agora. " +
        "Escolha outra oferta ou peça a liberação à governança."

private fun offerOf(
    type: Uuid,
    name: String,
    total: String = "300.00",
) = QuoteOfferResponse(
    accommodationTotal = total,
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
    roomTotal = total,
    roomTypeCode = "STD",
    roomTypeId = type,
    roomTypeName = name,
    total = total,
)

private fun quoteOf(vararg offers: QuoteOfferResponse) =
    QuoteResponse(
        adults = 1,
        arrival = "2026-11-13",
        channel = "DIRECT",
        childAges = emptyList(),
        departure = "2026-11-15",
        nights = 2,
        offers = offers.toList(),
    )

@Suppress("LongParameterList") // fixture: cada campo do status que os testes variam, com padrão
private fun statusOf(
    room: Uuid,
    number: String,
    type: Uuid = typeStd,
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

private fun problem(
    status: Int,
    code: String?,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class WalkInStateTest {
    private val standard = offerOf(typeStd, "Standard")
    private val luxury = offerOf(typeLux, "Luxo", "500.00")

    private fun source() =
        FakeReceptionSource().apply {
            stayQuote = quoteOf(standard, luxury)
            roomStatusList +=
                listOf(
                    statusOf(room103, "103"),
                    statusOf(room101, "101"),
                    statusOf(room102, "102", ready = false),
                    statusOf(room201, "201", type = typeLux),
                )
        }

    private fun TestScope.state(
        source: FakeReceptionSource,
        permissions: Set<String> = setOf("reservations.availability.read"),
    ) = WalkInState(source, backgroundScope, permissions, { null }, {})

    private fun TestScope.started(source: FakeReceptionSource): WalkInState =
        state(source).also {
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

    @Test
    fun startReadsBusinessDay() =
        runTest {
            val state = started(source())

            assertEquals(BusinessDate.parse("2026-11-13"), state.arrival)
            assertNull(state.error)
            assertTrue(state.canQuote)
            assertEquals(1, state.adults)
            assertEquals("LEISURE", state.segment)
        }

    @Test
    fun businessDayFailureDisablesQuote() =
        runTest {
            val source = source().apply { businessDayError = problem(403, "FORBIDDEN") }
            val state = started(source)

            assertNull(state.arrival)
            assertFalse(state.canQuote)
            assertEquals(ActionText.error(problem(403, "FORBIDDEN")), state.error)
            state.departureText = "15/11/2026"
            state.quote()
            runCurrent()
            assertTrue(source.quoteStayCalls.isEmpty())
        }

    @Test
    fun withoutAvailabilityPermissionCannotQuote() =
        runTest {
            val source = source()
            val state = state(source, permissions = emptySet())
            state.start()
            runCurrent()
            state.departureText = "15/11/2026"
            state.quote()
            runCurrent()

            assertFalse(state.canQuote)
            assertTrue(source.quoteStayCalls.isEmpty())
        }

    @Test
    fun quoteSendsStay() =
        runTest {
            val source = source()
            val state = started(source)
            state.departureText = "15/11/2026"
            state.adults = 2
            state.childAges = listOf(4, 9)
            state.quote()
            runCurrent()

            assertEquals(
                listOf(
                    QuoteStayCall(
                        BusinessDate.parse("2026-11-13"),
                        BusinessDate.parse("2026-11-15"),
                        2,
                        listOf(4, 9),
                    ),
                ),
                source.quoteStayCalls,
            )
            assertEquals(listOf(standard, luxury), state.offers)
            assertFalse(state.noOffers)
            assertFalse(state.quoting)
            assertNull(state.error)
        }

    @Test
    fun invalidStayDoesNotQuote() =
        runTest {
            val source = source()
            val state = started(source)
            val cases =
                listOf<Triple<String, Int, List<Int>>>(
                    Triple("", 1, emptyList()),
                    Triple("32/13/2026", 1, emptyList()),
                    Triple("13/11/2026", 1, emptyList()),
                    Triple("12/11/2026", 1, emptyList()),
                    Triple("14/11/2027", 1, emptyList()),
                    Triple("15/11/2026", 0, emptyList()),
                    Triple("15/11/2026", 41, emptyList()),
                    Triple("15/11/2026", 1, listOf(18)),
                    Triple("15/11/2026", 1, listOf(-1)),
                )
            for ((departure, adults, ages) in cases) {
                state.departureText = departure
                state.adults = adults
                state.childAges = ages
                state.quote()
                runCurrent()
                assertTrue(state.error != null, "$departure/$adults/$ages")
            }
            assertTrue(source.quoteStayCalls.isEmpty())
        }

    @Test
    fun sameDayDepartureNotQuoted() =
        runTest {
            val source = source()
            val state = started(source)
            state.departureText = "13/11/2026"
            state.quote()
            runCurrent()

            assertEquals(NIGHT_TEXT, state.error)
            assertTrue(source.quoteStayCalls.isEmpty())
        }

    @Test
    fun limitsAreInclusive() =
        runTest {
            val source = source()
            val state = started(source)
            state.departureText = "14/11/2026"
            state.adults = 40
            state.childAges = listOf(0, 17)
            state.quote()
            runCurrent()
            state.departureText = "13/11/2027"
            state.quote()
            runCurrent()

            assertEquals(2, source.quoteStayCalls.size)
            assertNull(state.error)
        }

    @Test
    fun noOffersShowsMessageBeforeGuest() =
        runTest {
            val source = source().apply { stayQuote = quoteOf() }
            val state = quoted(source)

            assertTrue(state.noOffers)
            assertEquals(
                "Não há UH disponível para essa estadia. Mude a saída ou o número de hóspedes.",
                ActionText.noOffers(),
            )
            assertTrue(state.offers.isEmpty())
            assertFalse(state.stepGuestEnabled)
        }

    @Test
    fun chooseOfferListsReadyVacantRoomsOfType() =
        runTest {
            val source =
                source().apply {
                    roomStatusList +=
                        listOf(
                            statusOf(Uuid.random(), "104", active = false),
                            statusOf(Uuid.random(), "105", occupancy = RoomStatusResponse.Occupancy.OCCUPIED),
                        )
                }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(standard, state.offer)
            assertEquals(
                listOf(WalkInRoom(room101.toString(), "101"), WalkInRoom(room103.toString(), "103")),
                state.rooms,
            )
            assertEquals(
                listOf(BusinessDate.parse("2026-11-13") to BusinessDate.parse("2026-11-14")),
                source.assignedRoomsCalls,
            )
            assertFalse(state.noRooms)
            assertFalse(state.stepGuestEnabled)

            state.chooseRoom(state.rooms.first())
            assertEquals("101", state.room?.number)
            assertTrue(state.stepGuestEnabled)
        }

    @Test
    fun assignedRoomsExcluded() =
        runTest {
            val source = source().apply { assignedRooms = setOf(room101.toString()) }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(listOf("103"), state.rooms.map { it.number })
        }

    @Test
    fun noReadyRoomShowsMessage() =
        runTest {
            val source = source().apply { assignedRooms = setOf(room101.toString(), room103.toString()) }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertTrue(state.rooms.isEmpty())
            assertTrue(state.noRooms)
            assertEquals(NO_READY_TEXT, ActionText.noRooms())
            assertNull(state.error)
        }

    @Test
    fun statusPermissionDeniedShowsText() =
        runTest {
            val source = source().apply { roomStatusesError = problem(403, "FORBIDDEN") }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertEquals(NO_STATUS_TEXT, state.error)
            assertTrue(state.rooms.isEmpty())
        }

    @Test
    fun assignedRoomsFailureShowsNoRooms() =
        runTest {
            val source = source().apply { assignedRoomsError = problem(403, "FORBIDDEN") }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()

            assertTrue(state.rooms.isEmpty())
            assertEquals(ActionText.error(problem(403, "FORBIDDEN")), state.error)
            assertFalse(state.noRooms)
        }

    @Test
    fun changingStayDropsOfferAndRoom() =
        runTest {
            val edits: List<(WalkInState) -> Unit> =
                listOf(
                    { it.departureText = "16/11/2026" },
                    { it.adults = 2 },
                    { it.childAges = listOf(5) },
                )
            for (edit in edits) {
                val state = quoted(source())
                state.chooseOffer(standard)
                runCurrent()
                state.chooseRoom(state.rooms.first())
                assertTrue(state.stepGuestEnabled)

                edit(state)

                assertTrue(state.offers.isEmpty())
                assertNull(state.offer)
                assertTrue(state.rooms.isEmpty())
                assertNull(state.room)
                assertFalse(state.stepGuestEnabled)
            }
        }

    @Test
    fun changingSegmentKeepsOffers() =
        runTest {
            val state = quoted(source())
            state.chooseOffer(standard)
            runCurrent()
            state.chooseRoom(state.rooms.first())

            state.segment = "BUSINESS"

            assertEquals(listOf(standard, luxury), state.offers)
            assertEquals(standard, state.offer)
            assertEquals("101", state.room?.number)
        }

    @Test
    fun newestQuoteWins() =
        runTest {
            val source = source().apply { holdQuoteStay = true }
            val state = started(source)
            state.departureText = "15/11/2026"
            source.stayQuote = quoteOf(standard)
            state.quote()
            runCurrent()
            source.stayQuote = quoteOf(luxury)
            state.quote()
            runCurrent()
            assertEquals(2, source.heldQuoteStays.size)

            source.heldQuoteStays[1].complete(Unit)
            runCurrent()
            source.heldQuoteStays[0].complete(Unit)
            runCurrent()

            assertEquals(listOf(luxury), state.offers)
            assertFalse(state.quoting)
        }

    @Test
    fun newestOfferWins() =
        runTest {
            val source = source().apply { holdAssignedRooms = true }
            val state = quoted(source)
            state.chooseOffer(standard)
            runCurrent()
            state.chooseOffer(luxury)
            runCurrent()
            assertEquals(2, source.heldAssignedRooms.size)

            source.heldAssignedRooms[1].complete(Unit)
            runCurrent()
            source.heldAssignedRooms[0].complete(Unit)
            runCurrent()

            assertEquals(luxury, state.offer)
            assertEquals(listOf("201"), state.rooms.map { it.number })
        }

    @Test
    fun quoteErrorShowsText() =
        runTest {
            val source = source().apply { stayQuoteError = problem(400, "STAY_INVALID") }
            val state = quoted(source)

            assertEquals("A estadia está inválida. Confira a saída e o número de hóspedes.", state.error)
            assertTrue(state.offers.isEmpty())
            assertFalse(state.noOffers)
            assertFalse(state.quoting)
        }

    @Test
    fun closeCancels() =
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
            assertNull(state.error)
            state.quote()
            runCurrent()
            assertEquals(1, source.quoteStayCalls.size)
        }
}
