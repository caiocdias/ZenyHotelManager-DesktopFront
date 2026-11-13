package com.zeny.desktop.app.rooms

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import java.io.ByteArrayOutputStream
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
        val housekeeper =
            HousekeeperCurl(url.value, env("ZENY_LIVE_HOUSEKEEPER_LOGIN"), env("ZENY_LIVE_HOUSEKEEPER_PASSWORD"))
        housekeeper.login()
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

    /**
     * A Governanta muda uma UH vaga; em até 2 s o ladrilho tem a versão nova **e** mostra o rótulo novo na árvore
     * semântica; grava a captura. O relógio começa quando chegam os últimos bytes da resposta da escrita.
     */
    private fun DesktopComposeUiTest.followChange(
        state: RoomMapState,
        housekeeper: HousekeeperCurl,
    ) {
        val tile = pickVacant(state.floors.flatMap { it.second })
        println("LiveRoomMapCheck: UH ${tile.number} (${tile.roomId})")
        val original = housekeeper.status(tile.roomId)
        restoringAfter({ housekeeper.restore(tile.roomId, original) }) {
            val written = housekeeper.change(tile.roomId, forward(original))
            val label = vacantLabel(written.housekeeping)
            val inTile = hasAnyAncestor(hasTestTag("room-tile-${tile.roomId}"))
            waitUntil(timeoutMillis = CHANGE_TIMEOUT_MILLIS) {
                (state.tile(tile.roomId)?.version ?: -1) >= written.version &&
                    onAllNodes(hasText(label) and inTile, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val elapsed = written.receivedAt.elapsedNow().inWholeMilliseconds
            println("LiveRoomMapCheck: ladrilho em $elapsed ms (versão ${written.version}, rótulo \"$label\")")
            onAllNodes(hasText(tile.label) and inTile, useUnmergedTree = true).assertCountEquals(0)
            capture()
            assertTrue(elapsed <= LIMIT_MILLIS, "o ladrilho levou $elapsed ms (limite $LIMIT_MILLIS ms)")
        }
    }

    /**
     * A queda do `api`, sem tela (o `runTest` de fora do teste de UI tem limite fixo de um minuto). O estado corre numa
     * thread só, como na UI, e é lido nela. Com o `api` de volta e o mapa ainda reconectando, a Governanta muda uma UH:
     * o mapa só a vê se a reconexão recuperar o que perdeu (`reset` e recarga, ou a repetição pelo `Last-Event-ID`).
     * Tudo em até 240 s depois do `PRONTO`; o `Live` antes da escrita falha a verificação.
     */
    @Test
    fun recoversAfterApiRestart() {
        assumeTrue(System.getenv("ZENY_LIVE_OUTAGE") == "1", "sem ZENY_LIVE_OUTAGE=1: queda do api pulada")
        val housekeeper =
            HousekeeperCurl(url.value, env("ZENY_LIVE_HOUSEKEEPER_LOGIN"), env("ZENY_LIVE_HOUSEKEEPER_PASSWORD"))
        housekeeper.login()
        try {
            withViewer { source ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
                    val scope = CoroutineScope(SupervisorJob() + ui)
                    val state = runBlocking(ui) { RoomMapState(source, scope) }
                    try {
                        awaitOn(ui, LOAD_TIMEOUT_MILLIS) { state.isLoadedLive() }
                        assertNull(runBlocking(ui) { state.error }, "o mapa falhou")
                        val tile = pickVacant(runBlocking(ui) { state.floors.flatMap { it.second } })
                        println("LiveRoomMapCheck: UH ${tile.number} (${tile.roomId})")
                        val original = housekeeper.status(tile.roomId)
                        restoringAfter({ housekeeper.restore(tile.roomId, original) }) {
                            outage(ui, state, source, housekeeper, tile)
                        }
                    } finally {
                        runBlocking(ui) { state.close() }
                        scope.cancel()
                    }
                }
            }
        } finally {
            housekeeper.logout()
        }
    }

    private fun outage(
        ui: CoroutineDispatcher,
        state: RoomMapState,
        source: RoomsSource,
        housekeeper: HousekeeperCurl,
        tile: RoomTile,
    ) {
        val started = TimeSource.Monotonic.markNow()
        val remaining = { (OUTAGE_TIMEOUT_MILLIS - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1) }
        println("PRONTO: derrube o api")
        System.out.flush()
        awaitOn(ui, remaining()) { state.live is RealtimeStatus.Reconnecting }
        println("LiveRoomMapCheck: Reconnecting em ${started.elapsedNow().inWholeMilliseconds} ms")
        // O `api` de volta e o mapa ainda fora do ar: a escrita cai na janela que a reconexão precisa recuperar.
        // Fora da thread do estado: o `curl` bloqueia.
        while (!housekeeper.answers(tile.roomId)) {
            check(runBlocking(ui) { state.live } != RealtimeStatus.Live) {
                "o mapa voltou ao vivo antes de o api responder à Governanta: a queda não provou nada, repita"
            }
            check(remaining() > 1) { "o api não voltou em $OUTAGE_TIMEOUT_MILLIS ms" }
            Thread.sleep(POLL_MILLIS)
        }
        println("LiveRoomMapCheck: api respondeu em ${started.elapsedNow().inWholeMilliseconds} ms")
        check(runBlocking(ui) { state.live } != RealtimeStatus.Live) { "o mapa voltou ao vivo antes da escrita" }
        val written = housekeeper.change(tile.roomId, forward(housekeeper.status(tile.roomId)))
        val afterWrite = runBlocking(ui) { state.live }
        val writtenAt = started.elapsedNow().inWholeMilliseconds
        println("LiveRoomMapCheck: UH mudada em $writtenAt ms (versão ${written.version}), mapa $afterWrite")
        check(afterWrite != RealtimeStatus.Live) {
            "o mapa voltou ao vivo antes de a escrita terminar: a queda não provou nada, repita"
        }
        awaitOn(ui, remaining()) { state.live == RealtimeStatus.Live }
        println("LiveRoomMapCheck: Live de novo em ${started.elapsedNow().inWholeMilliseconds} ms")
        awaitOn(ui, remaining()) { (state.tile(tile.roomId)?.version ?: -1) >= written.version }
        println("LiveRoomMapCheck: ladrilho da UH mudada em ${started.elapsedNow().inWholeMilliseconds} ms")
        assertNull(runBlocking(ui) { state.error }, "o mapa ficou com erro depois da queda")
        assertMatchesServer(ui, state, source)
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

    private fun pickVacant(tiles: List<RoomTile>): RoomTile =
        tiles.firstOrNull { it.status == RoomDisplayStatus.DIRTY }
            ?: tiles.firstOrNull { it.status in VACANT_FREE }
            ?: fail("nenhuma UH vaga e sem bloqueio no mapa")

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

/** O rótulo do ladrilho de uma UH vaga e sem bloqueio com a governança [housekeeping]. */
private fun vacantLabel(housekeeping: Housekeeping): String =
    when {
        housekeeping.status == "DIRTY" -> RoomDisplayStatus.DIRTY
        housekeeping.status == "INSPECTED" -> RoomDisplayStatus.INSPECTED
        housekeeping.inspectionPending -> RoomDisplayStatus.CLEAN_PENDING_INSPECTION
        else -> RoomDisplayStatus.CLEAN
    }.label

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
 * Roda [block] e depois [restore], também quando [block] falha; a falha da restauração vai para os `suppressed` da
 * falha de [block], sem escondê-la.
 */
private inline fun <T> restoringAfter(
    restore: () -> Unit,
    block: () -> T,
): T {
    val result =
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            try {
                restore()
            } catch (
                @Suppress("TooGenericExceptionCaught") restoreFailure: Throwable,
            ) {
                failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
    restore()
    return result
}

/** A resposta de uma escrita: a versão e a governança novas e o instante em que chegaram os últimos bytes. */
private class Written(
    val version: Long,
    val housekeeping: Housekeeping,
    val receivedAt: TimeSource.Monotonic.ValueTimeMark,
)

/**
 * A Governanta por `curl`: cada chamada passa URL, cabeçalhos e corpo só pela configuração lida da entrada padrão
 * (`curl -q -K -`: sem o `~/.curlrc`, que poderia ligar `verbose` ou `trace`), nunca pela linha de comando. O `curl`
 * roda com ambiente enxuto (sem as senhas do ambiente do teste) e com o `stderr` descartado; o token e as respostas
 * nunca são impressos, e as falhas dizem só a etapa e o código.
 */
private class HousekeeperCurl(
    private val baseUrl: String,
    private val login: String,
    private val password: String,
) {
    private var token: String? = null

    override fun toString() = "HousekeeperCurl($login)"

    fun login() {
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
        token = null
        val response = call("login", "POST", "/api/v1/auth/login", body = body)
        check(response.code == HTTP_OK) { "login da Governanta recusado: HTTP ${response.code}" }
        token = response.json()["accessToken"]?.jsonPrimitive?.content ?: error("login sem accessToken")
    }

    fun logout() {
        if (token == null) return
        runCatching { call("logout", "POST", "/api/v1/auth/logout") }
        token = null
    }

    /**
     * O `api` responde ao status da UH? Durante a queda, o proxy devolve erro ou o `curl` falha: `false`. Um `401`
     * (token recusado depois do reinício) refaz o login.
     */
    fun answers(roomId: String): Boolean {
        val code =
            try {
                call("sondagem", "GET", "/api/v1/rooms/$roomId/status").code
            } catch (
                @Suppress("SwallowedException") e: RuntimeException,
            ) {
                // O `api` ainda fora: o `curl` falhou ou a resposta não veio inteira.
                return false
            }
        if (code == HTTP_UNAUTHORIZED) {
            println("LiveRoomMapCheck: token da Governanta recusado depois do reinício; novo login")
            runCatching { login() }
        }
        return code == HTTP_OK
    }

    fun status(roomId: String): Housekeeping = read(roomId).first

    /** Lê o `ETag` pelo `GET` de status e aplica [request]. */
    fun change(
        roomId: String,
        request: JsonObject,
    ): Written {
        val etag = read(roomId).second
        val response =
            call("mudança", "POST", "/api/v1/rooms/$roomId/housekeeping-status", ifMatch = etag, body = request)
        check(response.code == HTTP_OK) { "mudança de governança recusada: HTTP ${response.code}" }
        val json = response.json()
        return Written(json.getValue("version").jsonPrimitive.long, housekeepingOf(json), response.receivedAt)
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
        val response = call("status", "GET", "/api/v1/rooms/$roomId/status")
        check(response.code == HTTP_OK) { "status da UH recusado: HTTP ${response.code}" }
        return housekeepingOf(response.json()) to (response.headers["etag"] ?: error("status sem ETag"))
    }

    private fun housekeepingOf(json: JsonObject) =
        Housekeeping(
            json.getValue("housekeepingStatus").jsonPrimitive.content,
            json.getValue("inspectionPending").jsonPrimitive.boolean,
        )

    private fun call(
        step: String,
        method: String,
        path: String,
        ifMatch: String? = null,
        body: JsonObject? = null,
    ): CurlResponse {
        val config =
            buildString {
                appendLine("silent")
                appendLine("include")
                // Sem buffer: o `write-out` sai logo depois do fim da resposta, não na saída do processo.
                appendLine("no-buffer")
                appendLine("connect-timeout = $CURL_CONNECT_TIMEOUT_SECONDS")
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
        val builder = ProcessBuilder("curl", "-q", "-K", "-").redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().apply {
            val kept = KEPT_ENVIRONMENT.associateWith { System.getenv(it) }
            clear()
            kept.forEach { (name, value) -> if (value != null) put(name, value) }
        }
        val process =
            try {
                builder.start()
            } catch (e: IOException) {
                throw IllegalStateException(
                    "curl não encontrado: a verificação ao vivo do mapa precisa dele no PATH",
                    e,
                )
            }
        process.outputStream.use { it.write(config.toByteArray()) }
        val (output, receivedAt) = readAll(process)
        check(process.waitFor(CURL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "curl não terminou ($step)" }
        check(process.exitValue() == 0) { "curl falhou na etapa $step (código ${process.exitValue()})" }
        return CurlResponse.parse(output, receivedAt)
    }

    /** Lê a saída inteira e marca quando chegou o último trecho (o `write-out`, logo depois do fim da resposta). */
    private fun readAll(process: Process): Pair<String, TimeSource.Monotonic.ValueTimeMark> {
        val bytes = ByteArrayOutputStream()
        var last = TimeSource.Monotonic.markNow()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        process.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                last = TimeSource.Monotonic.markNow()
                bytes.write(buffer, 0, read)
            }
        }
        return bytes.toString(Charsets.UTF_8) to last
    }

    /** Uma string da configuração do `curl`, entre aspas e com `\` e `"` escapados. */
    private fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val READ_BUFFER_BYTES = 8192
        const val CURL_CONNECT_TIMEOUT_SECONDS = 5

        /** O mínimo para achar e rodar o `curl`; nada de credencial. */
        val KEPT_ENVIRONMENT = listOf("PATH", "HOME")
    }
}

private class CurlResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: String,
    val receivedAt: TimeSource.Monotonic.ValueTimeMark,
) {
    fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

    companion object {
        /** Cabeçalhos (`include`; o último bloco vale), corpo e, na última linha, o código (`write-out`). */
        fun parse(
            output: String,
            receivedAt: TimeSource.Monotonic.ValueTimeMark,
        ): CurlResponse {
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
            return CurlResponse(code, headers, rest, receivedAt)
        }
    }
}
