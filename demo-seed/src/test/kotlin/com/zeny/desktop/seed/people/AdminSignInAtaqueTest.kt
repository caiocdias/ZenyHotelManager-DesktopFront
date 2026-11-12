package com.zeny.desktop.seed.people

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.cli.SeedConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminSignInAtaqueTest {
    private val iam = StrictIam()

    private fun config(newPassword: String? = "Nova-Admin-123") =
        SeedConfig(
            server = ServerUrl.parse("http://localhost:8080")!!,
            adminLogin = "admin",
            adminPassword = Secret("Inicial-Admin-1"),
            adminNewPassword = newPassword?.let { Secret(it) },
            managerPassword = Secret("m"),
            receptionistPassword = Secret("r"),
            housekeeperPassword = Secret("h"),
            managerPin = Secret("1234"),
        )

    private fun assertAllClosed() {
        assertTrue(iam.sessions.isNotEmpty())
        assertTrue(iam.sessions.all { it.closed })
    }

    @Test
    fun freshAdminSpendsOneFailureAndReturnsAnOpenSignedInSession() =
        runTest {
            val admin = iam.plant("admin", "Inicial-Admin-1", mustChange = true)
            val session = AdminSignIn(iam).open(config())
            assertEquals("Nova-Admin-123", admin.password)
            assertEquals(1, iam.sessions.size)
            assertFalse(session.closed)
            assertNotNull(session.user)
            assertTrue((iam.failuresByLogin["admin"] ?: 0) <= 1)
        }

    @Test
    fun adminAlreadyActiveOnInitialPasswordEntersWithOneFailureAtMost() =
        runTest {
            iam.plant("admin", "Inicial-Admin-1")
            val session = AdminSignIn(iam).open(config())
            assertFalse(session.closed)
            assertTrue((iam.failuresByLogin["admin"] ?: 0) <= 1)
        }

    @Test
    fun adminRerunNeverSpendsAFailureEvenAfterTwoRuns() =
        runTest {
            iam.plant("admin", "Inicial-Admin-1", mustChange = true)
            AdminSignIn(iam).open(config())
            AdminSignIn(iam).open(config())
            AdminSignIn(iam).open(config())
            assertEquals(1, iam.failuresByLogin["admin"])
        }

    @Test
    fun lockedOnNewPasswordAttemptStopsWithoutTryingTheInitialOne() =
        runTest {
            iam.plant("admin", "Inicial-Admin-1").locked = true
            assertFailsWith<SeedFailure> { AdminSignIn(iam).open(config()) }
            assertEquals(0, iam.totalFailures)
            assertAllClosed()
        }

    @Test
    fun bothRejectedSpendsAtMostTwoFailuresAndClosesTheSession() =
        runTest {
            iam.plant("admin", "Outra-Senha-9999")
            assertFailsWith<SeedFailure> { AdminSignIn(iam).open(config()) }
            assertTrue((iam.failuresByLogin["admin"] ?: 0) <= 2)
            assertAllClosed()
        }

    @Test
    fun mustChangeWithoutNewPasswordSpendsNoFailureAndClosesTheSession() =
        runTest {
            iam.plant("admin", "Inicial-Admin-1", mustChange = true)
            assertFailsWith<SeedFailure> { AdminSignIn(iam).open(config(null)) }
            assertEquals(0, iam.totalFailures)
            assertAllClosed()
            assertTrue(iam.sessions.all { it.user == null }, "sessão provisória ficou ativa")
        }

    @Test
    fun newPasswordStillProvisionalFailsWithoutBurningAnAttemptOnTheInitialPassword() =
        runTest {
            iam.plant("admin", "Nova-Admin-123", mustChange = true)
            assertFailsWith<SeedFailure> { AdminSignIn(iam).open(config()) }
            assertEquals(0, iam.totalFailures, "tentativa extra com a senha inicial queima o limite de bloqueio")
            assertAllClosed()
        }

    @Test
    fun changePasswordFailureClosesSessionKeepsAdminOnInitialAndHidesSecrets() =
        runTest {
            val admin = iam.plant("admin", "Inicial-Admin-1", mustChange = true)
            iam.changePasswordHook = { error("WEAK_PASSWORD") }
            val e = assertFailsWith<IllegalStateException> { AdminSignIn(iam).open(config()) }
            assertFalse(e.toString().contains("Inicial-Admin-1") || e.toString().contains("Nova-Admin-123"))
            assertEquals("Inicial-Admin-1", admin.password)
            assertTrue(admin.mustChange)
            assertAllClosed()
        }

    @Test
    fun cancellationDuringChangePasswordIsNotSwallowedAndClosesSession() =
        runTest {
            iam.plant("admin", "Inicial-Admin-1", mustChange = true)
            iam.changePasswordHook = { throw CancellationException("cancelado") }
            assertFailsWith<CancellationException> { AdminSignIn(iam).open(config()) }
            assertAllClosed()
        }

    @Test
    fun inactiveAdminPropagatesTheServerErrorAndClosesTheSession() =
        runTest {
            iam.plant("admin", "Nova-Admin-123", active = false)
            val e = assertFailsWith<IllegalStateException> { AdminSignIn(iam).open(config()) }
            assertEquals("ACCOUNT_DISABLED", e.message)
            assertAllClosed()
        }

    @Test
    fun failureMessagesNeverContainThePasswords() =
        runTest {
            iam.plant("admin", "Outra-Senha-9999")
            val e = assertFailsWith<SeedFailure> { AdminSignIn(iam).open(config()) }
            listOf(e.message, e.toString()).forEach {
                assertFalse(it!!.contains("Inicial-Admin-1") || it.contains("Nova-Admin-123"))
            }
            assertEquals(
                "SeedConfig masks",
                if (config().toString().contains("Inicial-Admin-1")) "leak" else "SeedConfig masks",
            )
        }
}
