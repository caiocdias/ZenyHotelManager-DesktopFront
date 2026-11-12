package com.zeny.desktop.app.approval

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * O coordenador de aprovação da sessão atual, provido pelo `AppRoot` em volta da casca: uma feature faz
 * `LocalApprovalCoordinator.current.run(action)`. Fora da casca não há sessão, e ler o Local falha.
 */
val LocalApprovalCoordinator =
    staticCompositionLocalOf<ApprovalCoordinator> {
        error("Sem coordenador de aprovação: só dentro da casca, com sessão aberta.")
    }
