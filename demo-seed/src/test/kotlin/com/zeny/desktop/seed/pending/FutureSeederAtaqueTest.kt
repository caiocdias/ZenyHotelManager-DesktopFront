package com.zeny.desktop.seed.pending

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.config.SeededConfig
import com.zeny.desktop.seed.data.DemoCatalog
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val EXTERNAL_IDS = listOf("DEMO-FUT-F1", "DEMO-FUT-F2", "DEMO-FUT-C1")
private val STATUSES = listOf("PROVISIONAL", "CONFIRMED", "IN_HOUSE", "CHECKED_OUT", "CANCELLED", "NO_SHOW", "OUTRA")

/**
 * Ataque ao `FutureSeeder` (M3 E05, testador de etapa): a execução interrompida entre criar e cancelar a `C1`, a
 * retomada depois de uma criação recusada, cada situação achada, a recusa de configuração antes de qualquer escrita
 * e as mensagens sem dado de hóspede.
 */
class FutureSeederAtaqueTest {
    private val date = LocalDate.of(2026, 11, 16)
    private val data = DemoCatalog.build(date)
    private val holder = data.guests.first { it.key == "G12" }
    private val config =
        SeededConfig(
            roomTypeIds = mapOf("STD" to "rt-std", "LUX" to "rt-lux", "SUI" to "rt-sui"),
            roomIds = data.rooms.associate { it.number to "room-${it.number}" },
            ratePlanId = "plan-balcao",
            activeRoomsByType = emptyMap(),
            outOfOrderRoomsByType = emptyMap(),
        )
    private val store = FakeFutureGateway().apply { guests[holder.cpf] = "guest-12" }

    /** Delega ao falso e recusa as escritas pedidas; grava as leituras do hóspede e das reservas. */
    private class Flaky(
        private val inner: FakeFutureGateway,
        private val failCreateOf: String? = null,
        private val cancelFailure: Throwable? = null,
    ) : FutureGateway by inner {
        val guestLookups = mutableListOf<String>()
        val finds = mutableListOf<String>()

        override suspend fun guestId(cpf: String): String? {
            guestLookups += cpf
            return inner.guestId(cpf)
        }

        override suspend fun findReservation(externalId: String): PendingReservationRef? {
            finds += externalId
            return inner.findReservation(externalId)
        }

        override suspend fun createReservation(spec: PendingReservationSpec): PendingReservationRef {
            if (spec.externalId == failCreateOf) {
                throw ApiError.Problem(409, "ROOM_TYPE_UNAVAILABLE", "sem inventário", emptyList())
            }
            return inner.createReservation(spec)
        }

        override suspend fun cancelReservation(
            reservationId: String,
            reason: String,
        ) {
            cancelFailure?.let { throw it }
            inner.cancelReservation(reservationId, reason)
        }
    }

    private suspend fun seed(
        gateway: FutureGateway,
        config: SeededConfig = this.config,
        on: LocalDate = date,
    ) = FutureSeeder(gateway).seed(data, config, on)

    @Test
    fun cancelFailureLeavesC1ConfirmedAndTheNextRunNeverCancelsIt() =
        runTest {
            val refusal = ApiError.Problem(503, "SERVICE_UNAVAILABLE", null, emptyList())

            val thrown = assertFailsWith<ApiError.Problem> { seed(Flaky(store, cancelFailure = refusal)) }

            assertSame(refusal, thrown, "a recusa do cancelamento sobe como veio, sem embrulho")
            assertEquals(EXTERNAL_IDS, store.reservationSpecs.map { it.externalId })
            assertEquals("CONFIRMED", store.reservations.getValue("DEMO-FUT-C1").status)
            store.calls.clear()

            val result = seed(store)

            assertEquals(FutureSeedResult(created = 0, existing = 3), result)
            assertTrue(store.calls.isEmpty(), "a C1 achada não é cancelada de novo: ${store.calls}")
            assertEquals("CONFIRMED", store.reservations.getValue("DEMO-FUT-C1").status)
            assertTrue(store.cancellations.isEmpty())
        }

    @Test
    fun createRefusalMidwayResumesOnlyTheMissingOnTheNextRun() =
        runTest {
            assertFailsWith<ApiError.Problem> { seed(Flaky(store, failCreateOf = "DEMO-FUT-F2")) }

            assertEquals(listOf("createReservation DEMO-FUT-F1"), store.calls, "nada depois da recusa da F2")
            assertTrue(store.cancellations.isEmpty())
            store.calls.clear()

            val result = seed(store)

            val c1 = store.reservations.getValue("DEMO-FUT-C1").id
            assertEquals(FutureSeedResult(created = 2, existing = 1), result)
            assertEquals(
                listOf("createReservation DEMO-FUT-F2", "createReservation DEMO-FUT-C1", "cancelReservation $c1"),
                store.calls,
            )
            assertEquals(listOf(c1 to "GUEST_REQUEST"), store.cancellations)
            assertEquals("CONFIRMED", store.reservations.getValue("DEMO-FUT-F1").status)
        }

    @Test
    fun c1CreationRefusedThenTheNextRunCreatesAndCancelsItOnce() =
        runTest {
            assertFailsWith<ApiError.Problem> { seed(Flaky(store, failCreateOf = "DEMO-FUT-C1")) }
            assertTrue(store.cancellations.isEmpty(), "sem C1 criada, nada é cancelado")

            val result = seed(store)

            assertEquals(FutureSeedResult(created = 1, existing = 2), result)
            assertEquals(listOf(store.reservations.getValue("DEMO-FUT-C1").id to "GUEST_REQUEST"), store.cancellations)
            assertEquals("CANCELLED", store.reservations.getValue("DEMO-FUT-C1").status)

            seed(store)

            assertEquals(1, store.cancellations.size, "a terceira execução não cancela de novo")
        }

    @Test
    fun everyFoundSituationOfEachReservationIsLeftUntouched() =
        runTest {
            for (found in EXTERNAL_IDS) {
                for (status in STATUSES) {
                    val gateway = FakeFutureGateway().apply { guests[holder.cpf] = "guest-12" }
                    gateway.reservations[found] = PendingReservationRef("res-achada", status, "room-101")

                    val result = seed(gateway)

                    val label = "$found achada como $status"
                    assertEquals(FutureSeedResult(created = 2, existing = 1), result, label)
                    assertEquals(PendingReservationRef("res-achada", status, "room-101"), gateway.reservations[found])
                    assertTrue(gateway.calls.none { it.endsWith(found) || it.endsWith("res-achada") }, label)
                    assertEquals(EXTERNAL_IDS - found, gateway.reservationSpecs.map { it.externalId }, label)
                    val cancelled = gateway.cancellations.map { it.first }
                    val createdC1 = gateway.reservations.getValue("DEMO-FUT-C1").id
                    val expected = if (found == "DEMO-FUT-C1") emptyList() else listOf(createdC1)
                    assertEquals(expected, cancelled, "só a C1 criada agora é cancelada ($label)")
                }
            }
        }

    @Test
    fun missingRoomTypeFailsBeforeAnyWriteAndWithoutGuestData() =
        runTest {
            for (missing in listOf("STD", "LUX")) {
                val gateway = Flaky(store)
                val partial = config.copy(roomTypeIds = config.roomTypeIds - missing)

                val failure = assertFailsWith<SeedFailure> { seed(gateway, partial) }

                assertTrue(store.calls.isEmpty(), "sem o tipo $missing, nenhuma reserva é criada: ${store.calls}")
                assertTrue(gateway.finds.isEmpty(), "a recusa vem antes de qualquer busca de reserva")
                assertClean(failure.message.orEmpty())
            }
        }

    @Test
    fun missingHolderFailsBeforeAnyReservationReadAndWithoutGuestData() =
        runTest {
            store.guests.clear()
            val gateway = Flaky(store)

            val failure = assertFailsWith<SeedFailure> { seed(gateway) }

            assertEquals(listOf(holder.cpf), gateway.guestLookups, "o titular é procurado pelo CPF do G12, uma vez")
            assertTrue(gateway.finds.isEmpty())
            assertTrue(store.calls.isEmpty())
            assertClean(failure.message.orEmpty())
        }

    @Test
    fun theHolderIsTheG12AndEveryReservationGoesToHim() =
        runTest {
            store.guests[data.guests.first { it.key == "G1" }.cpf] = "guest-1"
            val gateway = Flaky(store)

            seed(gateway)

            assertEquals(listOf(holder.cpf), gateway.guestLookups)
            assertEquals(EXTERNAL_IDS, gateway.finds, "cada reserva é conferida pelo externalId, na ordem")
            assertTrue(store.reservationSpecs.all { it.holderGuestId == "guest-12" })
        }

    @Test
    fun datesFollowTheBusinessDateAcrossMonthAndYear() =
        runTest {
            val newYearEve = LocalDate.of(2026, 12, 20)

            seed(store, on = newYearEve)

            val byId = store.reservationSpecs.associateBy { it.externalId }
            assertEquals(LocalDate.of(2027, 1, 3) to LocalDate.of(2027, 1, 6), byId.getValue("DEMO-FUT-F1").period())
            assertEquals(LocalDate.of(2027, 1, 10) to LocalDate.of(2027, 1, 12), byId.getValue("DEMO-FUT-F2").period())
            assertEquals(LocalDate.of(2027, 1, 17) to LocalDate.of(2027, 1, 19), byId.getValue("DEMO-FUT-C1").period())
        }

    private fun PendingReservationSpec.period() = arrival to departure

    private fun assertClean(message: String) {
        assertTrue(message.isNotBlank(), "a falha diz o que faltou")
        listOf(holder.cpf, holder.fullName, "Doze", "Demonstração", "guest-12").forEach {
            assertFalse(it in message, "a mensagem não traz [$it]: $message")
        }
        val formatted = holder.cpf.replace(Regex("(\\d{3})(\\d{3})(\\d{3})(\\d{2})"), "$1.$2.$3-$4")
        assertFalse(formatted in message, message)
    }
}
