package com.zeny.desktop.app.folio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zeny.desktop.app.AreaContent
import com.zeny.desktop.app.cashier.FakeCashierSource
import com.zeny.desktop.app.cashier.LateAnswer
import com.zeny.desktop.app.folioWiring
import com.zeny.desktop.app.reception.FakeReceptionSource
import com.zeny.desktop.app.reservations.confirmDetail
import com.zeny.desktop.app.reservations.searchItemOf
import com.zeny.desktop.app.shell.Area
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.core.facade.DayList
import com.zeny.desktop.core.facade.PdfDocument
import com.zeny.desktop.core.facade.StatementAudience
import com.zeny.desktop.core.http.Page
import com.zeny.desktop.core.session.SessionState
import com.zeny.desktop.design.pdf.FileDialogSaveChooser
import com.zeny.desktop.design.pdf.PdfActions
import com.zeny.desktop.design.theme.ZenyTheme
import com.zeny.desktop.sdk.model.FolioResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val READ = "billing.folios.read"
private const val OPERATE = "billing.cashier.operate"
private const val NUMBER = "F-0001"
private const val OLD_NUMBER = "F-DA-SESSAO-ANTIGA"
private const val OPEN_TIMEOUT_MILLIS = 10_000L
private val RESERVATION = Uuid.parse("21000000-0000-0000-0000-0000000000f1")

/**
 * Ataque da etapa à sessão e ao PDF interno: a troca de `sessionId` com a conta aberta (aba "Contas" e diálogo
 * "Conta") e o extrato interno pelo `folioWiring` real, com as ações reais do visualizador.
 */
@OptIn(ExperimentalTestApi::class)
class FolioSessionAtaqueTest {
    private fun reception() =
        FakeReceptionSource().apply {
            permissions = permissions + READ
            pages[DayList.ARRIVALS to null] = Page(listOf(searchItemOf("R-1").copy(id = RESERVATION)), null)
            reservations[RESERVATION.toString()] =
                confirmDetail(RESERVATION, "R-1", status = "CONFIRMED", guaranteeType = "NONE")
            holderError = ApiError.Problem(404, null, null, emptyList())
            ratePlanError = ApiError.Problem(404, null, null, emptyList())
        }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** O mesmo usuário noutra sessão: a conexão passa a "B" e a casca segue (ou não) para ela. */
    private fun FolioConnection.switchTo(session: String) {
        state.value = SessionState.Active("u-2", session, false)
    }

    @Test
    fun sessionSwitchClosesTheOpenFolioAndDropsTheLateAnswer() =
        runComposeUiTest {
            val source = FakeFolioSource()
            val late = LateAnswer<FolioResponse>()
            val connection = FolioConnection(source, cashier = FakeCashierSource(), sessionId = "A")
            var shell by mutableStateOf("A")
            var permissions by mutableStateOf(setOf(READ, OPERATE))
            setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, shell, permissions) } }
            waitForIdle()
            onNodeWithText("Contas").performClick()
            waitForIdle()
            source.folio = { late.await() }
            click("folio-row-$FOLIO_ID")
            onNodeWithTag("folio-panel").assertExists()
            assertTrue(late.asked, "a conta da sessão A não foi pedida")

            // A conexão já está noutra sessão e a casca ainda não: nada da área se desenha com o conjunto de "A".
            connection.switchTo("B")
            waitForIdle()
            onNodeWithText("Contas").assertDoesNotExist()
            onNodeWithTag("folio-panel").assertDoesNotExist()

            // A casca segue para "B", com o mesmo conjunto: a aba volta sem a conta aberta na sessão anterior.
            shell = "B"
            permissions = setOf(READ, OPERATE)
            waitForIdle()
            late.answer(folioOf(number = OLD_NUMBER, balance = "999.00"))
            waitForIdle()
            onNodeWithText("Contas").performClick()
            waitForIdle()
            onNodeWithTag("folio-panel").assertDoesNotExist()
            onNodeWithText(OLD_NUMBER, substring = true).assertDoesNotExist()
            onNodeWithText("R$ 999,00", substring = true).assertDoesNotExist()
            assertEquals(2, source.pageCalls.size, "a sessão B não releu a lista das contas")
        }

    @Test
    fun sessionSwitchWithoutReadHidesTheFoliosTab() =
        runComposeUiTest {
            val source = FakeFolioSource()
            val connection = FolioConnection(source, cashier = FakeCashierSource(), sessionId = "A")
            var shell by mutableStateOf("A")
            var permissions by mutableStateOf(setOf(READ, OPERATE))
            setContent { ZenyTheme { AreaContent(Area.ACCOUNTS, connection, shell, permissions) } }
            waitForIdle()
            onNodeWithText("Contas").performClick()
            waitForIdle()
            click("folio-row-$FOLIO_ID")
            onNodeWithTag("folio-panel").assertExists()

            connection.switchTo("B")
            shell = "B"
            permissions = setOf(OPERATE)
            waitForIdle()

            onNodeWithText("Contas").assertDoesNotExist()
            onNodeWithTag("folio-panel").assertDoesNotExist()
            onNodeWithText("Turno de caixa").assertExists()
        }

    @Test
    fun sessionSwitchClosesTheReceptionFolioDialogAndDropsTheLateAnswer() =
        runComposeUiTest {
            val source = FakeFolioSource()
            val late = LateAnswer<List<FolioResponse>>()
            source.byReservation = { late.await() }
            val desk = reception()
            val connection = FolioConnection(source, reception = desk, sessionId = "A")
            var shell by mutableStateOf("A")
            setContent { ZenyTheme { AreaContent(Area.RECEPTION, connection, shell, desk.permissions) } }
            waitForIdle()
            click("row-$RESERVATION")
            click("open-folio")
            onNodeWithTag("folio-dialog").assertExists()
            assertTrue(late.asked, "as contas da reserva não foram pedidas")

            connection.switchTo("B")
            shell = "B"
            waitForIdle()
            onNodeWithTag("folio-dialog").assertDoesNotExist()

            late.answer(listOf(folioOf(number = OLD_NUMBER, balance = "999.00")))
            waitForIdle()
            onNodeWithTag("folio-dialog").assertDoesNotExist()
            onNodeWithText(OLD_NUMBER, substring = true).assertDoesNotExist()
            assertTrue(source.folioCalls.isEmpty(), "a resposta atrasada da sessão A abriu uma conta")
        }

    /**
     * O extrato interno pelo `folioWiring` real (o `pdfFactory` do `AppRoot`): o servidor manda `extrato-<número>.pdf`
     * e o nome sugerido é `extrato-interno-<número>.pdf`; as ações são as reais, e só o "Salvar como…" (o diálogo de
     * arquivo) leva ao disco: abrir e fechar o visualizador não cria arquivo. O nome sugerido e o seletor são privados
     * no `PdfActions` e lidos por reflexão (nenhuma costura pública os mostra).
     */
    @Test
    @Suppress("InjectDispatcher") // o PdfDocumentState real abre no Default: o escopo do teste é real, não virtual
    fun internalPdfFromTheRealWiringSuggestsItsOwnNameAndWritesNothing() {
        val source = FakeFolioSource()
        source.pdf = { PdfDocument(FOLIO_PDF, "extrato-$NUMBER.pdf") }
        val watched = listOf(File(System.getProperty("java.io.tmpdir")), File(".").absoluteFile)
        val before = watched.associateWith { pdfsIn(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val wiring = assertNotNull(folioWiring(FolioConnection(source), setOf(READ), scope, null, null))
            val names =
                listOf(wiring.guestPdf, wiring.internalPdf).map { factory ->
                    val state = assertNotNull(factory).invoke(FOLIO_ID.toString(), NUMBER)
                    state.start()
                    runBlocking {
                        withTimeout(OPEN_TIMEOUT_MILLIS) {
                            while (state.viewer == null && state.error == null) delay(POLL_MILLIS)
                        }
                    }
                    assertNull(state.error, "o extrato não abriu: ${state.error}")
                    val actions = assertNotNull(state.viewer).actions
                    assertIs<FileDialogSaveChooser>(privateField(actions, "chooser"), "outro caminho até o disco")
                    val name = privateField(actions, "suggestedName")
                    state.close()
                    name
                }

            assertEquals(listOf("extrato-$NUMBER.pdf", "extrato-interno-$NUMBER.pdf"), names)
            assertEquals(
                listOf(
                    FOLIO_ID.toString() to StatementAudience.GUEST,
                    FOLIO_ID.toString() to StatementAudience.INTERNAL,
                ),
                source.pdfCalls,
            )
            watched.forEach { dir ->
                val created = pdfsIn(dir) - before.getValue(dir)
                assertTrue(created.isEmpty(), "arquivo criado em $dir: $created")
            }
        } finally {
            scope.cancel()
        }
    }

    private fun pdfsIn(dir: File): Set<String> =
        dir
            .listFiles()
            .orEmpty()
            .map { it.name }
            .filter { it.endsWith(".pdf") }
            .toSet()

    private fun privateField(
        actions: PdfActions,
        name: String,
    ): Any? {
        val field = PdfActions::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(actions)
    }

    private companion object {
        const val POLL_MILLIS = 20L
    }
}
