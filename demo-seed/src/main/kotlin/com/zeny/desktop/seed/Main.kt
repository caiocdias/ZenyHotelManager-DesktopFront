package com.zeny.desktop.seed

import com.zeny.desktop.seed.cli.ParseResult
import com.zeny.desktop.seed.cli.SeedArgs
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import kotlin.system.exitProcess

private const val USAGE_ERROR = 2

/** Códigos: 0 concluído ou ajuda; 1 falha do semeador; 2 uso inválido (mensagem e uso no `err`). */
internal fun runCli(
    args: List<String>,
    env: Map<String, String>,
    out: PrintStream,
    err: PrintStream,
): Int =
    when (val parsed = SeedArgs.parse(args, env)) {
        ParseResult.Help -> {
            out.println(SeedArgs.usage)
            0
        }

        is ParseResult.Invalid -> {
            err.println(parsed.message)
            err.println()
            err.println(SeedArgs.usage)
            USAGE_ERROR
        }

        is ParseResult.Ok -> {
            runBlocking { DemoSeed.run(parsed.config, out::println) }
        }
    }

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList(), System.getenv(), System.out, System.err))
}
