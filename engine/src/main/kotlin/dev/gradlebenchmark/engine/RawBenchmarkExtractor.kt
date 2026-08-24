package dev.gradlebenchmark.engine

import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Recovers Gradle Profiler's result model from whatever it wrote.
 *
 * Released Gradle Profiler (0.25.2, the latest at time of writing) does not write
 * `benchmark.json`, despite shipping the writer that would produce it. The model is
 * embedded in `benchmark.html` as a JavaScript object literal that drives the report's
 * charts, so it is extracted from there.
 *
 * `benchmark.json` is preferred when present, so this keeps working unchanged once a
 * release does write it.
 *
 * The CSV is not a viable source: it carries scenario *titles* rather than names, and none
 * of the arguments, JVM arguments, or system properties that scenario identity depends on.
 */
public object RawBenchmarkExtractor {

    public const val JSON_FILE_NAME: String = "benchmark.json"
    public const val HTML_FILE_NAME: String = "benchmark.html"

    private const val EMBED_MARKER = "const benchmarkResult ="

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Serializes an extracted model back out, so `raw/benchmark.json` exists on disk. */
    public val prettyJson: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    public fun extractFrom(rawDir: Path): ExtractionResult {
        val jsonFile = rawDir.resolve(JSON_FILE_NAME)
        if (jsonFile.exists()) {
            return parse(jsonFile.readText(), "$jsonFile")
        }

        val htmlFile = rawDir.resolve(HTML_FILE_NAME)
        if (!htmlFile.exists()) {
            return ExtractionResult.Failed(
                "Gradle Profiler produced no readable result in $rawDir.",
                "Expected $JSON_FILE_NAME or $HTML_FILE_NAME.",
            )
        }

        val embedded = embeddedObject(htmlFile.readText())
            ?: return ExtractionResult.Failed(
                "Could not find the result model embedded in $htmlFile.",
                "This usually means the Gradle Profiler report format changed. " +
                    "Expected a '$EMBED_MARKER' object literal.",
            )

        return parse(embedded, "$htmlFile")
    }

    private fun parse(text: String, source: String): ExtractionResult =
        runCatching { json.decodeFromString<RawBenchmark>(text) }
            .fold(
                onSuccess = { ExtractionResult.Extracted(it) },
                onFailure = {
                    ExtractionResult.Failed(
                        "Gradle Profiler output in $source could not be parsed.",
                        it.message,
                    )
                },
            )

    /**
     * Pulls the object literal out of the report by matching braces.
     *
     * A regex would need to handle braces inside string values; counting depth while
     * tracking string and escape state is both shorter and correct.
     */
    internal fun embeddedObject(html: String): String? {
        val markerAt = html.indexOf(EMBED_MARKER)
        if (markerAt < 0) return null

        val start = html.indexOf('{', markerAt + EMBED_MARKER.length)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until html.length) {
            val character = html[index]
            when {
                escaped -> escaped = false
                character == '\\' && inString -> escaped = true
                character == '"' -> inString = !inString
                inString -> Unit
                character == '{' -> depth++
                character == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, index + 1)
                }
            }
        }
        return null
    }
}

/** Outcome of recovering the profiler's result model. */
public sealed interface ExtractionResult {
    public data class Extracted(val benchmark: RawBenchmark) : ExtractionResult

    public data class Failed(val summary: String, val detail: String?) : ExtractionResult
}
