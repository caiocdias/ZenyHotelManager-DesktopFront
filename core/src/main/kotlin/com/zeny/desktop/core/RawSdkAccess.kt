package com.zeny.desktop.core

/**
 * Marca o acesso cru às operações do `sdk` (`ZenyCore.apis` e `ZenyCore.executor`). Só o próprio `core` e o
 * `demo-seed` fazem o opt-in; as features chegam ao servidor por fachadas do `core` (ADR 0008).
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Acesso cru às operações do sdk: só o core e o demo-seed (ADR 0008).",
)
@Retention(AnnotationRetention.BINARY)
annotation class RawSdkAccess
