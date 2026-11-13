package com.zeny.desktop.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Servidor falso para o `ZenyCore`: aceita só o access atual; o refresh token vale uma vez e gira a cada
 * `/auth/refresh`; refresh token repetido revoga a sessão (`401 REFRESH_TOKEN_REUSED`). Os tokens são
 * `<accessPrefix><n>` e `<refreshPrefix><n>`, com `n` crescente a cada login ou refresh. Com [dispatcher] (um
 * `StandardTestDispatcher`), o engine roda no tempo virtual do teste.
 */
class FakeZenyServer(
    private val accessPrefix: String = "A",
    private val refreshPrefix: String = "R",
    dispatcher: CoroutineDispatcher? = null,
) {
    val requests = CopyOnWriteArrayList<HttpRequestData>()
    val userId: String = UUID.randomUUID().toString()

    @Volatile var sessionId: String = ""
        private set

    /** Código do `401` dado a qualquer access, até o válido (por exemplo, conta desativada). */
    @Volatile var rejectAllWith: String? = null

    /** Login seguinte responde `200` com um corpo sem `sessionId` (formato incompatível). */
    @Volatile var malformedLogin = false

    /** Roda antes de conferir o access de uma rota protegida (barreiras de concorrência). */
    @Volatile var beforeSecured: suspend (HttpRequestData) -> Unit = {}

    /** Rotas protegidas além de `/auth/me` e `/auth/logout`, com o access já aceito. */
    @Volatile var routes: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond("", HttpStatusCode.NotFound)
    }

    private val lock = Any()
    private var generation = 0
    private var access: String? = null
    private var refresh: String? = null

    val engine: MockEngine =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher?.let { this.dispatcher = it }
                addHandler { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/api/v1/auth/login" -> login()
                        "/api/v1/auth/refresh" -> refresh(request)
                        else -> secured(request)
                    }
                }
            },
        )

    /** O access atual expira: a próxima requisição com ele recebe `401 UNAUTHENTICATED`; o refresh continua valendo. */
    fun expireAccess() {
        synchronized(lock) { access = null }
    }

    fun count(path: String): Int = requests.count { it.url.encodedPath == path }

    fun authorizationOf(request: HttpRequestData): List<String>? = request.headers.getAll(HttpHeaders.Authorization)

    /** O `Authorization` de cada requisição a `path`, na ordem de chegada. */
    fun authorizations(path: String): List<List<String>?> =
        requests.filter { it.url.encodedPath == path }.map(::authorizationOf)

    private fun MockRequestHandleScope.login(): HttpResponseData {
        if (malformedLogin) {
            // Os tokens que o login seguinte vai dar, num corpo cortado no meio (o parser ecoa o trecho na
            // mensagem dele); nada muda no servidor.
            malformedLogin = false
            val next = synchronized(lock) { generation + 1 }
            return json(
                """{"tokenType":"Bearer","accessToken":"$accessPrefix$next","refreshToken":"$refreshPrefix$next""",
            )
        }
        val (a, r, s) =
            synchronized(lock) {
                generation++
                sessionId = UUID.randomUUID().toString()
                access = "$accessPrefix$generation"
                refresh = "$refreshPrefix$generation"
                Triple(access!!, refresh!!, sessionId)
            }
        return tokens(a, r, s)
    }

    private suspend fun MockRequestHandleScope.refresh(request: HttpRequestData): HttpResponseData {
        val sent =
            Json
                .parseToJsonElement(request.body.toByteArray().decodeToString())
                .jsonObject
                .getValue("refreshToken")
                .jsonPrimitive.content
        val next =
            synchronized(lock) {
                if (sent != refresh) {
                    access = null
                    refresh = null
                    null
                } else {
                    generation++
                    access = "$accessPrefix$generation"
                    refresh = "$refreshPrefix$generation"
                    Triple(access!!, refresh!!, sessionId)
                }
            } ?: return problem(HttpStatusCode.Unauthorized, "REFRESH_TOKEN_REUSED")
        return tokens(next.first, next.second, next.third)
    }

    private suspend fun MockRequestHandleScope.secured(request: HttpRequestData): HttpResponseData {
        beforeSecured(request)
        val rejection = rejectAllWith
        val current = synchronized(lock) { access }
        return when {
            rejection != null -> {
                problem(HttpStatusCode.Unauthorized, rejection)
            }

            current == null || authorizationOf(request) != listOf("Bearer $current") -> {
                problem(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            }

            request.url.encodedPath == "/api/v1/auth/me" -> {
                me()
            }

            request.url.encodedPath == "/api/v1/auth/logout" -> {
                synchronized(lock) {
                    access = null
                    refresh = null
                }
                respond("", HttpStatusCode.NoContent)
            }

            else -> {
                routes(request)
            }
        }
    }

    private fun MockRequestHandleScope.me() =
        json(
            """{"login":"maria.silva","name":"Maria Silva","permissions":[],"roles":[],""" +
                """"sessionId":"$sessionId","userId":"$userId"}""",
        )

    private fun MockRequestHandleScope.tokens(
        access: String,
        refresh: String,
        session: String,
    ) = json(
        """
        {"accessToken":"$access","accessTokenExpiresAt":"2026-11-11T13:15:00Z",
         "passwordChangeRequired":false,"refreshToken":"$refresh",
         "refreshTokenExpiresAt":"2026-12-11T13:00:00Z","sessionId":"$session",
         "tokenType":"Bearer","userId":"$userId"}
        """.trimIndent(),
    )
}

internal fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    vararg extra: Pair<String, String>,
): HttpResponseData =
    respond(
        body,
        status,
        headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            *extra.map { it.first to listOf(it.second) }.toTypedArray(),
        ),
    )

internal fun MockRequestHandleScope.problem(
    status: HttpStatusCode,
    code: String,
): HttpResponseData = json("""{"code":"$code","detail":"detalhe"}""", status)
