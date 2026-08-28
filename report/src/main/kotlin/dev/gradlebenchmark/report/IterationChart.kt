package dev.gradlebenchmark.report

import dev.gradlebenchmark.core.MeasurementResult
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the iteration series for one measurement as inline SVG.
 *
 * The chart exists because warm-up convergence is the thing a table cannot show. A series
 * reading `5872, 580, 469, 450, 444, 399` is obviously still falling when plotted, and easy
 * to skim past as a line of text.
 *
 * Inline SVG rather than a charting library: the report has to be a single self-contained
 * file with no scripts or external requests, and the output has to be byte-identical for
 * the same input so it can be checked by a golden test.
 */
internal object IterationChart {

    private const val WIDTH = 640
    private const val HEIGHT = 120
    private const val PADDING_LEFT = 4
    private const val PADDING_RIGHT = 4
    private const val PADDING_TOP = 8
    private const val PADDING_BOTTOM = 16

    /**
     * Renders warm-ups and measured iterations on one axis.
     *
     * Warm-ups are drawn first and muted, so the eye reads left to right as the build
     * settling and then being measured. The median of the measured values is drawn as a
     * reference line, which is what every reported number is derived from.
     */
    fun render(measurement: MeasurementResult): String {
        val warmUps = measurement.warmUpValues
        val measured = measurement.values
        val all = warmUps + measured
        if (all.isEmpty()) return ""

        // The first warm-up is routinely tens of times the measured values: a real run
        // opened with 3887ms against measured values of 43 to 68ms. Scaling to it collapses
        // everything else into a flat line at the bottom, which defeats the purpose of
        // drawing the series at all.
        //
        // So the scale follows the measured values, keeping warm-ups that are close enough
        // to be informative about convergence, and clamping the rest to the top edge where
        // they are marked as off scale.
        val reference = measured.ifEmpty { warmUps }
        val cutoff = reference.max() * OFF_SCALE_MULTIPLE
        val onScale = all.filter { it <= cutoff }.ifEmpty { listOf(reference.max()) }

        val highest = onScale.max()
        val lowest = min(onScale.min(), highest)
        val span = max(highest - lowest, max(highest * FLAT_SERIES_HEADROOM, 1e-9))
        val upper = highest + span * PADDING_FRACTION
        val lower = max(0.0, lowest - span * PADDING_FRACTION)
        val range = max(upper - lower, 1e-9)

        val plotWidth = WIDTH - PADDING_LEFT - PADDING_RIGHT
        val plotHeight = HEIGHT - PADDING_TOP - PADDING_BOTTOM
        val step = if (all.size > 1) plotWidth.toDouble() / (all.size - 1) else 0.0

        fun x(index: Int) = PADDING_LEFT + index * step
        fun y(value: Double): Double {
            val clamped = value.coerceIn(lower, upper)
            return PADDING_TOP + plotHeight - ((clamped - lower) / range) * plotHeight
        }

        val medianY = y(measurement.statistics.median)
        val boundary = if (warmUps.isEmpty()) null else x(warmUps.size - 1) + step / 2

        return buildString {
            append("""<svg class="chart" viewBox="0 0 $WIDTH $HEIGHT" role="img" """)
            append("""aria-label="${all.size} iterations, """)
            append("""${warmUps.size} warm-up and ${measured.size} measured">""")

            // Median of the measured values, which every reported number derives from.
            append(
                """<line class="median" x1="$PADDING_LEFT" y1="${fmt(medianY)}" """ +
                    """x2="${WIDTH - PADDING_RIGHT}" y2="${fmt(medianY)}" />""",
            )

            // Where warm-up ends and measurement begins.
            boundary?.let {
                append(
                    """<line class="boundary" x1="${fmt(it)}" y1="$PADDING_TOP" """ +
                        """x2="${fmt(it)}" y2="${PADDING_TOP + plotHeight}" />""",
                )
            }

            if (warmUps.isNotEmpty()) {
                append(polyline("warmup", warmUps.indices.map { x(it) to y(warmUps[it]) }))
                // Mark any warm-up that had to be clamped, so a flat line at the top edge is
                // never mistaken for a measurement.
                warmUps.forEachIndexed { index, value ->
                    if (value > upper) {
                        append(
                            """<text class="axis offscale" x="${fmt(x(index))}" """ +
                                """y="${PADDING_TOP - 1}">${formatOffScale(value)}</text>""",
                        )
                    }
                }
            }
            if (measured.isNotEmpty()) {
                val points = measured.indices.map { x(warmUps.size + it) to y(measured[it]) }
                append(polyline("measured", points))
                points.forEach { (px, py) ->
                    append("""<circle class="point" cx="${fmt(px)}" cy="${fmt(py)}" r="2.5" />""")
                }
            }

            boundary?.let {
                append(
                    """<text class="axis" x="$PADDING_LEFT" y="${HEIGHT - 4}">""" +
                        """${warmUps.size} warm-up</text>""",
                )
                append(
                    """<text class="axis" x="${fmt(it + 4)}" y="${HEIGHT - 4}">""" +
                        """${measured.size} measured</text>""",
                )
            }
            append("</svg>")
        }
    }

    private fun polyline(cssClass: String, points: List<Pair<Double, Double>>): String {
        val coordinates = points.joinToString(" ") { (x, y) -> "${fmt(x)},${fmt(y)}" }
        return """<polyline class="$cssClass" points="$coordinates" />"""
    }

    /** Fixed precision, so the same input always renders the same bytes. */
    private fun fmt(value: Double): String = "%.1f".format(value)

    private fun formatOffScale(value: Double): String = "%.0f".format(value)

    /** How far above the measured values a warm-up may sit before it is clamped. */
    private const val OFF_SCALE_MULTIPLE = 3.0

    /** Vertical room given to a series whose values barely differ. */
    private const val FLAT_SERIES_HEADROOM = 0.05

    private const val PADDING_FRACTION = 0.35
}
