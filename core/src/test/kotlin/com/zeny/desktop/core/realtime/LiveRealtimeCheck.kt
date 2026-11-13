package com.zeny.desktop.core.realtime

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.sdk.model.HousekeepingStatusRequest
import com.zeny.desktop.sdk.model.HousekeepingStatusRequest.Transition
import com.zeny.desktop.sdk.model.RoomStatusResponse
import com.zeny.desktop.sdk.model.RoomStatusResponse.HousekeepingStatus
import com.zeny.desktop.sdk.model.RoomStatusResponse.Occupancy
import de.quati.kotlin.util.Option
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private const val ROOMS_TOPIC = "rooms"
private const val ROOM_STATUS_CHANGED = "rooms.room-status"
private const val LIMIT_MILLIS = 2_000L
private const val ROUNDS = 3
private const val MAX_RESTORE_STEPS = 3
private val STEP_TIMEOUT = 30.seconds

/**
 * Verificação ao vivo do tempo real: a Recepcionista (sessão A) ouve o tópico `rooms` e a Governanta (sessão B) muda
 * a governança de uma UH vaga; do fim da resposta da escrita até A ver a `Message` dessa UH **e** a rebusca refletir
 * a nova versão, no máximo 2 s. Repete [ROUNDS] vezes e restaura o status num `finally`.
 *
 * Pulada sem o opt-in `ZENY_LIVE_REALTIME=1`; com ele, exige `ZENY_LIVE_URL`, `ZENY_LIVE_VIEWER_LOGIN`/`PASSWORD` e
 * `ZENY_LIVE_HOUSEKEEPER_LOGIN`/`PASSWORD` (credenciais só do ambiente, nunca impressas). Comando, só num subshell:
 * `(set -a; . <arquivo>; set +a; ZENY_LIVE_REALTIME=1 ./gradlew :core:test --tests '*LiveRealtimeCheck*' --rerun)`
 */
@OptIn(RawSdkAccess::class)
class LiveRealtimeCheck {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("ZENY_LIVE_REALTIME") == "1", "sem ZENY_LIVE_REALTIME=1: verificação real pulada")
    }

    @Test
    fun propagationUnderTwoSeconds() {
        val url = ServerUrl.parse(env("ZENY_LIVE_URL")) ?: fail("ZENY_LIVE_URL inválida")
        val viewer = ZenyCore(url, MemorySettings())
        val housekeeper = ZenyCore(url, MemorySettings())
        try {
            runBlocking {
                viewer.session.login(env("ZENY_LIVE_VIEWER_LOGIN"), env("ZENY_LIVE_VIEWER_PASSWORD"))
                housekeeper.session.login(env("ZENY_LIVE_HOUSEKEEPER_LOGIN"), env("ZENY_LIVE_HOUSEKEEPER_PASSWORD"))
                val room = pickVacantRoom(viewer)
                val roomId = room.roomId.toString()
                println("LiveRealtimeCheck: UH ${room.number} ($roomId)")
                // A maior versão anunciada para a UH, desde antes da escrita: a mensagem pode chegar antes da resposta.
                val announced = MutableStateFlow(-1L)
                val ready = CompletableDeferred<Unit>()
                val listener =
                    launch {
                        viewer.realtime.subscribe(setOf(ROOMS_TOPIC)).collect { event ->
                            when {
                                event == RealtimeEvent.Ready || event == RealtimeEvent.Reset -> {
                                    ready.complete(Unit)
                                }

                                event is RealtimeEvent.Message && event.type == ROOM_STATUS_CHANGED &&
                                    event.id == roomId -> {
                                    announced.update { maxOf(it, event.version) }
                                }
                            }
                        }
                    }
                try {
                    withTimeout(STEP_TIMEOUT) { ready.await() }
                    val times = (1..ROUNDS).map { round -> measure(viewer, housekeeper, roomId, announced, round) }
                    println("LiveRealtimeCheck: tempos (ms) = $times")
                    times.forEach { assertTrue(it <= LIMIT_MILLIS, "propagação de $it ms passou de $LIMIT_MILLIS ms") }
                } finally {
                    listener.cancel()
                }
            }
        } finally {
            runBlocking {
                runCatching { viewer.session.logout() }
                runCatching { housekeeper.session.logout() }
            }
            viewer.close()
            housekeeper.close()
        }
    }

    /** Uma rodada: a escrita de B, medida até A ver a mensagem e a rebusca trazer a versão escrita. */
    private suspend fun measure(
        viewer: ZenyCore,
        housekeeper: ZenyCore,
        roomId: String,
        announced: StateFlow<Long>,
        round: Int,
    ): Long {
        val original = statusOf(housekeeper, roomId)
        try {
            val (newVersion, writeEnd) = change(housekeeper, roomId, forward(original))
            withTimeout(STEP_TIMEOUT) { announced.first { it >= newVersion } }
            val refetched = viewer.rooms.status(roomId)
            val elapsed = writeEnd.elapsedNow().inWholeMilliseconds
            assertTrue(refetched.version >= newVersion, "rebusca sem a versão $newVersion")
            println("LiveRealtimeCheck: rodada $round: $elapsed ms (versão $newVersion)")
            return elapsed
        } finally {
            restore(housekeeper, roomId, original)
        }
    }

    private fun forward(status: RoomStatusResponse): HousekeepingStatusRequest =
        when (status.housekeepingStatus) {
            HousekeepingStatus.DIRTY -> {
                HousekeepingStatusRequest(Option.Some(false), Transition.MARK_CLEAN)
            }

            HousekeepingStatus.CLEAN, HousekeepingStatus.INSPECTED -> {
                HousekeepingStatusRequest(transition = Transition.MARK_DIRTY)
            }
        }

    /** O próximo passo de [current] rumo a [target] (no máximo dois passos pela SPEC §8.4); `null` já chegou. */
    private fun stepTowards(
        current: RoomStatusResponse,
        target: RoomStatusResponse,
    ): HousekeepingStatusRequest? {
        val same =
            current.housekeepingStatus == target.housekeepingStatus &&
                current.inspectionPending == target.inspectionPending
        if (same) return null
        return when (target.housekeepingStatus) {
            HousekeepingStatus.DIRTY -> {
                HousekeepingStatusRequest(transition = Transition.MARK_DIRTY)
            }

            HousekeepingStatus.CLEAN -> {
                if (current.housekeepingStatus == HousekeepingStatus.DIRTY) {
                    HousekeepingStatusRequest(Option.Some(target.inspectionPending), Transition.MARK_CLEAN)
                } else {
                    HousekeepingStatusRequest(transition = Transition.MARK_DIRTY)
                }
            }

            HousekeepingStatus.INSPECTED -> {
                if (current.housekeepingStatus == HousekeepingStatus.DIRTY) {
                    HousekeepingStatusRequest(Option.Some(false), Transition.MARK_CLEAN)
                } else {
                    HousekeepingStatusRequest(transition = Transition.MARK_INSPECTED)
                }
            }
        }
    }

    /** Volta a governança de [roomId] à de [original]; se não conseguir, diz qual UH ficou alterada. */
    private suspend fun restore(
        housekeeper: ZenyCore,
        roomId: String,
        original: RoomStatusResponse,
    ) {
        try {
            repeat(MAX_RESTORE_STEPS) {
                val step = stepTowards(statusOf(housekeeper, roomId), original) ?: return
                change(housekeeper, roomId, step)
            }
            val final = statusOf(housekeeper, roomId)
            check(stepTowards(final, original) == null) { "governança não voltou" }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            println(
                "LiveRealtimeCheck: UH ${original.number} ($roomId) ficou alterada; " +
                    "era ${original.housekeepingStatus}, inspeção pendente ${original.inspectionPending}: " +
                    "${e::class.simpleName}",
            )
            throw e
        }
    }

    private suspend fun statusOf(
        core: ZenyCore,
        roomId: String,
    ): RoomStatusResponse =
        core.executor.execute { core.apis.rooms.getRoomStatus(Uuid.parse(roomId), it.headers) }.value

    /** Lê o `ETag` e escreve; devolve a versão nova e o instante do fim da resposta. */
    private suspend fun change(
        core: ZenyCore,
        roomId: String,
        request: HousekeepingStatusRequest,
    ): Pair<Long, TimeSource.Monotonic.ValueTimeMark> {
        val id = Uuid.parse(roomId)
        val etag = core.executor.execute { core.apis.rooms.getRoomStatus(id, it.headers) }.etag
        val changed =
            core.executor.execute {
                core.apis.rooms.changeRoomHousekeepingStatus(id, etag, request, it.headers)
            }
        return changed.value.version to TimeSource.Monotonic.markNow()
    }

    /** Uma UH ativa, vaga e sem bloqueio, de preferência suja (um passo para ir, um para voltar). */
    private suspend fun pickVacantRoom(viewer: ZenyCore): RoomStatusResponse {
        val candidates =
            viewer.rooms.statuses().filter {
                it.active && it.occupancy == Occupancy.VACANT && (it.outOfOrder as? Option.Some)?.value == null
            }
        return candidates.firstOrNull { it.housekeepingStatus == HousekeepingStatus.DIRTY }
            ?: candidates.firstOrNull()
            ?: fail("nenhuma UH vaga e sem bloqueio no servidor")
    }

    private fun env(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fail("defina $name para a verificação real")
}
