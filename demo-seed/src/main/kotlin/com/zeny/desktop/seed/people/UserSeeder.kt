package com.zeny.desktop.seed.people

import com.zeny.desktop.seed.SeedFailure
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.cli.SeedConfig
import com.zeny.desktop.seed.data.DemoRole
import com.zeny.desktop.seed.data.SeedItem
import com.zeny.desktop.seed.data.SeedReport
import com.zeny.desktop.seed.data.UserSpec
import kotlinx.coroutines.CancellationException
import java.security.SecureRandom

/** Senhas provisórias aleatórias, com maiúscula, minúscula, dígito e símbolo. */
object ProvisionalPasswords {
    private const val LENGTH = 24
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#%^&*-_=+"
    private val random = SecureRandom()

    fun next(): Secret {
        val classes = listOf(UPPER, LOWER, DIGITS, SYMBOLS)
        val all = classes.joinToString("")
        val chars =
            classes.map { it.pick() } + List(LENGTH - classes.size) { all.pick() }
        return Secret(chars.shuffled(random).joinToString(""))
    }

    private fun String.pick() = this[random.nextInt(length)]
}

/** Fecha a sessão (saída em melhor esforço e liberação do cliente), também na falha. */
@Suppress("TooGenericExceptionCaught", "SwallowedException") // a saída é melhor esforço: sem sessão ou sem rede, segue
internal suspend fun SessionPort.shutDown() {
    try {
        signOut()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // nada a encerrar
    } finally {
        close()
    }
}

/** Abre a sessão do administrador: re-execução entra com a nova senha, a primeira troca a inicial por ela. */
class AdminSignIn<S : SessionPort>(
    private val sessions: SessionFactory<S>,
) {
    suspend fun open(config: SeedConfig): S {
        val session = sessions.open()
        var handedOver = false
        try {
            signInOrChange(session, config)
            handedOver = true
            return session
        } finally {
            if (!handedOver) session.shutDown()
        }
    }

    private suspend fun signInOrChange(
        session: S,
        config: SeedConfig,
    ) {
        val new = config.adminNewPassword
        if (new != null && enteredWithNew(session, config.adminLogin, new)) return
        when (session.signIn(config.adminLogin, config.adminPassword)) {
            SignIn.Active -> Unit
            SignIn.MustChangePassword -> changeInitial(session, config, new)
            SignIn.InvalidCredentials -> throw SeedFailure(REJECTED)
            SignIn.Locked -> throw SeedFailure(LOCKED)
        }
    }

    private suspend fun enteredWithNew(
        session: S,
        login: String,
        new: Secret,
    ): Boolean =
        when (session.signIn(login, new)) {
            SignIn.Active -> true
            SignIn.Locked -> throw SeedFailure(LOCKED)
            SignIn.MustChangePassword, SignIn.InvalidCredentials -> false
        }

    private suspend fun changeInitial(
        session: S,
        config: SeedConfig,
        new: Secret?,
    ) {
        if (new == null) throw SeedFailure(MUST_CHANGE)
        session.changePassword(config.adminPassword, new)
    }

    private companion object {
        const val MUST_CHANGE =
            "O administrador precisa trocar a senha: informe --admin-new-password " +
                "(ou ZENY_SEED_ADMIN_NEW_PASSWORD)."
        const val REJECTED = "Login ou senha do administrador recusados."
        const val LOCKED = "A conta do administrador está bloqueada por tentativas; espere e tente de novo."
    }
}

/** Garante os usuários da demo com as senhas pedidas e o PIN do Gerente; segura para re-execução. */
class UserSeeder(
    private val users: UserGateway,
    private val sessions: SessionFactory<SessionPort>,
    private val report: SeedReport,
) {
    suspend fun seed(
        specs: List<UserSpec>,
        passwords: Map<DemoRole, Secret>,
        managerPin: Secret,
    ) {
        val roleIds = users.roleIdsByTemplate()
        for (spec in specs) {
            val password = passwords[spec.role] ?: throw SeedFailure("Falta a senha do usuário ${spec.login}.")
            val roleId =
                roleIds[spec.role.template]
                    ?: throw SeedFailure("O servidor não tem o modelo de perfil ${spec.role.template}.")
            seedOne(spec, password, roleId, managerPin)
        }
    }

    private suspend fun seedOne(
        spec: UserSpec,
        password: Secret,
        roleId: String,
        managerPin: Secret,
    ) {
        val pin = managerPin.takeIf { spec.role == DemoRole.MANAGER }
        val existing = users.findUser(spec.login)
        if (existing == null) {
            val provisional = ProvisionalPasswords.next()
            users.createUser(spec.login, spec.name, provisional, listOf(roleId), pin)
            report.created(SeedItem.USER)
            complete(spec.login, password, provisional)
            return
        }
        report.kept(SeedItem.USER)
        if (!existing.active) users.reactivate(existing.id)
        if (roleId !in existing.roleIds) users.setRoles(existing.id, (existing.roleIds + roleId).toList())
        if (!verified(spec.login, password)) {
            val provisional = ProvisionalPasswords.next()
            users.resetPassword(existing.id, provisional)
            complete(spec.login, password, provisional)
        }
        if (pin != null) users.resetPin(existing.id, pin)
    }

    /** Entra com a senha pedida numa sessão própria; `false` se foi recusada ou a conta está bloqueada. */
    private suspend fun verified(
        login: String,
        password: Secret,
    ): Boolean =
        withSession { session ->
            when (session.signIn(login, password)) {
                SignIn.Active -> true
                SignIn.InvalidCredentials, SignIn.Locked -> false
                SignIn.MustChangePassword -> false
            }
        }

    /** Com a senha provisória, troca pela pedida e confere que ela entra. */
    private suspend fun complete(
        login: String,
        password: Secret,
        provisional: Secret,
    ) {
        withSession { session ->
            if (session.signIn(login, provisional) != SignIn.MustChangePassword) {
                throw SeedFailure("Não foi possível entrar com a senha provisória do usuário $login.")
            }
            session.changePassword(provisional, password)
        }
        val ok = withSession { it.signIn(login, password) == SignIn.Active }
        if (!ok) throw SeedFailure("O usuário $login não entrou com a senha definida.")
    }

    private suspend fun <T> withSession(block: suspend (SessionPort) -> T): T {
        val session = sessions.open()
        try {
            return block(session)
        } finally {
            session.shutDown()
        }
    }
}
