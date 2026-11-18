package com.zeny.desktop.app.shell

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.HolderSummary
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reservations.AvailabilitySource
import com.zeny.desktop.app.reservations.FakeAvailabilitySource
import com.zeny.desktop.app.reservations.FakeReservationChangeSource
import com.zeny.desktop.app.reservations.FakeReservationSearchSource
import com.zeny.desktop.app.reservations.ReservationChangeSource
import com.zeny.desktop.app.reservations.ReservationSearchSource
import com.zeny.desktop.app.reservations.confirmDetail
import com.zeny.desktop.app.reservations.searchItemOf
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.app.traces.FakeTracesSource
import com.zeny.desktop.app.traces.TracesSource
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.Department
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.TraceResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "reservations.reservations.read"
private const val MANAGE = "reservations.reservations.manage"
private const val AVAILABILITY_READ = "reservations.availability.read"
private const val TRACES_READ = "frontdesk.traces.read"
private const val TRACES_MANAGE = "frontdesk.traces.manage"
private const val TRACES_RESOLVE = "frontdesk.traces.resolve"
private const val CHECKIN = "frontdesk.stays.checkin"
private const val ROOMS_STATUS_READ = "rooms.status.read"
private const val TAB = "Disponibilidade e cotação"
private const val CHANGE = "open-reservation-change"
private const val HOLDER = "30000000-0000-0000-0000-000000000003"
private const val PANEL_DATE = "Data operacional: 13/11/2026"
private val DEPARTMENT = Department("40000000-0000-0000-0000-000000000001", "Governança")

/**
 * Ataques à entrega do conjunto da casca (M4 E06, ADR 0013): o `me` preso de uma sessão nunca chega à área da
 * seguinte; a área que a casca desenhou some quando a conexão passa a outra sessão; os Recados abertos pela casca
 * mostram a lista e só as ações do conjunto lido, com uma leitura do `me` só.
 */
@OptIn(ExperimentalTestApi::class)
class ShellPermissionsAtaqueTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    /** Conexão com a Reservas (o R-1 com detalhe), a Recepção e os Recados falsos; o `me` atende por chamada. */
    private class Conn(
        initial: SessionState = SessionState.LoggedOut,
        val answers: MutableList<suspend () -> Me> = mutableListOf(),
    ) : ServerConnection {
        override val state = MutableStateFlow(initial)
        val search = FakeReservationSearchSource()
        val reception = FakeReceptionSource()
        val availability = FakeAvailabilitySource()
        val traces = FakeTracesSource()
        private val change = FakeReservationChangeSource()
        var meCalls = 0

        init {
            val item = searchItemOf(code = "R-1", holderGuestId = HOLDER)
            val detail = confirmDetail(item.id, status = "CONFIRMED")
            search.reservations += item
            search.holders[HOLDER] = HolderSummary("Ana Fictícia", null)
            reception.reservations[item.id.toString()] = detail
            reception.holders[detail.holderGuestId.toString()] = HolderSummary("Ana Fictícia", null)
            reception.histories[item.id.toString()] = emptyList()
            reception.ratePlans[detail.reservation.ratePlanId.toString()] = "Tarifa Balcão"
            traces.departments = listOf(DEPARTMENT)
            traces.seed(openTrace())
        }

        override suspend fun login(
            login: String,
            password: String,
        ) {
            state.value = SessionState.Active("u-1", "s-1", false)
        }

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() {
            state.value = SessionState.LoggedOut
        }

        override suspend fun me(): Me {
            val answer = answers.getOrElse(meCalls) { answers.last() }
            meCalls++
            return answer()
        }

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 13))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun receptionSource(): ReceptionSource = reception

        override fun reservationSearchSource(): ReservationSearchSource = search

        override fun availabilitySource(): AvailabilitySource = availability

        override fun reservationChangeSource(): ReservationChangeSource = change

        override fun tracesSource(): TracesSource = traces

        override fun close() = Unit
    }

    private fun me(vararg permissions: String) = Me("u-1", "ana", "Ana Souza", setOf(*permissions))

    private fun ComposeUiTest.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.click(text: String) {
        onNodeWithText(text).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.shell(conn: Conn) {
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AppController(Settings(), { conn }, { ProbeResult.Ok("1.0") }, scope) }
            ZenyTheme { AppRoot(controller, devMode = false) }
        }
        onNodeWithTag("login-user").performTextInput("ana")
        onNodeWithTag("login-password").performTextInput("segredo-123")
        click("Entrar")
    }

    @Test
    fun heldMeOfSessionAIsCancelledAndSessionBAreaNeverSeesItsLargerSet() =
        runComposeUiTest {
            val gateA = CompletableDeferred<Me>()
            var aCancelled = false
            val conn =
                Conn(
                    answers =
                        mutableListOf(
                            {
                                try {
                                    gateA.await()
                                } catch (e: CancellationException) {
                                    aCancelled = true
                                    throw e
                                }
                            },
                            { me(READ) },
                        ),
                )
            shell(conn)
            assertFalse(hasText("Reservas"), "menu da sessão A sem o me responder")

            conn.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()
            assertTrue(aCancelled, "a leitura do me da sessão A seguiu em voo depois da troca")

            // A resposta da sessão A, com mais permissões, chega depois da troca: não vale na B.
            gateA.complete(me(READ, MANAGE, AVAILABILITY_READ))
            waitForIdle()
            click("Reservas")
            click("R-1")

            onNodeWithTag("reservation-detail").assertExists()
            assertFalse(hasText(TAB), "a aba de disponibilidade veio do me da sessão A")
            assertFalse(hasTag(CHANGE), "as ações da reserva vieram do me da sessão A")
            assertEquals(2, conn.meCalls)
        }

    @Test
    fun heldMeOfSessionBHidesTheChangeActionUntilItAnswersAndThenGrantsIt() =
        runComposeUiTest {
            val gateB = CompletableDeferred<Me>()
            val conn = Conn(answers = mutableListOf({ me(READ, MANAGE) }, { gateB.await() }))
            shell(conn)
            click("Reservas")
            click("R-1")
            assertTrue(hasTag(CHANGE), "na sessão A, com manage, a ação aparece")

            conn.state.value = SessionState.Active("u-1", "s-2", false)
            waitForIdle()

            assertFalse(hasTag(CHANGE), "a ação da sessão A valeu na B com o me da B em voo")
            assertFalse(hasTag("reservation-detail"), "o detalhe da sessão A seguiu na B com o me em voo")
            assertFalse(hasText("R-1"), "a área foi desenhada com o me da sessão B em voo")

            gateB.complete(me(READ, MANAGE))
            waitForIdle()
            click("R-1")

            onNodeWithTag("reservation-detail").assertExists()
            assertTrue(hasTag(CHANGE), "lida a permissão da sessão B, a ação volta")
            assertEquals(2, conn.meCalls)
        }

    @Test
    fun receptionDrawnForAVanishesWhenTheConnectionMovesToB() =
        runComposeUiTest {
            // O quadro da corrida: a casca ainda é a da sessão A, e a conexão já está na B.
            val conn = Conn(SessionState.Active("u-1", "A", false))
            setContent {
                ZenyTheme { AreaContent(Area.RECEPTION, conn, "A", setOf(CHECKIN, ROOMS_STATUS_READ)) }
            }
            waitForIdle()
            onNodeWithText(PANEL_DATE).assertExists()
            assertEquals(1, conn.reception.activeSubscriptions)

            conn.state.value = SessionState.Active("u-1", "B", false)
            waitForIdle()

            assertFalse(hasText(PANEL_DATE), "o painel da sessão A seguiu na tela com a conexão na B")
            assertEquals(0, conn.reception.activeSubscriptions, "a assinatura da sessão A seguiu aberta na B")
            assertEquals(1, conn.reception.subscriptions, "a Recepção assinou de novo com o conjunto da A")
        }

    @Test
    fun reservationsDrawnForAVanishesWhenTheConnectionMovesToB() =
        runComposeUiTest {
            val conn = Conn(SessionState.Active("u-1", "A", false))
            setContent {
                ZenyTheme { AreaContent(Area.RESERVATIONS, conn, "A", setOf(READ, MANAGE, AVAILABILITY_READ)) }
            }
            waitForIdle()
            onNodeWithText("R-1").assertExists()
            assertTrue(hasText(TAB))
            val searches = conn.search.searchCalls.size

            conn.state.value = SessionState.Active("u-1", "B", false)
            waitForIdle()

            assertFalse(hasText("R-1"), "a busca da sessão A seguiu na tela com a conexão na B")
            assertFalse(hasText(TAB), "a aba do conjunto da A seguiu com a conexão na B")
            assertEquals(searches, conn.search.searchCalls.size, "a área buscou de novo com o conjunto da A")
        }

    @Test
    fun noShellSessionOrNoActiveConnectionDrawsNothing() {
        listOf(
            null to SessionState.Active("u-1", "B", false),
            "A" to SessionState.LoggedOut,
        ).forEach { (sessionId, session) ->
            runComposeUiTest {
                val conn = Conn(session)
                setContent {
                    ZenyTheme {
                        AreaContent(Area.RESERVATIONS, conn, sessionId, setOf(READ, MANAGE, AVAILABILITY_READ))
                        AreaContent(Area.RECEPTION, conn, sessionId, setOf(CHECKIN, ROOMS_STATUS_READ))
                    }
                }
                waitForIdle()

                assertFalse(hasText("R-1"), "Reservas desenhada com casca $sessionId e conexão $session")
                assertFalse(hasText(PANEL_DATE), "Recepção desenhada com casca $sessionId e conexão $session")
                assertEquals(0, conn.search.searchCalls.size)
                assertEquals(0, conn.reception.subscriptions)
                assertEquals(0, conn.reception.businessDayCalls)
            }
        }
    }

    @Test
    fun receptionTracesFromTheShellShowTheListWithOneMeRead() =
        runComposeUiTest {
            val conn = Conn(answers = mutableListOf({ me(CHECKIN, READ, TRACES_READ, TRACES_MANAGE, TRACES_RESOLVE) }))
            shell(conn)
            click("Recepção")
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()

            onNodeWithTag("traces-panel").assertExists()
            onNodeWithText("texto sigiloso 1").assertExists()
            assertTrue(hasTag("trace-edit-0"), "editar sumiu com frontdesk.traces.manage lido pela casca")
            assertTrue(hasTag("trace-resolve-0"), "resolver sumiu com frontdesk.traces.resolve lido pela casca")
            assertTrue(hasTag("trace-new"), "novo recado sumiu com frontdesk.traces.manage lido pela casca")

            click("Reservas")
            click("Recepção")
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()
            onNodeWithTag("traces-panel").assertExists()
            assertEquals(1, conn.meCalls, "uma área leu o me por conta própria")
        }

    @Test
    fun receptionTracesWithReadOnlySetShowTheListWithoutActions() =
        runComposeUiTest {
            val conn = Conn(answers = mutableListOf({ me(CHECKIN, TRACES_READ) }))
            conn.traces.permissions = setOf(TRACES_READ, TRACES_MANAGE, TRACES_RESOLVE)
            shell(conn)
            click("Recepção")
            onNodeWithTag("tab-TRACES").performClick()
            waitForIdle()

            onNodeWithTag("traces-panel").assertExists()
            onNodeWithText("texto sigiloso 1").assertExists()
            assertFalse(hasTag("trace-edit-0"), "editar sem frontdesk.traces.manage no me")
            assertFalse(hasTag("trace-resolve-0"), "resolver sem frontdesk.traces.resolve no me")
            assertFalse(hasTag("trace-new"), "novo recado sem frontdesk.traces.manage no me")
        }

    // Pendência da E06: a seção de recados da reserva (`forReservation` em `receptionTraces`, AppRoot) não tinha
    // asserção pela casca. A reserva aberta pela linha do dia mostra os recados dela com o conjunto do `me`.
    @Test
    fun reservationTracesSectionFromTheShellShowsTheReservationTraces() =
        runComposeUiTest {
            val conn = Conn(answers = mutableListOf({ me(CHECKIN, READ, TRACES_READ) }))
            val id = conn.withArrivalAndItsTrace()
            shell(conn)
            click("Recepção")
            onNodeWithTag("row-$id").performClick()
            waitForIdle()

            onNodeWithTag("reservation-detail").assertExists()
            inDetail("Recados").assertExists()
            inDetail("recado da reserva aberta").assertExists()
            assertEquals(1, conn.meCalls, "a seção leu o me por conta própria")
        }

    @Test
    fun reservationTracesSectionIsAbsentWhenTheShellSetLacksTracesReadEvenIfTheSourceWouldAnswer() =
        runComposeUiTest {
            val conn = Conn(answers = mutableListOf({ me(CHECKIN, READ) }))
            conn.traces.permissions = setOf(TRACES_READ, TRACES_MANAGE, TRACES_RESOLVE)
            val id = conn.withArrivalAndItsTrace()
            shell(conn)
            click("Recepção")
            onNodeWithTag("row-$id").performClick()
            waitForIdle()

            onNodeWithTag("reservation-detail").assertExists()
            inDetail("Recados").assertDoesNotExist()
            onNodeWithText("recado da reserva aberta").assertDoesNotExist()
        }

    private fun Conn.withArrivalAndItsTrace(): String {
        val item = searchItemOf(code = "R-7", holderGuestId = HOLDER)
        val detail = confirmDetail(item.id, status = "CONFIRMED")
        reception.pages[DayList.ARRIVALS to null] = Page(listOf(item), null)
        reception.reservations[item.id.toString()] = detail
        reception.holders[detail.holderGuestId.toString()] = HolderSummary("Ana Fictícia", null)
        reception.holders[HOLDER] = HolderSummary("Ana Fictícia", null)
        reception.ratePlans[item.ratePlanId.toString()] = "Tarifa Balcão"
        reception.ratePlans[detail.reservation.ratePlanId.toString()] = "Tarifa Balcão"
        reception.histories[item.id.toString()] = emptyList()
        traces.reservationCodes[item.id.toString()] = "R-7"
        traces.seed(
            openTrace().copy(
                id = Uuid.parse("50000000-0000-0000-0000-000000000077"),
                reservationId = Option.Some(item.id),
                text = "recado da reserva aberta",
            ),
        )
        return item.id.toString()
    }

    private fun ComposeUiTest.inDetail(text: String) =
        onNode(androidx.compose.ui.test.hasText(text) and hasAnyAncestor(hasTestTag("reservation-detail")))

    private companion object {
        fun openTrace() =
            TraceResponse(
                assigneeName = Option.Some("Maria"),
                createdAt = "2026-11-14T10:00:00Z",
                createdBy = Uuid.random(),
                departmentId = Uuid.parse(DEPARTMENT.id),
                departmentName = DEPARTMENT.name,
                dueOn = "2026-11-14",
                id = Uuid.parse("50000000-0000-0000-0000-000000000001"),
                reservationId = Option.Undefined,
                roomId = Option.Undefined,
                roomNumber = Option.Undefined,
                status = "OPEN",
                text = "texto sigiloso 1",
                version = 1,
            )
    }
}
