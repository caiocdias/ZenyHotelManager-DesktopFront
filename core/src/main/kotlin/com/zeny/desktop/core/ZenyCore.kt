package com.zeny.desktop.core

import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.facade.AccountFacade
import com.zeny.desktop.core.facade.ApprovalFacade
import com.zeny.desktop.core.facade.HotelFacade
import com.zeny.desktop.core.facade.RoomsFacade
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.core.realtime.RealtimeClient
import com.zeny.desktop.core.session.AuthPlugin
import com.zeny.desktop.core.session.Authenticator
import com.zeny.desktop.core.session.IamTokenRefresher
import com.zeny.desktop.core.session.SessionManager
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.session.TokenStore
import com.zeny.desktop.sdk.client.FrontdeskApi
import com.zeny.desktop.sdk.client.GuestsApi
import com.zeny.desktop.sdk.client.HotelApi
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.client.MetaApi
import com.zeny.desktop.sdk.client.RatesApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.client.RoomsApi
import com.zeny.desktop.sdk.client.StreamApi
import de.quati.ogen.client.ktor.HttpClientOgen
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * O `core` montado para um servidor: cliente HTTP com o `AuthPlugin`, as operações geradas, o voo único do refresh,
 * a sessão e o tempo real. Trocar de servidor é criar outro `ZenyCore` e, no antigo, `session.logout()` e
 * `close()`. O `ZenyCore` é dono do `engine`, que o `close()` fecha.
 */
class ZenyCore internal constructor(
    val serverUrl: ServerUrl,
    settings: LocalSettings,
    private val engine: HttpClientEngine,
    dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    @Suppress("InjectDispatcher") // Refresh e stream são E/S; só os testes do `core` trocam o dispatcher.
    constructor(
        serverUrl: ServerUrl,
        settings: LocalSettings,
        engine: HttpClientEngine = Java.create(),
    ) : this(serverUrl, settings, engine, Dispatchers.IO)

    /**
     * Escopo do voo único do refresh e das conexões do tempo real; o `SupervisorJob` isola a falha de um job dos
     * demais.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = TokenStore()

    /** O `AuthPlugin` pede o `Authenticator` a cada requisição, então o cliente nasce antes dele. */
    private val http: HttpClient = createCoreHttpClient(engine) { install(AuthPlugin) { authenticator = { auth } } }
    internal val sdkApis = Apis(ogenBase(http, serverUrl.value))
    internal val sdkExecutor = ApiExecutor()
    private val auth: Authenticator =
        Authenticator(store, IamTokenRefresher(sdkApis.iam, sdkExecutor), scope) { code, sessionId ->
            session.sessionEnded(code, sessionId)
        }

    val session: SessionManager = SessionManager(sdkApis.iam, sdkExecutor, store, auth, settings)

    val account: AccountFacade = AccountFacade(sdkApis.iam, sdkExecutor)
    val hotel: HotelFacade = HotelFacade(sdkApis.hotel, sdkExecutor)
    val approvals: ApprovalFacade = ApprovalFacade(sdkApis.iam, sdkExecutor)
    val rooms: RoomsFacade = RoomsFacade(sdkApis.rooms, sdkExecutor)

    /**
     * O stream da sessão corrente: cada conexão leva o `Bearer` da sessão aberta quando ela abre. Sair de uma sessão
     * (logout, `Ended` ou troca de `sessionId`) encerra as assinaturas abertas sob ela.
     */
    val realtime: RealtimeClient = RealtimeClient(sdkApis.stream, scope) { session.state.value.sessionId() }

    init {
        // Síncrono, na própria transição: nenhuma sessão que sai escapa, nem a que o `StateFlow` juntaria. Ordem de
        // lock: sessão → tempo real; a sessão corrente acima é lida sem lock, então o inverso nunca acontece.
        session.onSessionExit = realtime::endSession
    }

    private fun SessionState.sessionId(): String? = (this as? SessionState.Active)?.sessionId

    /** Executa as operações cruas; as features usam as fachadas do `core` (ADR 0008). */
    @RawSdkAccess
    val executor: ApiExecutor get() = sdkExecutor

    /** As operações geradas do `sdk`; as features usam as fachadas do `core` (ADR 0008). */
    @RawSdkAccess
    val apis: Apis get() = sdkApis

    /**
     * Fecha localmente: pede o cancelamento do escopo do refresh e do tempo real, sem esperar que os jobs terminem,
     * e fecha o cliente e o `engine`. As assinaturas completam e os corpos abertos do stream se soltam à medida que
     * os jobs cancelados terminam, o que pode ser depois de o `close()` voltar. Não faz logout nem avisa o
     * servidor; para trocar de servidor, chame `session.logout()` e depois `close()`. Chamadas feitas depois do
     * `close()`, ou em curso durante ele, terminam em `CancellationException`, não em `ApiError`.
     */
    override fun close() {
        scope.cancel()
        http.close()
        engine.close()
    }
}

/** As `<Tag>Api` geradas usadas pelo `core` e pelo `demo-seed`, sobre a mesma base, criadas no primeiro uso. */
class Apis(
    base: HttpClientOgen,
) {
    val iam: IamApi by lazy { IamApi(base) }
    val meta: MetaApi by lazy { MetaApi(base) }
    val hotel: HotelApi by lazy { HotelApi(base) }
    val rooms: RoomsApi by lazy { RoomsApi(base) }
    val frontdesk: FrontdeskApi by lazy { FrontdeskApi(base) }
    val reservations: ReservationsApi by lazy { ReservationsApi(base) }
    val stream: StreamApi by lazy { StreamApi(base) }
    val rates: RatesApi by lazy { RatesApi(base) }
    val guests: GuestsApi by lazy { GuestsApi(base) }
}
