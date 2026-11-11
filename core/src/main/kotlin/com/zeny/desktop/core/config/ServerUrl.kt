package com.zeny.desktop.core.config

import java.net.URI
import java.net.URISyntaxException

/** Endereço base do servidor, já validado e sem a `/` final. */
@JvmInline
value class ServerUrl private constructor(
    val value: String,
) {
    companion object {
        private const val NO_PORT = -1
        private val PORT_RANGE = 1..65535

        fun parse(raw: String?): ServerUrl? {
            val trimmed = raw?.trim().orEmpty()
            val uri = toUri(trimmed)
            val schemeOk = uri?.scheme?.lowercase() in setOf("http", "https")
            val valid =
                uri != null &&
                    schemeOk &&
                    !uri.host.isNullOrEmpty() &&
                    (uri.port == NO_PORT || uri.port in PORT_RANGE) &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.rawUserInfo == null
            return if (valid) ServerUrl(trimmed.trimEnd('/')) else null
        }

        private fun toUri(text: String): URI? =
            try {
                URI(text)
            } catch (
                @Suppress("SwallowedException") e: URISyntaxException,
            ) {
                null
            }
    }
}
