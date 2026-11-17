package com.zeny.desktop.app.reservations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.CancellationTermsResponse
import com.zeny.desktop.sdk.model.GuaranteeTermsResponse
import com.zeny.desktop.sdk.model.ReservationDetailResponse
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.ReservationResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val PMANAGE = "reservations.reservations.manage"
private const val PREAD = "reservations.reservations.read"
private const val PAVAIL = "reservations.availability.read"
private const val PTAB = "Disponibilidade e cotação"
private const val PPENDING = "Pendências"
private const val PCHANGE = "open-reservation-change"
private const val PNOTICE = "permissions-notice"
private const val PRETRY = "permissions-retry"
private const val PNOTICE_TEXT =
    "Não foi possível ler as suas permissões. As ações desta área ficam indisponíveis até a leitura dar certo."
private const val PHOLDER = "30000000-0000-0000-0000-000000000002"
private val ALL = setOf(PMANAGE, PREAD, PAVAIL)

private fun permDetail(item: ReservationListItemResponse) =
    ReservationDetailResponse(
        bookingCode = item.bookingCode,
        holderGuestId = item.holderGuestId,
        reservation =
            ReservationResponse(
                additionalPackagesTotal = "0.00",
                adults = 2,
                arrival = item.arrival,
                bookingId = item.bookingId,
                cancellationTerms =
                    CancellationTermsResponse(
                        24,
                        "Flexível",
                        penaltyType = CancellationTermsResponse.PenaltyType.NIGHTS,
                        policyId = Uuid.random(),
                        version = 1,
                    ),
                childAges = emptyList(),
                code = item.code,
                createdAt = "2026-11-10T12:00:00Z",
                dayUse = false,
                departure = item.departure,
                folioId = item.folioId,
                guaranteeTerms =
                    GuaranteeTermsResponse(name = "Sem garantia", policyId = Uuid.random(), type = "NONE", version = 1),
                guests = emptyList(),
                id = item.id,
                nightlyRates = emptyList(),
                penaltiesCharged = "0.00",
                ratePlanCategory = ReservationResponse.RatePlanCategory.NORMAL,
                ratePlanId = item.ratePlanId,
                roomTotal = "300.00",
                roomTypeId = Uuid.parse(typeId(1)),
                segment = "LEISURE",
                status = item.status,
                total = "300.00",
                version = 1,
            ),
        restrictionAlerts = emptyList(),
    )

/** A Recepção falsa com a leitura das permissões programável: conta, prende em [gate], falha ou responde [answer]. */
private class ScriptedReception(
    private val fake: FakeReceptionSource,
    var answer: Set<String>,
) : ReceptionSource by fake {
    var calls = 0
    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null

    override suspend fun permissions(): Set<String> {
        calls++
        gate?.await()
        failure?.let { throw it }
        return answer
    }
}

/** Ataques à ligação das permissões na área Reservas: valor velho, falha, repetição, porta lida e sair/voltar. */
@OptIn(ExperimentalTestApi::class)
@Suppress("TooManyFunctions") // um teste por ataque do brief
class ReservationsPermissionsAtaqueTest {
    private class Conn(
        private val reception: ReceptionSource?,
        private val availability: AvailabilitySource?,
        private val pending: PendingSource? = null,
        sessionId: String = "s-1",
    ) : ServerConnection {
        private val search =
            FakeReservationSearchSource().apply {
                reservations += searchItemOf(code = "R-1", holderGuestId = PHOLDER)
                holders[PHOLDER] = HolderSummary("Ana Fictícia", null)
            }
        private val change = FakeReservationChangeSource()
        override val state = MutableStateFlow<SessionState>(SessionState.Active("u-1", sessionId, false))

        override suspend fun login(
            login: String,
            password: String,
        ) = error("não usado")

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() {
            state.value = SessionState.LoggedOut
        }

        override suspend fun me(): Me = Me("u-1", "ana", "Ana Souza", emptySet())

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 13))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun receptionSource(): ReceptionSource? = reception

        override fun reservationSearchSource(): ReservationSearchSource = search

        override fun availabilitySource(): AvailabilitySource? = availability

        override fun pendingSource(): PendingSource? = pending

        override fun reservationChangeSource(): ReservationChangeSource = change

        override fun close() = Unit
    }

    private fun fakeReception(): FakeReceptionSource =
        FakeReceptionSource().apply {
            val item = searchItemOf(code = "R-1", holderGuestId = PHOLDER)
            reservations[item.id.toString()] = permDetail(item)
            holders[PHOLDER] = HolderSummary("Ana Fictícia", null)
            histories[item.id.toString()] = emptyList()
            ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
        }

    private fun availability(permissions: Set<String> = ALL) =
        FakeAvailabilitySource().apply { this.permissions = permissions }

    private fun ComposeUiTest.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.area(conn: Conn) {
        setContent { ZenyTheme { AreaContent(Area.RESERVATIONS, conn) } }
        waitForIdle()
    }

    private fun ComposeUiTest.openR1() {
        onNodeWithText("R-1").performClick()
        waitForIdle()
        onNodeWithTag("reservation-detail").assertExists()
    }

    private fun ComposeUiTest.assertNoWrite() {
        assertFalse(hasTag(PCHANGE), "ação de escrita visível")
        assertFalse(hasText(PTAB), "aba de disponibilidade visível")
    }

    private fun ComposeUiTest.assertWrite() {
        assertTrue(hasTag(PCHANGE), "sem a ação")
        assertTrue(hasText(PTAB), "sem a aba")
    }

    /** O R-1 da conexão e o do detalhe falso têm ids diferentes; o detalhe do falso é programado por conexão. */
    private fun conn(
        reception: ReceptionSource?,
        availability: AvailabilitySource?,
        fake: FakeReceptionSource? = null,
        pending: PendingSource? = null,
        sessionId: String = "s-1",
    ): Conn {
        val c = Conn(reception, availability, pending, sessionId)
        fake?.let { f ->
            val item = c.reservationSearchSource().let { (it as FakeReservationSearchSource).reservations.first() }
            f.reservations.clear()
            f.reservations[item.id.toString()] = permDetail(item)
            f.histories[item.id.toString()] = emptyList()
            f.ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
        }
        return c
    }

    @Test
    fun sameConnectionNewSessionNeverShowsThePreviousGrantAndFailureStaysClosed() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, ALL)
            val conn = conn(reception, availability(), fake)
            area(conn)
            openR1()
            assertWrite()

            reception.gate = CompletableDeferred()
            reception.failure = IOException("segredo-xyz")
            conn.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()
            openR1()
            assertNoWrite()
            assertFalse(hasTag(PNOTICE))

            reception.gate!!.complete(Unit)
            waitForIdle()
            assertNoWrite()
            onNodeWithTag(PNOTICE).assertTextEquals(PNOTICE_TEXT)
            assertFalse(hasText("segredo-xyz", substring = true), "mensagem da exceção na tela")

            // A repetição da B responde com MENOS que a A: sem ação, sem aba, sem aviso.
            reception.failure = null
            reception.answer = setOf(PREAD)
            onNodeWithTag(PRETRY).performClick()
            waitForIdle()
            assertNoWrite()
            assertFalse(hasTag(PNOTICE))
        }

    private fun ComposeUiTest.hasText(
        text: String,
        substring: Boolean,
    ) = onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun newConnectionNeverShowsThePreviousConnectionGrant() =
        runComposeUiTest {
            val fakeA = fakeReception()
            val connA = conn(ScriptedReception(fakeA, ALL), availability(), fakeA)
            val fakeB = fakeReception()
            val receptionB = ScriptedReception(fakeB, setOf(PREAD)).apply { gate = CompletableDeferred() }
            val availB = availability(setOf(PREAD))
            val connB = conn(receptionB, availB, fakeB, sessionId = "s-1")
            var current by mutableStateOf(connA)
            setContent { ZenyTheme { AreaContent(Area.RESERVATIONS, current) } }
            waitForIdle()
            openR1()
            assertWrite()

            current = connB
            waitForIdle()
            openR1()
            assertNoWrite()
            assertEquals(1, receptionB.calls)
            receptionB.gate!!.complete(Unit)
            waitForIdle()
            assertNoWrite()
            assertFalse(hasTag(PNOTICE))
        }

    @Test
    fun failedReadShowsNoticeNoActionNoTabAndRetryReadsOnce() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, ALL).apply { failure = IOException("fora") }
            val avail = availability()
            area(conn(reception, avail, fake))
            openR1()
            onNodeWithTag(PNOTICE).assertTextEquals(PNOTICE_TEXT)
            assertNoWrite()
            assertEquals(1, reception.calls)

            // A repetição presa: uma leitura só, e o aviso sai enquanto lê (nada de ação por um quadro).
            reception.gate = CompletableDeferred()
            onNodeWithTag(PRETRY).performClick()
            waitForIdle()
            assertEquals(2, reception.calls)
            assertFalse(hasTag(PNOTICE))
            assertNoWrite()
            waitForIdle()
            assertEquals(2, reception.calls)

            // A repetição falha de novo: o aviso volta.
            reception.gate!!.complete(Unit)
            waitForIdle()
            onNodeWithTag(PNOTICE).assertTextEquals(PNOTICE_TEXT)
            assertNoWrite()
            assertEquals(0, avail.permissionsCalls, "a falha da Recepção não cai para a porta de disponibilidade")

            // A que dá certo traz tudo e tira o aviso.
            reception.gate = null
            reception.failure = null
            onNodeWithTag(PRETRY).performClick()
            waitForIdle()
            assertFalse(hasTag(PNOTICE))
            assertWrite()
            assertEquals(3, reception.calls)
            assertEquals(0, avail.permissionsCalls)
        }

    @Test
    fun retryGrantedWithoutManageBringsNoActionAndWithoutAvailabilityReadNoTab() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, setOf(PREAD, PAVAIL)).apply { failure = IOException("fora") }
            area(conn(reception, availability(), fake))
            openR1()
            onNodeWithTag(PNOTICE).assertExists()

            reception.failure = null
            onNodeWithTag(PRETRY).performClick()
            waitForIdle()
            assertFalse(hasTag(PNOTICE))
            assertFalse(hasTag(PCHANGE), "ação sem manage")
            assertTrue(hasText(PTAB))
        }

    @Test
    fun retryGrantedWithoutAvailabilityReadBringsNoTabButKeepsActions() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, setOf(PREAD, PMANAGE)).apply { failure = IOException("fora") }
            area(conn(reception, availability(), fake))
            openR1()
            onNodeWithTag(PNOTICE).assertExists()

            reception.failure = null
            onNodeWithTag(PRETRY).performClick()
            waitForIdle()
            assertFalse(hasTag(PNOTICE))
            assertTrue(hasTag(PCHANGE))
            assertFalse(hasText(PTAB), "aba sem availability.read")
        }

    @Test
    fun loadingShowsNoActionNoAvailabilityTabNoNotice() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, ALL).apply { gate = CompletableDeferred() }
            val avail = availability()
            area(conn(reception, avail, fake))
            openR1()
            assertNoWrite()
            assertFalse(hasTag(PNOTICE))
            assertEquals(0, avail.availabilityCalls.size)
            assertEquals(0, avail.permissionsCalls)
        }

    @Test
    fun receptionReadFailureDoesNotBecomeGrantThroughTheAvailabilityPort() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, ALL).apply { failure = IOException("fora") }
            val avail = availability()
            area(conn(reception, avail, fake))
            openR1()
            assertNoWrite()
            assertTrue(hasTag(PNOTICE))
            assertEquals(0, avail.permissionsCalls)
            assertEquals(0, avail.availabilityCalls.size)
        }

    @Test
    fun withBothPortsOnlyTheReceptionIsRead() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, ALL)
            val avail = availability()
            area(conn(reception, avail, fake))
            openR1()
            onNodeWithText(PTAB).performClick()
            waitForIdle()
            onNodeWithText("Busca").performClick()
            waitForIdle()
            assertEquals(1, reception.calls)
            assertEquals(0, avail.permissionsCalls)
        }

    @Test
    fun withOnlyTheAvailabilityPortItIsTheOneRead() =
        runComposeUiTest {
            val avail = availability()
            area(Conn(null, avail))
            assertTrue(hasText(PTAB))
            assertEquals(1, avail.permissionsCalls)
            assertFalse(hasTag(PNOTICE))
        }

    @Test
    fun onlyAvailabilityPortFailingShowsNoticeAndNoTab() =
        runComposeUiTest {
            val avail = availability().apply { failures[FakeAvailabilitySource.Op.PERMISSIONS] = IOException("x") }
            area(Conn(null, avail))
            assertFalse(hasText(PTAB))
            onNodeWithTag(PNOTICE).assertTextEquals(PNOTICE_TEXT)
            avail.failures.clear()
            onNodeWithTag(PRETRY).performClick()
            waitForIdle()
            assertTrue(hasText(PTAB))
            assertFalse(hasTag(PNOTICE))
            assertEquals(2, avail.permissionsCalls)
        }

    @Test
    fun withNoPermissionPortThereIsNoReadAndNoNotice() =
        runComposeUiTest {
            val pending = FakePendingSource()
            area(Conn(null, null, pending))
            assertFalse(hasTag(PNOTICE))
            assertFalse(hasText(PTAB))
            assertTrue(hasText(PPENDING), "a aba das pendências pede só a porta dela")
        }

    @Test
    fun pendingTabStaysWhileLoadingAndWhenPermissionsFail() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, ALL).apply { gate = CompletableDeferred() }
            area(conn(reception, availability(), fake, FakePendingSource()))
            assertTrue(hasText(PPENDING), "sumiu em Loading")
            assertFalse(hasText(PTAB))
            reception.failure = IOException("fora")
            reception.gate!!.complete(Unit)
            waitForIdle()
            assertTrue(hasTag(PNOTICE))
            assertTrue(hasText(PPENDING), "sumiu com a falha")
            assertFalse(hasText(PTAB))
            reception.failure = null
            onNodeWithTag(PRETRY).performClick()
            waitForIdle()
            assertTrue(hasText(PPENDING))
            assertTrue(hasText(PTAB))
        }

    @Test
    fun noNoticeWhenGrantedAndNoticeHasNoCodeOrMessage() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, ALL).apply { failure = IOException("detalhe-interno-123") }
            area(conn(reception, availability(), fake))
            onNodeWithTag(PNOTICE).assertTextEquals(PNOTICE_TEXT)
            assertFalse(hasText("detalhe-interno-123", substring = true))
            assertFalse(hasText("IOException", substring = true))
            reception.failure = null
            onNodeWithTag(PRETRY).performClick()
            waitForIdle()
            assertFalse(hasTag(PNOTICE))
            assertFalse(hasTag(PRETRY))
        }

    @Test
    fun leavingTheAreaAndComingBackReadsAgainAndNeverReusesTheOldGrant() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, ALL)
            val conn = conn(reception, availability(), fake)
            var show by mutableStateOf(true)
            setContent { ZenyTheme { if (show) AreaContent(Area.RESERVATIONS, conn) } }
            waitForIdle()
            openR1()
            assertWrite()
            assertEquals(1, reception.calls)

            show = false
            waitForIdle()
            reception.gate = CompletableDeferred()
            reception.answer = setOf(PREAD)
            show = true
            waitForIdle()
            openR1()
            assertEquals(2, reception.calls)
            assertNoWrite()
            reception.gate!!.complete(Unit)
            waitForIdle()
            assertFalse(hasTag(PCHANGE))
            assertFalse(hasText(PTAB))
            assertFalse(hasTag(PNOTICE))
            assertEquals(2, reception.calls)
        }

    @Test
    fun leavingWhileReadIsHeldDropsTheLateAnswer() =
        runComposeUiTest {
            val fake = fakeReception()
            val reception = ScriptedReception(fake, ALL).apply { gate = CompletableDeferred() }
            val conn = conn(reception, availability(), fake)
            var show by mutableStateOf(true)
            setContent { ZenyTheme { if (show) AreaContent(Area.RESERVATIONS, conn) } }
            waitForIdle()
            show = false
            waitForIdle()
            reception.gate!!.complete(Unit)
            waitForIdle()
            show = true
            reception.gate = CompletableDeferred()
            waitForIdle()
            openR1()
            assertNoWrite()
            assertEquals(2, reception.calls)
        }
}
