package dev.gradlebenchmark.report

import dev.gradlebenchmark.core.BenchmarkComparison
import dev.gradlebenchmark.core.BenchmarkRun
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

/**
 * Writes the normalized run to `run.json`.
 *
 * This is the canonical machine-readable artifact, so it is written deterministically:
 * pretty-printed with defaults encoded, so the same run always produces the same bytes and
 * a diff between two runs shows only what actually changed.
 */
public object RunJsonWriter {

    public val json: Json = Json {
        prettyPrint = true
        // Without this, a field that happens to equal its default is omitted, so two runs
        // could serialize to different shapes while describing the same thing.
        encodeDefaults = true
    }

    public fun render(run: BenchmarkRun): String = json.encodeToString(run)

    public fun write(run: BenchmarkRun, destination: Path): Path {
        destination.createParentDirectories()
        destination.writeText(render(run))
        return destination
    }
}

/**
 * Writes the interpretation to `comparison.json`.
 *
 * The canonical artifact other tools ingest, so it is written on the same terms as
 * `run.json`: pretty-printed, defaults encoded, deterministic.
 */
public object ComparisonJsonWriter {

    public fun render(comparison: BenchmarkComparison): String = RunJsonWriter.json.encodeToString(comparison)

    public fun write(comparison: BenchmarkComparison, destination: Path): Path {
        destination.createParentDirectories()
        destination.writeText(render(comparison))
        return destination
    }
}
