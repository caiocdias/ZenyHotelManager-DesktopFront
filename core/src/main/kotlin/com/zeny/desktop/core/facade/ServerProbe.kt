package com.zeny.desktop.core.facade

import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.sdk.client.MetaApi
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout

private const val CONNECT_TIMEOUT_MILLIS = 5_000L
private const val REQUEST_TIMEOUT_MILLIS = 10_000L
private const val APPLICATION = "zeny-hotel-manager"
private const val API_VERSION = "v1"
private const val NOT_ZENY_MESSAGE = "Esse endereço não é um servidor do Zeny Hotel Manager."

sealed interface ProbeResult {
    data class Ok(
        val version: String,
    ) : ProbeResult

    data class Failed(
        val message: String,
    ) : ProbeResult
}

/** Confere, sem autenticação, que o endereço é um servidor do Zeny (`GET /api/v1/meta/version`). */
object ServerProbe {
    suspend fun check(
        url: ServerUrl,
        engineFactory: () -> HttpClientEngine = { Java.create() },
    ): ProbeResult = probe(url, engineFactory, REQUEST_TIMEOUT_MILLIS)

    /** O prazo de requisição entra só para o teste não esperar os 10 s reais. */
    internal suspend fun probe(
        url: ServerUrl,
        engineFactory: () -> HttpClientEngine,
        requestTimeoutMillis: Long,
    ): ProbeResult {
        val engine = engineFactory()
        val http =
            createCoreHttpClient(engine) {
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    this.requestTimeoutMillis = requestTimeoutMillis
                }
            }
        return try {
            val meta = MetaApi(ogenBase(http, url.value))
            val body = ApiExecutor().execute { meta.getServerVersion() }.value
            if (body.application == APPLICATION && body.apiVersion == API_VERSION) {
                ProbeResult.Ok(body.version)
            } else {
                ProbeResult.Failed(NOT_ZENY_MESSAGE)
            }
        } catch (e: ApiError.Network) {
            ProbeResult.Failed(e.userMessage)
        } catch (
            @Suppress("SwallowedException") e: ApiError,
        ) {
            ProbeResult.Failed(NOT_ZENY_MESSAGE)
        } finally {
            http.close()
            engine.close()
        }
    }
}
