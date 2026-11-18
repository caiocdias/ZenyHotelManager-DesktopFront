package com.zeny.desktop.core.error

private const val KEY_IN_USE = "IDEMPOTENCY_KEY_IN_USE"
private const val SERVER_ERROR = 500

/** O que a falha de uma escrita diz sobre o efeito dela no servidor. */
enum class WriteFailure {
    /** O servidor respondeu e não fez a escrita: a intenção acaba, e uma nova tentativa é outra intenção. */
    DEFINITIVE,

    /**
     * O servidor pode ter feito a escrita sem a resposta chegar. Numa escrita com `Idempotency-Key`, a repetição leva
     * a mesma chave; numa escrita sem chave, quer dizer "pode ter sido feito, confira", e não "repita".
     */
    UNKNOWN,

    /**
     * Um `5xx` do próprio servidor numa escrita com chave: o efeito é desconhecido e a resposta ficou guardada sob a
     * chave, então repetir só devolve a mesma resposta. O caminho é fechar e conferir antes de refazer.
     */
    UNKNOWN_STORED,
}

/**
 * Classifica a falha de uma escrita. Rede, resposta incompatível, sessão encerrada e `IDEMPOTENCY_KEY_IN_USE` (com
 * qualquer status) são [WriteFailure.UNKNOWN]. Um `Problem` `5xx` é [WriteFailure.UNKNOWN_STORED]: o filtro de
 * idempotência do servidor guarda a resposta qualquer que seja o status. Um `Unexpected` `5xx` (`502`/`504` de proxy,
 * que não chega ao filtro) é [WriteFailure.UNKNOWN]. Todo o resto é [WriteFailure.DEFINITIVE].
 */
fun classifyWriteFailure(error: ApiError): WriteFailure =
    when {
        error is ApiError.Network || error is ApiError.IncompatibleResponse || error is ApiError.SessionEnded -> {
            WriteFailure.UNKNOWN
        }

        error is ApiError.Problem && error.code == KEY_IN_USE -> {
            WriteFailure.UNKNOWN
        }

        error is ApiError.Problem && error.status >= SERVER_ERROR -> {
            WriteFailure.UNKNOWN_STORED
        }

        error is ApiError.Unexpected && error.status >= SERVER_ERROR -> {
            WriteFailure.UNKNOWN
        }

        else -> {
            WriteFailure.DEFINITIVE
        }
    }
