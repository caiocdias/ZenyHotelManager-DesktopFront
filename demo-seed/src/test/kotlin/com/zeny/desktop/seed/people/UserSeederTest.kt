package com.zeny.desktop.seed.people

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserSeederTest {
    private val specs = DemoCatalog.build(LocalDate.of(2026, 11, 12)).users
    private val passwords =
        mapOf(
            DemoRole.MANAGER to Secret("Senha-Gerente-1"),
            DemoRole.RECEPTIONIST to Secret("Senha-Recepcao-2"),
            DemoRole.HOUSEKEEPER to Secret("Senha-Governanta-3"),
        )
    private val pin = Secret("482913")
    private val iam = FakeIam()
    private val report = SeedReport()

    private suspend fun seed() = UserSeeder(iam, iam, report).seed(specs, passwords, pin)

    private fun assertAllWork() {
        specs.forEach {
            val user = assertNotNull(iam.users[it.login])
            assertEquals(passwords.getValue(it.role).value, user.password)
            assertFalse(user.mustChange)
        }
    }

    @Test
    fun freshServerCreatesThreeUsersAndPin() =
        runTest {
            seed()
            assertEquals(3, iam.users.size)
            assertAllWork()
            assertEquals("482913", iam.users.getValue("gerente").pin)
            assertNull(iam.users.getValue("recepcao").pin)
            assertEquals(setOf("role-m"), iam.users.getValue("gerente").roleIds)
            assertEquals(3, report.createdCount(SeedItem.USER))
            assertTrue(iam.sessionsOpened.all { it.closed && it.signedOut })
        }

    @Test
    fun secondRunCreatesNothingAndResetsNoPassword() =
        runTest {
            seed()
            iam.users.getValue("gerente").pin = null
            val created = iam.createCalls
            val resets = iam.resetPasswordCalls
            val second = SeedReport()
            UserSeeder(iam, iam, second).seed(specs, passwords, pin)
            assertEquals(created, iam.createCalls)
            assertEquals(resets, iam.resetPasswordCalls)
            assertEquals(3, second.keptCount(SeedItem.USER))
            assertEquals("482913", iam.users.getValue("gerente").pin)
            assertEquals(0, iam.totalFailures)
        }

    @Test
    fun existingUserWithOtherPasswordIsReset() =
        runTest {
            iam.plant("recepcao", "Senha-Antiga-9", roleIds = setOf("role-r"))
            seed()
            assertAllWork()
            assertEquals(1, iam.resetPasswordCalls)
            assertTrue(iam.totalFailures <= 1)
        }

    @Test
    fun lockedUserIsResetAndRecovered() =
        runTest {
            iam.plant("governanta", "Senha-Antiga-9", roleIds = setOf("role-h")).locked = true
            seed()
            assertAllWork()
            assertFalse(iam.users.getValue("governanta").locked)
        }

    @Test
    fun interruptedUserStillOnProvisionalIsCompleted() =
        runTest {
            iam.plant("gerente", "provisoria-desconhecida", mustChange = true, roleIds = setOf("role-m"))
            seed()
            assertAllWork()
            assertEquals(1, iam.resetPasswordCalls)
        }

    @Test
    fun inactiveUserReactivated() =
        runTest {
            val known = passwords.getValue(DemoRole.RECEPTIONIST).value
            iam.plant("recepcao", known, active = false, roleIds = setOf("role-r"))
            seed()
            assertTrue(iam.users.getValue("recepcao").active)
            assertEquals(0, iam.resetPasswordCalls)
        }

    @Test
    fun missingRoleAdded() =
        runTest {
            iam.plant("recepcao", passwords.getValue(DemoRole.RECEPTIONIST).value, roleIds = setOf("outra"))
            seed()
            assertEquals(setOf("outra", "role-r"), iam.users.getValue("recepcao").roleIds)
        }

    @Test
    fun missingTemplateFails() =
        runTest {
            iam.roles.remove("RECEPTIONIST")
            val e = assertFailsWith<SeedFailure> { seed() }
            assertTrue(e.message!!.contains("RECEPTIONIST"))
        }

    @Test
    fun sessionsClosedOnFailure() =
        runTest {
            iam.plant("gerente", "x", roleIds = setOf("role-m"))
            iam.failOnSignIn = true
            assertFailsWith<IllegalStateException> { seed() }
            assertTrue(iam.sessionsOpened.isNotEmpty())
            assertTrue(iam.sessionsOpened.all { it.closed })
        }

    @Test
    fun provisionalPasswordsAreStrongAndDistinct() {
        val all = List(100) { ProvisionalPasswords.next().value }
        assertEquals(100, all.toSet().size)
        all.forEach { p ->
            assertEquals(24, p.length)
            assertTrue(p.any { it.isUpperCase() })
            assertTrue(p.any { it.isLowerCase() })
            assertTrue(p.any { it.isDigit() })
            assertTrue(p.any { !it.isLetterOrDigit() })
        }
    }

    @Test
    fun noSecretInFailuresOrReport() =
        runTest {
            iam.plant("gerente", "Senha-Antiga-9", mustChange = true, roleIds = setOf("role-m"))
            seed()
            val planted = passwords.values.map { it.value } + pin.value + "Senha-Antiga-9"
            val texts = report.lines().toMutableList()
            iam.roles.remove("MANAGER")
            val other = FakeIam(iam.roles)
            other.plant("recepcao", "x")
            val failure =
                assertFailsWith<SeedFailure> { UserSeeder(other, other, SeedReport()).seed(specs, passwords, pin) }
            texts += failure.message!!
            texts.forEach { text -> planted.forEach { assertFalse(text.contains(it)) } }
        }
}
