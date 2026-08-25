package dev.gradlebenchmark.core

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The parts of a scenario that answer *are we measuring the same operation?*
 *
 * A difference here means the two results describe different work, so comparing them is
 * meaningless and must be refused.
 *
 * Gradle version and build JVM are deliberately absent: those describe the system *being*
 * measured, and a change in them is usually the very thing a benchmark exists to detect.
 * They live in [WorkloadConfiguration] and are reported rather than blocking.
 *
 * [args], [jvmArgs] and [systemProperties] are here not because they are all identity, but
 * because they cannot be classified automatically. `--rerun-tasks` changes what work is
 * performed while `-Xmx8g` is the independent variable, and no rule separates them
 * reliably, so blocking is the safe failure.
 */
@Serializable
public data class WorkloadIdentity(
    val name: String,
    val tasks: String? = null,
    val action: String? = null,
    val cleanup: String? = null,
    val invoker: String? = null,
    val mutators: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val jvmArgs: List<String> = emptyList(),
    val systemProperties: Map<String, String> = emptyMap(),
) {
    /**
     * Stable fingerprint of this identity.
     *
     * Rendered explicitly rather than via serialization, so the byte sequence being hashed
     * is visible in the source and cannot shift under a library upgrade. Map keys are
     * sorted; list order is preserved, because argument order can matter.
     */
    public fun hash(): String {
        val canonical = buildString {
            appendField("name", name)
            appendField("tasks", tasks)
            appendField("action", action)
            appendField("cleanup", cleanup)
            appendField("invoker", invoker)
            appendList("mutators", mutators)
            appendList("args", args)
            appendList("jvmArgs", jvmArgs)
            appendList(
                "systemProperties",
                systemProperties.toSortedMap().map { (key, value) -> key + "=" + value },
            )
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendField(key: String, value: String?) {
        append(key).append(FIELD_SEPARATOR).append(value ?: "").append(RECORD_SEPARATOR)
    }

    private fun StringBuilder.appendList(key: String, values: List<String>) {
        append(key).append(FIELD_SEPARATOR)
        values.forEach { value -> append(value).append(LIST_SEPARATOR) }
        append(RECORD_SEPARATOR)
    }

    public companion object {
        // Written as escapes rather than literal control characters: the bytes are the
        // same, but invisible characters in source are trivially mangled by editors and
        // tooling, and a silent change here would alter every hash the tool has ever
        // produced.
        //
        // They are control characters because a printable separator can occur inside a
        // value: with a comma, args ["--ab", "--c"] and ["--a", "b--c"] would render
        // identically and two different benchmarks would share an identity.
        private const val FIELD_SEPARATOR = "\u001e"
        private const val RECORD_SEPARATOR = "\u001d"
        private const val LIST_SEPARATOR = "\u001f"

        /**
         * Absolute POSIX-style paths, as they appear inside mutator descriptions.
         *
         * Stops at whitespace, comma or bracket, which is how Gradle Profiler delimits them.
         */
        private val ABSOLUTE_PATH = Regex("""/[^\s,()]+""")

        /**
         * Rewrites machine-specific paths inside a mutator description to project-relative.
         *
         * Mutator descriptions embed absolute paths:
         *
         * ```
         * ApplyAbiChangeToSourceFileMutator(/Users/someone/Projects/App/./app/src/main/java/Repo.kt)
         * ```
         *
         * Left alone, the same benchmark would fingerprint differently on a laptop and on
         * CI, and every historical comparison would report an incompatible baseline. That
         * failure is silent, which makes it worse than a loud one.
         *
         * *Which* file a benchmark mutates genuinely is part of its identity, so the path is
         * relativized rather than dropped. Normalizing also collapses the `./` segment that
         * appears when the project directory is given as `.`, so a relative and an absolute
         * `--project-dir` agree.
         */
        public fun normalizeMutator(mutator: String, projectDir: Path?): String {
            if (projectDir == null) return mutator
            val root = projectDir.toAbsolutePath().normalize()

            return ABSOLUTE_PATH.replace(mutator) { match ->
                val candidate = runCatching { Path.of(match.value).normalize() }.getOrNull()
                if (candidate != null && candidate.startsWith(root)) {
                    root.relativize(candidate).toString()
                } else {
                    match.value
                }
            }
        }
    }
}

/**
 * The parts of a scenario describing the system under measurement.
 *
 * Differences here are recorded and surfaced, never blocking. A Gradle upgrade that made
 * builds slower is the most valuable thing this tool can report, and rejecting it as
 * incomparable would throw that away.
 */
@Serializable
public data class WorkloadConfiguration(
    val gradleVersion: String? = null,
    val buildJvmVersion: String? = null,
    val usesScanPlugin: Boolean = false,
)
