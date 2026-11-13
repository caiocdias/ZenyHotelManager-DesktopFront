package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.seed.cli.ParseResult
import com.zeny.desktop.seed.cli.SeedArgs
import com.zeny.desktop.seed.people.AdminSignIn
import com.zeny.desktop.seed.people.shutDown
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verificação ao vivo: só roda com `ZENY_SEED_SERVER` e `ZENY_SEED_LIVE=1` (sem elas, pula); credenciais só do
 * ambiente (nunca impressas). Comando, só nesse subshell:
 * `(set -a; . <arquivo de segredos>; set +a; ZENY_SEED_LIVE=1 ./gradlew :demo-seed:test --tests '*Live*' --rerun)`
 */
class LiveAdminCheck {
    @Test
    fun adminOpensTwiceInARow() {
        assumeTrue(System.getenv("ZENY_SEED_SERVER") != null && System.getenv("ZENY_SEED_LIVE") == "1")
        val parsed = SeedArgs.parse(emptyList(), System.getenv())
        assertTrue(parsed is ParseResult.Ok, "variáveis ZENY_SEED_* incompletas")
        val config = parsed.config
        repeat(2) {
            runBlocking {
                val session = AdminSignIn(SdkSessionFactory(config.server)).open(config)
                try {
                    val state = session.seed.core.session.state.value
                    assertTrue(state is SessionState.Active && !state.passwordChangeRequired, "sessão não ativa")
                    // Prova que o par renovado depois da troca de senha autentica de fato.
                    session.seed.core.account
                        .me()
                } finally {
                    session.shutDown()
                }
            }
        }
    }
}
