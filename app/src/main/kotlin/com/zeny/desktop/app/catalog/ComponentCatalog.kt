package com.zeny.desktop.app.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zeny.desktop.core.error.ApiError
import com.zeny.desktop.design.feedback.ConfirmDialog
import com.zeny.desktop.design.feedback.EmptyState
import com.zeny.desktop.design.feedback.ErrorState
import com.zeny.desktop.design.feedback.LoadingState
import com.zeny.desktop.design.feedback.StatusChip
import com.zeny.desktop.design.feedback.StatusTone
import com.zeny.desktop.design.feedback.ToastHost
import com.zeny.desktop.design.feedback.ToastState
import com.zeny.desktop.design.feedback.chipModel
import com.zeny.desktop.design.form.FormErrorBanner
import com.zeny.desktop.design.form.FormField
import com.zeny.desktop.design.form.FormState
import com.zeny.desktop.design.form.FormTextField
import com.zeny.desktop.design.input.DateField
import com.zeny.desktop.design.input.DocumentField
import com.zeny.desktop.design.input.MoneyField
import com.zeny.desktop.design.input.PhoneField
import com.zeny.desktop.design.layout.AppScaffold
import com.zeny.desktop.design.layout.NavItem
import com.zeny.desktop.design.table.DataTable
import com.zeny.desktop.design.table.TableState
import com.zeny.desktop.design.theme.ZenyColors
import kotlinx.coroutines.launch

/** Verdadeiro só com `ZENY_DEV=1` ou `-Dzeny.dev=true`. */
fun isDevMode(
    env: (String) -> String? = System::getenv,
    prop: (String) -> String? = System::getProperty,
): Boolean = env("ZENY_DEV") == "1" || prop("zeny.dev") == "true"

private const val THEME = "Tema"
private const val FIELDS = "Campos"
private const val FORM = "Formulário"
private const val TABLE = "Tabela"
private const val CHIPS = "Chips de status"
private const val STATES = "Estados"
private const val DIALOG = "Diálogo"
private const val TOASTS = "Avisos"

/** Títulos das seções, na ordem em que aparecem. */
internal val CATALOG_SECTIONS = listOf(THEME, FIELDS, FORM, TABLE, CHIPS, STATES, DIALOG, TOASTS)

// Dados fictícios: CPF e CNPJ gerados com dígito verificador válido, de ninguém.
private const val SAMPLE_CPF = "52998224725"
private const val SAMPLE_CNPJ = "11222333000181"
private const val TABLE_HEIGHT = 260

private val navItems = CATALOG_SECTIONS.map { NavItem(it, it) }

@Composable
fun ComponentCatalog() {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val tops = remember { HashMap<String, Int>() }
    var selected by remember { mutableStateOf<String?>(THEME) }
    AppScaffold(
        items = navItems,
        selected = selected,
        onSelect = { id ->
            selected = id
            tops[id]?.let { scope.launch { scroll.animateScrollTo(it) } }
        },
        topBar = {
            Text(
                "Catálogo de componentes",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val section: @Composable (String, @Composable () -> Unit) -> Unit = { title, body ->
                Section(title, onTop = { tops[title] = it }, body)
            }
            section(THEME) { ThemeSection() }
            section(FIELDS) { FieldsSection() }
            section(FORM) { FormSection() }
            section(TABLE) { TableSection() }
            section(CHIPS) { ChipsSection() }
            section(STATES) { StatesSection() }
            section(DIALOG) { DialogSection() }
            section(TOASTS) { ToastsSection() }
        }
    }
}

@Composable
private fun Section(
    title: String,
    onTop: (Int) -> Unit,
    body: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().onGloballyPositioned { onTop(it.positionInParent().y.toInt()) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("section-$title"))
        body()
    }
}

@Composable
private fun ThemeSection() {
    val swatches =
        listOf(
            "Primária" to ZenyColors.Primary,
            "Secundária" to ZenyColors.Secondary,
            "Sucesso" to ZenyColors.Success,
            "Alerta" to ZenyColors.Warning,
            "Perigo" to ZenyColors.Danger,
            "Info" to ZenyColors.Info,
            "Neutra" to ZenyColors.Neutral,
        )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.forEach { (name, color) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(width = 72.dp, height = 24.dp).background(color)) {}
                Text(name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun FieldsSection() {
    var money by remember { mutableStateOf("1234,56") }
    var date by remember { mutableStateOf("11/11/2026") }
    var document by remember { mutableStateOf(SAMPLE_CPF) }
    var cnpj by remember { mutableStateOf(SAMPLE_CNPJ) }
    var phone by remember { mutableStateOf("11987654321") }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MoneyField(money, { money = it }, "Valor", modifier = Modifier.weight(1f))
        DateField(date, { date = it }, "Data", modifier = Modifier.weight(1f))
        DocumentField(document, { document = it }, "CPF", modifier = Modifier.weight(1f))
        DocumentField(cnpj, { cnpj = it }, "CNPJ", modifier = Modifier.weight(1f))
        PhoneField(phone, { phone = it }, "Telefone", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FormSection() {
    val form =
        remember {
            FormState(
                listOf(
                    FormField("name", "Nome do hóspede", "Maria Exemplo"),
                    FormField("email", "E-mail", "") { if (it.isBlank()) "Informe o e-mail." else null },
                ),
            ).also { it.validate() }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormErrorBanner(form)
        form.fields.forEach { FormTextField(it, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun TableSection() {
    val state = remember { TableState(pagerFactory = ::fakeRoomPager, key = { it.number }) }
    DataTable(state, roomColumns, modifier = Modifier.fillMaxWidth().height(TABLE_HEIGHT.dp))
}

@Composable
private fun ChipsSection() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("AVAILABLE", "OCCUPIED", "CLEANING", "MAINTENANCE").forEach {
            StatusChip(chipModel(it, statusLabels, statusTones))
        }
    }
}

@Composable
private fun StatesSection() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { EmptyState("Nenhum quarto encontrado.") }
        Box(Modifier.weight(1f)) { LoadingState() }
        Box(Modifier.weight(1f)) {
            ErrorState(ApiError.Unexpected(status = 500), onRetry = {})
        }
    }
}

@Composable
private fun DialogSection() {
    var open by remember { mutableStateOf(false) }
    Button(onClick = { open = true }) { Text("Abrir diálogo de confirmação") }
    if (open) {
        ConfirmDialog(
            title = "Cancelar reserva?",
            message = "A reserva de exemplo será cancelada.",
            confirmLabel = "Cancelar reserva",
            destructive = true,
            onConfirm = { open = false },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun ToastsSection() {
    val toasts = remember { ToastState() }
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Sucesso" to StatusTone.SUCCESS, "Alerta" to StatusTone.WARNING, "Erro" to StatusTone.DANGER)
            .forEach { (label, tone) ->
                Button(onClick = { scope.launch { toasts.show("Aviso de exemplo: $label", tone) } }) { Text(label) }
            }
    }
    ToastHost(toasts)
}
