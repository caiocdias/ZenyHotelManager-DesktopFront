package com.zeny.desktop.seed

/** Falha do semeador com mensagem pronta para o operador (pt-BR, sem JSON nem código cru). */
class SeedFailure(
    message: String,
) : Exception(message)
