package com.zeny.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.zeny.desktop.app.catalog.ComponentCatalog
import com.zeny.desktop.app.catalog.isDevMode
import com.zeny.desktop.app.session.CoreConnection
import com.zeny.desktop.core.ZenyCore
import com.zeny.desktop.core.config.PreferencesSettings
import com.zeny.desktop.core.config.ServerUrlResolver
import com.zeny.desktop.core.config.jarDirectory
import com.zeny.desktop.core.facade.ServerProbe
import com.zeny.desktop.design.theme.ZenyTheme
import kotlinx.coroutines.CoroutineScope
import java.util.logging.Level
import java.util.logging.Logger

const val APP_TITLE = "Zeny Hotel Manager"

// Referência forte: o JUL guarda os loggers por referência fraca e perderia o nível ajustado.
private val preferencesLogger: Logger = Logger.getLogger("java.util.prefs")

fun main() =
    application {
        val dev = isDevMode()
        Window(onCloseRequest = ::exitApplication, title = APP_TITLE) {
            ZenyTheme {
                if (dev) ComponentCatalog() else ConnectedApp()
            }
        }
    }

/** O app ligado ao servidor: um controlador por janela, fechado (logout com limite) quando a janela sai. */
@Composable
private fun ConnectedApp() {
    val scope = rememberCoroutineScope()
    val controller = remember { appController(scope) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    AppRoot(controller, devMode = isShellDevMode())
}

private fun appController(scope: CoroutineScope): AppController {
    // Na primeira abertura o JDK anuncia em stderr, como INFO, que criou o diretório de preferências.
    // Avisos e erros das preferências seguem visíveis.
    preferencesLogger.level = Level.WARNING
    val settings = PreferencesSettings()
    val resolver = ServerUrlResolver(settings, envFileDir = jarDirectory(AppController::class.java))
    return AppController(
        settings = settings,
        connect = { url -> CoreConnection(ZenyCore(url, settings)) },
        probe = ServerProbe::check,
        scope = scope,
        resolveServer = resolver::resolve,
    )
}
