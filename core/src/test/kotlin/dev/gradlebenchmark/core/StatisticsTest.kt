package dev.gradlebenchmark.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class StatisticsTest {

    private val offset = org.assertj.core.data.Offset.offset(1e-9)

    @Test
    fun `a single measurement is its own every statistic`() {
        val stats = Statistics.of(listOf(42.0))

        assertThat(stats.mean).isEqualTo(42.0)
        assertThat(stats.median).isEqualTo(42.0)
        assertThat(stats.min).isEqualTo(42.0)
        assertThat(stats.max).isEqualTo(42.0)
        assertThat(stats.stddev).isEqualTo(0.0)
    }

    @Test
    fun `median of an even count interpolates between the middle pair`() {
        assertThat(Statistics.of(listOf(10.0, 20.0)).median).isEqualTo(15.0)
    }

    @Test
    fun `median of an odd count is the middle value`() {
        assertThat(Statistics.of(listOf(30.0, 10.0, 20.0)).median).isEqualTo(20.0)
    }

    @Test
    fun `input order does not affect the result`() {
        val ascending = Statistics.of(listOf(1.0, 2.0, 3.0, 4.0))
        val descending = Statistics.of(listOf(4.0, 3.0, 2.0, 1.0))

        assertThat(ascending).isEqualTo(descending)
    }

    @Test
    fun `quantiles use R-7 linear interpolation, matching the profiler's report`() {
        // For [1,2,3,4]: pos = 3*0.25 = 0.75 -> 1 + 0.75*(2-1) = 1.75
        val stats = Statistics.of(listOf(1.0, 2.0, 3.0, 4.0))

        assertThat(stats.p25).isEqualTo(1.75, offset)
        assertThat(stats.median).isEqualTo(2.5, offset)
        assertThat(stats.p75).isEqualTo(3.25, offset)
    }

    @Test
    fun `standard deviation is the population deviation, dividing by n`() {
        val values = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)

        // Population sigma is 2.0; the sample deviation would be ~2.138.
        assertThat(Statistics.of(values).stddev).isEqualTo(2.0, offset)
    }

    @Test
    fun `standard deviation matches an independent computation`() {
        val values = listOf(2296.17, 2011.65)
        val mean = values.average()
        val expected = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)

        assertThat(Statistics.of(values).stddev).isEqualTo(expected, offset)
    }

    @Test
    fun `real measurements from an Android project summarize correctly`() {
        // assemble_incremental, measured builds, in milliseconds.
        val stats = Statistics.of(listOf(482.80, 443.81, 438.17))

        assertThat(stats.median).isEqualTo(443.81, offset)
        assertThat(stats.min).isEqualTo(438.17, offset)
        assertThat(stats.max).isEqualTo(482.80, offset)
        assertThat(stats.mean).isEqualTo((482.80 + 443.81 + 438.17) / 3, offset)
    }

    @Test
    fun `no measurements is rejected rather than yielding zeros`() {
        assertThatThrownBy { Statistics.of(emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no measurements")
    }

    @Test
    fun `a statistic selects the value a comparison interprets`() {
        val stats = Statistics.of(listOf(1.0, 2.0, 3.0, 4.0))

        assertThat(Statistic.MEDIAN.from(stats)).isEqualTo(stats.median)
        assertThat(Statistic.MEAN.from(stats)).isEqualTo(stats.mean)
        assertThat(Statistic.P75.from(stats)).isEqualTo(stats.p75)
    }
}
