package com.zeny.desktop.seed.cli

import com.zeny.desktop.core.config.ServerUrl
import java.net.URI

data class SeedConfig(
    val server: ServerUrl,
    val adminLogin: String,
    val adminPassword: Secret,
    val adminNewPassword: Secret?,
    val managerPassword: Secret,
    val receptionistPassword: Secret,
    val housekeeperPassword: Secret,
    val managerPin: Secret,
)

sealed interface ParseResult {
    data class Ok(
        val config: SeedConfig,
    ) : ParseResult

    data object Help : ParseResult

    data class Invalid(
        val message: String,
    ) : ParseResult
}

/** Lê a linha de comando e as variáveis de ambiente; o argumento vence a variável. Nenhuma mensagem ecoa segredo. */
object SeedArgs {
    private const val INVALID_SERVER = "Endereço inválido. Use http://endereço:porta."
    private const val SERVER_WITH_PATH = "Informe só o endereço e a porta, sem caminho."
    private val PASSWORD_LENGTH = 12..128
    private val PIN_LENGTH = 4..8
    private val OPTIONS =
        listOf(
            "server",
            "admin-login",
            "admin-password",
            "admin-new-password",
            "manager-password",
            "receptionist-password",
            "housekeeper-password",
            "manager-pin",
        )

    val usage: String =
        """
        Uso: demo-seed [opções]

        Opções (cada uma também vem da variável de ambiente indicada; o argumento vence a variável):
          --server URL                   ZENY_SEED_SERVER
          --admin-login LOGIN            ZENY_SEED_ADMIN_LOGIN
          --admin-password SENHA         ZENY_SEED_ADMIN_PASSWORD
          --admin-new-password SENHA     ZENY_SEED_ADMIN_NEW_PASSWORD (opcional)
          --manager-password SENHA       ZENY_SEED_MANAGER_PASSWORD
          --receptionist-password SENHA  ZENY_SEED_RECEPTIONIST_PASSWORD
          --housekeeper-password SENHA   ZENY_SEED_HOUSEKEEPER_PASSWORD
          --manager-pin PIN              ZENY_SEED_MANAGER_PIN
          -h, --help                     mostra esta ajuda

        Senhas e PIN: prefira as variáveis de ambiente; argumento fica no histórico do shell e na lista de processos.
        Senhas têm de 12 a 128 caracteres; o PIN, de 4 a 8 dígitos.
        """.trimIndent()

    fun parse(
        args: List<String>,
        env: Map<String, String>,
    ): ParseResult =
        if (args.any { it == "--help" || it == "-h" }) {
            ParseResult.Help
        } else {
            try {
                val given = readOptions(args)
                build { name -> given[name] ?: env[envName(name)] }
            } catch (e: Failure) {
                ParseResult.Invalid(e.message)
            }
        }

    private fun readOptions(args: List<String>): Map<String, String> {
        val given = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val name = optionName(arg)
            if ('=' in arg) {
                given[name] = arg.substringAfter('=')
            } else {
                val next = args.getOrNull(i + 1)
                if (next == null || next.startsWith("--")) throw Failure("Falta o valor de --$name.")
                given[name] = next
                i++
            }
            i++
        }
        return given
    }

    /** Só um token que começa com "--" pode ser citado; qualquer outro pode ser um segredo solto. */
    private fun optionName(arg: String): String {
        if (!arg.startsWith("--")) throw Failure("Argumento inesperado; use --opção valor ou --opção=valor.")
        val name = arg.substringBefore('=').removePrefix("--")
        if (name !in OPTIONS) throw Failure("Opção desconhecida: --$name.")
        return name
    }

    private fun envName(option: String) = "ZENY_SEED_" + option.uppercase().replace('-', '_')

    private class Failure(
        override val message: String,
    ) : Exception(message)

    private fun build(lookup: (String) -> String?): ParseResult {
        fun required(name: String) = lookup(name) ?: throw missing(name)

        fun password(name: String) = checked(name, required(name)) { it.length in PASSWORD_LENGTH }

        fun pin(name: String) =
            checked(name, required(name)) { v -> v.length in PIN_LENGTH && v.all { it in '0'..'9' } }
        return try {
            val server = server(required("server"))
            val login = required("admin-login").also { if (it.isBlank()) throw missing("admin-login") }
            val adminPassword = password("admin-password")
            val newPassword = lookup("admin-new-password")?.let { password("admin-new-password") }
            val manager = password("manager-password")
            val receptionist = password("receptionist-password")
            val housekeeper = password("housekeeper-password")
            val managerPin = pin("manager-pin")
            ParseResult.Ok(
                SeedConfig(server, login, adminPassword, newPassword, manager, receptionist, housekeeper, managerPin),
            )
        } catch (e: Failure) {
            ParseResult.Invalid(e.message)
        }
    }

    private fun missing(name: String) = Failure("Falta --$name (ou ${envName(name)}).")

    private fun checked(
        name: String,
        value: String,
        valid: (String) -> Boolean,
    ): Secret {
        if (valid(value)) return Secret(value)
        val rule =
            if (name ==
                "manager-pin"
            ) {
                "o PIN deve ter de 4 a 8 dígitos"
            } else {
                "a senha deve ter de 12 a 128 caracteres"
            }
        throw Failure("Valor inválido em --$name: $rule.")
    }

    private fun server(raw: String): ServerUrl {
        val url = ServerUrl.parse(raw) ?: throw Failure(INVALID_SERVER)
        if (URI(url.value).rawPath.orEmpty().isNotEmpty()) throw Failure(SERVER_WITH_PATH)
        return url
    }
}
