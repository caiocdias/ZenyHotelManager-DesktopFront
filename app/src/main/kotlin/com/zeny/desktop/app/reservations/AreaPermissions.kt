package com.zeny.desktop.app.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val NOTICE_TEXT =
    "Não foi possível ler as suas permissões. As ações desta área ficam indisponíveis até a leitura dar certo."

/** A leitura das permissões efetivas da sessão para uma área. */
sealed interface AreaPermissions {
    data object Loading : AreaPermissions

    data object Failed : AreaPermissions

    data class Granted(
        val permissions: Set<String>,
    ) : AreaPermissions
}

/**
 * Lê as permissões uma vez; a falha fica visível e pode ser repetida. Nunca concede sem uma leitura que deu certo:
 * qualquer falha da leitura (da API ou não) vira [AreaPermissions.Failed], e o cancelamento nunca é engolido.
 *
 * As mutações correm no escopo da UI (uma thread só); [close] cancela só a leitura daqui, e a resposta tardia é
 * descartada.
 */
class AreaPermissionsState(
    private val read: suspend () -> Set<String>,
    scope: CoroutineScope,
) {
    /** Filho do job da UI: o [close] cancela só a leitura daqui. */
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + jobs)
    private var started = false
    private var closed = false

    var value: AreaPermissions by mutableStateOf(AreaPermissions.Loading)
        private set

    /** A primeira leitura; as chamadas seguintes não leem de novo. */
    fun start() {
        if (started || closed) return
        started = true
        launchRead()
    }

    /** Só a partir de [AreaPermissions.Failed]: volta a [AreaPermissions.Loading] e lê de novo. */
    fun retry() {
        if (closed || value != AreaPermissions.Failed) return
        value = AreaPermissions.Loading
        launchRead()
    }

    /** A área saiu de cena: cancela a leitura e descarta a resposta atrasada. */
    fun close() {
        closed = true
        jobs.cancel()
    }

    private fun launchRead() {
        ownScope.launch {
            val result =
                try {
                    // Cópia: a leitura não muda o que foi concedido depois.
                    AreaPermissions.Granted(read().toSet())
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    // O cancelamento sobe; qualquer outra falha (até um Error) é "sem leitura", nunca uma concessão
                    // vazia, e a área não fica travada em Loading.
                    if (e is CancellationException) throw e
                    AreaPermissions.Failed
                }
            if (!closed) value = result
        }
    }
}

/** O aviso da leitura que falhou, com o botão de repetir: uma faixa acima do conteúdo da área, que continua na tela. */
@Composable
fun PermissionsNotice(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            NOTICE_TEXT,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("permissions-notice"),
        )
        Button(onClick = onRetry, modifier = Modifier.testTag("permissions-retry")) { Text("Tentar de novo") }
    }
}
