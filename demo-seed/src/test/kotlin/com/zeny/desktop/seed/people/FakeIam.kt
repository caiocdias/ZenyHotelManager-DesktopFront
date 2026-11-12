package com.zeny.desktop.seed.people

import com.zeny.desktop.seed.cli.Secret

class FakeUser(
    val id: String,
    var password: String,
    var mustChange: Boolean,
    var active: Boolean = true,
    var roleIds: Set<String> = emptySet(),
) {
    var pin: String? = null
    var failures = 0
    var locked = false
}

/** IAM em memória: senha, troca obrigatória, PIN e bloqueio na 3ª falha seguida de login. */
@Suppress("TooManyFunctions") // falso da porta, que espelha as operações do contrato
class FakeIam(
    val roles: MutableMap<String, String> =
        mutableMapOf("MANAGER" to "role-m", "RECEPTIONIST" to "role-r", "HOUSEKEEPER" to "role-h"),
) : UserGateway,
    SessionFactory<FakeIam.FakeSession> {
    val users = mutableMapOf<String, FakeUser>()
    val sessionsOpened = mutableListOf<FakeSession>()
    var totalFailures = 0
    var createCalls = 0
    var resetPasswordCalls = 0
    var failOnSignIn = false
    private var nextId = 1

    fun plant(
        login: String,
        password: String,
        mustChange: Boolean = false,
        active: Boolean = true,
        roleIds: Set<String> = emptySet(),
    ) = FakeUser("u${nextId++}", password, mustChange, active, roleIds).also { users[login] = it }

    override fun open() = FakeSession().also { sessionsOpened += it }

    override suspend fun roleIdsByTemplate(): Map<String, String> = roles.toMap()

    override suspend fun findUser(login: String): UserRef? = users[login]?.let { UserRef(it.id, it.active, it.roleIds) }

    override suspend fun createUser(
        login: String,
        name: String,
        provisional: Secret,
        roleIds: List<String>,
        pin: Secret?,
    ): String {
        createCalls++
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
        resetPasswordCalls++
        byId(userId).apply {
            password = provisional.value
            mustChange = true
            failures = 0
            locked = false
        }
    }

    override suspend fun resetPin(
        userId: String,
        pin: Secret,
    ) {
        byId(userId).pin = pin.value
    }

    private fun byId(id: String) = users.values.first { it.id == id }

    inner class FakeSession : SessionPort {
        var closed = false
        var signedOut = false
        private var current: FakeUser? = null

        override suspend fun signIn(
            login: String,
            password: Secret,
        ): SignIn {
            check(!closed) { "sessão fechada" }
            if (failOnSignIn) error("falha de rede simulada")
            val user = users[login] ?: return SignIn.InvalidCredentials
            val result = attempt(user, password)
            if (result == SignIn.Active || result == SignIn.MustChangePassword) current = user
            return result
        }

        private fun attempt(
            user: FakeUser,
            password: Secret,
        ): SignIn =
            when {
                user.locked -> {
                    SignIn.Locked
                }

                user.password != password.value -> {
                    failed(user)
                }

                else -> {
                    user.failures = 0
                    if (user.mustChange) SignIn.MustChangePassword else SignIn.Active
                }
            }

        private fun failed(user: FakeUser): SignIn {
            totalFailures++
            user.failures++
            user.locked = user.failures >= LOCK_AFTER
            return if (user.locked) SignIn.Locked else SignIn.InvalidCredentials
        }

        override suspend fun changePassword(
            current: Secret,
            new: Secret,
        ) {
            val user = checkNotNull(this.current) { "sem login" }
            check(user.password == current.value) { "senha atual errada" }
            user.password = new.value
            user.mustChange = false
        }

        override suspend fun signOut() {
            signedOut = true
            current = null
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val LOCK_AFTER = 3
    }
}
