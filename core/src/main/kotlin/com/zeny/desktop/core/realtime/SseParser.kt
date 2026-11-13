package com.zeny.desktop.core.realtime

/** Um evento do stream: [event] (`message` quando o servidor não nomeia), [id] da posição e [data] já unida. */
data class SseEvent(
    val event: String,
    val id: String?,
    val data: String,
)

/**
 * Interpreta o stream de eventos do servidor (SSE) linha a linha, pela especificação HTML.
 * Não é thread-safe: uma instância por conexão. O `id:` vazio passa adiante como `""`; a `StreamConnection`, que
 * guarda a posição, o ignora em vez de zerá-la (divergência conhecida da especificação, explicada lá).
 */
class SseParser {
    private var eventName = ""
    private var data: StringBuilder? = null
    private var lastId: String? = null

    /** Consome uma linha (sem o terminador `\n`); devolve o evento quando a linha vazia o despacha. */
    fun feed(line: String): SseEvent? {
        val text = line.removeSuffix("\r")
        return when {
            text.isEmpty() -> {
                dispatch()
            }

            text.startsWith(':') -> {
                null
            }

            else -> {
                applyField(text)
                null
            }
        }
    }

    private fun applyField(text: String) {
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> eventName = value
            "data" -> (data ?: StringBuilder().also { data = it }).append(value).append('\n')
            "id" -> if ('\u0000' !in value) lastId = value
        }
    }

    /** Descarta o evento parcial e a posição: a próxima conexão recomeça do zero. */
    fun reset() {
        eventName = ""
        data = null
        lastId = null
    }

    private fun dispatch(): SseEvent? {
        val buffer = data
        val name = eventName.ifEmpty { "message" }
        eventName = ""
        data = null
        return buffer?.let { SseEvent(name, lastId, it.removeSuffix("\n").toString()) }
    }
}
