package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.people.SessionFactory
import com.zeny.desktop.seed.people.SessionPort
import com.zeny.desktop.seed.people.SignIn
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
private const val ACCOUNT_LOCKED = "ACCOUNT_LOCKED"
private val SIGN_OUT_LIMIT = 3.seconds

/** Sessão de um usuário sobre o `core`; `seed` dá aos gateways a mesma sessão autenticada. */
class SdkSession(
    val seed: SeedCore,
) : SessionPort {
    override suspend fun signIn(
        login: String,
        password: Secret,
    ): SignIn =
        try {
            val state = seed.core.session.login(login, password.value)
            if (state.passwordChangeRequired) SignIn.MustChangePassword else SignIn.Active
        } catch (e: ApiError.Problem) {
            when (e.code) {
                INVALID_CREDENTIALS -> SignIn.InvalidCredentials
                ACCOUNT_LOCKED -> SignIn.Locked
                else -> throw e
            }
        }

    /** Depois da troca, o `core` renova o par: a sessão segue autenticada. */
    override suspend fun changePassword(
        current: Secret,
        new: Secret,
    ) = seed.core.session.changePassword(current.value, new.value)

    /** Melhor esforço, limitado a 3 s; sem sessão aberta, só limpa o que já está limpo. */
    override suspend fun signOut() {
        withTimeoutOrNull(SIGN_OUT_LIMIT) { seed.core.session.logout() }
    }

    override fun close() = seed.close()
}

/** Cada `open()` cria um `SeedCore` próprio: `MemorySettings` novo e, nele, um `deviceId` novo. */
class SdkSessionFactory(
    private val server: ServerUrl,
    private val engineFactory: (() -> HttpClientEngine)? = null,
) : SessionFactory<SdkSession> {
    override fun open(): SdkSession = SdkSession(SeedCore(server, engineFactory?.invoke()))
}
