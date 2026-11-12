package com.zeny.desktop.core.http

import de.quati.ogen.client.ktor.HttpClientOgen
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey

private const val CONNECT_TIMEOUT_MILLIS = 5_000L
private const val REQUEST_TIMEOUT_MILLIS = 30_000L
private const val SOCKET_TIMEOUT_MILLIS = 30_000L

/**
 * Marca a requisição cujo `401` `INVALID_CREDENTIALS` ou `ACCOUNT_LOCKED` confere uma credencial
 * (hoje só `changePassword`) e não encerra a sessão.
 */
val CredentialCheck: AttributeKey<Boolean> = AttributeKey("zeny.CredentialCheck")

/** Cliente HTTP do `core`; o `configure` roda por último (é onde entra o `AuthPlugin`). Sem `Logging`. */
fun createCoreHttpClient(
    engine: HttpClientEngine,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        // Um redirecionamento não é seguido: o Authorization não vai para outro endereço, e o 3xx vira erro tipado.
        followRedirects = false
        install(ContentNegotiation) { json(CoreJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
        }
        configure()
    }

/** Base das `<Tag>Api` geradas, com o mesmo `Json` do cliente. */
fun ogenBase(
    http: HttpClient,
    baseUrl: String,
): HttpClientOgen = HttpClientOgen.Base(http, baseUrl, {}, CoreJson)
