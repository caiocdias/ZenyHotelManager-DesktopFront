package com.zeny.desktop.core.config

import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val ENV_NAME = "ZENY_SERVER_URL"
private const val DEFAULT_URL = "http://localhost:8088"

/** Precedência do design §6.3: preferência salva, variável de ambiente, `.env`, padrão. */
class ServerUrlResolver(
    private val settings: LocalSettings,
    private val env: (String) -> String? = System::getenv,
    private val envFileDir: Path?,
) {
    fun resolve(): ServerUrl =
        ServerUrl.parse(settings.serverUrl)
            ?: ServerUrl.parse(env(ENV_NAME))
            ?: ServerUrl.parse(readDotEnv())
            ?: checkNotNull(ServerUrl.parse(DEFAULT_URL))

    private fun readDotEnv(): String? =
        readFile(envFileDir?.resolve(".env"))
            ?.removePrefix("\uFEFF")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filterNot { it.startsWith("#") }
            ?.map { it.removePrefix("export ").trimStart() }
            ?.mapNotNull { line ->
                val name = line.substringBefore('=').trim()
                if (name == ENV_NAME && '=' in line) unquote(line.substringAfter('=').trim()) else null
            }?.lastOrNull()

    private fun readFile(file: Path?): String? =
        try {
            file?.let { String(Files.readAllBytes(it), Charsets.UTF_8) }
        } catch (
            @Suppress("SwallowedException") e: IOException,
        ) {
            null
        }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.first() in "\"'" && value.last() == value.first()) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}

/** Diretório do JAR que contém [anchor], ou `null` quando a classe vem de um diretório `classes/`. */
fun jarDirectory(anchor: Class<*>): Path? {
    val path =
        try {
            anchor.protectionDomain
                ?.codeSource
                ?.location
                ?.let { Paths.get(it.toURI()) }
        } catch (
            @Suppress("SwallowedException") e: URISyntaxException,
        ) {
            null
        } catch (
            @Suppress("SwallowedException") e: FileSystemNotFoundException,
        ) {
            null
        }
    return path?.takeIf { Files.isRegularFile(it) }?.parent
}
