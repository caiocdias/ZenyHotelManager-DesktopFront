package com.zeny.desktop.seed.people

import com.zeny.desktop.seed.cli.Secret

sealed interface SignIn {
    data object Active : SignIn

    data object MustChangePassword : SignIn

    data object InvalidCredentials : SignIn

    data object Locked : SignIn
}

/** Sessão de um usuário contra o servidor; `close()` libera o cliente sem rede. */
interface SessionPort : AutoCloseable {
    suspend fun signIn(
        login: String,
        password: Secret,
    ): SignIn

    suspend fun changePassword(
        current: Secret,
        new: Secret,
    )

    suspend fun signOut()
}

/** Abre sessões novas; genérica para o administrador receber a sessão concreta do adaptador. */
fun interface SessionFactory<out S : SessionPort> {
    fun open(): S
}

data class UserRef(
    val id: String,
    val active: Boolean,
    val roleIds: Set<String>,
)

@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seed
interface UserGateway {
    suspend fun roleIdsByTemplate(): Map<String, String>

    suspend fun findUser(login: String): UserRef?

    suspend fun createUser(
        login: String,
        name: String,
        provisional: Secret,
        roleIds: List<String>,
        pin: Secret?,
    ): String

    suspend fun reactivate(userId: String)

    suspend fun setRoles(
        userId: String,
        roleIds: List<String>,
    )

    suspend fun resetPassword(
        userId: String,
        provisional: Secret,
    )

    suspend fun resetPin(
        userId: String,
        pin: Secret,
    )
}
