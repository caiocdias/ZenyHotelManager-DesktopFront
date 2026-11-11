package com.zeny.desktop.core

import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.core.http.createCoreHttpClient
import com.zeny.desktop.core.http.ogenBase
import com.zeny.desktop.core.session.AuthPlugin
import com.zeny.desktop.core.session.Authenticator
import com.zeny.desktop.core.session.IamTokenRefresher
import com.zeny.desktop.core.session.SessionManager
import com.zeny.desktop.core.session.TokenStore
import com.zeny.desktop.sdk.client.FrontdeskApi
import com.zeny.desktop.sdk.client.HotelApi
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.client.MetaApi
import com.zeny.desktop.sdk.client.ReservationsApi
import com.zeny.desktop.sdk.client.RoomsApi
import com.zeny.desktop.sdk.client.StreamApi
import de.quati.ogen.client.ktor.HttpClientOgen
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * O `core` montado para um servidor: cliente HTTP com o `AuthPlugin`, as operações geradas, o voo único do refresh
 * e a sessão. Trocar de servidor é criar outro `ZenyCore` e fechar o antigo. O `ZenyCore` é dono do `engine`:
 * `close()` cancela o escopo do refresh e fecha o cliente e o `engine`.
 */
class ZenyCore(
    val serverUrl: ServerUrl,
    settings: LocalSettings,
    private val engine: HttpClientEngine = Java.create(),
) : AutoCloseable {
    /** Escopo do voo único do refresh; o `SupervisorJob` isola a falha de um refresh dos seguintes. */
    @Suppress("InjectDispatcher") // O refresh é E/S e o dispatcher não varia: os testes trocam o engine, não o escopo.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = TokenStore()

    /** O `AuthPlugin` pede o `Authenticator` a cada requisição, então o cliente nasce antes dele. */
    private val http: HttpClient = createCoreHttpClient(engine) { install(AuthPlugin) { authenticator = { auth } } }
    private val sdkApis = Apis(ogenBase(http, serverUrl.value))
    private val sdkExecutor = ApiExecutor()
    private val auth: Authenticator =
        Authenticator(store, IamTokenRefresher(sdkApis.iam, sdkExecutor), scope) { code, sessionId ->
            session.sessionEnded(code, sessionId)
        }

    val session: SessionManager = SessionManager(sdkApis.iam, sdkExecutor, store, auth, settings)

    /** Executa as operações cruas; as features usam as fachadas do `core` (ADR 0008). */
    @RawSdkAccess
    val executor: ApiExecutor get() = sdkExecutor

    /** As operações geradas do `sdk`; as features usam as fachadas do `core` (ADR 0008). */
    @RawSdkAccess
    val apis: Apis get() = sdkApis

    override fun close() {
        scope.cancel()
        http.close()
        engine.close()
    }
}

/** As `<Tag>Api` geradas usadas até a E06, sobre a mesma base, criadas no primeiro uso. */
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
}
