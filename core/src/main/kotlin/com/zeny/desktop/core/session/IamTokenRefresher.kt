package com.zeny.desktop.core.session

import com.zeny.desktop.core.http.ApiExecutor
import com.zeny.desktop.sdk.client.IamApi
import com.zeny.desktop.sdk.model.RefreshRequest
import com.zeny.desktop.sdk.model.TokenResponse

/** Troca o par em `POST /auth/refresh`; só o voo único do `Authenticator` o chama. */
class IamTokenRefresher(
    private val iam: IamApi,
    private val executor: ApiExecutor,
) : TokenRefresher {
    override suspend fun refresh(current: Tokens): Tokens =
        executor.execute { iam.refreshTokens(RefreshRequest(current.refreshToken), it.headers) }.value.toTokens()
}

internal fun TokenResponse.toTokens(): Tokens =
    Tokens(
        accessToken = accessToken,
        refreshToken = refreshToken,
        sessionId = sessionId.toString(),
        userId = userId.toString(),
        passwordChangeRequired = passwordChangeRequired,
    )
