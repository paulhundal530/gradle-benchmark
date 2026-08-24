package dev.gradlebenchmark.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Gradle Profiler's own result model.
 *
 * This is source data for debugging, not the product's public schema. The normalized
 * `run.json` built on top of it is the stable artifact.
 *
 * Released Gradle Profiler does not write this model to a file. It is embedded in
 * `benchmark.html`, so [RawBenchmarkExtractor] materializes it. Unknown keys are ignored
 * so a profiler upgrade that adds fields does not break parsing.
 */
@Serializable
public data class RawBenchmark(
    val title: String? = null,
    val date: String? = null,
    val environment: RawEnvironment = RawEnvironment(),
    val scenarios: List<RawScenario> = emptyList(),
)

@Serializable
public data class RawEnvironment(val profilerVersion: String? = null, val operatingSystem: String? = null)

@Serializable
public data class RawScenario(
    val definition: RawScenarioDefinition = RawScenarioDefinition(),
    val samples: List<RawSample> = emptyList(),
    val iterations: List<RawIteration> = emptyList(),
) {
    /**
     * Iterations that actually counted.
     *
     * Warm-ups are excluded from statistics, so a scenario with no measured iterations
     * produced no data no matter how many builds it ran.
     */
    public val measuredIterations: List<RawIteration>
        get() = iterations.filter { it.phase == MEASURE_PHASE }

    public val warmUpIterations: List<RawIteration>
        get() = iterations.filter { it.phase == WARM_UP_PHASE }

    public companion object {
        public const val MEASURE_PHASE: String = "MEASURE"
        public const val WARM_UP_PHASE: String = "WARM_UP"
    }
}

/**
 * A resolved scenario definition.
 *
 * [gradleHome] and [javaHome] are absolute paths that differ between machines, so they are
 * captured for debugging but must never contribute to scenario identity.
 */
@Serializable
public data class RawScenarioDefinition(
    val name: String = "",
    val title: String? = null,
    val displayName: String? = null,
    val buildTool: String? = null,
    val tasks: String? = null,
    val version: String? = null,
    val gradleHome: String? = null,
    val javaHome: String? = null,
    val usesScanPlugin: Boolean = false,
    val action: String? = null,
    val cleanup: String? = null,
    val invoker: String? = null,
    val mutators: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val jvmArgs: List<String> = emptyList(),
    val systemProperties: Map<String, String> = emptyMap(),
    val id: String? = null,
)

/**
 * One measured quantity, such as build execution time.
 *
 * A scenario carries more than one sample when options like `--measure-gc` are used, which
 * is why metric selection has a measurement axis as well as a statistic axis.
 *
 * [stats] is absent in released Gradle Profiler, which computes no statistics. It is
 * modelled so a future version that does emit them parses cleanly; statistics are computed
 * from [RawIteration.values] regardless, so the two can never disagree.
 */
@Serializable
public data class RawSample(val name: String = "", val unit: String = "", val stats: JsonElement? = null)

@Serializable
public data class RawIteration(
    val id: String? = null,
    val phase: String = "",
    val iteration: Int = 0,
    val title: String? = null,
    @SerialName("values") val values: Map<String, Double> = emptyMap(),
)
