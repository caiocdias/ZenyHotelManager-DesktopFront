package com.zeny.desktop.app.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ataques à Task 3 (E04). Fixtures lidas dos `templates` dos modelos de role do servidor (somente leitura):
 * /home/caio/repos/ZenyHotelManager/src/main/kotlin/com/zeny/hotel/<modulo>/<Modulo>Permissions.kt
 * (frontdesk, reservations, billing, guests, rooms, iam, hotel, rates). Só entram as permissões que o menu
 * consulta ou que poderiam confundi-lo. Observação do servidor: iam.users.manage, iam.roles.manage e
 * hotel.settings.manage NÃO têm template (só o Administrador as recebe); rates.plans.manage é do Gerente.
 */
class AreasAtaqueTest {
    private val all = Area.entries.toSet()

    // Gerente real: sem iam.*.manage e sem hotel.settings.manage; com rates.plans.manage.
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
            "iam.users.read",
            "iam.roles.read",
            "hotel.settings.read",
            "rates.plans.read",
            "rates.plans.manage",
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
            "hotel.settings.read",
            "rates.plans.read",
            "rates.discounts.apply",
        )

    private val housekeeper =
        setOf("frontdesk.traces.read", "rooms.status.read", "rooms.registry.read", "rooms.status.manage")

    // Financeiro: sem frontdesk.stays.*, sem rooms.status.read.
    private val finance =
        setOf(
            "reservations.reservations.read",
            "reservations.availability.read",
            "billing.folios.read",
            "billing.cashier.operate",
            "billing.shifts.read",
            "guests.profiles.read",
            "guests.companies.read",
            "hotel.settings.read",
            "rates.plans.read",
        )

    // Manutenção: traces.read, rooms.outoforder.read, rooms.outofservice.manage, rooms.offline.read.
    private val maintenance =
        setOf("frontdesk.traces.read", "rooms.outoforder.read", "rooms.outofservice.manage", "rooms.offline.read")

    private fun seen(
        permissions: Set<String>,
        devMode: Boolean = false,
    ) = visibleAreas(permissions, all, devMode).map { it.first }

    @Test
    fun managerSeesEveryAreaThroughRatesPlansManage() {
        assertEquals(Area.entries, seen(manager))
    }

    @Test
    fun managerWithoutRatesPlansManageLosesManagement() {
        assertFalse(Area.MANAGEMENT in seen(manager - "rates.plans.manage"))
    }

    @Test
    fun financeSeesReservationsAccountsGuestsOnly() {
        assertEquals(listOf(Area.RESERVATIONS, Area.ACCOUNTS, Area.GUESTS), seen(finance))
    }

    @Test
    fun maintenanceSeesNoArea() {
        assertEquals(emptyList(), seen(maintenance))
    }

    @Test
    fun housekeeperSeesOnlyHousekeepingEvenWithManagePermissionOnRoomStatus() {
        assertEquals(listOf(Area.HOUSEKEEPING), seen(housekeeper))
    }

    @Test
    fun noNonAdministratorRoleSeesManagementWithoutAManagePermission() {
        val managePermissions = Area.MANAGEMENT.permissions
        listOf(receptionist, housekeeper, finance, maintenance, manager).forEach { role ->
            if (role.none { it in managePermissions }) {
                assertFalse(Area.MANAGEMENT in seen(role, devMode = true), "role $role")
            }
        }
    }

    @Test
    fun readPermissionsNeverOpenManagement() {
        val reads =
            setOf("iam.users.read", "iam.roles.read", "hotel.settings.read", "rates.plans.read", "iam.sessions.manage")

        assertEquals(emptyList(), seen(reads, devMode = true))
    }

    @Test
    fun permissionsWithDifferentCaseDoNotMatch() {
        assertEquals(
            emptyList(),
            seen(setOf("FRONTDESK.STAYS.CHECKIN", "Guests.Profiles.Read", "ROOMS.STATUS.READ")),
        )
    }

    @Test
    fun permissionsWithWhitespaceDoNotMatch() {
        assertEquals(
            emptyList(),
            seen(
                setOf(" frontdesk.stays.checkin", "guests.profiles.read ", "rooms.status.read\n", "iam.users.manage\t"),
            ),
        )
    }

    @Test
    fun emptyPermissionsShowNoAreaEvenInDevMode() {
        assertEquals(emptyList(), visibleAreas(emptySet(), all, devMode = false))
        assertEquals(emptyList(), visibleAreas(emptySet(), all, devMode = true))
    }

    @Test
    fun anySinglePermissionOfAnAreaOpensIt() {
        Area.entries.forEach { area ->
            area.permissions.forEach { permission ->
                assertEquals(listOf(area), seen(setOf(permission)), "permissão $permission")
            }
        }
    }

    @Test
    fun devModeWithAllPermissionsMarksEveryNotImplementedAreaAsComingSoon() {
        val result = visibleAreas(Area.entries.flatMap { it.permissions }.toSet(), emptySet(), devMode = true)

        assertEquals(Area.entries.map { it to false }, result)
    }

    @Test
    fun notImplementedAreasAreHiddenWithoutDevModeEvenWithPermission() {
        val everything = Area.entries.flatMap { it.permissions }.toSet()

        assertEquals(emptyList(), visibleAreas(everything, emptySet(), devMode = false))
    }

    @Test
    fun implementedAreaWithoutPermissionStaysHiddenInBothModes() {
        assertEquals(emptyList(), visibleAreas(emptySet(), setOf(Area.MANAGEMENT), devMode = false))
        assertEquals(emptyList(), visibleAreas(emptySet(), setOf(Area.MANAGEMENT), devMode = true))
    }

    @Test
    fun orderIsStableAcrossCallsAndInputOrder() {
        val permissions = Area.entries.flatMap { it.permissions }
        val first = visibleAreas(permissions.toSet(), all, devMode = true)
        val reversed = visibleAreas(permissions.reversed().toSet(), all, devMode = true)
        val ids = first.map { it.first.id }

        assertEquals(first, reversed)
        assertEquals(listOf("recepcao", "reservas", "contas", "hospedes", "governanca", "gestao"), ids)
    }

    @Test
    fun areaIdsAreUniqueAndLabelsNonBlank() {
        val ids = Area.entries.map { it.id }

        assertEquals(Area.entries.size, ids.toSet().size)
        assertTrue(Area.entries.all { it.label.isNotBlank() && it.permissions.isNotEmpty() })
    }

    @Test
    fun noPermissionBelongsToTwoAreas() {
        val flat = Area.entries.flatMap { it.permissions }

        assertEquals(flat.size, flat.toSet().size)
    }
}
