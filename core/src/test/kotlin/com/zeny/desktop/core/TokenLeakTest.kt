package com.zeny.desktop.core

import com.zeny.desktop.core.config.PreferencesSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.session.Tokens
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.streams.asSequence
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val SECRET = "SECRET"

/** Critério 6: os tokens (`A-SECRET-n`/`R-SECRET-n`) não chegam ao disco, à saída, a `toString()` nem aos erros. */
@OptIn(RawSdkAccess::class)
class TokenLeakTest {
    private val node: Preferences = Preferences.userRoot().node("zeny-test-${UUID.randomUUID()}")
    private val settings = PreferencesSettings(node)
    private val server = FakeZenyServer(accessPrefix = "A-$SECRET-", refreshPrefix = "R-$SECRET-")
    private val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), settings, server.engine)

    @AfterTest
    fun tearDown() {
        core.close()
        node.removeNode()
        Preferences.userRoot().flush()
    }

    private suspend fun login() = core.session.login("maria.silva", "senha-longa-123")

    private suspend fun me() = core.executor.execute { core.apis.iam.getMe(it.headers) }

    @Test
    fun noTokenReachesDiskOutputToStringOrErrors() =
        runTest(timeout = 5.seconds) {
            val captured = ByteArrayOutputStream()
            val (out, err) = System.out to System.err
            val errors = mutableListOf<Throwable>()
            val states = mutableListOf<SessionState>()
            System.setOut(PrintStream(captured, true))
            System.setErr(PrintStream(captured, true))
            try {
                settings.serverUrl = core.serverUrl.value
                // O corpo do login incompatível traz os tokens: o erro não pode ecoá-lo.
                server.malformedLogin = true
                errors += assertFailsWith<ApiError.IncompatibleResponse> { login() }
                states += login() // A-SECRET-1 / R-SECRET-1
                server.expireAccess()
                assertEquals(200, me().status) // refresh: A-SECRET-2 / R-SECRET-2
                assertEquals(200, me().status)
                server.beforeSecured = { throw IOException("conexão caiu") }
                errors += assertFailsWith<ApiError.Network> { me() }
                server.beforeSecured = {}
                server.rejectAllWith = "ACCOUNT_DISABLED"
                errors += assertFailsWith<ApiError.SessionEnded> { me() }
                states += core.session.state.value
                core.session.logout()
                states += core.session.state.value
            } finally {
                System.setOut(out)
                System.setErr(err)
            }
            node.flush()
            Preferences.userRoot().flush()

            assertEquals(1, server.count("/api/v1/auth/refresh"))
            val (active, ended, loggedOut) = states
            assertIs<SessionState.Active>(active)
            assertIs<SessionState.Ended>(ended)
            assertIs<SessionState.LoggedOut>(loggedOut)
            assertNoSecretOnDisk()
            assertFalse(SECRET in captured.toString(Charsets.UTF_8), "token na saída")
            assertEquals(setOf("serverUrl", "lastLogin", "deviceId"), node.keys().toSet())
            assertFalse(SECRET in Tokens("A-$SECRET-1", "R-$SECRET-1", "s1", "u1", false).toString())
            for (state in states) assertFalse(SECRET in state.toString(), "token em $state")
            for (error in errors) assertNoSecret(error)
        }

    /** A mensagem, a pilha e a cadeia de causas de cada erro. */
    private fun assertNoSecret(error: Throwable) {
        assertFalse(SECRET in error.stackTraceToString(), "token na pilha de ${error::class.simpleName}")
        for (cause in generateSequence(error) { it.cause }) {
            assertFalse(SECRET in cause.message.orEmpty(), "token na mensagem de ${cause::class.qualifiedName}")
            assertFalse(SECRET in cause.toString(), "token no toString de ${cause::class.qualifiedName}")
        }
    }

    /** Os arquivos sob o `userRoot` das preferências e o `tmpdir` dos testes (Task 1), lidos byte a byte. */
    private fun assertNoSecretOnDisk() {
        val roots = listOf("java.util.prefs.userRoot", "java.io.tmpdir").map { Paths.get(System.getProperty(it)) }
        for (root in roots) assertTrue(root.toString().contains("core/build/tmp"), "fora do isolamento: $root")
        val files = roots.flatMap(::regularFiles)
        val texts = files.associateWith { String(Files.readAllBytes(it), Charsets.ISO_8859_1) }
        // A varredura enxerga o nó gravado: senão o teste passaria sem ler nada.
        assertTrue(texts.values.any { "maria.silva" in it }, "preferências não encontradas em $roots")
        for ((file, text) in texts) assertFalse(SECRET in text, "token em $file")
    }

    private fun regularFiles(root: Path): List<Path> =
        if (Files.isDirectory(root)) {
            Files.walk(root).use { paths -> paths.asSequence().filter(Files::isRegularFile).toList() }
        } else {
            emptyList()
        }
}
