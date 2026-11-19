package com.zeny.desktop.app.folio

import com.zeny.desktop.design.input.MoneyInput
import kotlinx.coroutines.CoroutineScope

/**
 * A confirmação "Liberar" da caução [row] da conta [folioId]: só mostra o capturável que o servidor devolveu ([text]) e
 * confirma; não há campo. O envio, a `Idempotency-Key` por intenção, a classificação da falha e o fechamento são do
 * ajudante comum `FolioWrite`: o sucesso vai a [onReleased]; a recusa definitiva, depois do texto do catálogo, a
 * [onRefused] com o `code`.
 */
class ReleaseState(
    private val source: PaymentSource,
    scope: CoroutineScope,
    private val folioId: String,
    val row: PreAuthorizationRow,
    private val onReleased: () -> Unit,
    onRefused: (code: String) -> Unit,
) : FolioWriteForm {
    private val write =
        FolioWrite(
            scope,
            FolioText.RELEASE_UNCONFIRMED,
            FolioText.RELEASE_SERVER_UNCONFIRMED,
            openShift = null,
            onRefused = onRefused,
        )

    /** "A sobra de <capturável> será liberada na maquininha.", sem valor quando o capturável é zero. */
    val text: String = FolioText.release(row.capturable?.let(MoneyInput::format))

    override val busy: Boolean get() = write.busy

    override val unconfirmed: Boolean get() = write.unconfirmed

    override val closeOnly: Boolean get() = write.closeOnly

    override val message: String? get() = write.message

    override val canConfirm: Boolean get() = write.canSend

    override val closesOnEscape: Boolean get() = write.closesOnEscape

    override fun confirm() {
        write.send({
            source.releasePreAuthorization(folioId, row.id, it)
            WriteOutcome.Done(Unit)
        }) { onReleased() }
    }

    fun close() = write.close()

    override fun toString(): String = "ReleaseState(busy=$busy, unconfirmed=$unconfirmed, closeOnly=$closeOnly)"
}
