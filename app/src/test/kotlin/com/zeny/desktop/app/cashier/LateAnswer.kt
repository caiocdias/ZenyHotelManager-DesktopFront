package com.zeny.desktop.app.cashier

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Uma resposta da porta que ignora o cancelamento de quem a pediu, como um servidor que responde depois que a tela
 * desistiu: a suspensão não é cancelável (`suspendCoroutine`), e a resposta chega a quem chamou mesmo com o job já
 * cancelado. Só o `ensureActive()` depois da resposta (ou uma geração) impede que ela seja aplicada.
 */
class LateAnswer<T> {
    private var waiting: Continuation<T>? = null

    val asked: Boolean get() = waiting != null

    suspend fun await(): T = suspendCoroutine { waiting = it }

    fun answer(value: T) = checkNotNull(waiting) { "ninguém esperava a resposta" }.resume(value)

    fun fail(error: Throwable) = checkNotNull(waiting) { "ninguém esperava a resposta" }.resumeWithException(error)
}
