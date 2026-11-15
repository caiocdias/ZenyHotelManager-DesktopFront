package com.zeny.desktop.seed.people

import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.DemoRole
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import com.zeny.desktop.seed.data.UserSpec
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ataque à concessão da role de setores: quem recebe, quando, quantas vezes e como falha. */
class UserSeederRoleAtaqueTest {
    private val baseSpecs = DemoCatalog.build(LocalDate.of(2026, 11, 12)).users
    private val passwords =
        mapOf(
            DemoRole.MANAGER to Secret("Senha-Gerente-1"),
            DemoRole.RECEPTIONIST to Secret("Senha-Recepcao-2"),
            DemoRole.HOUSEKEEPER to Secret("Senha-Governanta-3"),
        )
    private val iam = FakeIam()
    private val report = SeedReport()
    private val events = mutableListOf<String>()
    private var setRolesCalls = 0

    /** Delega tudo ao FakeIam e só registra a ordem das chamadas relevantes. */
    private val spy =
        object : UserGateway by iam {
            override suspend fun ensureRole(
                name: String,
                description: String,
                grants: Map<String, String>,
            ): RoleEnsured {
                events += "ensureRole"
                return iam.ensureRole(name, description, grants)
            }

            override suspend fun createUser(
                login: String,
                name: String,
                provisional: Secret,
                roleIds: List<String>,
                pin: Secret?,
            ): String {
                events += "createUser:$login"
                return iam.createUser(login, name, provisional, roleIds, pin)
            }

            override suspend fun setRoles(
                userId: String,
                roleIds: List<String>,
            ) {
                setRolesCalls++
                events += "setRoles"
                iam.setRoles(userId, roleIds)
            }
        }

    private suspend fun seed(specs: List<UserSpec> = baseSpecs) {
        val seeder = UserSeeder(spy, iam, report)
        seeder.seed(specs, passwords, Secret("482913"))
    }

    private fun roleId(): String {
        val role = iam.customRoles.values.single()
        return role.id
    }

    @Test
    fun roleIsEnsuredExactlyOnceBeforeAnyUserEvenWithSeveralReceptionists() =
        runTest {
            val extra = UserSpec(DemoRole.RECEPTIONIST, "recepcao2", "Segunda Recepção")
            seed(baseSpecs + extra)
            assertEquals(1, iam.ensureRoleCalls)
            assertEquals("ensureRole", events.first())
            assertEquals(setOf("role-r", roleId()), iam.users.getValue("recepcao").roleIds)
            assertEquals(setOf("role-r", roleId()), iam.users.getValue("recepcao2").roleIds)
            assertEquals(1, report.createdCount(SeedItem.ROLE))
            assertEquals(0, report.keptCount(SeedItem.ROLE))
        }

    @Test
    fun newReceptionistIsCreatedWithExactlyTemplateAndDemoRoleInOneCall() =
        runTest {
            seed()
            val creates = events.filter { it.startsWith("createUser") }
            assertEquals(3, creates.size)
            assertEquals(0, setRolesCalls)
            assertEquals(setOf("role-r", roleId()), iam.users.getValue("recepcao").roleIds)
        }

    @Test
    fun managerAndHousekeeperGetOnlyTheirTemplateRoleAcrossReruns() =
        runTest {
            seed()
            seed()
            seed()
            assertEquals(setOf("role-m"), iam.users.getValue("gerente").roleIds)
            assertEquals(setOf("role-h"), iam.users.getValue("governanta").roleIds)
        }

    @Test
    fun existingManagerAndHousekeeperAreNotGivenTheRoleAndSetRolesIsNotCalledForThem() =
        runTest {
            iam.plant("gerente", passwords.getValue(DemoRole.MANAGER).value, roleIds = setOf("role-m"))
            iam.plant("governanta", passwords.getValue(DemoRole.HOUSEKEEPER).value, roleIds = setOf("role-h"))
            seed(baseSpecs.filter { it.role != DemoRole.RECEPTIONIST })
            assertEquals(0, setRolesCalls)
            assertEquals(setOf("role-m"), iam.users.getValue("gerente").roleIds)
            assertEquals(setOf("role-h"), iam.users.getValue("governanta").roleIds)
        }

    @Test
    fun existingReceptionistGainsRoleExactlyOnceAndSecondRunDoesNotCallSetRoles() =
        runTest {
            iam.plant("recepcao", passwords.getValue(DemoRole.RECEPTIONIST).value, roleIds = setOf("role-r", "outra"))
            seed()
            val id = roleId()
            assertEquals(1, setRolesCalls)
            assertEquals(setOf("role-r", "outra", id), iam.users.getValue("recepcao").roleIds)
            seed()
            assertEquals(1, setRolesCalls)
            assertEquals(setOf("role-r", "outra", id), iam.users.getValue("recepcao").roleIds)
        }

    @Test
    fun receptionistWithDemoRoleButWithoutTemplateGetsTemplateBackAndKeepsDemoRole() =
        runTest {
            val demo = iam.ensureRole("Consulta de setores (demo)", "x", mapOf("staff.employees.read" to "ALL")).id
            iam.plant("recepcao", passwords.getValue(DemoRole.RECEPTIONIST).value, roleIds = setOf(demo))
            seed()
            assertEquals(setOf("role-r", demo), iam.users.getValue("recepcao").roleIds)
            assertEquals(1, iam.customRoles.size)
        }

    @Test
    fun reactivatedReceptionistAlsoEndsWithDemoRole() =
        runTest {
            val pass = passwords.getValue(DemoRole.RECEPTIONIST).value
            val user = iam.plant("recepcao", pass, active = false, roleIds = setOf("role-r"))
            seed()
            assertTrue(user.active)
            assertEquals(setOf("role-r", roleId()), user.roleIds)
        }

    @Test
    fun managerThatAlreadyHasDemoRoleFromEarlierRunIsNotStrippedOrDuplicated() =
        runTest {
            val demo = iam.ensureRole("Consulta de setores (demo)", "x", mapOf("staff.employees.read" to "ALL")).id
            iam.plant("gerente", passwords.getValue(DemoRole.MANAGER).value, roleIds = setOf("role-m", demo))
            seed()
            // o brief só manda conceder à recepção; o seed nunca revoga nem regrava quem já tem o template
            assertEquals(setOf("role-m", demo), iam.users.getValue("gerente").roleIds)
        }

    @Test
    fun ensureRoleFailureStopsBeforeAnyUserIsCreatedOrChangedAndLeaksNothing() =
        runTest {
            val pass = passwords.getValue(DemoRole.RECEPTIONIST).value
            val existing = iam.plant("recepcao", pass, active = false, roleIds = setOf("role-r"))
            iam.ensureRoleError = ApiError.Problem(500, "SEGREDO_DO_SERVIDOR", "corpo do servidor", emptyList())
            val e = assertFailsWith<SeedFailure> { seed() }
            assertEquals("Não foi possível garantir o perfil de consulta de setores da recepção.", e.message)
            assertFalse(e.toString().contains("SEGREDO_DO_SERVIDOR"))
            assertFalse(e.toString().contains("corpo do servidor"))
            assertEquals(null, e.cause)
            assertEquals(0, iam.createCalls)
            assertEquals(0, setRolesCalls)
            assertEquals(1, iam.users.size)
            assertFalse(existing.active)
            assertEquals(setOf("role-r"), existing.roleIds)
            assertEquals(0, report.createdCount(SeedItem.USER) + report.keptCount(SeedItem.USER))
            assertEquals(0, report.createdCount(SeedItem.ROLE) + report.keptCount(SeedItem.ROLE))
            assertEquals(0, iam.sessionsOpened.size)
        }

    @Test
    fun reportCountsRoleCreatedThenKeptAndRendersPerfisBeforeUsuarios() =
        runTest {
            seed()
            assertEquals(1, report.createdCount(SeedItem.ROLE))
            assertEquals(0, report.keptCount(SeedItem.ROLE))
            seed()
            assertEquals(1, report.createdCount(SeedItem.ROLE))
            assertEquals(1, report.keptCount(SeedItem.ROLE))
            val lines = report.lines()
            val perfis = lines.indexOfFirst { it.startsWith("perfis:") }
            val usuarios = lines.indexOfFirst { it.startsWith("usuários:") }
            assertTrue(perfis in 0 until usuarios, lines.toString())
            assertEquals("perfis: 1 criados, 1 já existiam", lines[perfis])
            assertTrue(report.toString().contains("perfis: 1 criados, 1 já existiam; usuários:"), report.toString())
        }

    @Test
    fun rerunWithRoleDeactivatedOnServerStillKeepsAndReusesSameRoleId() =
        runTest {
            seed()
            val role = iam.customRoles.values.single()
            role.active = false
            seed()
            assertEquals(1, iam.customRoles.size)
            assertTrue(role.active)
            assertEquals(setOf("role-r", role.id), iam.users.getValue("recepcao").roleIds)
        }
}
