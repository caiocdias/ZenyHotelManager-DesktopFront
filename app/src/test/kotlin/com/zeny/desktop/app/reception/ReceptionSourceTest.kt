package com.zeny.desktop.app.reception

import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.config.ServerUrl
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.sdk.model.GuestDocumentResponse.Type
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReceptionSourceTest {
    private class MemorySettings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = UUID.randomUUID().toString()
    }

    /** Uma conexão que só existe para provar o padrão da interface: nada além de `close` é usado. */
    private class BareConnection : ServerConnection {
        override val state: StateFlow<SessionState> get() = error("não usado")

        override suspend fun login(
            login: String,
            password: String,
        ) = error("não usado")

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = error("não usado")

        override suspend fun logout() = error("não usado")

        override suspend fun me(): Me = error("não usado")

        override suspend fun hotel(): HotelInfo = error("não usado")

        override suspend fun businessDate(): BusinessDate = error("não usado")

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun close() = Unit
    }

    @Test
    fun summaryPrefersSocialName() {
        assertEquals("Mari", guestOf("Maria da Silva", socialName = "Mari").toHolderSummary().name)
    }

    @Test
    fun summaryFallsBackToFullNameWhenSocialBlank() {
        assertEquals("Maria da Silva", guestOf("Maria da Silva", socialName = "  ").toHolderSummary().name)
        assertEquals("Maria da Silva", guestOf("Maria da Silva").toHolderSummary().name)
    }

    @Test
    fun summaryUsesFirstMaskedDocument() {
        val guest =
            guestOf(
                "Maria da Silva",
                documents = listOf(Type.CPF to "***.***.***-34", Type.RG to "**.***.789-0"),
            )

        assertEquals("CPF ***.***.***-34", guest.toHolderSummary().document)
    }

    @Test
    fun summaryWithoutDocumentIsNull() {
        assertNull(guestOf("Maria da Silva").toHolderSummary().document)
    }

    @Test
    fun summaryToStringHidesData() {
        assertEquals("HolderSummary(***)", HolderSummary("Maria da Silva", "CPF ***.***.***-34").toString())
    }

    @Test
    fun coreConnectionOffersReceptionSource() =
        runTest {
            val core = ZenyCore(checkNotNull(ServerUrl.parse("http://servidor.teste")), MemorySettings())
            CoreConnection(core).use { connection ->
                val source = assertNotNull(connection.receptionSource())

                // O id é validado pela fachada do `core`, antes de ir à rede.
                assertFailsWith<IllegalArgumentException> { source.reservation("não-é-uuid") }
                assertFailsWith<IllegalArgumentException> { source.holder("não-é-uuid") }
            }
        }

    @Test
    fun defaultConnectionHasNoReceptionSource() {
        assertNull(BareConnection().receptionSource())
    }
}
