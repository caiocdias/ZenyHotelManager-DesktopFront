package com.zeny.desktop.seed.people

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.data.DemoCatalog
import com.zeny.desktop.seed.data.DemoRole
import com.zeny.desktop.seed.data.SeedReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UserSeederAtaqueTest {
    private val specs = DemoCatalog.build(LocalDate.of(2026, 11, 12)).users
    private val passwords =
        mapOf(
            DemoRole.MANAGER to Secret("Senha-Gerente-1"),
            DemoRole.RECEPTIONIST to Secret("Senha-Recepcao-2"),
            DemoRole.HOUSEKEEPER to Secret("Senha-Governanta-3"),
        )
    private val pin = Secret("482913")
    private val iam = StrictIam()
    private val report = SeedReport()

    private suspend fun seed() = UserSeeder(iam, iam, report).seed(specs, passwords, pin)

    private fun assertAllWork() {
        specs.forEach {
            val user = iam.users.getValue(it.login)
            assertEquals(passwords.getValue(it.role).value, user.password, it.login)
            assertFalse(user.mustChange, it.login)
            assertTrue(user.active, it.login)
        }
        assertEquals("482913", iam.users.getValue("gerente").pin)
    }

    private fun assertNoOpenSessions() {
        assertTrue(iam.sessions.isNotEmpty())
        assertTrue(iam.sessions.all { it.closed }, "sessão sem close")
        assertTrue(iam.sessions.all { it.user == null }, "sessão ainda ativa no servidor")
    }

    private fun allSecrets() =
        passwords.values.map { it.value } + pin.value + iam.provisionals + iam.users.values.map { it.password }

    private fun assertClean(text: String?) {
        val t = text.orEmpty()
        (passwords.values.map { it.value } + pin.value + iam.provisionals).forEach {
            assertFalse(t.contains(it), "segredo vazou em: $t")
        }
    }

    @Test
    fun freshRunUnderStrictServerHasNoFailuresAndLeavesNoSession() =
        runTest {
            seed()
            assertAllWork()
            assertEquals(0, iam.totalFailures)
            assertNoOpenSessions()
        }

    @Test
    fun secondRunUnderStrictServerHasNoFailuresAndResetsNothing() =
        runTest {
            seed()
            val provisionals = iam.provisionals.size
            seed()
            assertEquals(provisionals, iam.provisionals.size)
            assertEquals(0, iam.totalFailures)
            assertAllWork()
            assertNoOpenSessions()
        }

    @Test
    fun eachUserSpendsAtMostOneFailedAttemptPerRun() =
        runTest {
            iam.plant("gerente", "Senha-Antiga-111", roleIds = setOf("role-m"))
            iam.plant("recepcao", "Senha-Antiga-222", mustChange = true, roleIds = setOf("role-r"))
            iam.plant("governanta", "Senha-Antiga-333", roleIds = setOf("role-h"))
            seed()
            assertAllWork()
            specs.forEach { assertTrue((iam.failuresByLogin[it.login] ?: 0) <= 1, it.login) }
            assertNoOpenSessions()
        }

    @Test
    fun userAlreadyAtTwoFailuresIsStillRecoveredByTheThirdAttempt() =
        runTest {
            iam.plant("gerente", "Senha-Antiga-111", roleIds = setOf("role-m")).failures = 2
            seed()
            assertAllWork()
            assertEquals(1, iam.failuresByLogin["gerente"])
        }

    @Test
    fun lockedUserWithCorrectPasswordIsRecoveredWithoutNewFailures() =
        runTest {
            val u = iam.plant("recepcao", passwords.getValue(DemoRole.RECEPTIONIST).value, roleIds = setOf("role-r"))
            u.locked = true
            seed()
            assertAllWork()
            assertEquals(0, iam.totalFailures)
            assertFalse(u.locked)
        }

    @Test
    fun resumeCreatedButNeverChangedManagerGetsPassword() =
        runTest {
            iam.plant("gerente", "Provisoria-Perdida-1", mustChange = true, roleIds = setOf("role-m"))
            seed()
            assertAllWork()
            assertTrue((iam.failuresByLogin["gerente"] ?: 0) <= 1)
            assertNoOpenSessions()
        }

    @Test
    fun resumeChangedButWithoutPinSetsPinAndKeepsPassword() =
        runTest {
            iam.plant("gerente", passwords.getValue(DemoRole.MANAGER).value, roleIds = setOf("role-m"))
            seed()
            assertAllWork()
            assertEquals(0, iam.totalFailures)
            assertEquals(0, iam.provisionals.count { it.isNotEmpty() } - 2, "só os 2 outros usuários são criados")
        }

    @Test
    fun resumeInactiveUserWithWrongPasswordAndMissingRoleRecoversEverything() =
        runTest {
            iam.plant("recepcao", "Senha-Antiga-222", mustChange = true, active = false, roleIds = setOf("outra"))
            seed()
            assertAllWork()
            assertEquals(setOf("outra", "role-r"), iam.users.getValue("recepcao").roleIds)
            assertTrue((iam.failuresByLogin["recepcao"] ?: 0) <= 1)
            assertNoOpenSessions()
        }

    @Test
    fun missingRoleIsAddedKeepingEveryOtherRole() =
        runTest {
            iam.plant("governanta", passwords.getValue(DemoRole.HOUSEKEEPER).value, roleIds = setOf("a", "b"))
            seed()
            assertEquals(setOf("a", "b", "role-h"), iam.users.getValue("governanta").roleIds)
        }

    @Test
    fun changePasswordFailureClosesSessionsLeavesUserOnProvisionalAndNextRunRecovers() =
        runTest {
            iam.changePasswordHook = { error("SERVER_ERROR") }
            assertFailsWith<IllegalStateException> { seed() }
            assertNoOpenSessions()
            val user = iam.users.getValue("gerente")
            assertTrue(user.mustChange)
            assertTrue(user.active)
            iam.changePasswordHook = {}
            seed()
            assertAllWork()
            assertTrue(iam.failuresByLogin.values.all { it <= 1 })
        }

    @Test
    fun finalSignInNotActiveFailsWithoutSecretsAndClosesSessions() =
        runTest {
            iam.changePasswordHook = { iam.users.getValue("gerente").rejectSignIn = true }
            val e = assertFailsWith<SeedFailure> { seed() }
            assertTrue(e.message!!.contains("gerente"))
            assertClean(e.message)
            assertClean(e.toString())
            assertNoOpenSessions()
        }

    @Test
    fun changePasswordFailureOnExistingUserLeavesPinUntouchedAndNoOpenSession() =
        runTest {
            iam.plant("gerente", "Senha-Antiga-111", roleIds = setOf("role-m"))
            iam.changePasswordHook = { error("SERVER_ERROR") }
            assertFailsWith<IllegalStateException> { seed() }
            assertNoOpenSessions()
            assertNull(iam.users.getValue("gerente").pin)
        }

    @Test
    fun cancellationInsideChangePasswordIsNotSwallowedAndSessionsClose() =
        runTest {
            iam.changePasswordHook = { throw CancellationException("cancelado") }
            assertFailsWith<CancellationException> { seed() }
            assertTrue(iam.sessions.all { it.closed })
        }

    @Test
    fun cancellationInsideSignOutIsNotSwallowedAndStillCloses() =
        runTest {
            iam.signOutHook = { throw CancellationException("cancelado") }
            assertFailsWith<CancellationException> { seed() }
            assertTrue(iam.sessions.isNotEmpty() && iam.sessions.all { it.closed })
        }

    @Test
    fun cancelledSeedStillSignsOutTheOpenSessionOnTheServer() =
        runTest {
            iam.signOutChecksCancellation = true
            iam.changePasswordHook = { awaitCancellation() }
            val job = launch(start = CoroutineStart.UNDISPATCHED) { seed() }
            advanceUntilIdle()
            job.cancel()
            advanceUntilIdle()
            assertTrue(iam.sessions.all { it.closed }, "cliente não fechado")
            assertTrue(iam.sessions.all { it.user == null }, "sessão do servidor ficou aberta após o cancelamento")
        }

    @Test
    fun provisionalPasswordsPassServerLengthRuleAreDistinctAndDifferFromDesired() =
        runTest {
            seed()
            assertEquals(3, iam.provisionals.size)
            assertEquals(3, iam.provisionals.toSet().size)
            iam.provisionals.forEach { assertTrue(it.length in 12..128) }
            assertTrue(iam.provisionals.none { p -> passwords.values.any { it.value == p } })
        }

    @Test
    fun noSecretInAnyFailureReportOrToString() =
        runTest {
            val texts = mutableListOf<String?>()
            iam.changePasswordHook = { iam.users.getValue("gerente").rejectSignIn = true }
            val e = assertFailsWith<SeedFailure> { seed() }
            texts += listOf(e.message, e.toString(), e.cause?.toString())
            iam.roles.remove("HOUSEKEEPER")
            iam.users.clear()
            val e2 = assertFailsWith<SeedFailure> { seed() }
            texts += listOf(e2.message, e2.toString())
            val e3 =
                assertFailsWith<SeedFailure> { UserSeeder(iam, iam, report).seed(specs, emptyMap(), pin) }
            texts += listOf(e3.message, e3.toString())
            texts += report.lines()
            texts += passwords.values.map { it.toString() } + pin.toString()
            val config =
                SeedConfig(
                    ServerUrl.parse("http://localhost:8080")!!,
                    "admin",
                    Secret("Admin-Inicial-9"),
                    Secret("Admin-Nova-9"),
                    passwords.getValue(DemoRole.MANAGER),
                    passwords.getValue(DemoRole.RECEPTIONIST),
                    passwords.getValue(DemoRole.HOUSEKEEPER),
                    pin,
                )
            texts += config.toString()
            texts.forEach { text ->
                (allSecrets() + "Admin-Inicial-9" + "Admin-Nova-9").forEach {
                    assertFalse(text.orEmpty().contains(it), "segredo vazou em: $text")
                }
            }
            assertTrue(UserRef("1", true, emptySet()).toString().isNotEmpty())
        }
}
