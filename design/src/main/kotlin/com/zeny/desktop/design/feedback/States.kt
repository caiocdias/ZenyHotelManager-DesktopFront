package com.zeny.desktop.design.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.error.ApiError

/** Texto de erro para o usuário: sempre o `userMessage`, nunca a causa nem o `code`. */
fun errorMessage(error: ApiError): String = error.userMessage

@Composable
private fun CenteredState(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
) = CenteredState(modifier) {
    Text(message, style = MaterialTheme.typography.bodyLarge)
}

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = "Carregando…",
) = CenteredState(modifier) {
    CircularProgressIndicator()
    Text(message, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun ErrorState(
    error: ApiError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) = CenteredState(modifier) {
    Text(errorMessage(error), color = MaterialTheme.colorScheme.error)
    Button(onClick = onRetry) { Text("Tentar de novo") }
}
