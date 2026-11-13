@file:OptIn(RawSdkAccess::class, ExperimentalUuidApi::class)

package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.RawSdkAccess
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.http.CursorPager
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.sdk.model.CreateUserRequest
import com.zeny.desktop.sdk.model.ResetPasswordRequest
import com.zeny.desktop.sdk.model.ResetPinRequest
import com.zeny.desktop.sdk.model.RoleResponse
import com.zeny.desktop.sdk.model.SetUserRolesRequest
import com.zeny.desktop.seed.cli.Secret
import com.zeny.desktop.seed.people.UserGateway
import com.zeny.desktop.seed.people.UserRef
import de.quati.kotlin.util.Option
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val PAGE_SIZE = 200
private const val PRECONDITION_REQUIRED = 428

/**
 * Usuários e perfis sobre o `sdk`. As operações usadas não levam `Idempotency-Key` no contrato; o admin tem
 * todas as permissões, então nenhuma pede token de aprovação (se pedir, o `403` sobe como `ApiError`).
 */
@Suppress("TooManyFunctions") // porta que espelha as operações do contrato usadas pelo seed
class SdkUserGateway(
    private val seed: SeedCore,
) : UserGateway {
    override suspend fun roleIdsByTemplate(): Map<String, String> =
        allPages { cursor, limit ->
            val page = seed.executor.execute { seed.apis.iam.listRoles(cursor, limit, it.headers) }.value
            Page(page.items, page.nextCursor.value())
        }.filter(RoleResponse::active)
            .mapNotNull { role -> role.template.value()?.let { it.value to role.id.toString() } }
            .toMap()

    override suspend fun findUser(login: String): UserRef? =
        allPages { cursor, limit ->
            val page = seed.executor.execute { seed.apis.iam.listUsers(cursor, limit, it.headers) }.value
            Page(page.items, page.nextCursor.value())
        }.firstOrNull { it.login.equals(login, ignoreCase = true) }
            ?.let { UserRef(it.id.toString(), it.active, it.roles.map { role -> role.id.toString() }.toSet()) }

    override suspend fun createUser(
        login: String,
        name: String,
        provisional: Secret,
        roleIds: List<String>,
        pin: Secret?,
    ): String {
        val request =
            CreateUserRequest(
                login = login,
                name = name,
                password = provisional.value,
                pin = pin?.let { Option.Some(it.value) } ?: Option.Undefined,
                roleIds = roleIds.map(Uuid::parse),
            )
        return redacting(provisional, pin) {
            seed.executor
                .execute { seed.apis.iam.createUser(null, request, it.headers) }
                .value.id
                .toString()
        }
    }

    override suspend fun reactivate(userId: String) {
        val id = Uuid.parse(userId)
        seed.executor.execute { seed.apis.iam.reactivateUser(id, null, it.headers) }
    }

    /** O `If-Match` vem do `ETag` de uma leitura nova do usuário. */
    override suspend fun setRoles(
        userId: String,
        roleIds: List<String>,
    ) {
        val id = Uuid.parse(userId)
        val etag =
            seed.executor
                .execute { seed.apis.iam.getUser(id, it.headers) }
                .etag
                ?.takeIf { it.isNotBlank() }
        val request = SetUserRolesRequest(roleIds.map(Uuid::parse))
        if (etag == null) {
            // sem a precondição o servidor responderia 428; nem se tenta a escrita
            throw ApiError.Problem(
                PRECONDITION_REQUIRED,
                "PRECONDITION_REQUIRED",
                "GET do usuário sem ETag",
                emptyList(),
            )
        }
        seed.executor.execute { seed.apis.iam.setUserRoles(id, etag, null, request, it.headers) }
    }

    override suspend fun resetPassword(
        userId: String,
        provisional: Secret,
    ) {
        val id = Uuid.parse(userId)
        val request = ResetPasswordRequest(provisional.value)
        redacting(provisional) {
            seed.executor.execute { seed.apis.iam.resetUserPassword(id, null, request, it.headers) }
        }
    }

    override suspend fun resetPin(
        userId: String,
        pin: Secret,
    ) {
        val id = Uuid.parse(userId)
        val request = ResetPinRequest(pin.value)
        redacting(pin) { seed.executor.execute { seed.apis.iam.resetUserPin(id, null, request, it.headers) } }
    }
}

private fun <T> Option<T?>.value(): T? = (this as? Option.Some)?.value

private suspend fun <I> allPages(fetch: suspend (cursor: String?, limit: Int) -> Page<I>): List<I> {
    val pager = CursorPager(PAGE_SIZE, fetch)
    val all = mutableListOf<I>()
    while (pager.hasMore) all += pager.next()
    return all
}

private suspend fun <T> redacting(
    vararg secrets: Secret?,
    block: suspend () -> T,
): T = redactingValues(secrets.mapNotNull { it?.value }, block)
