package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovableAction
import com.zeny.desktop.core.facade.ReservationVersion
import com.zeny.desktop.core.http.ApiResponse
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomMoveResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val atRes = Uuid.parse("20000000-0000-0000-0000-0000000000a1")
private val atHolder = Uuid.parse("30000000-0000-0000-0000-0000000000a1")
private val atType = Uuid.parse("10000000-0000-0000-0000-0000000000a1")
private val atOtherType = Uuid.parse("10000000-0000-0000-0000-0000000000a2")
private val atR101 = Uuid.parse("50000000-0000-0000-0000-000000000101")
private val atR102 = Uuid.parse("50000000-0000-0000-0000-000000000102")
private val atR103 = Uuid.parse("50000000-0000-0000-0000-000000000103")
private val atR110 = Uuid.parse("50000000-0000-0000-0000-000000000110")
private val atR201 = Uuid.parse("50000000-0000-0000-0000-000000000201")
private const val AT_ETAG = "\"v3\""

private fun atAssignment(
    arrival: String,
    departure: String,
    dayUse: Boolean,
    room: Uuid,
    number: String,
) = RoomAssignmentResponse(
    active = true,
    assignedAt = "2026-11-12T15:00:00Z",
    dayUse = dayUse,
    doNotMove = false,
    endsOn = departure,
    id = Uuid.random(),
    reason = "INITIAL",
    reservationId = atRes,
    roomId = room,
    roomNumber = Option.Some(number),
    startsOn = arrival,
)

@Suppress("LongParameterList") // fixture: cada campo da reserva que os testes variam, com padrão
private fun atVersion(
    etag: String = AT_ETAG,
    status: String = "IN_HOUSE",
    arrival: String = "2026-11-12",
    departure: String = "2026-11-15",
    dayUse: Boolean = false,
    room: Uuid = atR101,
    number: String = "101",
    type: Uuid = atType,
) = ReservationVersion(
    ReservationDetailResponse(
        bookingCode = "B-1",
        holderGuestId = atHolder,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = arrival,
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
                code = "R-1",
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = dayUse,
                departure = departure,
                folioId = Uuid.random(),
                guaranteeTerms =
                    GuaranteeTermsResponse(
                        name = "Sem garantia",
                        policyId = Uuid.random(),
                        type = "NONE",
                        version = 1,
                    ),
                guests = emptyList(),
                id = atRes,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.COURTESY,
                ratePlanId = Uuid.random(),
                roomAssignment = Option.Some(atAssignment(arrival, departure, dayUse, room, number)),
                roomTotal = "500.00",
                roomTypeId = type,
                segment = "LEISURE",
                status = status,
                total = "500.00",
                version = 3,
            ),
        restrictionAlerts = emptyList(),
    ),
    etag,
)

@Suppress("LongParameterList") // fixture: cada campo do status que os testes variam, com padrão
private fun atStatus(
    room: Uuid,
    number: String,
    type: Uuid = atType,
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

private fun atMoved(): RoomMoveResponse {
    fun room(
        id: Uuid,
        number: String,
    ) = """{"housekeepingStatus":"DIRTY","inspectionPending":false,"number":"$number","occupancy":"VACANT",
        "roomId":"$id","version":5}"""
    val any = Uuid.random()
    val json =
        """{"difference":"0.00","remainingAfter":"1000.00","remainingBefore":"1000.00","tariff":"KEEP",
        "fromRoom":${room(atR101, "101")},"toRoom":${room(atR102, "102")},
        "reservation":{"adults":2,"arrival":"2026-11-12","bookingId":"$any","children":0,"code":"R-1",
        "dayUse":false,"departure":"2026-11-15","folioId":"$any","guests":[],"id":"$atRes",
        "roomTypeId":"$atType","status":"IN_HOUSE","version":4}}"""
    return Json { ignoreUnknownKeys = true }.decodeFromString(RoomMoveResponse.serializer(), json)
}

private fun atProblem(
    status: Int,
    code: String?,
) = ApiError.Problem(status, code, "detalhe do servidor", emptyList())

/** Imita o `ApprovalCoordinator`: tenta sem token e, no `APPROVAL_REQUIRED`, repete com token ou cancela (`null`). */
private class AtApprover(
    var cancel: Boolean = false,
) {
    var calls = 0

    suspend fun approve(action: ApprovableAction<RoomMoveResponse>): ApiResponse<RoomMoveResponse>? {
        calls++
        return try {
            action.run(null)
        } catch (_: ApiError.ApprovalRequired) {
            if (cancel) null else action.run("tok")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomMoveStateAtaqueTest {
    private val id = atRes.toString()
    private var changes = 0
    private val approver = AtApprover()

    private fun source(today: String = "2026-11-13") =
        FakeReceptionSource().apply {
            businessDay =
                com.zeny.desktop.core.facade
                    .BusinessDay(BusinessDate.parse(today), null)
            versions[id] = atVersion()
            roomStatusList +=
                listOf(
                    atStatus(atR101, "101", occupancy = RoomStatusResponse.Occupancy.OCCUPIED),
                    atStatus(atR110, "110"),
                    atStatus(atR102, "102"),
                    atStatus(atR103, "103"),
                    atStatus(atR201, "201", type = atOtherType),
                )
            moveResponse = atMoved()
        }

    private fun TestScope.state(source: FakeReceptionSource) =
        RoomMoveState(source, backgroundScope, id, approver::approve) { changes++ }

    private fun TestScope.started(source: FakeReceptionSource): RoomMoveState =
        state(source).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.ready(source: FakeReceptionSource): RoomMoveState =
        started(source).also { state -> state.room = state.rooms.first { it.number == "102" } }

    private fun TestScope.confirmNow(state: RoomMoveState) {
        state.confirm()
        runCurrent()
    }

    private fun day(value: String) = BusinessDate.parse(value)

    // ---- ordem das leituras e período ----

    @Test
    fun versionIsReadBeforeBusinessDayAndBusinessDayBeforeAssignedRooms() =
        runTest {
            val source = source().apply { holdVersion = true }
            val state = state(source)
            state.start()
            runCurrent()
            assertEquals(0, source.businessDayCalls)
            source.heldVersions.single().complete(Unit)
            source.holdVersion = false
            source.holdBusinessDay = true
            runCurrent()
            assertEquals(1, source.businessDayCalls)
            assertTrue(source.assignedRoomsCalls.isEmpty())
            assertEquals(0, source.roomStatusesCalls)
            assertTrue(state.loading)
            source.heldBusinessDays.single().complete(Unit)
            runCurrent()
            assertEquals(1, source.assignedRoomsCalls.size)
            assertFalse(state.loading)
        }

    @Test
    fun freshVersionDefinesCurrentRoomOnSecondStart() =
        runTest {
            val source = source()
            val state = started(source)
            assertEquals("101", state.currentRoomNumber)
            assertEquals(listOf("102", "103", "110"), state.rooms.map { it.number })

            source.versions[id] = atVersion(etag = "\"v4\"", room = atR102, number = "102")
            source.roomStatusList.replaceAll {
                when (it.roomId) {
                    atR101 -> atStatus(atR101, "101")
                    atR102 -> atStatus(atR102, "102", occupancy = RoomStatusResponse.Occupancy.OCCUPIED)
                    else -> it
                }
            }
            state.start()
            runCurrent()
            assertEquals("102", state.currentRoomNumber)
            assertEquals(listOf("101", "103", "110"), state.rooms.map { it.number })
        }

    @Test
    fun freshVersionDefinesRoomTypeOnSecondStart() =
        runTest {
            val source = source()
            val state = started(source)
            source.versions[id] = atVersion(type = atOtherType, room = atR110, number = "110")
            source.roomStatusList.replaceAll {
                if (it.roomId == atR110) atStatus(atR110, "110", type = atOtherType) else it
            }
            state.start()
            runCurrent()
            assertEquals(listOf("201"), state.rooms.map { it.number })
        }

    @Test
    fun currentRoomIsExcludedEvenIfStatusSaysVacantAndReady() =
        runTest {
            val source =
                source().apply {
                    roomStatusList.replaceAll { if (it.roomId == atR101) atStatus(atR101, "101") else it }
                }
            val state = started(source)
            assertEquals(listOf("102", "103", "110"), state.rooms.map { it.number })
        }

    @Test
    fun roomsFollowNaturalOrder() =
        runTest {
            val source =
                source().apply {
                    roomStatusList.clear()
                    roomStatusList +=
                        listOf(
                            atStatus(Uuid.random(), "10"),
                            atStatus(Uuid.random(), "9"),
                            atStatus(Uuid.random(), "2"),
                            atStatus(Uuid.random(), "100"),
                        )
                }
            val state = started(source)
            assertEquals(listOf("2", "9", "10", "100"), state.rooms.map { it.number })
        }

    @Test
    fun overnightPeriodAcrossMonthBoundary() =
        runTest {
            val source =
                source("2026-11-30").apply {
                    versions[id] = atVersion(arrival = "2026-11-28", departure = "2026-12-03")
                }
            started(source)
            assertEquals(listOf(day("2026-11-30") to day("2026-12-02")), source.assignedRoomsCalls)
        }

    @Test
    fun lastNightPeriodIsTodayOnly() =
        runTest {
            val source = source().apply { versions[id] = atVersion(departure = "2026-11-14") }
            val state = started(source)
            assertEquals(listOf(day("2026-11-13") to day("2026-11-13")), source.assignedRoomsCalls)
            assertEquals(listOf("102", "103", "110"), state.rooms.map { it.number })
        }

    @Test
    fun overdueStayHasNoRoomsAndDoesNotAskAssignments() =
        runTest {
            val source = source().apply { versions[id] = atVersion(arrival = "2026-11-08", departure = "2026-11-12") }
            val state = started(source)
            assertTrue(source.assignedRoomsCalls.isEmpty())
            assertTrue(state.rooms.isEmpty())
            assertEquals("A estadia desta reserva não tem mais noites; a UH não é mais trocada.", state.error)
            assertFalse(state.canConfirm)
            assertFalse(state.loading)
        }

    @Test
    fun dayUseTodayIgnoresDepartureField() =
        runTest {
            val source =
                source().apply {
                    versions[id] = atVersion(arrival = "2026-11-13", departure = "2026-11-13", dayUse = true)
                }
            val state = started(source)
            assertEquals(listOf(day("2026-11-13") to day("2026-11-13")), source.assignedRoomsCalls)
            assertNull(state.error)
            assertFalse(state.rooms.isEmpty())
        }

    @Test
    fun dayUseStartedYesterdayHasNoNights() =
        runTest {
            val source =
                source().apply {
                    versions[id] = atVersion(arrival = "2026-11-12", departure = "2026-11-14", dayUse = true)
                }
            val state = started(source)
            assertTrue(source.assignedRoomsCalls.isEmpty())
            assertTrue(state.rooms.isEmpty())
            assertEquals("A estadia desta reserva não tem mais noites; a UH não é mais trocada.", state.error)
        }

    @Test
    fun readFailuresNeverShowUnfilteredList() =
        runTest {
            val cases: List<(FakeReceptionSource) -> Unit> =
                listOf(
                    { it.versionError = atProblem(500, "X") },
                    { it.businessDayError = atProblem(500, "X") },
                    { it.roomStatusesError = atProblem(500, "X") },
                    { it.assignedRoomsError = atProblem(500, "X") },
                    { it.assignedRoomsError = CancellationException("alheia") },
                    { it.roomStatusesError = CancellationException("alheia") },
                    { it.assignedRoomsError = RuntimeException("boom") },
                )
            cases.forEach { setup ->
                val source = source().also(setup)
                val state = started(source)
                assertTrue(state.rooms.isEmpty())
                assertNotNull(state.error)
                assertFalse(state.canConfirm)
                assertFalse(state.loading)
                state.room = null
                confirmNow(state)
                assertTrue(source.moveCalls.isEmpty())
            }
        }

    @Test
    fun readFailureAfterGoodLoadClearsStaleList() =
        runTest {
            val source = source()
            val state = ready(source)
            source.assignedRoomsError = atProblem(500, "X")
            state.start()
            runCurrent()
            assertTrue(state.rooms.isEmpty())
            assertNull(state.room)
            assertFalse(state.canConfirm)
            assertEquals(ActionText.error(atProblem(500, "X")), state.error)
            source.assignedRoomsError = null
            state.start()
            runCurrent()
            assertNull(state.error)
            assertEquals(3, state.rooms.size)
        }

    @Test
    fun notInHouseDoesNotListRoomsOrConfirm() =
        runTest {
            for (status in listOf("CONFIRMED", "CHECKED_OUT", "CANCELLED")) {
                val source = source().apply { versions[id] = atVersion(status = status) }
                val state = started(source)
                assertFalse(state.canConfirm)
                assertTrue(state.rooms.isEmpty())
                assertEquals(ActionText.moveError(atProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")), state.error)
            }
        }

    // ---- chave de idempotência ----

    @Test
    fun unknownResultsKeepKey() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            source.moveOutcomes += ApiError.IncompatibleResponse(RuntimeException("formato"))
            source.moveOutcomes += ApiError.SessionEnded("SESSION_EXPIRED")
            source.moveOutcomes += atProblem(409, "IDEMPOTENCY_KEY_IN_USE")
            repeat(5) { confirmNow(state) }
            assertEquals(
                1,
                source.moveRuns
                    .map { it.key }
                    .toSet()
                    .size,
            )
            assertEquals(5, source.moveRuns.size)
            assertNotNull(state.done)
        }

    @Test
    fun definitiveResponsesRenewKey() =
        runTest {
            val codes = listOf(403 to "FORBIDDEN", 422 to "ROOM_MOVE_INVALID", 404 to "NOT_FOUND")
            codes.forEach { (status, code) ->
                val source = source()
                val state = ready(source)
                source.moveOutcomes += atProblem(status, code)
                confirmNow(state)
                confirmNow(state)
                val keys = source.moveRuns.map { it.key }
                assertEquals(2, keys.size, code)
                assertNotEquals(keys[0], keys[1], code)
            }
        }

    @Test
    fun problemServerErrorsKeepKeyAndAskToCheckBeforeRedoing() =
        runTest {
            val text = "O servidor não confirmou a troca de UH. Feche e confira a reserva antes de refazer."
            listOf(500 to "INTERNAL", 503 to null).forEach { (status, code) ->
                val source = source()
                val state = ready(source)
                source.moveOutcomes += atProblem(status, code)
                confirmNow(state)
                assertEquals(text, state.error, "texto de $status")
                confirmNow(state)
                val keys = source.moveRuns.map { it.key }
                assertEquals(2, keys.size, "$status")
                assertEquals(keys[0], keys[1], "chave renovada depois de $status")
            }
        }

    @Test
    fun unexpectedServerErrorKeepsKeyWithGenericText() =
        runTest {
            listOf(502, 504).forEach { status ->
                val failure = ApiError.Unexpected(status)
                val source = source()
                val state = ready(source)
                source.moveOutcomes += failure
                confirmNow(state)
                assertEquals(ActionText.moveError(failure), state.error, "texto de $status")
                confirmNow(state)
                val keys = source.moveRuns.map { it.key }
                assertEquals(2, keys.size, "$status")
                assertEquals(keys[0], keys[1], "chave renovada depois de $status")
            }
        }

    @Test
    fun definitiveResponseWithoutCodeRenewsKey() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.Unexpected(418)
            confirmNow(state)
            confirmNow(state)
            val keys = source.moveRuns.map { it.key }
            assertNotEquals(keys[0], keys[1])
        }

    @Test
    fun preconditionFailedRenewsKeyEvenWhenEtagComesBackEqual() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.PreconditionFailed(null)
            confirmNow(state)
            confirmNow(state)
            val runs = source.moveRuns
            assertEquals(2, runs.size)
            assertNotEquals(runs[0].key, runs[1].key)
        }

    @Test
    fun revertingAnInputStillRenewsKey() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            confirmNow(state)
            state.reason = "OPERATIONAL"
            state.reason = "GUEST_REQUEST"
            confirmNow(state)
            val keys = source.moveRuns.map { it.key }
            assertNotEquals(keys[0], keys[1])
        }

    @Test
    fun settingSameValuesKeepsKey() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            confirmNow(state)
            state.reason = "GUEST_REQUEST"
            state.tariff = "KEEP"
            state.room = state.room
            confirmNow(state)
            assertEquals(
                1,
                source.moveRuns
                    .map { it.key }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun newEtagAfterUnknownResultRenewsKeyAndSendsNewEtag() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            confirmNow(state)
            source.versions[id] = atVersion(etag = "\"v9\"")
            state.start()
            runCurrent()
            assertEquals("102", state.room?.number)
            confirmNow(state)
            val runs = source.moveRuns
            assertEquals(listOf(AT_ETAG, "\"v9\""), runs.map { it.etag })
            assertNotEquals(runs[0].key, runs[1].key)
        }

    @Test
    fun sameEtagReloadAfterUnknownResultKeepsKey() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.Network(RuntimeException("rede"))
            confirmNow(state)
            state.start()
            runCurrent()
            confirmNow(state)
            assertEquals(
                1,
                source.moveRuns
                    .map { it.key }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun successRenewsNothingAndSecondConfirmIsNoop() =
        runTest {
            val source = source()
            val state = ready(source)
            confirmNow(state)
            confirmNow(state)
            assertEquals(1, source.moveRuns.size)
            assertEquals(1, changes)
        }

    @Test
    fun approvalCancelledThenConfirmAgainStillWorks() =
        runTest {
            val source = source().apply { moveRequiresApproval = ApiError.ApprovalRequired("a", "p", id, null, null) }
            approver.cancel = true
            val state = ready(source)
            confirmNow(state)
            approver.cancel = false
            confirmNow(state)
            assertNotNull(state.done)
            assertEquals(1, changes)
            assertEquals(listOf(null, null, "tok"), source.moveRuns.map { it.token })
        }

    // ---- códigos e textos ----

    @Test
    fun refusalCodesClearRoomReReadAndShowExactText() =
        runTest {
            val unfit = "A UH não serve para esta troca. Escolha outra UH da lista."
            val expected =
                mapOf(
                    "ROOM_NOT_READY" to
                        "A UH não está pronta. Escolha outra UH da lista ou peça a liberação à governança.",
                    "ROOM_ALREADY_ASSIGNED" to
                        "A UH já está alocada a outra reserva no período. Escolha outra UH da lista.",
                    "ROOM_OUT_OF_ORDER" to unfit,
                    "ROOM_TYPE_MISMATCH" to unfit,
                    "ROOM_MOVE_SAME_ROOM" to "A UH escolhida é a mesma atual. Escolha outra UH.",
                    "ROOM_INACTIVE" to unfit,
                    "UNKNOWN_ROOM" to unfit,
                )
            expected.forEach { (code, text) ->
                val source = source()
                val state = ready(source)
                val before = source.versionCalls.size
                source.moveOutcomes += atProblem(409, code)
                confirmNow(state)
                assertEquals(text, state.error, code)
                assertNull(state.room, code)
                assertEquals(before + 1, source.versionCalls.size, code)
                assertFalse(state.loading, code)
                assertFalse(state.busy, code)
                assertEquals(0, changes, code)
                assertEquals(3, state.rooms.size, code)
                // uma nova escolha depois da releitura usa outra chave
                state.room = state.rooms.first { it.number == "103" }
                confirmNow(state)
                val keys = source.moveRuns.map { it.key }
                assertNotEquals(keys[0], keys[1], code)
                assertNotNull(state.done, code)
                changes = 0
            }
        }

    @Test
    fun otherCodesShowTheirExactTextWithoutReload() =
        runTest {
            val expected =
                mapOf(
                    "ROOM_MOVE_INVALID" to "O motivo da troca é inválido. Escolha outro motivo.",
                    "ROOM_BLOCK_RESERVATION_STAY_FIXED" to
                        "A tarifa desta reserva de grupo não pode ser recalculada. Mantenha a tarifa.",
                    "RESERVATION_STAY_ENDED" to
                        "A estadia desta reserva não tem mais noites; a UH não é mais trocada.",
                )
            expected.forEach { (code, text) ->
                val source = source()
                val state = ready(source)
                source.moveOutcomes += atProblem(422, code)
                confirmNow(state)
                assertEquals(text, state.error, code)
                assertEquals(1, source.versionCalls.size, code)
                assertEquals(0, changes, code)
            }
        }

    @Test
    fun noErrorTextLeaksRawCodeOrDetail() =
        runTest {
            val codes =
                listOf(
                    "ROOM_NOT_READY",
                    "ROOM_ALREADY_ASSIGNED",
                    "ROOM_OUT_OF_ORDER",
                    "ROOM_TYPE_MISMATCH",
                    "ROOM_MOVE_SAME_ROOM",
                    "ROOM_INACTIVE",
                    "UNKNOWN_ROOM",
                    "RESERVATION_TRANSITION_NOT_ALLOWED",
                    "ROOM_MOVE_INVALID",
                    "IDEMPOTENCY_KEY_IN_USE",
                    "SOMETHING_NEW",
                )
            codes.forEach { code ->
                val source = source()
                val state = ready(source)
                source.moveOutcomes += atProblem(409, code)
                confirmNow(state)
                val text = state.error.orEmpty()
                assertTrue(text.isNotBlank(), code)
                assertFalse(text.contains(code), code)
                // um código desconhecido mostra o detalhe do servidor (regra do ErrorCatalog); os conhecidos, não
                if (code != "SOMETHING_NEW") assertFalse(text.contains("detalhe do servidor"), code)
                assertFalse(text.contains("{"), code)
            }
        }

    @Test
    fun preconditionKeepsItsTextAfterReloadAndNotifiesNothing() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.PreconditionFailed(null)
            source.versions[id] = atVersion(etag = "\"v4\"")
            confirmNow(state)
            assertEquals("Alguém alterou esta reserva. Confira e confirme de novo.", state.error)
            assertEquals(0, changes)
            assertFalse(state.loading)
            assertTrue(state.canConfirm)
        }

    @Test
    fun preconditionThenReloadFailureShowsReloadErrorNotStaleList() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.PreconditionFailed(null)
            source.assignedRoomsError = atProblem(500, "X")
            confirmNow(state)
            assertEquals(ActionText.error(atProblem(500, "X")), state.error)
            assertTrue(state.rooms.isEmpty())
            assertNull(state.room)
            assertFalse(state.canConfirm)
        }

    @Test
    fun preconditionReloadFindsStayNoLongerInHouse() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += ApiError.PreconditionFailed(null)
            source.versions[id] = atVersion(etag = "\"v4\"", status = "CHECKED_OUT")
            confirmNow(state)
            assertFalse(state.canConfirm)
            assertTrue(state.rooms.isEmpty())
            confirmNow(state)
            assertEquals(1, source.moveRuns.size)
        }

    @Test
    fun transitionNotAllowedDisablesConfirmAndNotifiesOnce() =
        runTest {
            val source = source()
            val state = ready(source)
            source.moveOutcomes += atProblem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
            confirmNow(state)
            assertEquals(1, changes)
            assertFalse(state.canConfirm)
            confirmNow(state)
            assertEquals(1, source.moveRuns.size)
            assertEquals(1, changes)
        }
}
