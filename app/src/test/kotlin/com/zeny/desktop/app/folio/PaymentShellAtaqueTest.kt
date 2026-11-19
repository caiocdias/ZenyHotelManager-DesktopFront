package com.zeny.desktop.app.folio

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AppController
import com.zeny.desktop.app.AppRoot
import com.zeny.desktop.app.cashier.CashierSource
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reception.ReceptionSource
import com.zeny.desktop.app.reservations.confirmDetail
import com.zeny.desktop.app.reservations.searchItemOf
import com.zeny.desktop.app.session.ServerConnection
import com.zeny.desktop.core.config.LocalSettings
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.ApprovalInput
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.HotelInfo
import com.zeny.desktop.core.facade.Me
import com.zeny.desktop.core.facade.ProbeResult
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.core.types.BusinessDate
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.test.Test
import kotlin.uuid.Uuid

private val ARRIVAL = Uuid.parse("20000000-0000-0000-0000-0000000000c1")
private val SHELL_PERMISSIONS =
    setOf(
        "frontdesk.stays.checkin",
        "billing.folios.read",
        "billing.payments.receive",
        "billing.cashier.operate",
    )

/**
 * Ataque da etapa ao atalho "Abrir turno" pela casca inteira (`AppRoot`): a lambda que a casca dá à Recepção abre a
 * área "Contas e caixa". O teste de `AreaContent` passa a lambda dele mesmo e não prova a da casca.
 */
@OptIn(ExperimentalTestApi::class)
class PaymentShellAtaqueTest {
    private class Settings : LocalSettings {
        override var serverUrl: String? = null
        override var lastLogin: String? = null
        override val deviceId: String = "device-1"
    }

    private class Fake(
        private val reception: ReceptionSource,
        private val folios: FolioSource,
        private val payments: PaymentSource,
        private val cashier: CashierSource,
    ) : ServerConnection {
        override val state = MutableStateFlow<SessionState>(SessionState.LoggedOut)

        override suspend fun login(
            login: String,
            password: String,
        ) {
            state.value = SessionState.Active("u-1", "s-1", false)
        }

        override suspend fun changePassword(
            current: String,
            new: String,
        ) = error("não usado")

        override suspend fun retryPasswordChangeRefresh() = Unit

        override suspend fun logout() {
            state.value = SessionState.LoggedOut
        }

        override suspend fun me(): Me = Me("u-1", "ana", "Ana Souza", SHELL_PERMISSIONS)

        override suspend fun hotel(): HotelInfo = HotelInfo("Hotel Mar Azul", configured = true)

        override suspend fun businessDate(): BusinessDate = BusinessDate(LocalDate.of(2026, 11, 18))

        override suspend fun grant(
            required: ApiError.ApprovalRequired,
            input: ApprovalInput,
        ): String = error("não usado")

        override fun receptionSource(): ReceptionSource = reception

        override fun folioSource(): FolioSource = folios

        override fun paymentSource(): PaymentSource = payments

        override fun cashierSource(): CashierSource = cashier

        override fun close() = Unit
    }

    @Test
    fun openShiftFromReceptionInsideTheShellOpensTheAccountsArea() =
        runComposeUiTest {
            val desk =
                FakeReceptionSource().apply {
                    permissions = permissions + SHELL_PERMISSIONS
                    pages[DayList.ARRIVALS to null] = Page(listOf(searchItemOf("R-1").copy(id = ARRIVAL)), null)
                    reservations[ARRIVAL.toString()] =
                        confirmDetail(ARRIVAL, "R-1", status = "CONFIRMED", guaranteeType = "NONE")
                    holderError = ApiError.Problem(404, null, null, emptyList())
                    ratePlanError = ApiError.Problem(404, null, null, emptyList())
                }
            val payments =
                FakePaymentSource().apply {
                    receive = { throw ApiError.Problem(422, "CASHIER_SHIFT_REQUIRED", null, emptyList()) }
                }
            val fake = Fake(desk, FakeFolioSource(), payments, FakeCashierSource())
            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember { AppController(Settings(), { fake }, { ProbeResult.Ok("1.0") }, scope) }
                ZenyTheme { AppRoot(controller, devMode = false) }
            }
            onNodeWithTag("login-user").performTextInput("ana")
            onNodeWithTag("login-password").performTextInput("segredo-123")
            onNodeWithText("Entrar").performClick()
            waitForIdle()
            onNodeWithText("Recepção").performClick()
            waitForIdle()
            onNodeWithTag("tab-ARRIVALS").assertExists()

            onNodeWithTag("row-$ARRIVAL").performClick()
            waitForIdle()
            onNodeWithTag("open-folio").performClick()
            waitForIdle()
            onNodeWithTag("folio-panel").performScrollToNode(hasTestTag("receive-payment"))
            onNodeWithTag("receive-payment").performClick()
            waitForIdle()
            onNodeWithTag("payment-submit").performClick()
            waitForIdle()
            onNodeWithText("É preciso um turno de caixa aberto.").assertExists()
            onNodeWithTag("open-shift").performClick()
            waitForIdle()

            onNodeWithTag("tab-ARRIVALS").assertDoesNotExist()
            onNodeWithTag("folio-dialog").assertDoesNotExist()
            onNodeWithTag("payment-dialog").assertDoesNotExist()
            onNodeWithText("Contas").assertExists()
        }
}
