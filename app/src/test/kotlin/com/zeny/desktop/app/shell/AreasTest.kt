package com.zeny.desktop.app.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AreasTest {
    // Fixtures lidas dos `templates = mapOf(RoleTemplate.X to ...)` dos modelos de role do servidor
    // (somente leitura):
    // /home/caio/repos/ZenyHotelManager/src/main/kotlin/com/zeny/hotel/<modulo>/<Modulo>Permissions.kt
    // (frontdesk, reservations, billing, guests, rooms, iam, hotel, rates). Só entram as permissões que
    // o menu consulta ou que poderiam confundi-lo (as de leitura vizinhas); o Administrador recebe todas.
    private val everyMenuPermission = Area.entries.flatMap { it.permissions }.toSet()

    private val administrator =
        everyMenuPermission +
            setOf("frontdesk.traces.read", "hotel.settings.read", "rates.plans.read", "rooms.registry.read")

    private val manager =
        setOf(
            "frontdesk.stays.checkin",
            "frontdesk.stays.checkout",
            "frontdesk.traces.read",
            "reservations.reservations.read",
            "billing.folios.read",
            "billing.cashier.operate",
            "guests.profiles.read",
            "rooms.status.read",
            "rooms.registry.read",
            "iam.users.manage",
            "iam.roles.manage",
            "rates.plans.manage",
            "hotel.settings.read",
        )

    private val receptionist =
        setOf(
            "frontdesk.stays.checkin",
            "frontdesk.stays.checkout",
            "frontdesk.traces.read",
            "reservations.reservations.read",
            "billing.folios.read",
            "billing.cashier.operate",
            "guests.profiles.read",
            "rooms.status.read",
            "rooms.registry.read",
            "hotel.settings.read",
            "rates.plans.read",
        )

    private val housekeeper =
        setOf(
            "frontdesk.traces.read",
            "rooms.status.read",
            "rooms.registry.read",
        )

    private fun ids(result: List<Pair<Area, Boolean>>) = result.map { it.first }

    @Test
    fun adminSeesEveryArea() {
        val result = visibleAreas(administrator, Area.entries.toSet(), devMode = false)

        assertEquals(Area.entries, ids(result))
        assertTrue(result.all { it.second })
    }

    @Test
    fun receptionistSeesFrontOfficeNotManagement() {
        val seen = ids(visibleAreas(receptionist, Area.entries.toSet(), devMode = false))

        assertEquals(
            listOf(Area.RECEPTION, Area.RESERVATIONS, Area.ACCOUNTS, Area.GUESTS, Area.HOUSEKEEPING),
            seen,
        )
        assertFalse(Area.MANAGEMENT in seen)
    }

    @Test
    fun managerSeesManagement() {
        assertTrue(Area.MANAGEMENT in ids(visibleAreas(manager, Area.entries.toSet(), devMode = false)))
    }

    @Test
    fun housekeeperDoesNotSeeReception() {
        val seen = ids(visibleAreas(housekeeper, Area.entries.toSet(), devMode = false))

        assertEquals(listOf(Area.HOUSEKEEPING), seen)
    }

    @Test
    fun unknownPermissionsAreIgnored() {
        val result = visibleAreas(setOf("nao.existe.nada", "frontdesk.traces.read"), Area.entries.toSet(), false)

        assertEquals(emptyList(), result)
    }

    @Test
    fun notImplementedHiddenOutsideDevMode() {
        val implemented = setOf(Area.RESERVATIONS)

        assertEquals(
            listOf(Area.RESERVATIONS to true),
            visibleAreas(administrator, implemented, devMode = false),
        )
        val dev = visibleAreas(administrator, implemented, devMode = true)
        assertEquals(Area.entries, ids(dev))
        assertEquals(Area.entries.map { it in implemented }, dev.map { it.second })
    }

    @Test
    fun devModeStillHidesAreasWithoutPermission() {
        assertEquals(
            listOf(Area.HOUSEKEEPING to false),
            visibleAreas(housekeeper, emptySet(), devMode = true),
        )
    }

    @Test
    fun orderFollowsEnum() {
        val shuffled = administrator.toList().shuffled(kotlin.random.Random(7)).toSet()

        assertEquals(Area.entries, ids(visibleAreas(shuffled, Area.entries.toSet(), devMode = true)))
    }

    @Test
    fun noAreaIsImplementedYet() {
        assertTrue(IMPLEMENTED_AREAS.isEmpty())
    }
}
