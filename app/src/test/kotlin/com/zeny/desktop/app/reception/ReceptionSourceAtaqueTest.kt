@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.zeny.desktop.app.reception

import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.realtime.RealtimeEvent
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.GuestDocumentResponse.Type
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Ataques à função pura do resumo do titular e ao falso compartilhado das Tasks 6 a 9. */
class ReceptionSourceAtaqueTest {
    private val date = BusinessDate.parse("2026-11-13")

    @Test
    fun summaryTreatsExplicitNullSocialNameAsAbsent() {
        val guest = guestOf("Maria da Silva").copy(socialName = Option.Some(null))

        assertEquals("Maria da Silva", guest.toHolderSummary().name)
    }

    @Test
    fun summaryTreatsEmptySocialNameAsAbsent() {
        assertEquals("Maria da Silva", guestOf("Maria da Silva", socialName = "").toHolderSummary().name)
    }

    @Test
    fun summaryTreatsTabAndNewlineSocialNameAsBlank() {
        assertEquals("Maria da Silva", guestOf("Maria da Silva", socialName = " \t\n ").toHolderSummary().name)
    }

    @Test
    fun summaryKeepsSocialNameThatEqualsFullName() {
        assertEquals("Maria", guestOf("Maria", socialName = "Maria").toHolderSummary().name)
    }

    @Test
    fun summaryDocumentOnlyFromFirstEvenWhenFirstIsNotCpf() {
        val guest =
            guestOf(
                "Maria da Silva",
                documents = listOf(Type.PASSPORT to "XX***99", Type.CPF to "***.***.***-34", Type.RG to "**.789-0"),
            )
        val document = guest.toHolderSummary().document

        assertEquals("Passaporte XX***99", document)
        assertFalse("CPF" in document.orEmpty() || "RG" in document.orEmpty())
    }

    @Test
    fun summaryLabelsEveryDocumentTypeOfTheContract() {
        val expected =
            mapOf(
                Type.CPF to "CPF",
                Type.RG to "RG",
                Type.CNH to "CNH",
                Type.RNM to "RNM",
                Type.PASSPORT to "Passaporte",
                Type.FOREIGN_ID to "Documento estrangeiro",
            )
        Type.entries.forEach { type ->
            val summary = guestOf("Maria", documents = listOf(type to "***1")).toHolderSummary()
            assertEquals("${expected.getValue(type)} ***1", summary.document, "tipo $type")
        }
    }

    @Test
    fun summaryKeepsMaskedNumberVerbatim() {
        val summary = guestOf("Maria", documents = listOf(Type.CPF to "")).toHolderSummary()

        assertEquals("CPF ", summary.document)
    }

    @Test
    fun summaryToStringHidesDataInEveryRenderingPath() {
        val summary = HolderSummary("Maria da Silva", "CPF ***.***.***-34")
        val renderings =
            listOf(
                summary.toString(),
                "$summary",
                listOf(summary).toString(),
                mapOf("k" to summary).toString(),
                guestOf("Maria da Silva", documents = listOf(Type.CPF to "***.***.***-34"))
                    .toHolderSummary()
                    .toString(),
                HolderSummary("Maria da Silva", null).toString(),
            )

        renderings.forEach { text ->
            assertFalse("Maria" in text, text)
            assertFalse("CPF" in text, text)
            assertFalse("***.***" in text, text)
        }
    }

    @Test
    fun summaryEqualityStillSeesTheFieldsDespiteMaskedToString() {
        assertNotEquals(HolderSummary("Maria", null), HolderSummary("Joana", null))
        assertNotEquals(HolderSummary("Maria", null), HolderSummary("Maria", "CPF ***"))
        assertEquals(HolderSummary("Maria", "CPF ***"), HolderSummary("Maria", "CPF ***"))
    }

    @Test
    fun fakeRecordsDayPageCallsInOrderWithListDateAndCursor() =
        runTest {
            val fake = FakeReceptionSource()
            val second = Page<ReservationListItemResponse>(emptyList(), "c2")
            fake.pages[DayList.ARRIVALS to "c1"] = second

            assertEquals(Page(emptyList(), null), fake.dayPage(DayList.ARRIVALS, date, null, 50))
            assertEquals(second, fake.dayPage(DayList.ARRIVALS, date, "c1", 50))
            fake.dayPage(DayList.DEPARTURES, date, "c9", 10)

            assertEquals(
                listOf(
                    DayPageCall(DayList.ARRIVALS, date, null),
                    DayPageCall(DayList.ARRIVALS, date, "c1"),
                    DayPageCall(DayList.DEPARTURES, date, "c9"),
                ),
                fake.dayPageCalls,
            )
        }

    @Test
    fun fakeHeldDayPageReturnsOnlyAfterRelease() =
        runTest {
            val fake = FakeReceptionSource()
            val page = Page<ReservationListItemResponse>(emptyList(), "x")
            fake.pages[DayList.ARRIVALS to null] = page
            fake.holdDayPage = true

            val call = async { fake.dayPage(DayList.ARRIVALS, date, null, 50) }
            advanceUntilIdle()
            assertFalse(call.isCompleted)
            assertEquals(1, fake.dayPageCalls.size)

            fake.heldDayPages.single().complete(Unit)
            advanceUntilIdle()
            assertEquals(page, call.await())
        }

    @Test
    fun fakeHeldDayPageAnswersWithSnapshotTakenAtCallTime() =
        runTest {
            val fake = FakeReceptionSource()
            val before = Page<ReservationListItemResponse>(emptyList(), "antes")
            fake.pages[DayList.ARRIVALS to null] = before
            fake.holdDayPage = true

            val call = async { fake.dayPage(DayList.ARRIVALS, date, null, 50) }
            advanceUntilIdle()
            fake.pages[DayList.ARRIVALS to null] = Page(emptyList(), "depois")
            fake.heldDayPages.single().complete(Unit)

            assertEquals(before, call.await())
        }

    @Test
    fun fakeHeldDayPagesReleaseOutOfOrder() =
        runTest {
            val fake = FakeReceptionSource()
            fake.pages[DayList.ARRIVALS to null] = Page(emptyList(), "a")
            fake.pages[DayList.DEPARTURES to null] = Page(emptyList(), "d")
            fake.holdDayPage = true

            val first = async { fake.dayPage(DayList.ARRIVALS, date, null, 50) }
            val second = async { fake.dayPage(DayList.DEPARTURES, date, null, 50) }
            advanceUntilIdle()
            fake.heldDayPages[1].complete(Unit)
            advanceUntilIdle()

            assertTrue(second.isCompleted)
            assertFalse(first.isCompleted)
            assertEquals("d", second.await().nextCursor)
            fake.heldDayPages[0].complete(Unit)
            assertEquals("a", first.await().nextCursor)
        }

    @Test
    fun fakeHeldDayPageThrowsConfiguredErrorOnlyAfterRelease() =
        runTest {
            val fake = FakeReceptionSource()
            fake.holdDayPage = true
            fake.dayPageError = IllegalStateException("falha fictícia")

            val call = async { runCatching { fake.dayPage(DayList.ARRIVALS, date, null, 50) } }
            advanceUntilIdle()
            assertFalse(call.isCompleted)
            fake.heldDayPages.single().complete(Unit)

            assertEquals("falha fictícia", call.await().exceptionOrNull()?.message)
        }

    @Test
    fun fakeHolderAndReservationHoldsRecordIdsAndRelease() =
        runTest {
            val fake = FakeReceptionSource()
            fake.holders["g1"] = HolderSummary("Maria", null)
            fake.holdHolder = true
            fake.holdReservation = true

            val holder = async { fake.holder("g1") }
            val reservation = async { runCatching { fake.reservation("r1") } }
            advanceUntilIdle()

            assertEquals(listOf("g1"), fake.holderCalls)
            assertEquals(listOf("r1"), fake.reservationCalls)
            assertEquals("g1", fake.heldHolders.single().first)
            assertEquals("r1", fake.heldReservations.single().first)
            assertFalse(holder.isCompleted)
            assertFalse(reservation.isCompleted)

            val (_, holderGate) = fake.heldHolders.single()
            val (_, reservationGate) = fake.heldReservations.single()
            holderGate.complete(Unit)
            reservationGate.complete(Unit)
            advanceUntilIdle()
            assertEquals("Maria", holder.await().name)
            // Sem reserva configurada o falso falha alto, em vez de inventar uma resposta.
            assertTrue(reservation.await().exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun fakeUnknownHolderAndPlanFailLoudly() =
        runTest {
            val fake = FakeReceptionSource()

            assertFailsWith<IllegalStateException> { fake.holder("x") }
            assertFailsWith<IllegalStateException> { fake.ratePlanName("p") }
            assertEquals(listOf("x"), fake.holderCalls)
            assertEquals(listOf("p"), fake.ratePlanCalls)
        }

    @Test
    fun fakeConfiguredErrorsAreThrownAndStillCounted() =
        runTest {
            val fake = FakeReceptionSource()
            fake.permissionsError = IllegalStateException("a")
            fake.businessDayError = IllegalStateException("b")
            fake.roomTypesError = IllegalStateException("c")

            assertFailsWith<IllegalStateException> { fake.permissions() }
            assertFailsWith<IllegalStateException> { fake.businessDay() }
            assertFailsWith<IllegalStateException> { fake.roomTypes() }
            assertEquals(listOf(1, 1, 1), listOf(fake.permissionsCalls, fake.businessDayCalls, fake.roomTypesCalls))
        }

    @Test
    fun fakeRoomTypesReturnsACopyNotTheLiveList() =
        runTest {
            val fake = FakeReceptionSource()
            val first = fake.roomTypes()

            assertEquals(emptyList(), first)
            assertFalse(first === fake.types)
        }

    @Test
    fun fakeEventsRecordsEachSubscriptionAndItsTopicsSeparately() =
        runTest {
            val fake = FakeReceptionSource()
            val a = launch(start = CoroutineStart.UNDISPATCHED) { fake.events(setOf("t1", "t2")).collect { } }
            val b = launch(start = CoroutineStart.UNDISPATCHED) { fake.events(setOf("t3")).collect { } }

            assertEquals(2, fake.subscriptions)
            assertEquals(2, fake.activeSubscriptions)
            assertEquals(listOf(setOf("t1", "t2"), setOf("t3")), fake.subscribedTopics)
            a.cancel()
            b.cancel()
        }

    @Test
    fun fakeEventsCancellationIsCountedAndReleasesActiveSubscription() =
        runTest {
            val fake = FakeReceptionSource()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { fake.events(setOf("t")).collect { } }
            assertEquals(1, fake.activeSubscriptions)

            job.cancel()
            advanceUntilIdle()

            assertEquals(0, fake.activeSubscriptions)
            assertEquals(1, fake.cancelledSubscriptions)
        }

    @Test
    fun fakeEventsDeliversEmittedEventsInOrderIncludingBeforeSubscription() =
        runTest {
            val fake = FakeReceptionSource()
            val message = RealtimeEvent.Message("t", "reservation.updated", "1", 7L)
            fake.emit(RealtimeEvent.Ready)
            val received = async { fake.events(setOf("t")).toList() }
            fake.emit(message)
            fake.emit(RealtimeEvent.Reset)
            advanceUntilIdle()
            fake.end()

            assertEquals(listOf(RealtimeEvent.Ready, message, RealtimeEvent.Reset), received.await())
            assertEquals(0, fake.activeSubscriptions)
            assertEquals(0, fake.cancelledSubscriptions)
        }

    @Test
    fun fakeEndWithErrorFailsTheFlowAndNextSubscriptionIsFresh() =
        runTest {
            val fake = FakeReceptionSource()
            val failing = async { runCatching { fake.events(setOf("t")).toList() } }
            advanceUntilIdle()
            fake.end(IllegalStateException("queda fictícia"))
            advanceUntilIdle()

            assertEquals("queda fictícia", failing.await().exceptionOrNull()?.message)
            assertEquals(0, fake.activeSubscriptions)

            val retry = async { fake.events(setOf("t")).toList() }
            advanceUntilIdle()
            assertEquals(2, fake.subscriptions)
            assertEquals(1, fake.activeSubscriptions)
            fake.emit(RealtimeEvent.Ready)
            fake.end()
            assertEquals(listOf(RealtimeEvent.Ready), retry.await())
        }

    @Test
    fun fakeEventsDoesNotSubscribeUntilCollected() {
        val fake = FakeReceptionSource()
        val flow = fake.events(setOf("t"))

        assertTrue(flow is Flow<*>)
        assertEquals(0, fake.subscriptions)
        assertEquals(emptyList(), fake.subscribedTopics)
    }
}
