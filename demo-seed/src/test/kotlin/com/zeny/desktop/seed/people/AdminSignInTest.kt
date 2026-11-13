package com.zeny.desktop.seed.people

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.cli.SeedConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminSignInTest {
    private fun config(newPassword: String? = "Nova-Admin-1") =
        SeedConfig(
            server = ServerUrl.parse("http://localhost:8080")!!,
            adminLogin = "admin",
            adminPassword = Secret("Inicial-1"),
            adminNewPassword = newPassword?.let { Secret(it) },
            managerPassword = Secret("m"),
            receptionistPassword = Secret("r"),
            housekeeperPassword = Secret("h"),
            managerPin = Secret("1234"),
        )

    @Test
    fun adminFreshChangesToNewPassword() =
        runTest {
            val iam = FakeIam()
            val admin = iam.plant("admin", "Inicial-1", mustChange = true)
            val session = AdminSignIn(iam).open(config())
            assertEquals("Nova-Admin-1", admin.password)
            assertFalse(admin.mustChange)
            assertFalse(session.closed)
        }

    @Test
    fun adminRerunUsesNewPasswordWithoutFailure() =
        runTest {
            val iam = FakeIam()
            iam.plant("admin", "Nova-Admin-1")
            AdminSignIn(iam).open(config())
            assertEquals(0, iam.totalFailures)
        }

    @Test
    fun adminMustChangeWithoutNewPasswordFails() =
        runTest {
            val iam = FakeIam()
            iam.plant("admin", "Inicial-1", mustChange = true)
            val e = assertFailsWith<SeedFailure> { AdminSignIn(iam).open(config(null)) }
            assertEquals(
                "O administrador precisa trocar a senha: informe --admin-new-password " +
                    "(ou ZENY_SEED_ADMIN_NEW_PASSWORD).",
                e.message,
            )
            assertTrue(iam.sessionsOpened.all { it.closed })
        }

    @Test
    fun adminBothRejectedFails() =
        runTest {
            val iam = FakeIam()
            iam.plant("admin", "Outra-Senha-9")
            val e = assertFailsWith<SeedFailure> { AdminSignIn(iam).open(config()) }
            assertEquals("Login ou senha do administrador recusados.", e.message)
            assertTrue(iam.sessionsOpened.all { it.closed })
        }

    @Test
    fun adminLockedFails() =
        runTest {
            val iam = FakeIam()
            iam.plant("admin", "Outra-Senha-9").locked = true
            val e = assertFailsWith<SeedFailure> { AdminSignIn(iam).open(config()) }
            assertEquals("A conta do administrador está bloqueada por tentativas; espere e tente de novo.", e.message)
            assertTrue(iam.sessionsOpened.all { it.closed })
        }
}
