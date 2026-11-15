package com.zeny.desktop.design.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val SAVED_MESSAGE = "Arquivo salvo."
private const val PRINTED_MESSAGE = "Enviado para a impressora."

/**
 * Visualizador da ficha: barra de comandos, página renderizada com rolagem e linha de status. Abre o
 * [document] ao entrar na composição e o fecha ao sair dela. "Salvar como…" e "Imprimir" ficam desabilitados
 * enquanto uma das duas ações corre.
 */
@Composable
@Suppress("LongMethod") // a barra e a área da página são uma peça só
fun PdfViewer(
    title: String,
    document: PdfDocumentState,
    actions: PdfActions,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(document) { document.open() }
    DisposableEffect(document) { onDispose { document.close() } }

    fun run(block: suspend () -> String?) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            try {
                status = block()
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().testTag("pdf-viewer")) {
        // A barra quebra de linha em janela estreita: o "Fechar" nunca fica espremido para fora.
        FlowRow(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("Página ${document.page + 1} de ${document.pageCount}")
            OutlinedButton(
                onClick = document::previous,
                enabled = document.page > 0,
                modifier = Modifier.testTag("pdf-prev"),
            ) { Text("Anterior") }
            OutlinedButton(
                onClick = document::next,
                enabled = document.page < document.pageCount - 1,
                modifier = Modifier.testTag("pdf-next"),
            ) { Text("Próxima") }
            OutlinedButton(onClick = document::zoomOut, modifier = Modifier.testTag("pdf-zoom-out")) { Text("−") }
            Text("${document.zoom}%")
            OutlinedButton(onClick = document::zoomIn, modifier = Modifier.testTag("pdf-zoom-in")) { Text("+") }
            OutlinedButton(
                onClick = {
                    run {
                        when (val result = actions.save()) {
                            SaveResult.Saved -> SAVED_MESSAGE
                            SaveResult.Cancelled -> null
                            is SaveResult.Failed -> result.message
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag("pdf-save"),
            ) { Text("Salvar como…") }
            OutlinedButton(
                onClick = {
                    run {
                        when (val result = actions.print()) {
                            PrintResult.Printed -> PRINTED_MESSAGE
                            PrintResult.Cancelled -> null
                            is PrintResult.Failed -> result.message
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag("pdf-print"),
            ) { Text("Imprimir") }
            Button(onClick = onClose, modifier = Modifier.testTag("pdf-close")) { Text("Fechar") }
        }
        status?.let { Text(it, Modifier.padding(horizontal = 8.dp).testTag("pdf-status")) }
        PageArea(document, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun PageArea(
    document: PdfDocumentState,
    modifier: Modifier,
) {
    val error = document.error
    val image = document.image
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            image != null -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    val density = LocalDensity.current
                    Image(
                        bitmap = image,
                        contentDescription = "Página ${document.page + 1}",
                        modifier =
                            Modifier
                                .size(with(density) { image.width.toDp() }, with(density) { image.height.toDp() })
                                .testTag("pdf-page"),
                    )
                }
            }
        }
        if (error == null && document.rendering) CircularProgressIndicator(Modifier.testTag("pdf-loading"))
    }
}
