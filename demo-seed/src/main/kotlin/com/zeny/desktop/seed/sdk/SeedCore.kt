package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.Apis
import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.http.ApiExecutor
import io.ktor.client.engine.HttpClientEngine

/**
 * O `ZenyCore` do seed: preferências só em memória (`MemorySettings`, com `deviceId` próprio) e, sem `engine`,
 * o engine padrão do `core`. É o único ponto do `demo-seed` que abre o acesso cru ao `sdk` (ADR 0008), junto
 * dos `Sdk*Gateway`.
 */
@OptIn(RawSdkAccess::class)
class SeedCore(
    server: ServerUrl,
    engine: HttpClientEngine? = null,
) : AutoCloseable {
    val core: ZenyCore =
        if (engine == null) ZenyCore(server, MemorySettings()) else ZenyCore(server, MemorySettings(), engine)
    val apis: Apis get() = core.apis
    val executor: ApiExecutor get() = core.executor

    override fun close() = core.close()
}
