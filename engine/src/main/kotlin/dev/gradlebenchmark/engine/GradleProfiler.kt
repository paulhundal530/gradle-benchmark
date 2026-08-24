package dev.gradlebenchmark.engine

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Result of one Gradle Profiler invocation. */
public data class ProfilerInvocation(val exitCode: Int, val stdout: String, val stderr: String) {
    public val succeeded: Boolean get() = exitCode == 0

    /** stdout and stderr combined, since the profiler writes diagnostics to both. */
    public val combinedOutput: String get() = (stdout + "\n" + stderr).trim()
}

/**
 * Runs the Gradle Profiler executable.
 *
 * An interface so command logic can be tested without spawning processes, per the
 * requirement to put explicit boundaries where external processes need testing.
 */
public interface GradleProfiler {
    public fun invoke(arguments: List<String>): ProfilerInvocation
}

/**
 * Invokes a real `gradle-profiler` executable.
 *
 * No timeout is applied by default. Benchmarks on large repositories legitimately run for
 * a long time, and killing a valid multi-minute run is worse than letting CI's own
 * job-level limit handle a genuine hang.
 */
public class ProcessGradleProfiler(
    private val executable: String = DEFAULT_EXECUTABLE,
    private val workingDirectory: Path? = null,
    private val timeout: java.time.Duration? = null,
) : GradleProfiler {

    override fun invoke(arguments: List<String>): ProfilerInvocation {
        val process = ProcessBuilder(listOf(executable) + arguments)
            .apply { workingDirectory?.let { directory(it.toFile()) } }
            .start()

        // Read both streams before waiting: a full pipe buffer would otherwise deadlock
        // a profiler run that produces more output than the buffer holds.
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()

        val finished = if (timeout == null) {
            process.waitFor()
            true
        } else {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        }

        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            return ProfilerInvocation(
                exitCode = TIMED_OUT_EXIT_CODE,
                stdout = stdout,
                stderr = "Gradle Profiler exceeded the configured timeout of $timeout.",
            )
        }

        return ProfilerInvocation(process.exitValue(), stdout, stderr)
    }

    public companion object {
        public const val DEFAULT_EXECUTABLE: String = "gradle-profiler"
        internal const val TIMED_OUT_EXIT_CODE: Int = -1
    }
}
