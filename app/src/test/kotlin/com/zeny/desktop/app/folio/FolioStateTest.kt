package com.zeny.desktop.app.folio

import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.sdk.model.FolioOwnerDto
import com.zeny.desktop.sdk.model.FolioResponse
import com.zeny.desktop.sdk.model.FolioStatementResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val CREATE = "billing.postings.create"
private const val REVERSE = "billing.postings.reverse"

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // um teste por regra do brief
class FolioStateTest {
    private fun TestScope.started(source: FakeFolioSource) =
        FolioState(wiringOf(source), backgroundScope, FOLIO_ID.toString()).also {
            it.start()
            runCurrent()
        }

    @Test
    fun loadsFolioAndPostings() =
        runTest {
            val source = FakeFolioSource()
            source.folio = { folioOf(balance = "1234.5", creditLimit = "5000.00", exceeded = true) }
            source.postings = { listOf(postingOf(description = "Diária", amount = "300.00")) }

            val state = started(source)

            val view = assertNotNull(state.view)
            assertEquals("F-0001", view.number)
            assertEquals("R$ 1.234,50", view.balance)
            assertEquals("R$ 5.000,00", view.creditLimit)
            assertTrue(view.limitExceeded)
            assertEquals("Aberta", view.status)
            assertEquals(listOf("Diária"), state.postings.map { it.description })
            assertEquals("R$ 300,00", state.postings.single().amount)
            assertEquals("18/11/2026", state.postings.single().date)
            assertEquals("1", state.postings.single().quantity)
            assertEquals(listOf(FOLIO_ID.toString()), source.folioCalls)
            assertEquals(listOf(FOLIO_ID.toString()), source.postingCalls)
        }

    @Test
    fun serviceChargeSitsUnderItsItem() =
        runTest {
            val source = FakeFolioSource()
            val item = Uuid.random()
            source.postings = {
                listOf(
                    postingOf(item, "Restaurante", "100.00"),
                    postingOf(description = "Diária", amount = "300.00"),
                    postingOf(description = "Taxa", amount = "10.00", type = "SERVICE_CHARGE", serviceChargeFor = item),
                )
            }

            val state = started(source)

            assertEquals(listOf("Restaurante", "Taxa", "Diária"), state.postings.map { it.description })
            val charge = state.postings[1]
            assertTrue(charge.serviceCharge)
            assertEquals("Taxa de serviço", charge.type)
            assertFalse(state.postings[0].serviceCharge)
        }

    @Test
    fun unknownTypeShowsAsOther() =
        runTest {
            val source = FakeFolioSource()
            source.postings = {
                listOf(
                    postingOf(description = "Novo", type = "LOYALTY_BURN"),
                    postingOf(description = "Diária", type = "REGULAR"),
                    postingOf(description = "Volta", type = "REVERSAL"),
                    postingOf(description = "Acerto", type = "ADJUSTMENT"),
                    postingOf(description = "Sai", type = "TRANSFER_OUT"),
                    postingOf(description = "Entra", type = "TRANSFER_IN"),
                )
            }

            val state = started(source)

            assertEquals(
                listOf(
                    "Outro (LOYALTY_BURN)",
                    "Lançamento",
                    "Estorno",
                    "Ajuste",
                    "Transferência (saída)",
                    "Transferência (entrada)",
                ),
                state.postings.map { it.type },
            )
            assertNotNull(state.view)
        }

    @Test
    fun reversedPostingIsMarked() =
        runTest {
            val source = FakeFolioSource()
            source.postings = {
                listOf(postingOf(description = "Frigobar", reversedBy = Uuid.random()), postingOf(description = "Bar"))
            }

            val state = started(source)

            assertEquals(listOf(true, false), state.postings.map { it.reversed })
        }

    @Test
    fun guestStatementShowsOnlyWhatServerSends() =
        runTest {
            val source = FakeFolioSource()
            source.statement = { audience ->
                if (audience == StatementAudience.GUEST) {
                    val line = lineOf("Pagamento (Pix)", kind = "PAYMENT")
                    statementOf(FolioStatementResponse.Audience.GUEST, listOf(line))
                } else {
                    statementOf(lines = listOf(lineOf("Frigobar", reason = "Cortesia da gerência")))
                }
            }
            val state = started(source)

            state.audience = StatementAudience.GUEST
            state.showStatement()
            runCurrent()

            val guest = assertNotNull(state.statement)
            val guestLine =
                guest.days
                    .single()
                    .lines
                    .single()
            assertEquals("Pagamento (Pix)", guestLine.description)
            assertEquals("Pagamento", guestLine.kind)
            assertNull(guestLine.reason)
            assertFalse(guest.toString().contains(USER_ID.toString()))
            assertFalse(guest.toString().contains("PIX"))

            state.audience = StatementAudience.INTERNAL
            runCurrent()

            val internal = assertNotNull(state.statement)
            assertEquals(
                "Cortesia da gerência",
                internal.days
                    .single()
                    .lines
                    .single()
                    .reason,
            )
            assertFalse(internal.toString().contains(USER_ID.toString()))
            assertEquals(
                listOf(StatementAudience.GUEST, StatementAudience.INTERNAL).map { FOLIO_ID.toString() to it },
                source.statementCalls,
            )
        }

    @Test
    fun switchingAudienceDropsTheShownStatement() =
        runTest {
            val source = FakeFolioSource()
            source.statement = { statementOf(lines = listOf(lineOf("Frigobar", reason = "Cortesia da gerência"))) }
            val state = started(source)
            state.showStatement()
            runCurrent()
            assertNotNull(
                state.statement
                    ?.days
                    ?.single()
                    ?.lines
                    ?.single()
                    ?.reason,
            )

            source.statement = { throw ApiError.Network(RuntimeException("sem rede")) }
            state.audience = StatementAudience.GUEST

            assertNull(state.statement, "o extrato interno some antes da releitura")
            runCurrent()
            assertNull(state.statement)
            assertNotNull(state.statementError)
        }

    @Test
    fun postAndReverseFollowPermissionsAndStatus() =
        runTest {
            val regular = postingOf(description = "Frigobar")
            val item = Uuid.random()
            val source = FakeFolioSource()
            source.postings = {
                listOf(
                    regular,
                    postingOf(item, "Bar"),
                    postingOf(description = "Taxa", type = "SERVICE_CHARGE", serviceChargeFor = item),
                    postingOf(description = "Lavanderia", reversedBy = Uuid.random()),
                    postingOf(description = "Estorno", type = "REVERSAL"),
                    postingOf(description = "Novo", type = "LOYALTY_BURN"),
                )
            }

            fun TestScope.opened(
                permissions: Set<String>,
                status: FolioResponse.Status = FolioResponse.Status.OPEN,
            ): FolioState {
                source.folio = { folioOf(status = status) }
                return FolioState(wiringOf(source, permissions), backgroundScope, FOLIO_ID.toString()).also {
                    it.start()
                    runCurrent()
                }
            }

            val reader = opened(setOf(READ))
            assertFalse(reader.canPost)
            assertTrue(reader.postings.none { reader.canReverse(it) })

            val poster = opened(setOf(READ, CREATE))
            assertTrue(poster.canPost)
            assertEquals(listOf(true, true, false, false, false, false), poster.postings.map { poster.canReverse(it) })

            val reverser = opened(setOf(READ, REVERSE))
            assertFalse(reverser.canPost)
            assertEquals(
                listOf("Frigobar", "Bar"),
                reverser.postings.filter { reverser.canReverse(it) }.map { it.description },
            )

            val closed = opened(setOf(READ, CREATE, REVERSE), FolioResponse.Status.CLOSED)
            assertFalse(closed.canPost)
            assertTrue(closed.postings.none { closed.canReverse(it) })
            closed.startPosting()
            closed.startReversal(regular.id.toString())
            assertNull(closed.posting)
            assertNull(closed.reversal)
            assertEquals(0, source.codeCalls)
        }

    @Test
    fun statementBalanceIsTheServerBalance() =
        runTest {
            val source = FakeFolioSource()
            source.statement = { statementOf(balance = "987.65") }
            val state = started(source)

            state.showStatement()
            runCurrent()

            val statement = assertNotNull(state.statement)
            assertEquals(StatementAudience.INTERNAL, state.audience)
            assertEquals("R$ 987,65", statement.balance)
            assertEquals("R$ 170,00", statement.charges)
            assertEquals("R$ 20,00", statement.payments)
            val day = statement.days.single()
            assertEquals("18/11/2026", day.date)
            assertEquals("R$ 140,00", day.balanceAtEnd)
            assertEquals("R$ 170,00", day.charges)
            assertEquals("R$ 30,00", day.payments)
        }

    @Test
    fun unknownStatementKindShowsAsOther() =
        runTest {
            val source = FakeFolioSource()
            source.statement = {
                statementOf(lines = listOf(lineOf("Ponto", kind = "LOYALTY"), lineOf("Diária", kind = "POSTING")))
            }
            val state = started(source)

            state.showStatement()
            runCurrent()

            val lines = assertNotNull(state.statement).days.single().lines
            assertEquals(listOf("Outro (LOYALTY)", "Lançamento"), lines.map { it.kind })
            assertEquals("R$ 20,00", lines.first().amount)
        }

    @Test
    fun ownerShowsTheTypeLabelNeverTheId() =
        runTest {
            val labels =
                mapOf(
                    FolioOwnerDto.Type.RESERVATION to "Reserva",
                    FolioOwnerDto.Type.GROUP to "Grupo",
                    FolioOwnerDto.Type.EVENT to "Evento",
                    FolioOwnerDto.Type.COMPANY to "Empresa",
                    FolioOwnerDto.Type.WALK_IN to "Avulsa",
                )
            labels.forEach { (type, label) ->
                val source = FakeFolioSource()
                source.folio = { folioOf(ownerType = type) }

                val view = assertNotNull(started(source).view)

                assertEquals(label, view.owner)
                assertFalse(view.toString().contains(OWNER_ID.toString()))
            }
        }

    @Test
    fun closedFolioSaysSo() =
        runTest {
            val source = FakeFolioSource()
            source.folio = { folioOf(status = FolioResponse.Status.CLOSED) }

            val view = assertNotNull(started(source).view)

            assertTrue(view.closed)
            assertEquals("Conta encerrada", view.status)
        }

    @Test
    fun loadFailureShowsErrorAndRetryReloads() =
        runTest {
            val source = FakeFolioSource()
            source.folio = { throw ApiError.Network(RuntimeException("sem rede")) }
            val state = started(source)

            assertNotNull(state.error)
            assertNull(state.view)

            source.folio = { folioOf() }
            state.reload()
            runCurrent()

            assertNull(state.error)
            assertNotNull(state.view)
            assertEquals(2, source.folioCalls.size)
        }

    @Test
    fun lateAnswerAfterCloseIsDropped() =
        runTest {
            val source = FakeFolioSource()
            val late = LateAnswer<FolioResponse>()
            source.folio = { late.await() }
            val state = started(source)
            assertTrue(late.asked)

            state.close()
            late.answer(folioOf())
            runCurrent()

            assertNull(state.view)
            assertTrue(state.postings.isEmpty())
            assertTrue(source.postingCalls.isEmpty())
        }
}
