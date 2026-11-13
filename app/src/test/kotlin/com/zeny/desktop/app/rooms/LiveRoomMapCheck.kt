package com.zeny.desktop.app.rooms

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.realtime.RealtimeStatus
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

private const val WIDTH = 1280
private const val HEIGHT = 800
private const val LOAD_TIMEOUT_MILLIS = 30_000L
private const val CHANGE_TIMEOUT_MILLIS = 30_000L
private const val OUTAGE_TIMEOUT_MILLIS = 240_000L
private const val SETTLE_TIMEOUT_MILLIS = 10_000L
private const val POLL_MILLIS = 50L
private const val LIMIT_MILLIS = 2_000L
private const val CURL_TIMEOUT_SECONDS = 60L
private const val MAX_RESTORE_STEPS = 3
private const val CAPTURE = "build/live/room-map.png"

/**
 * Verificação ao vivo do mapa de UHs: a Recepcionista abre o `RoomMapState` real sobre o `CoreConnection` e a
 * Governanta muda a governança de uma UH vaga por `curl` (o `app` não importa cliente HTTP, ADR 0008); o ladrilho
 * mostra a versão nova em até 2 s. Grava `build/live/room-map.png`.
 *
 * Pulada sem o opt-in `ZENY_LIVE_REALTIME=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`/`PASSWORD` e
 * `ZENY_LIVE_HOUSEKEEPER_LOGIN`/`PASSWORD` (credenciais só do ambiente, nunca impressas). A queda do `api`
 * ([recoversAfterApiRestart]) pede também `ZENY_LIVE_OUTAGE=1` e alguém que derrube e suba o `api` ao ver `PRONTO`
 * (a saída aparece com `--info`). Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_REALTIME=1 ./gradlew :app:test --tests '*LiveRoomMapCheck*' --rerun)`
 */
@OptIn(ExperimentalTestApi::class)
class LiveRoomMapCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    private val url: ServerUrl by lazy { ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida") }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_REALTIME") == "1", "sem ZENY_LIVE_REALTIME=1: verificação real pulada")
    }

    @Test
    fun capturesMapAndFollowsChange() {
        val housekeeper = HousekeeperCurl(url.value)
        housekeeper.login(env("ZENY_LIVE_HOUSEKEEPER_LOGIN"), env("ZENY_LIVE_HOUSEKEEPER_PASSWORD"))
        try {
            withViewer { source ->
                runDesktopComposeUiTest(WIDTH, HEIGHT) {
                    lateinit var state: RoomMapState
                    setContent {
                        val scope = rememberCoroutineScope()
                        state = remember { RoomMapState(source, scope) }
                        ZenyTheme { RoomMapScreen(state) }
                    }
                    try {
                        waitUntil(timeoutMillis = LOAD_TIMEOUT_MILLIS) { state.isLoadedLive() }
                        assertNull(state.error, "o mapa falhou: ${state.error?.userMessage}")
                        followChange(state, housekeeper)
                    } finally {
                        state.close()
                    }
                }
            }
        } finally {
            housekeeper.logout()
        }
    }

    /** A Governanta muda uma UH vaga; o ladrilho mostra a versão nova em até 2 s; grava a captura. */
    private fun DesktopComposeUiTest.followChange(
        state: RoomMapState,
        housekeeper: HousekeeperCurl,
    ) {
        val tiles = state.floors.flatMap { it.second }
        val tile =
            tiles.firstOrNull { it.status == RoomDisplayStatus.DIRTY }
                ?: tiles.firstOrNull { it.status in VACANT_FREE }
                ?: fail("nenhuma UH vaga e sem bloqueio no mapa")
        println("LiveRoomMapCheck: UH ${tile.number} (${tile.roomId})")
        val original = housekeeper.status(tile.roomId)
        try {
            val newVersion = housekeeper.change(tile.roomId, forward(original))
            val writeEnd = TimeSource.Monotonic.markNow()
            waitUntil(timeoutMillis = CHANGE_TIMEOUT_MILLIS) { (state.tile(tile.roomId)?.version ?: -1) >= newVersion }
            val elapsed = writeEnd.elapsedNow().inWholeMilliseconds
            println("LiveRoomMapCheck: ladrilho em $elapsed ms (versão $newVersion)")
            onNodeWithTag("room-tile-${tile.roomId}").assertExists()
            capture()
            assertTrue(elapsed <= LIMIT_MILLIS, "o ladrilho levou $elapsed ms (limite $LIMIT_MILLIS ms)")
        } finally {
            housekeeper.restore(tile.roomId, original)
        }
    }

    /**
     * Sem tela: o `runTest` de fora do teste de UI tem limite fixo de um minuto, e a queda passa disso. O estado corre
     * numa thread só, como na UI, e é lido nela.
     */
    @Test
    fun recoversAfterApiRestart() {
        assumeTrue(System.getenv("ZENY_LIVE_OUTAGE") == "1", "sem ZENY_LIVE_OUTAGE=1: queda do api pulada")
        withViewer { source ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
                val scope = CoroutineScope(SupervisorJob() + ui)
                val state = runBlocking(ui) { RoomMapState(source, scope) }
                try {
                    awaitOn(ui, LOAD_TIMEOUT_MILLIS) { state.isLoadedLive() }
                    assertNull(runBlocking(ui) { state.error }, "o mapa falhou")
                    val started = TimeSource.Monotonic.markNow()
                    println("PRONTO: derrube o api")
                    System.out.flush()
                    awaitOn(ui, OUTAGE_TIMEOUT_MILLIS) { state.live is RealtimeStatus.Reconnecting }
                    println("LiveRoomMapCheck: Reconnecting em ${started.elapsedNow().inWholeMilliseconds} ms")
                    awaitOn(ui, OUTAGE_TIMEOUT_MILLIS) { state.live == RealtimeStatus.Live }
                    println("LiveRoomMapCheck: Live de novo em ${started.elapsedNow().inWholeMilliseconds} ms")
                    assertMatchesServer(ui, state, source)
                } finally {
                    runBlocking(ui) { state.close() }
                    scope.cancel()
                }
            }
        }
    }

    /** Entra como Recepcionista pelo `CoreConnection` real; a sessão tem de seguir ativa no fim; fecha tudo. */
    private fun withViewer(block: (RoomsSource) -> Unit) {
        val connection = CoreConnection(ZenyCore(url, MemorySettings()))
        try {
            runBlocking { connection.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD")) }
            block(connection.roomsSource())
            assertIs<SessionState.Active>(connection.state.value, "a sessão da Recepcionista caiu")
        } finally {
            runCatching { runBlocking { connection.logout() } }
            connection.close()
        }
    }

    /** Os ladrilhos (sem filtro: todas as UHs ativas) batem com um `statuses()` novo; repete por até 10 s. */
    private fun assertMatchesServer(
        ui: CoroutineDispatcher,
        state: RoomMapState,
        source: RoomsSource,
    ) {
        val started = TimeSource.Monotonic.markNow()
        var matches: Boolean
        do {
            val expected =
                runBlocking { source.statuses() }
                    .filter { it.active }
                    .associate { it.roomId.toString() to Triple(it.version, displayStatus(it), housekeepingStatus(it)) }
            val shown =
                runBlocking(ui) {
                    state.floors.flatMap { it.second }.associate {
                        it.roomId to
                            Triple(it.version, it.status, it.housekeeping)
                    }
                }
            matches = expected == shown
            if (!matches) Thread.sleep(POLL_MILLIS)
        } while (!matches && started.elapsedNow().inWholeMilliseconds < SETTLE_TIMEOUT_MILLIS)
        println("LiveRoomMapCheck: comparação com statuses() ${if (matches) "bateu" else "não bateu"}")
        assertTrue(matches, "os ladrilhos não batem com o servidor depois de $SETTLE_TIMEOUT_MILLIS ms")
    }

    /** Espera [condition], lida na thread do estado, por até [timeoutMillis]. */
    private fun awaitOn(
        ui: CoroutineDispatcher,
        timeoutMillis: Long,
        condition: () -> Boolean,
    ) = runBlocking {
        withTimeout(timeoutMillis) { while (!withContext(ui) { condition() }) delay(POLL_MILLIS) }
    }

    private fun RoomMapState.isLoadedLive() = !loading && hasRooms && live == RealtimeStatus.Live

    private fun DesktopComposeUiTest.capture() {
        val file = File(CAPTURE)
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        println("LiveRoomMapCheck: captura em ${file.absolutePath}")
    }

    private fun RoomMapState.tile(roomId: String): RoomTile? =
        floors.flatMap { it.second }.firstOrNull {
            it.roomId ==
                roomId
        }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fail("defina $name para a verificação real")

    private companion object {
        val VACANT_FREE =
            setOf(
                RoomDisplayStatus.DIRTY,
                RoomDisplayStatus.CLEAN,
                RoomDisplayStatus.CLEAN_PENDING_INSPECTION,
                RoomDisplayStatus.INSPECTED,
            )
    }
}

/** A governança de uma UH como o servidor a guarda. */
private data class Housekeeping(
    val status: String,
    val inspectionPending: Boolean,
)

private fun forward(original: Housekeeping): JsonObject =
    if (original.status == "DIRTY") transition("MARK_CLEAN", requiresInspection = false) else transition("MARK_DIRTY")

/** O próximo passo de [current] rumo a [target] (no máximo dois pela SPEC §8.4); `null` já chegou. */
private fun stepTowards(
    current: Housekeeping,
    target: Housekeeping,
): JsonObject? =
    when {
        current == target -> null
        target.status == "DIRTY" -> transition("MARK_DIRTY")
        current.status == "DIRTY" && target.status == "CLEAN" -> transition("MARK_CLEAN", target.inspectionPending)
        current.status == "DIRTY" -> transition("MARK_CLEAN", requiresInspection = false)
        target.status == "INSPECTED" -> transition("MARK_INSPECTED")
        else -> transition("MARK_DIRTY")
    }

private fun transition(
    name: String,
    requiresInspection: Boolean? = null,
): JsonObject =
    JsonObject(
        buildMap {
            put("transition", JsonPrimitive(name))
            if (requiresInspection != null) put("requiresInspection", JsonPrimitive(requiresInspection))
        },
    )

/**
 * A Governanta por `curl`: cada chamada passa URL, cabeçalhos e corpo só pela configuração lida da entrada padrão
 * (`curl -K -`), nunca pela linha de comando; o token e as respostas nunca são impressos.
 */
private class HousekeeperCurl(
    private val baseUrl: String,
) {
    private var token: String? = null

    fun login(
        login: String,
        password: String,
    ) {
        val device =
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(UUID.randomUUID().toString()),
                    "name" to JsonPrimitive("LiveRoomMapCheck"),
                ),
            )
        val body =
            JsonObject(
                mapOf("device" to device, "login" to JsonPrimitive(login), "password" to JsonPrimitive(password)),
            )
        val response = call("POST", "/api/v1/auth/login", body = body)
        check(response.code == HTTP_OK) { "login da Governanta recusado: HTTP ${response.code}" }
        token = response.json()["accessToken"]?.jsonPrimitive?.content ?: error("login sem accessToken")
    }

    fun logout() {
        if (token == null) return
        runCatching { call("POST", "/api/v1/auth/logout") }
        token = null
    }

    fun status(roomId: String): Housekeeping = read(roomId).first

    /** Lê o `ETag` pelo `GET` de status e aplica [request]; devolve a versão nova. */
    fun change(
        roomId: String,
        request: JsonObject,
    ): Long {
        val etag = read(roomId).second
        val response = call("POST", "/api/v1/rooms/$roomId/housekeeping-status", ifMatch = etag, body = request)
        check(response.code == HTTP_OK) { "mudança de governança recusada: HTTP ${response.code}" }
        return response
            .json()
            .getValue("version")
            .jsonPrimitive.long
    }

    /** Volta a governança de [roomId] à [original]; se não conseguir, diz qual UH ficou alterada. */
    fun restore(
        roomId: String,
        original: Housekeeping,
    ) {
        try {
            repeat(MAX_RESTORE_STEPS) {
                val step = stepTowards(status(roomId), original) ?: return
                change(roomId, step)
            }
            check(status(roomId) == original) { "governança não voltou" }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            println("LiveRoomMapCheck: UH $roomId ficou alterada; era $original: ${e.message}")
            throw e
        }
    }

    private fun read(roomId: String): Pair<Housekeeping, String> {
        val response = call("GET", "/api/v1/rooms/$roomId/status")
        check(response.code == HTTP_OK) { "status da UH recusado: HTTP ${response.code}" }
        val json = response.json()
        val housekeeping =
            Housekeeping(
                json.getValue("housekeepingStatus").jsonPrimitive.content,
                json.getValue("inspectionPending").jsonPrimitive.boolean,
            )
        return housekeeping to (response.headers["etag"] ?: error("status sem ETag"))
    }

    private fun call(
        method: String,
        path: String,
        ifMatch: String? = null,
        body: JsonObject? = null,
    ): CurlResponse {
        val config =
            buildString {
                appendLine("silent")
                appendLine("show-error")
                appendLine("include")
                appendLine("max-time = $CURL_TIMEOUT_SECONDS")
                appendLine("request = ${quoted(method)}")
                appendLine("url = ${quoted(baseUrl.trimEnd('/') + path)}")
                appendLine("header = ${quoted("Accept: application/json")}")
                token?.let { appendLine("header = ${quoted("Authorization: Bearer $it")}") }
                ifMatch?.let { appendLine("header = ${quoted("If-Match: $it")}") }
                if (body != null) {
                    appendLine("header = ${quoted("Content-Type: application/json")}")
                    appendLine("data-raw = ${quoted(body.toString())}")
                }
                appendLine("write-out = ${quoted("\n%{http_code}")}")
            }
        val process =
            try {
                ProcessBuilder("curl", "-K", "-").start()
            } catch (e: IOException) {
                throw IllegalStateException(
                    "curl não encontrado: a verificação ao vivo do mapa precisa dele no PATH",
                    e,
                )
            }
        process.outputStream.use { it.write(config.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        check(process.waitFor(CURL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "curl não terminou" }
        check(process.exitValue() == 0) { "curl falhou (${process.exitValue()}): ${errors.trim()}" }
        return CurlResponse.parse(output)
    }

    /** Uma string da configuração do `curl`, entre aspas e com `\` e `"` escapados. */
    private fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private companion object {
        const val HTTP_OK = 200
    }
}

private class CurlResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

    companion object {
        /** Cabeçalhos (`include`; o último bloco vale), corpo e, na última linha, o código (`write-out`). */
        fun parse(output: String): CurlResponse {
            val code = output.substringAfterLast('\n').trim().toInt()
            var rest = output.substringBeforeLast('\n')
            var headerBlock = ""
            while (rest.startsWith("HTTP/")) {
                headerBlock = rest.substringBefore("\r\n\r\n")
                rest = rest.substringAfter("\r\n\r\n", "")
            }
            val headers =
                headerBlock
                    .lines()
                    .drop(1)
                    .filter { ':' in it }
                    .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return CurlResponse(code, headers, rest)
        }
    }
}
