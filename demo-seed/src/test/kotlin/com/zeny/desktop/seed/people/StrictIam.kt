package com.zeny.desktop.seed.people

import com.zeny.desktop.seed.cli.Secret
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class StrictUser(
    val id: String,
    var password: String,
    var mustChange: Boolean,
    var active: Boolean = true,
    var roleIds: Set<String> = emptySet(),
) {
    var pin: String? = null
    var failures = 0
    var locked = false
    var rejectSignIn = false
}

/**
 * IAM em memória mais rigoroso que o `FakeIam`, segundo o servidor descrito no despacho: senha 12 a 128,
 * login de conta inativa falha, sessão revogada por `reset-password` e `changePassword` encerra as outras
 * sessões do usuário; com troca obrigatória só `changePassword` e `signOut` respondem.
 */
@Suppress("TooManyFunctions") // falso da porta, que espelha as operações do contrato
class StrictIam :
    UserGateway,
    SessionFactory<StrictIam.StrictSession> {
    val users = mutableMapOf<String, StrictUser>()
    val sessions = mutableListOf<StrictSession>()
    val failuresByLogin = mutableMapOf<String, Int>()
    val provisionals = mutableListOf<String>()
    val roles = mutableMapOf("MANAGER" to "role-m", "RECEPTIONIST" to "role-r", "HOUSEKEEPER" to "role-h")
    var changePasswordHook: suspend () -> Unit = {}
    var signOutHook: suspend () -> Unit = {}
    var signOutChecksCancellation = false
    private var nextId = 1

    val totalFailures get() = failuresByLogin.values.sum()

    fun plant(
        login: String,
        password: String,
        mustChange: Boolean = false,
        active: Boolean = true,
        roleIds: Set<String> = emptySet(),
    ) = StrictUser("u${nextId++}", password, mustChange, active, roleIds).also { users[login] = it }

    override fun open() = StrictSession().also { sessions += it }

    override suspend fun roleIdsByTemplate(): Map<String, String> = roles.toMap()

    override suspend fun findUser(login: String): UserRef? = users[login]?.let { UserRef(it.id, it.active, it.roleIds) }

    override suspend fun createUser(
        login: String,
        name: String,
        provisional: Secret,
        roleIds: List<String>,
        pin: Secret?,
    ): String {
        checkLength(provisional.value)
        provisionals += provisional.value
        check(login !in users) { "LOGIN_TAKEN" }
        return plant(login, provisional.value, mustChange = true, roleIds = roleIds.toSet())
            .also { it.pin = pin?.value }
            .id
    }

    override suspend fun reactivate(userId: String) {
        byId(userId).active = true
    }

    override suspend fun setRoles(
        userId: String,
        roleIds: List<String>,
    ) {
        byId(userId).roleIds = roleIds.toSet()
    }

    override suspend fun resetPassword(
        userId: String,
        provisional: Secret,
    ) {
        checkLength(provisional.value)
        provisionals += provisional.value
        val user = byId(userId)
        user.password = provisional.value
        user.mustChange = true
        user.failures = 0
        user.locked = false
        sessions.filter { it.user === user }.forEach { it.user = null }
    }

    override suspend fun resetPin(
        userId: String,
        pin: Secret,
    ) {
        byId(userId).pin = pin.value
    }

    private fun checkLength(password: String) = check(password.length in MIN_PASSWORD..MAX_PASSWORD) { "WEAK_PASSWORD" }

    private fun byId(id: String) = users.values.first { it.id == id }

    inner class StrictSession : SessionPort {
        var closed = false
        var signedOut = false
        var user: StrictUser? = null

        override suspend fun signIn(
            login: String,
            password: Secret,
        ): SignIn {
            check(!closed) { "sessão fechada" }
            val found = users[login] ?: return SignIn.InvalidCredentials
            check(found.active) { "ACCOUNT_DISABLED" }
            return when {
                found.locked -> {
                    SignIn.Locked
                }

                found.password != password.value || found.rejectSignIn -> {
                    failed(login, found)
                }

                else -> {
                    found.failures = 0
                    user = found
                    if (found.mustChange) SignIn.MustChangePassword else SignIn.Active
                }
            }
        }

        private fun failed(
            login: String,
            found: StrictUser,
        ): SignIn {
            failuresByLogin.merge(login, 1, Int::plus)
            found.failures++
            found.locked = found.failures >= LOCK_AFTER
            return if (found.locked) SignIn.Locked else SignIn.InvalidCredentials
        }

        override suspend fun changePassword(
            current: Secret,
            new: Secret,
        ) {
            val me = checkNotNull(user) { "sem sessão" }
            changePasswordHook()
            check(me.password == current.value) { "senha atual errada" }
            checkLength(new.value)
            me.password = new.value
            me.mustChange = false
            sessions.filter { it !== this && it.user === me }.forEach { it.user = null }
        }

        override suspend fun signOut() {
            signOutHook()
            if (signOutChecksCancellation) currentCoroutineContext().ensureActive()
            checkNotNull(user) { "sessão já encerrada" }
            user = null
            signedOut = true
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val LOCK_AFTER = 3
        const val MIN_PASSWORD = 12
        const val MAX_PASSWORD = 128
    }
}
