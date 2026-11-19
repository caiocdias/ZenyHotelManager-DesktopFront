package com.zeny.desktop.app.reception

import com.zeny.desktop.app.rooms.roomType
import com.zeny.desktop.app.rooms.typeId
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.sdk.model.ReservationListItemResponse
import com.zeny.desktop.sdk.model.RoomTypeResponse
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

private fun listItem(holder: Uuid = Uuid.random()) =
    ReservationListItemResponse(
        adults = 2,
        arrival = "2026-11-13",
        arrivalOverdue = false,
        bookingCode = "B-1",
        bookingId = Uuid.random(),
        channel = "DIRECT",
        children = 1,
        code = "R-1",
        dayUse = false,
        departure = "2026-11-15",
        departureOverdue = false,
        folioId = Uuid.random(),
        holderGuestId = holder,
        id = Uuid.random(),
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

class ReservationRowResolverAtaqueTest {
    private val holderReads = mutableListOf<String>()
    private var typeReads = 0
    private var holderFailure: (String) -> Throwable? = { null }

    private fun resolver(readTypes: suspend () -> List<RoomTypeResponse>) =
        ReservationRowResolver(
            readHolder = { id ->
                holderReads += id
                holderFailure(id)?.let { throw it }
                HolderSummary("Titular", null)
            },
            readRoomTypes = {
                typeReads++
                readTypes()
            },
        )

    private fun problem(status: Int) = ApiError.Problem(status, "X", null, emptyList())

    private fun standard() = listOf(roomType(typeId(1), "Standard"))

    @Test
    fun holderForbiddenOrMissingGivesDashAndOtherFailuresRise() =
        runTest {
            val forbidden = Uuid.random()
            val missing = Uuid.random()
            val ok = Uuid.random()
            holderFailure = { id ->
                when (id) {
                    forbidden.toString() -> problem(403)
                    missing.toString() -> problem(404)
                    else -> null
                }
            }
            val rows = resolver { standard() }.rows(listOf(listItem(forbidden), listItem(missing), listItem(ok)))
            assertEquals(listOf("—", "—", "Titular"), rows.map { it.holder })

            holderFailure = { problem(500) }
            assertFailsWith<ApiError.Problem> { resolver { standard() }.rows(listOf(listItem())) }
            holderFailure = { ApiError.Network(IOException("sem rede")) }
            assertFailsWith<ApiError.Network> { resolver { standard() }.rows(listOf(listItem())) }
        }

    @Test
    fun failedPageReleasesTheLockSoTheNextPageStillResolves() =
        runTest {
            val bad = Uuid.random()
            holderFailure = { id -> if (id == bad.toString()) problem(500) else null }
            val resolver = resolver { standard() }

            assertFailsWith<ApiError.Problem> { resolver.rows(listOf(listItem(bad))) }
            assertEquals(listOf("Standard"), resolver.rows(listOf(listItem())).map { it.roomType })
        }

    @Test
    fun networkFailureOnTypesIsNotCachedAndTheRetryReadsAgain() =
        runTest {
            var fail = true
            val resolver =
                resolver {
                    if (fail) throw ApiError.Network(IOException("sem rede"))
                    standard()
                }
            assertFailsWith<ApiError.Network> { resolver.rows(listOf(listItem())) }
            fail = false
            assertEquals(listOf("Standard"), resolver.rows(listOf(listItem())).map { it.roomType })
            assertEquals(2, typeReads)
        }

    @Test
    fun typesProblemDashesAreCachedUntilForgotten() =
        runTest {
            var refuse = true
            val resolver =
                resolver {
                    if (refuse) throw problem(403)
                    standard()
                }
            assertEquals(listOf("—"), resolver.rows(listOf(listItem())).map { it.roomType })
            refuse = false
            assertEquals(listOf("—"), resolver.rows(listOf(listItem())).map { it.roomType })
            assertEquals(1, typeReads)

            resolver.forgetRoomTypes()
            assertEquals(listOf("Standard"), resolver.rows(listOf(listItem())).map { it.roomType })
        }

    @Test
    fun holdersAreKeptAcrossForgetRoomTypes() =
        runTest {
            val holder = Uuid.random()
            val resolver = resolver { standard() }
            resolver.rows(listOf(listItem(holder)))
            resolver.forgetRoomTypes()
            resolver.rows(listOf(listItem(holder)))
            assertEquals(1, holderReads.size)
            assertEquals(2, typeReads)
        }

    @Test
    fun parallelPagesWithSameHoldersReadThemOnce() =
        runTest {
            val shared = List(6) { Uuid.random() }
            val resolver = resolver { standard() }
            List(5) { async { resolver.rows(shared.map { listItem(it) }) } }.awaitAll()
            assertEquals(6, holderReads.size)
            assertEquals(1, typeReads)
        }

    @Test
    fun cancelledPageInsideTheLockReleasesItForTheNextOne() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val resolver =
                resolver {
                    gate.await()
                    standard()
                }
            val first = launch(start = CoroutineStart.UNDISPATCHED) { resolver.rows(listOf(listItem())) }
            first.cancelAndJoin()
            gate.complete(Unit)
            assertEquals(listOf("Standard"), resolver.rows(listOf(listItem())).map { it.roomType })
        }

    // Documenta o comportamento atual (já era assim antes da refatoração, não é regressão): a página da carga
    // anterior suspensa em `readRoomTypes` quando o painel chama `forgetRoomTypes()` regrava o cache com a leitura
    // antiga ao voltar, e a carga nova não relê os tipos. Se isso for corrigido, este teste deve ser invertido.
    @Test
    fun typesReadInFlightWhenTheLoadChangesStayCachedForTheNewLoadToday() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var generation = 0
            val resolver =
                resolver {
                    val mine = ++generation
                    if (mine == 1) gate.await()
                    listOf(roomType(typeId(1), if (mine == 1) "Antigo" else "Novo"))
                }
            val old = async { resolver.rows(listOf(listItem())) }
            testScheduler.runCurrent()
            resolver.forgetRoomTypes()
            gate.complete(Unit)
            old.await()

            assertEquals(listOf("Antigo"), resolver.rows(listOf(listItem())).map { it.roomType })
            assertEquals(1, typeReads)
        }

    @Test
    fun parallelPagesInFlightWhileTypesAreHeldReadTheTypesOnce() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val resolver =
                resolver {
                    gate.await()
                    standard()
                }
            val pages = List(3) { async { resolver.rows(listOf(listItem())) } }
            testScheduler.runCurrent()
            assertEquals(1, typeReads, "só a primeira página chega à leitura; as outras esperam a trava")

            gate.complete(Unit)
            val rows = pages.awaitAll()
            assertEquals(1, typeReads)
            assertEquals(List(3) { listOf("Standard") }, rows.map { page -> page.map { it.roomType } })
        }
}
