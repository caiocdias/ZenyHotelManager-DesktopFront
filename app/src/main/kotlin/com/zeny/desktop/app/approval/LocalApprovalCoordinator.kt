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

/**
 * O mesmo coordenador, mas `null` fora da casca em vez de falhar: para quem só precisa dele na hora da ação e não
 * pode quebrar ao compor sem sessão (a Recepção em teste). Provido junto com [LocalApprovalCoordinator].
 */
val LocalApprovalCoordinatorOrNull = staticCompositionLocalOf<ApprovalCoordinator?> { null }
