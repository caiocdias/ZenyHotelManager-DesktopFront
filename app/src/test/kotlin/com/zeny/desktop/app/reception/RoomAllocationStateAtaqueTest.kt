package com.zeny.desktop.app.reception

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import com.zeny.desktop.sdk.model.RoomAssignmentResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomSuggestionResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val atRes = Uuid.parse("20000000-0000-0000-0000-000000000002")
private val atHolder = Uuid.parse("30000000-0000-0000-0000-000000000002")
private val atA = Uuid.parse("50000000-0000-0000-0000-0000000000a1")
private val atB = Uuid.parse("50000000-0000-0000-0000-0000000000b2")
private val atC = Uuid.parse("50000000-0000-0000-0000-0000000000c3")

private fun atAssignment(
    room: Uuid,
    number: Option<String> = Option.Some("101"),
    doNotMove: Boolean = false,
) = RoomAssignmentResponse(
    active = true,
    assignedAt = "2026-11-13T15:00:00Z",
    dayUse = false,
    doNotMove = doNotMove,
    endsOn = "2026-11-15",
    id = Uuid.random(),
    reason = "INITIAL",
    reservationId = atRes,
    roomId = room,
    roomNumber = number,
    startsOn = "2026-11-13",
)

private fun atDetail(
    assignment: Option<RoomAssignmentResponse?> = Option.Undefined,
    childAges: List<Int> = emptyList(),
) = ReservationDetailResponse(
    bookingCode = "B-2",
    holderGuestId = atHolder,
    reservation =
        ReservationResponse(
            additionalPackagesTotal = "0.00",
            adults = 1,
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
            childAges = childAges,
            code = "R-2",
            createdAt = "2026-11-10T12:00:00Z",
            dayUse = false,
            departure = "2026-11-15",
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
            roomAssignment = assignment,
            roomTotal = "500.00",
            roomTypeId = Uuid.random(),
            segment = "LEISURE",
            status = "CONFIRMED",
            total = "500.00",
            version = 1,
        ),
    restrictionAlerts = emptyList(),
)

private fun atWith(
    room: Uuid,
    number: String,
    doNotMove: Boolean = false,
) = atDetail(Option.Some(atAssignment(room, Option.Some(number), doNotMove)))

private fun atSuggestion(
    room: Uuid,
    number: String,
    current: Boolean = false,
) = RoomSuggestionResponse(
    current = current,
    featureIds = emptyList(),
    floor = Option.Undefined,
    matchedFeatureIds = emptyList(),
    matchesFloorPreference = false,
    number = number,
    outOfService = false,
    roomId = room,
)

private fun atStatus(
    room: Uuid,
    number: String,
    ready: Boolean = false,
) = RoomStatusResponse(
    active = true,
    changedAt = "2026-11-12T10:00:00Z",
    housekeepingStatus = RoomStatusResponse.HousekeepingStatus.CLEAN,
    inspectionPending = false,
    number = number,
    occupancy = RoomStatusResponse.Occupancy.VACANT,
    readyForCheckIn = ready,
    roomId = room,
    roomTypeId = Uuid.random(),
    serviceFlag = RoomStatusResponse.ServiceFlag.NONE,
    version = 1,
)

private fun atProblem(
    code: String,
    status: Int = 422,
) = ApiError.Problem(status, code, null, emptyList())

/** Delega tudo ao falso, mas prende soltar, "não mover" e status até o teste soltar o portão. */
private class GatedSource(
    private val inner: FakeReceptionSource,
) : ReceptionSource by inner {
    var holdUnassign = false
    var holdDoNotMove = false
    var holdStatuses = false
    val heldUnassigns = mutableListOf<CompletableDeferred<Unit>>()
    val heldDoNotMoves = mutableListOf<CompletableDeferred<Unit>>()
    val heldStatuses = mutableListOf<CompletableDeferred<Unit>>()

    override suspend fun unassignRoom(id: String): ReservationDetailResponse {
        val result = inner.unassignRoom(id)
        if (holdUnassign) CompletableDeferred<Unit>().also { heldUnassigns += it }.await()
        return result
    }

    override suspend fun setDoNotMove(
        id: String,
        doNotMove: Boolean,
    ): ReservationDetailResponse {
        val result = inner.setDoNotMove(id, doNotMove)
        if (holdDoNotMove) CompletableDeferred<Unit>().also { heldDoNotMoves += it }.await()
        return result
    }

    override suspend fun roomStatuses(): List<RoomStatusResponse> {
        val result = inner.roomStatuses()
        if (holdStatuses) CompletableDeferred<Unit>().also { heldStatuses += it }.await()
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions")
class RoomAllocationStateAtaqueTest {
    private val id = atRes.toString()

    private fun fake(current: ReservationDetailResponse = atDetail()) =
        FakeReceptionSource().apply {
            reservations[id] = current
            suggestions[id] = listOf(atSuggestion(atA, "101"), atSuggestion(atB, "102"))
            roomStatusList += listOf(atStatus(atA, "101", ready = true), atStatus(atB, "102"))
        }

    private fun TestScope.state(
        source: ReceptionSource,
        seed: CheckInSeed = atDetail().toCheckInSeed(),
        canManage: Boolean = true,
        onChanged: () -> Unit = {},
    ) = RoomAllocationState(source, backgroundScope, seed, canManage, onChanged)

    // ---------- semente ----------

    @Test
    fun seedWithAssignmentWithoutNumberKeepsRoomIdAndNoNumber() {
        val seed = atDetail(Option.Some(atAssignment(atA, Option.Undefined))).toCheckInSeed()
        assertEquals(atA.toString(), seed.roomId)
        assertNull(seed.roomNumber)
    }

    @Test
    fun seedWithBlankNumberHasNoNumber() {
        val seed = atDetail(Option.Some(atAssignment(atA, Option.Some("  ")))).toCheckInSeed()
        assertNull(seed.roomNumber)
    }

    @Test
    fun seedWithExplicitNullAssignmentHasNoRoom() {
        val seed = atDetail(Option.Some(null), childAges = emptyList()).toCheckInSeed()
        assertNull(seed.roomId)
        assertNull(seed.roomNumber)
        assertFalse(seed.doNotMove)
        assertEquals(0, seed.children)
    }

    @Test
    fun seedCountsOnlyChildAgesAsChildren() {
        val seed = atDetail(childAges = listOf(1, 2, 3)).toCheckInSeed()
        assertEquals(3, seed.children)
        assertEquals(1, seed.adults)
    }

    @Test
    fun stateStartsFromSeedWithoutLoadingOrBusy() =
        runTest {
            val seed = atWith(atC, "OOO-9", doNotMove = true).toCheckInSeed()
            val state = state(fake(), seed)
            assertEquals(atC.toString(), state.currentRoomId)
            assertEquals("OOO-9", state.currentRoomNumber)
            assertTrue(state.doNotMove)
            assertTrue(state.options.isEmpty())
            assertFalse(state.loading)
            assertFalse(state.busy)
            assertNull(state.error)
        }

    // ---------- carga ----------

    @Test
    fun loadCallsSuggestionsAndStatusesInParallel() =
        runTest {
            val source = fake().apply { holdSuggestions = true }
            val state = state(source)
            state.load()
            runCurrent()

            // As sugestões ainda não responderam, mas os status já foram pedidos.
            assertEquals(1, source.suggestionCalls.size)
            assertEquals(1, source.roomStatusesCalls)
            assertTrue(state.loading)
            source.heldSuggestions.forEach { it.complete(Unit) }
            runCurrent()
            assertFalse(state.loading)
            assertEquals(2, state.options.size)
        }

    @Test
    fun suggestionWithoutStatusIsUnknownAndNotReady() =
        runTest {
            val source = fake().apply { roomStatusList.removeAll { it.roomId == atB } }
            val state = state(source)
            state.load()
            runCurrent()

            val byNumber = state.options.associateBy { it.number }
            assertEquals("Pronta", byNumber.getValue("101").readiness.label)
            assertEquals("Status desconhecido", byNumber.getValue("102").readiness.label)
            assertFalse(byNumber.getValue("102").ready)
            assertNull(state.error)
        }

    @Test
    fun joinIsByRoomIdNotByNumber() =
        runTest {
            val source =
                fake().apply {
                    roomStatusList.clear()
                    // Mesmo número "101", outra UH: não pode valer para a sugestão da UH A.
                    roomStatusList += atStatus(atC, "101", ready = true)
                }
            val state = state(source)
            state.load()
            runCurrent()

            assertTrue(state.options.none { it.ready })
            assertTrue(state.options.all { it.readiness.label == "Status desconhecido" })
        }

    @Test
    fun suggestionCurrentFlagIsOnlyAMarkerAndDoesNotChangeCurrentRoom() =
        runTest {
            val source = fake().apply { suggestions[id] = listOf(atSuggestion(atA, "101", current = true)) }
            val state = state(source)
            state.load()
            runCurrent()

            assertTrue(state.options.single().current)
            assertNull(state.currentRoomId)
        }

    @Test
    fun statusesOtherProblemAlsoListsWithoutError() =
        runTest {
            val source = fake().apply { roomStatusesError = atProblem("INTERNAL", 500) }
            val state = state(source)
            state.load()
            runCurrent()

            assertEquals(2, state.options.size)
            assertTrue(state.options.all { it.readiness.label == "Status desconhecido" && !it.ready })
            assertNull(state.error)
        }

    @Test
    fun statusesNetworkFailureShowsActionTextError() =
        runTest {
            val network = ApiError.Network(IOException("boom"))
            val source = fake().apply { roomStatusesError = network }
            val state = state(source)
            state.load()
            runCurrent()

            assertEquals(ActionText.error(network), state.error)
            assertTrue(state.options.isEmpty())
            assertFalse(state.loading)
        }

    @Test
    fun suggestionsFailureShowsErrorAndStopsLoading() =
        runTest {
            val problem = atProblem("ROOM_INACTIVE")
            val source = fake().apply { suggestionsError = problem }
            val state = state(source)
            state.load()
            runCurrent()

            assertEquals(ActionText.error(problem), state.error)
            assertFalse(state.loading)
            assertTrue(state.options.isEmpty())
        }

    @Test
    fun staleLoadResponseDoesNotOverwriteNewerOne() =
        runTest {
            val source = fake().apply { holdSuggestions = true }
            val state = state(source)
            state.load()
            runCurrent()
            source.suggestions[id] = listOf(atSuggestion(atC, "103"))
            state.load()
            runCurrent()
            assertEquals(2, source.heldSuggestions.size)

            // Solta a segunda primeiro, depois a primeira (atrasada).
            source.heldSuggestions[1].complete(Unit)
            runCurrent()
            source.heldSuggestions[0].complete(Unit)
            runCurrent()

            assertEquals(listOf("103"), state.options.map { it.number })
            assertFalse(state.loading)
        }

    @Test
    fun loadFailureDoesNotKeepOldOptionsHiddenButKeepsThemUntilSuccess() =
        runTest {
            val source = fake()
            val state = state(source)
            state.load()
            runCurrent()
            assertEquals(2, state.options.size)

            source.suggestionsError = atProblem("ROOM_INACTIVE")
            state.load()
            runCurrent()

            assertNotNull(state.error)
            assertEquals(2, state.options.size)
        }

    // ---------- permissão ----------

    @Test
    fun withoutPermissionCloseIsHarmlessAndNothingMoves() =
        runTest {
            val source = fake()
            val state = state(source, atWith(atA, "101", doNotMove = true).toCheckInSeed(), canManage = false)
            state.load()
            state.assign(atB.toString())
            state.close()
            runCurrent()

            assertFalse(state.loading)
            assertFalse(state.busy)
            assertTrue(state.doNotMove)
            assertEquals(0, source.roomStatusesCalls)
            assertTrue(source.suggestionCalls.isEmpty())
            assertTrue(source.assignCalls.isEmpty())
        }

    // ---------- uma ação por vez ----------

    @Test
    fun clicksWhileAssignBusyAreIgnoredForEveryAction() =
        runTest {
            val source = fake(atWith(atA, "101")).apply { holdAssign = true }
            val gated = GatedSource(source)
            val state = state(gated, atWith(atA, "101").toCheckInSeed())

            state.assign(atB.toString())
            runCurrent()
            assertTrue(state.busy)
            state.assign(atC.toString())
            state.unassign()
            state.toggleDoNotMove()
            runCurrent()

            assertEquals(1, source.assignCalls.size)
            assertTrue(source.unassignCalls.isEmpty())
            assertTrue(source.doNotMoveCalls.isEmpty())
            source.heldAssigns.forEach { it.complete(Unit) }
            runCurrent()
            assertFalse(state.busy)
            assertEquals(1, source.assignCalls.size)
        }

    @Test
    fun clicksWhileUnassignBusyAreIgnored() =
        runTest {
            val source = fake(atDetail())
            val gated = GatedSource(source).apply { holdUnassign = true }
            val state = state(gated, atWith(atA, "101").toCheckInSeed())

            state.unassign()
            runCurrent()
            assertTrue(state.busy)
            state.unassign()
            state.assign(atB.toString())
            state.toggleDoNotMove()
            runCurrent()

            assertEquals(1, source.unassignCalls.size)
            assertTrue(source.assignCalls.isEmpty())
            assertTrue(source.doNotMoveCalls.isEmpty())
            gated.heldUnassigns.forEach { it.complete(Unit) }
            runCurrent()
            assertFalse(state.busy)
        }

    @Test
    fun clicksWhileDoNotMoveBusyAreIgnored() =
        runTest {
            val source = fake(atWith(atA, "101", doNotMove = true))
            val gated = GatedSource(source).apply { holdDoNotMove = true }
            val state = state(gated, atWith(atA, "101").toCheckInSeed())

            state.toggleDoNotMove()
            runCurrent()
            state.toggleDoNotMove()
            state.unassign()
            state.assign(atB.toString())
            runCurrent()

            assertEquals(listOf(id to true), source.doNotMoveCalls)
            assertTrue(source.unassignCalls.isEmpty())
            assertTrue(source.assignCalls.isEmpty())
            gated.heldDoNotMoves.forEach { it.complete(Unit) }
            runCurrent()
            assertFalse(state.busy)
            assertTrue(state.doNotMove)
        }

    @Test
    fun busyStaysUntilReloadEnds() =
        runTest {
            val source = fake(atWith(atB, "102"))
            val gated = GatedSource(source).apply { holdStatuses = true }
            val state = state(gated)

            state.assign(atB.toString())
            runCurrent()
            // A ação respondeu; o recarregamento das opções está preso nos status.
            assertEquals("102", state.currentRoomNumber)
            assertTrue(state.busy)
            state.assign(atA.toString())
            runCurrent()
            assertEquals(1, source.assignCalls.size)

            gated.heldStatuses.forEach { it.complete(Unit) }
            runCurrent()
            assertFalse(state.busy)
            assertEquals(2, state.options.size)
        }

    // ---------- sucesso ----------

    @Test
    fun assignAdoptsRoomAndDoNotMoveFromResponseAndReloadsOptions() =
        runTest {
            val source = fake(atWith(atB, "102", doNotMove = true))
            source.suggestions[id] = listOf(atSuggestion(atB, "102", current = true), atSuggestion(atA, "101"))
            var changed = 0
            val state = state(source, onChanged = { changed++ })

            state.assign(atB.toString())
            runCurrent()

            assertEquals(atB.toString(), state.currentRoomId)
            assertEquals("102", state.currentRoomNumber)
            assertTrue(state.doNotMove)
            assertEquals(1, changed)
            assertEquals(listOf("102", "101"), state.options.map { it.number })
            assertTrue(state.options.first().current)
            assertEquals(1, source.suggestionCalls.size)
            assertEquals(1, source.roomStatusesCalls)
        }

    @Test
    fun responseWithoutAssignmentClearsRoom() =
        runTest {
            val source = fake(atDetail())
            val state = state(source, atWith(atA, "101", doNotMove = true).toCheckInSeed())

            state.assign(atB.toString())
            runCurrent()

            assertNull(state.currentRoomId)
            assertNull(state.currentRoomNumber)
            assertFalse(state.doNotMove)
        }

    @Test
    fun toggleSendsNegationBothWaysAndAdoptsResponse() =
        runTest {
            val source = fake(atWith(atA, "101", doNotMove = true))
            var changed = 0
            val state = state(source, atWith(atA, "101").toCheckInSeed(), onChanged = { changed++ })

            state.toggleDoNotMove()
            runCurrent()
            assertEquals(listOf(id to true), source.doNotMoveCalls)
            assertTrue(state.doNotMove)

            source.reservations[id] = atWith(atA, "101", doNotMove = false)
            state.toggleDoNotMove()
            runCurrent()
            assertEquals(listOf(id to true, id to false), source.doNotMoveCalls)
            assertFalse(state.doNotMove)
            assertEquals(2, changed)
        }

    @Test
    fun unassignAndToggleWithoutCurrentRoomCallNothing() =
        runTest {
            val source = fake()
            val state = state(source)
            state.unassign()
            state.toggleDoNotMove()
            runCurrent()

            assertTrue(source.unassignCalls.isEmpty())
            assertTrue(source.doNotMoveCalls.isEmpty())
            assertFalse(state.busy)
        }

    @Test
    fun reloadFailureAfterSuccessKeepsNewRoomNotifiesOnceAndReleasesBusy() =
        runTest {
            val source = fake(atWith(atB, "102")).apply { suggestionsError = atProblem("ROOM_INACTIVE") }
            var changed = 0
            val state = state(source, onChanged = { changed++ })

            state.assign(atB.toString())
            runCurrent()

            assertEquals("102", state.currentRoomNumber)
            assertEquals(1, changed)
            assertFalse(state.busy)
            assertNotNull(state.error)
        }

    // ---------- erros ----------

    @Test
    fun failuresShowActionTextNeverRawCodeAndDoNotNotify() =
        runTest {
            val codes = listOf("ROOM_ASSIGNMENT_LOCKED", "ROOM_ALREADY_ASSIGNED", "ROOM_ASSIGNMENT_NOT_ALLOWED")
            for (code in codes) {
                val source = fake(atWith(atB, "102")).apply { assignError = atProblem(code) }
                var changed = 0
                val seed = atWith(atA, "101", doNotMove = true).toCheckInSeed()
                val state = state(source, seed, onChanged = { changed++ })

                state.assign(atB.toString())
                runCurrent()

                val text = state.error
                assertNotNull(text, code)
                assertEquals(ActionText.error(atProblem(code)), text, code)
                assertFalse(text.contains(code), "código cru na tela: $code")
                assertTrue(text.isNotBlank(), code)
                assertEquals(0, changed, code)
                assertEquals("101", state.currentRoomNumber, code)
                assertTrue(state.doNotMove, code)
                assertFalse(state.busy, code)
            }
        }

    @Test
    fun unassignAndToggleFailuresShowErrorAndDoNotNotify() =
        runTest {
            val source =
                fake(atWith(atA, "101")).apply {
                    unassignError = atProblem("ROOM_ASSIGNMENT_LOCKED")
                    doNotMoveError = atProblem("ROOM_ASSIGNMENT_NOT_ALLOWED")
                }
            var changed = 0
            val state = state(source, atWith(atA, "101").toCheckInSeed(), onChanged = { changed++ })

            state.unassign()
            runCurrent()
            assertEquals(ActionText.error(atProblem("ROOM_ASSIGNMENT_LOCKED")), state.error)
            assertEquals("101", state.currentRoomNumber)

            state.toggleDoNotMove()
            runCurrent()
            assertEquals(ActionText.error(atProblem("ROOM_ASSIGNMENT_NOT_ALLOWED")), state.error)
            assertFalse(state.doNotMove)
            assertEquals(0, changed)
            assertFalse(state.busy)
        }

    @Test
    fun nextActionClearsPreviousError() =
        runTest {
            val source = fake(atWith(atB, "102")).apply { assignError = atProblem("ROOM_ALREADY_ASSIGNED") }
            val state = state(source)
            state.assign(atB.toString())
            runCurrent()
            assertNotNull(state.error)

            source.assignError = null
            state.assign(atB.toString())
            runCurrent()
            assertNull(state.error)
            assertEquals("102", state.currentRoomNumber)
        }

    @Test
    fun unknownThrowableBecomesFriendlyMessage() =
        runTest {
            val source = fake().apply { assignError = IllegalStateException("detalhe interno") }
            val state = state(source)
            state.assign(atA.toString())
            runCurrent()

            val text = assertNotNull(state.error)
            assertFalse(text.contains("detalhe interno"))
            assertFalse(state.busy)
        }

    // ---------- cancelamento ----------

    // Convenção do projeto: o cancelamento do PRÓPRIO estado nunca vira erro; uma CancellationException estranha,
    // vinda da chamada com o job do estado ativo, vira erro na tela e deixa o estado utilizável.

    @Test
    fun foreignCancellationFromAssignShowsErrorAndStaysUsable() =
        runTest {
            val source = fake(atWith(atB, "102")).apply { assignError = CancellationException("estranha") }
            val state = state(source)
            state.assign(atA.toString())
            runCurrent()

            assertNotNull(state.error)
            assertFalse(state.busy)

            source.assignError = null
            state.assign(atB.toString())
            runCurrent()
            assertEquals(2, source.assignCalls.size)
            assertEquals("102", state.currentRoomNumber)
            assertNull(state.error)
            assertFalse(state.busy)
        }

    @Test
    fun foreignCancellationFromUnassignAndToggleShowsErrorAndStaysUsable() =
        runTest {
            val source =
                fake(atWith(atA, "101", doNotMove = true)).apply {
                    unassignError = CancellationException("estranha")
                    doNotMoveError = CancellationException("estranha")
                }
            val state = state(source, atWith(atA, "101").toCheckInSeed())

            state.unassign()
            runCurrent()
            assertNotNull(state.error)
            assertFalse(state.busy)
            assertEquals("101", state.currentRoomNumber)

            state.toggleDoNotMove()
            runCurrent()
            assertNotNull(state.error)
            assertFalse(state.busy)
            assertFalse(state.doNotMove)

            source.doNotMoveError = null
            state.toggleDoNotMove()
            runCurrent()
            assertEquals(2, source.doNotMoveCalls.size)
            assertTrue(state.doNotMove)
            assertNull(state.error)
            assertFalse(state.busy)
        }

    @Test
    fun foreignCancellationFromLoadShowsErrorAndStaysUsable() =
        runTest {
            val source = fake().apply { suggestionsError = CancellationException("estranha") }
            val state = state(source)
            state.load()
            runCurrent()

            assertNotNull(state.error)
            assertFalse(state.loading)

            source.suggestionsError = null
            state.load()
            runCurrent()
            assertEquals(2, state.options.size)
            assertNull(state.error)
            assertFalse(state.loading)
        }

    @Test
    fun closeDuringLoadIsNotAnErrorAndReleasesLoading() =
        runTest {
            val source = fake().apply { holdSuggestions = true }
            val state = state(source)
            state.load()
            runCurrent()
            state.close()
            runCurrent()

            assertNull(state.error)
            assertFalse(state.loading)
        }

    @Test
    fun callerScopeCancellationDuringActionsIsNotAnError() =
        runTest {
            val parent = Job()
            val callerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
            val source = fake(atWith(atB, "102")).apply { holdAssign = true }
            val state = RoomAllocationState(source, callerScope, atDetail().toCheckInSeed(), true) {}
            state.assign(atB.toString())
            runCurrent()

            parent.cancel()
            runCurrent()

            assertNull(state.error)
            assertNull(state.currentRoomId)
        }

    // ---------- close ----------

    @Test
    fun lateAssignResponseAfterCloseChangesNothing() =
        runTest {
            val source = fake(atWith(atB, "102")).apply { holdAssign = true }
            var changed = 0
            val seed = atWith(atA, "101").toCheckInSeed()
            val state = state(source, seed, onChanged = { changed++ })

            state.assign(atB.toString())
            runCurrent()
            state.close()
            source.heldAssigns.forEach { it.complete(Unit) }
            runCurrent()

            assertEquals(0, changed)
            assertEquals("101", state.currentRoomNumber)
            assertEquals(atA.toString(), state.currentRoomId)
            assertNull(state.error)
            assertTrue(state.options.isEmpty())
            assertTrue(source.suggestionCalls.isEmpty())
        }

    @Test
    fun lateUnassignResponseAfterCloseChangesNothing() =
        runTest {
            val source = fake(atDetail())
            val gated = GatedSource(source).apply { holdUnassign = true }
            var changed = 0
            val state = state(gated, atWith(atA, "101").toCheckInSeed(), onChanged = { changed++ })

            state.unassign()
            runCurrent()
            state.close()
            gated.heldUnassigns.forEach { it.complete(Unit) }
            runCurrent()

            assertEquals(0, changed)
            assertEquals("101", state.currentRoomNumber)
            assertNull(state.error)
        }

    @Test
    fun lateToggleResponseAfterCloseChangesNothing() =
        runTest {
            val source = fake(atWith(atA, "101", doNotMove = true))
            val gated = GatedSource(source).apply { holdDoNotMove = true }
            var changed = 0
            val state = state(gated, atWith(atA, "101").toCheckInSeed(), onChanged = { changed++ })

            state.toggleDoNotMove()
            runCurrent()
            state.close()
            gated.heldDoNotMoves.forEach { it.complete(Unit) }
            runCurrent()

            assertEquals(0, changed)
            assertFalse(state.doNotMove)
            assertNull(state.error)
        }

    @Test
    fun closeWhileReloadAfterSuccessStopsWithoutErrorOrExtraNotify() =
        runTest {
            val source = fake(atWith(atB, "102")).apply { holdSuggestions = true }
            var changed = 0
            val state = state(source, onChanged = { changed++ })

            state.assign(atB.toString())
            runCurrent()
            assertEquals(1, changed)
            state.close()
            source.heldSuggestions.forEach { it.complete(Unit) }
            runCurrent()

            assertEquals(1, changed)
            assertNull(state.error)
            assertTrue(state.options.isEmpty())
        }

    @Test
    fun closeDoesNotCancelTheCallersScope() =
        runTest {
            val parent = Job()
            val callerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
            var ticks = 0
            val sibling =
                callerScope.launch {
                    while (true) {
                        ticks++
                        kotlinx.coroutines.yield()
                        if (ticks >= 3) awaitCancellation()
                    }
                }
            val source = fake()
            val state = RoomAllocationState(source, callerScope, atDetail().toCheckInSeed(), true) {}
            state.load()
            runCurrent()
            state.close()
            runCurrent()

            assertTrue(parent.isActive)
            assertTrue(sibling.isActive)
            // O escopo de quem chama segue lançando trabalho.
            var ran = false
            callerScope.launch { ran = true }
            runCurrent()
            assertTrue(ran)
            parent.cancel()
        }

    @Test
    fun cancellingTheCallerScopeStopsTheStateWork() =
        runTest {
            val parent = Job()
            val callerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
            val source = fake().apply { holdSuggestions = true }
            val state = RoomAllocationState(source, callerScope, atDetail().toCheckInSeed(), true) {}
            state.load()
            runCurrent()

            parent.cancel()
            source.heldSuggestions.forEach { it.complete(Unit) }
            runCurrent()

            assertTrue(state.options.isEmpty())
        }

    @Test
    fun closeIsIdempotentAndBlocksAllActions() =
        runTest {
            val source = fake(atWith(atA, "101"))
            val state = state(source, atWith(atA, "101").toCheckInSeed())
            state.close()
            state.close()
            state.load()
            state.assign(atB.toString())
            state.unassign()
            state.toggleDoNotMove()
            runCurrent()

            assertTrue(source.suggestionCalls.isEmpty())
            assertTrue(source.assignCalls.isEmpty())
            assertTrue(source.unassignCalls.isEmpty())
            assertTrue(source.doNotMoveCalls.isEmpty())
        }
}
