package com.zeny.desktop.seed.config

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.data.AreaSpec
import com.zeny.desktop.seed.data.CancellationPolicySpec
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.GuaranteePolicySpec
import com.zeny.desktop.seed.data.HotelSpec
import com.zeny.desktop.seed.data.HousekeepingTarget
import com.zeny.desktop.seed.data.RatePlanSpec
import com.zeny.desktop.seed.data.RoomSpec
import com.zeny.desktop.seed.data.RoomTypeSpec
import com.zeny.desktop.seed.data.SeasonSpec
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class InjectedFailure : RuntimeException("falha injetada")

/**
 * Decorador do falso: conta TODAS as escritas (inclusive preços), valida o que o servidor validaria
 * (transições de limpeza, referências, UH ocupada) e pode falhar na n-ésima escrita, antes ou depois de aplicá-la.
 */
@Suppress("TooManyFunctions", "LongParameterList") // porta que espelha as operações do contrato usadas pelo seed
private class StrictGateway(
    private val inner: FakeConfigGateway,
    private val failAt: Int = Int.MAX_VALUE,
    private val failAfterApply: Boolean = false,
) : ConfigGateway by inner {
    var writes = 0
    val kinds = mutableListOf<String>()
    val outOfOrderWindows = mutableListOf<Pair<LocalDate, LocalDate>>()

    private suspend fun <T> write(
        kind: String,
        block: suspend () -> T,
    ): T {
        writes++
        kinds += kind
        if (writes == failAt && !failAfterApply) throw InjectedFailure()
        val result = block()
        if (writes == failAt) throw InjectedFailure()
        return result
    }

    override suspend fun configureHotel(spec: HotelSpec) = write("hotel") { inner.configureHotel(spec) }

    override suspend fun createGuaranteePolicy(spec: GuaranteePolicySpec) =
        write("policy") { inner.createGuaranteePolicy(spec) }

    override suspend fun createCancellationPolicy(spec: CancellationPolicySpec) =
        write("policy") { inner.createCancellationPolicy(spec) }

    override suspend fun createRoomType(spec: RoomTypeSpec) = write("type") { inner.createRoomType(spec) }

    override suspend fun createArea(spec: AreaSpec) = write("area") { inner.createArea(spec) }

    override suspend fun createRoom(
        spec: RoomSpec,
        roomTypeId: String,
    ): String {
        assertTrue(roomTypeId in inner.roomTypes.values, "tipo inexistente $roomTypeId")
        return write("room") { inner.createRoom(spec, roomTypeId) }
    }

    override suspend fun changeHousekeeping(
        roomId: String,
        transition: HousekeepingTransition,
    ) {
        val room = inner.roomStates.values.first { it.id == roomId }
        assertTrue(!room.occupied, "transição em UH ocupada ${room.number}")
        val dirty = room.housekeeping == "DIRTY"
        val pending = room.housekeeping == "CLEAN" && room.inspectionPending
        val clean = room.housekeeping == "CLEAN"
        val valid =
            when (transition) {
                HousekeepingTransition.MARK_CLEAN, HousekeepingTransition.MARK_CLEAN_PENDING_INSPECTION -> dirty
                HousekeepingTransition.MARK_INSPECTED -> clean
                HousekeepingTransition.REJECT_INSPECTION -> pending
                HousekeepingTransition.MARK_DIRTY -> room.housekeeping != "DIRTY"
            }
        val shown = "${room.number} (${room.housekeeping}/${room.inspectionPending})"
        assertTrue(valid, "409 transição $transition inválida em $shown")
        write("status") { inner.changeHousekeeping(roomId, transition) }
    }

    override suspend fun openOutOfOrder(
        roomId: String,
        from: LocalDate,
        to: LocalDate,
    ) {
        val room = inner.roomStates.values.first { it.id == roomId }
        assertTrue(!room.outOfOrder, "409 OOO já aberto em ${room.number}")
        outOfOrderWindows += from to to
        write("ooo") { inner.openOutOfOrder(roomId, from, to) }
    }

    override suspend fun createSeason(spec: SeasonSpec) = write("season") { inner.createSeason(spec) }

    override suspend fun createRatePlan(
        spec: RatePlanSpec,
        roomTypeIds: List<String>,
        guaranteePolicyId: String,
        cancellationPolicyId: String,
    ): String {
        assertTrue(roomTypeIds.all { it in inner.roomTypes.values }, "tipo inexistente no plano")
        assertTrue(guaranteePolicyId in inner.guaranteePolicies.values, "garantia inexistente")
        assertTrue(cancellationPolicyId in inner.cancellationPolicies.values, "cancelamento inexistente")
        return write("plan") { inner.createRatePlan(spec, roomTypeIds, guaranteePolicyId, cancellationPolicyId) }
    }

    override suspend fun updatePrices(
        ratePlanId: String,
        roomTypeId: String,
        seasonId: String,
        oneAdult: BigDecimal,
        twoAdults: BigDecimal,
        extraAdult: BigDecimal,
    ) {
        assertTrue(ratePlanId in inner.ratePlans.values, "plano inexistente")
        assertTrue(roomTypeId in inner.roomTypes.values, "tipo inexistente")
        assertTrue(seasonId in inner.seasons.values, "temporada inexistente")
        write("price") { inner.updatePrices(ratePlanId, roomTypeId, seasonId, oneAdult, twoAdults, extraAdult) }
    }
}

class ConfigSeederAtaqueTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val data = DemoCatalog.build(date)
    private val fake = FakeConfigGateway()

    private fun level(room: RoomState) =
        when {
            room.housekeeping == "CLEAN" && room.inspectionPending -> HousekeepingTarget.CLEAN_PENDING_INSPECTION
            room.housekeeping == "CLEAN" -> HousekeepingTarget.CLEAN
            room.housekeeping == "INSPECTED" -> HousekeepingTarget.INSPECTED
            else -> HousekeepingTarget.DIRTY
        }

    private fun assertFinalState(f: FakeConfigGateway) {
        assertEquals(DemoCatalog.HOTEL_TRADE_NAME, f.hotelName)
        assertEquals(2, f.guaranteePolicies.size + f.cancellationPolicies.size)
        assertEquals(3, f.roomTypes.size)
        assertEquals(3, f.areas.size)
        assertEquals(20, f.roomStates.size)
        assertEquals(2, f.seasons.size)
        assertEquals(1, f.ratePlans.size)
        assertEquals(
            6,
            f.priceWrites
                .map { it.drop(1) }
                .toSet()
                .size,
        )
        assertEquals(listOf("210"), f.outOfOrderOpened)
        data.rooms.forEach { assertEquals(it.target, level(f.roomStates.getValue(it.number)), it.number) }
    }

    @Test
    fun secondRunWritesNothingExceptIdenticalPriceRows() =
        runTest {
            ConfigSeeder(StrictGateway(fake), SeedReport()).seed(data, date)
            val firstPrices = fake.priceWrites.toList()
            val second = StrictGateway(fake)
            ConfigSeeder(second, SeedReport()).seed(data, date)
            assertEquals(List(data.prices.size) { "price" }, second.kinds)
            assertEquals(firstPrices, fake.priceWrites.drop(firstPrices.size))
        }

    @Test
    fun thirdRunStillWritesOnlyPrices() =
        runTest {
            repeat(2) { ConfigSeeder(StrictGateway(fake), SeedReport()).seed(data, date) }
            val third = StrictGateway(fake)
            ConfigSeeder(third, SeedReport()).seed(data, date)
            assertEquals(setOf("price"), third.kinds.toSet())
        }

    @Test
    fun firstRunReportCounts() =
        runTest {
            val report = SeedReport()
            ConfigSeeder(StrictGateway(fake), report).seed(data, date)
            val expected =
                mapOf(
                    SeedItem.HOTEL to 1,
                    SeedItem.POLICY to 2,
                    SeedItem.ROOM_TYPE to 3,
                    SeedItem.AREA to 3,
                    SeedItem.ROOM to 20,
                    SeedItem.OUT_OF_ORDER to 1,
                    SeedItem.SEASON to 2,
                    SeedItem.RATE_PLAN to 1,
                    SeedItem.PRICES to 6,
                    SeedItem.ROOM_STATUS to 16,
                )
            expected.forEach { (item, n) -> assertEquals(n, report.createdCount(item), "created ${item.name}") }
            val cadastro = expected.keys - SeedItem.ROOM_STATUS
            cadastro.forEach { assertEquals(0, report.keptCount(it), "kept ${it.name}") }
            assertEquals(4, report.keptCount(SeedItem.ROOM_STATUS))
            assertEquals(0, report.createdCount(SeedItem.USER) + report.keptCount(SeedItem.RESERVATION))
        }

    @Test
    fun secondRunReportKeepsEveryConfigItem() =
        runTest {
            ConfigSeeder(StrictGateway(fake), SeedReport()).seed(data, date)
            val report = SeedReport()
            ConfigSeeder(StrictGateway(fake), report).seed(data, date)
            assertEquals(3, report.keptCount(SeedItem.AREA))
            assertEquals(2, report.keptCount(SeedItem.SEASON))
            assertEquals(1, report.keptCount(SeedItem.RATE_PLAN))
            assertEquals(20, report.keptCount(SeedItem.ROOM_STATUS))
            assertEquals(1, report.keptCount(SeedItem.OUT_OF_ORDER))
            SeedItem.entries.forEach { assertEquals(0, report.createdCount(it), it.name) }
        }

    @Test
    fun resumesAfterFailureAtEveryWriteBeforeApply() =
        runTest {
            assertResumesAtEveryWrite(failAfterApply = false)
        }

    @Test
    fun resumesAfterFailureAtEveryWriteAfterApply() =
        runTest {
            assertResumesAtEveryWrite(failAfterApply = true)
        }

    private suspend fun assertResumesAtEveryWrite(failAfterApply: Boolean) {
        val probe = StrictGateway(FakeConfigGateway())
        ConfigSeeder(probe, SeedReport()).seed(data, date)
        val total = probe.writes
        assertTrue(total > 50, "esperava dezenas de escritas, foram $total")
        for (n in 1..total) {
            val f = FakeConfigGateway()
            assertFailsWith<InjectedFailure>("falha em $n") {
                ConfigSeeder(StrictGateway(f, n, failAfterApply), SeedReport()).seed(data, date)
            }
            val report = SeedReport()
            val seeded = ConfigSeeder(StrictGateway(f), report).seed(data, date)
            assertFinalState(f)
            assertEquals(20, seeded.roomIds.size, "falha em $n")
            assertEquals(1, f.ratePlans.size)
            assertEquals(seeded.ratePlanId, f.ratePlans.values.single())
        }
    }

    @Test
    fun foreignHotelWithDifferentCaseOrSpacesStopsBeforeAnyWrite() =
        runTest {
            val variants =
                listOf(
                    DemoCatalog.HOTEL_TRADE_NAME.uppercase(),
                    " ${DemoCatalog.HOTEL_TRADE_NAME}",
                    "${DemoCatalog.HOTEL_TRADE_NAME} ",
                    "",
                )
            variants.forEach { name ->
                val f = FakeConfigGateway().also { it.hotelName = name }
                val gateway = StrictGateway(f)
                assertFailsWith<SeedFailure>("nome '$name'") { ConfigSeeder(gateway, SeedReport()).seed(data, date) }
                assertEquals(0, gateway.writes, "nome '$name'")
                assertEquals(0, f.writeCalls, "nome '$name'")
                assertEquals(0, f.priceWrites.size, "nome '$name'")
            }
        }

    @Test
    fun foreignHotelStopsBeforeWritesEvenWithPartialCatalogPresent() =
        runTest {
            ConfigSeeder(StrictGateway(fake), SeedReport()).seed(data, date)
            fake.hotelName = "Outro Hotel"
            fake.roomStates.remove("101")
            fake.ratePlans.clear()
            val gateway = StrictGateway(fake)
            val prices = fake.priceWrites.size
            assertFailsWith<SeedFailure> { ConfigSeeder(gateway, SeedReport()).seed(data, date) }
            assertEquals(0, gateway.writes)
            assertEquals(prices, fake.priceWrites.size)
        }

    @Test
    fun occupiedRoomsNeverGetTransitionWhateverTheirStatus() =
        runTest {
            ConfigSeeder(StrictGateway(fake), SeedReport()).seed(data, date)
            val statuses = listOf("DIRTY" to false, "CLEAN" to false, "CLEAN" to true, "INSPECTED" to false)
            fake.roomStates.keys.toList().forEachIndexed { i, number ->
                val (hk, pending) = statuses[i % statuses.size]
                val room = fake.roomStates.getValue(number)
                fake.roomStates[number] = room.copy(occupied = true, housekeeping = hk, inspectionPending = pending)
            }
            val before = fake.roomStates.toMap()
            val gateway = StrictGateway(fake)
            ConfigSeeder(gateway, SeedReport()).seed(data, date)
            assertEquals(before, fake.roomStates.toMap())
            assertTrue("status" !in gateway.kinds)
        }

    @Test
    fun unknownServerStatusNeverGetsTransition() =
        runTest {
            ConfigSeeder(StrictGateway(fake), SeedReport()).seed(data, date)
            listOf("MAINTENANCE", "clean", "").forEachIndexed { i, hk ->
                val number = data.rooms[i].number
                val room = fake.roomStates.getValue(number)
                fake.roomStates[number] = room.copy(housekeeping = hk, inspectionPending = false)
            }
            val gateway = StrictGateway(fake)
            val report = SeedReport()
            ConfigSeeder(gateway, report).seed(data, date)
            assertTrue("status" !in gateway.kinds)
            listOf("MAINTENANCE", "clean", "").forEachIndexed { i, hk ->
                assertEquals(hk, fake.roomStates.getValue(data.rooms[i].number).housekeeping)
            }
            assertEquals(20, report.keptCount(SeedItem.ROOM_STATUS))
        }

    @Test
    fun vacantRoomsWithDriftedStatusAreBroughtBackToTarget() =
        runTest {
            ConfigSeeder(StrictGateway(fake), SeedReport()).seed(data, date)
            val drift = listOf("DIRTY" to false, "CLEAN" to false, "CLEAN" to true, "INSPECTED" to false)
            data.rooms.forEachIndexed { i, spec ->
                val (hk, pending) = drift[i % drift.size]
                fake.roomStates[spec.number] =
                    fake.roomStates.getValue(spec.number).copy(housekeeping = hk, inspectionPending = pending)
            }
            ConfigSeeder(StrictGateway(fake), SeedReport()).seed(data, date)
            data.rooms.forEach { assertEquals(it.target, level(fake.roomStates.getValue(it.number)), it.number) }
        }

    @Test
    fun existingOutOfOrderRoomIsNotReopenedAndKeepsItsOwnStatus() =
        runTest {
            val typeId = "rt-ext"
            fake.roomTypes["SUI"] = typeId
            fake.roomStates["210"] = RoomState("room-ext", "210", typeId, false, "DIRTY", false, outOfOrder = true)
            val gateway = StrictGateway(fake)
            val report = SeedReport()
            ConfigSeeder(gateway, report).seed(data, date)
            assertTrue("ooo" !in gateway.kinds)
            assertEquals(1, report.keptCount(SeedItem.OUT_OF_ORDER))
            assertEquals(0, report.createdCount(SeedItem.OUT_OF_ORDER))
        }

    @Test
    fun outOfOrderWindowIsBusinessDateThroughPlusTwo() =
        runTest {
            val gateway = StrictGateway(fake)
            ConfigSeeder(gateway, SeedReport()).seed(data, date)
            assertEquals(listOf(date to date.plusDays(2)), gateway.outOfOrderWindows)
        }

    @Test
    fun existingRoomTypeWithLowercaseCodeIsReusedNotRecreated() =
        runTest {
            fake.roomTypes["std"] = "rt-ext"
            val gateway = StrictGateway(fake)
            val report = SeedReport()
            val seeded = ConfigSeeder(gateway, report).seed(data, date)
            assertEquals(2, report.createdCount(SeedItem.ROOM_TYPE))
            assertEquals(1, report.keptCount(SeedItem.ROOM_TYPE))
            assertEquals(3, fake.roomTypes.size)
            assertEquals("rt-ext", seeded.roomTypeIds["STD"])
            assertEquals(
                12,
                fake.roomStates.values.count { it.roomTypeId == "rt-ext" },
            )
            assertEquals(12, seeded.activeRoomsByType["STD"])
        }

    @Test
    fun existingAreaWithDifferentCaseIsNotRecreated() =
        runTest {
            fake.areas += "recepção"
            fake.areas += "PISCINA"
            val report = SeedReport()
            ConfigSeeder(StrictGateway(fake), report).seed(data, date)
            assertEquals(3, fake.areas.size)
            assertEquals(2, report.keptCount(SeedItem.AREA))
            assertEquals(1, report.createdCount(SeedItem.AREA))
        }

    @Test
    fun existingSeasonWithDifferentCaseIsReusedForPrices() =
        runTest {
            val name = data.seasons.first().name
            fake.seasons[name.uppercase()] = "se-ext"
            val report = SeedReport()
            ConfigSeeder(StrictGateway(fake), report).seed(data, date)
            assertEquals(2, fake.seasons.size)
            assertEquals(1, report.keptCount(SeedItem.SEASON))
            assertTrue(fake.priceWrites.any { it[2] == "se-ext" })
        }

    @Test
    fun resumesWhenPlanExistsButPricesNeverWritten() =
        runTest {
            ConfigSeeder(StrictGateway(fake), SeedReport()).seed(data, date)
            fake.priceWrites.clear()
            val gateway = StrictGateway(fake)
            ConfigSeeder(gateway, SeedReport()).seed(data, date)
            assertEquals(data.prices.size, fake.priceWrites.size)
            assertEquals(data.prices.size, gateway.writes)
        }
}
