package com.zeny.desktop.seed.people

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.DemoRole
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class UserSeederRoleTest {
    private val specs = DemoCatalog.build(LocalDate.of(2026, 11, 12)).users
    private val passwords =
        mapOf(
            DemoRole.MANAGER to Secret("Senha-Gerente-1"),
            DemoRole.RECEPTIONIST to Secret("Senha-Recepcao-2"),
            DemoRole.HOUSEKEEPER to Secret("Senha-Governanta-3"),
        )
    private val iam = FakeIam()
    private val report = SeedReport()

    private suspend fun seed() = UserSeeder(iam, iam, report).seed(specs, passwords, Secret("482913"))

    private fun singleRole() = iam.customRoles.values.single()

    @Test
    fun createsRoleAndGivesItToReceptionist() =
        runTest {
            seed()
            val role = singleRole()
            assertEquals("Consulta de setores (demo)", role.name)
            assertEquals("Leitura de setores para os recados da recepção (pedido PF-01).", role.description)
            assertEquals(mapOf("staff.employees.read" to "ALL"), role.grants)
            assertEquals(setOf("role-r", role.id), iam.users.getValue("recepcao").roleIds)
        }

    @Test
    fun keepsExistingRole() =
        runTest {
            seed()
            val role = singleRole()
            seed()
            assertEquals(1, iam.customRoles.size)
            assertEquals(1, role.version)
            assertEquals(1, report.createdCount(SeedItem.ROLE))
            assertEquals(1, report.keptCount(SeedItem.ROLE))
        }

    @Test
    fun existingReceptionistGetsRole() =
        runTest {
            iam.plant("recepcao", passwords.getValue(DemoRole.RECEPTIONIST).value, roleIds = setOf("role-r", "outra"))
            seed()
            val id = singleRole().id
            assertEquals(setOf("role-r", "outra", id), iam.users.getValue("recepcao").roleIds)
        }

    @Test
    fun otherUsersDoNotGetRole() =
        runTest {
            seed()
            val id = singleRole().id
            assertEquals(setOf("role-m"), iam.users.getValue("gerente").roleIds)
            assertEquals(setOf("role-h"), iam.users.getValue("governanta").roleIds)
            assertFalse(id in iam.users.getValue("gerente").roleIds)
        }

    @Test
    fun reportCountsRole() =
        runTest {
            seed()
            assertEquals(1, report.createdCount(SeedItem.ROLE))
            assertEquals(0, report.keptCount(SeedItem.ROLE))
            assertEquals(1, iam.ensureRoleCalls)
        }

    @Test
    fun ensureRoleFailureIsSeedFailure() =
        runTest {
            iam.ensureRoleError = ApiError.Problem(500, "SEGREDO_DO_SERVIDOR", "corpo do servidor", emptyList())
            val e = assertFailsWith<SeedFailure> { seed() }
            assertEquals("Não foi possível garantir o perfil de consulta de setores da recepção.", e.message)
            assertEquals(0, iam.users.size)
        }
}
