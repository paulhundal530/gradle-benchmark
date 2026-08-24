package dev.gradlebenchmark.engine

import dev.gradlebenchmark.core.Revision
import java.nio.file.Path

/** Determines which revision of a project is being measured. */
public interface RevisionResolver {
    public fun resolve(projectDir: Path?): Revision?
}

/**
 * Reads the revision from git.
 *
 * Every failure yields null rather than throwing: benchmarking a directory that is not a
 * git repository, or has no commits, is perfectly legitimate and must not fail a run. The
 * revision is metadata, not a prerequisite.
 *
 * A dirty working tree is recorded rather than rejected, because it materially affects
 * whether a result is reproducible and a later comparison may want to say so.
 */
public class GitRevisionResolver : RevisionResolver {

    override fun resolve(projectDir: Path?): Revision? {
        val directory = projectDir ?: Path.of("")

        val commit = git(directory, "rev-parse", "HEAD") ?: return null
        val branch = git(directory, "rev-parse", "--abbrev-ref", "HEAD")
        val status = git(directory, "status", "--porcelain")

        return Revision(
            commit = commit,
            branch = branch?.takeIf { it != "HEAD" },
            dirty = !status.isNullOrBlank(),
        )
    }

    private fun git(directory: Path, vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(directory.toFile())
            .redirectErrorStream(false)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()

        if (process.waitFor() == 0) output.trim().ifEmpty { null } else null
    }.getOrNull()
}
