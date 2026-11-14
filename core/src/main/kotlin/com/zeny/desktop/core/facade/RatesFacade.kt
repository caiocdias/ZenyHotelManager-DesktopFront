package com.zeny.desktop.core.facade

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.sdk.client.RatesApi
import com.zeny.desktop.sdk.model.RatePlanResponse
import kotlin.uuid.Uuid

/** A leitura de um plano tarifário. Um `id` que não é UUID lança `IllegalArgumentException` antes de ir à rede. */
class RatesFacade internal constructor(
    private val rates: RatesApi,
    private val executor: ApiExecutor,
) {
    suspend fun ratePlan(id: String): RatePlanResponse {
        val planId = Uuid.parseOrNull(id) ?: throw IllegalArgumentException("id de plano tarifário inválido")
        return executor.execute { rates.getRatePlan(planId, it.headers) }.value
    }
}
