package dev.gradlebenchmark.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    exitProcess(runCli(args).code)
}

/**
 * Runs the CLI and returns the exit code rather than terminating, so the mapping stays
 * testable.
 *
 * Clikt's own `main` would exit with its own status codes; we intercept instead, because
 * exit codes are a documented contract that CI wrappers branch on.
 */
internal fun runCli(args: Array<String>): ExitCode {
    val root = buildCommandTree()
    return try {
        root.parse(args)
        ExitCode.SUCCESS
    } catch (error: CliktError) {
        root.echoFormattedHelp(error)
        when {
            // --help and similar are a successful request for output, not a failure.
            error.statusCode == 0 -> ExitCode.SUCCESS
            error is UsageError -> ExitCode.INVALID_INPUT
            else -> ExitCode.INVALID_INPUT
        }
    }
}
