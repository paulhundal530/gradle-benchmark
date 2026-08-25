package dev.gradlebenchmark.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ObservationTest {

    /** Converged measurements from a real Android incremental build, in ms. */
    private val realSamples = listOf(339.0, 329.0, 320.0, 379.0, 316.0, 328.0, 326.0)

    private fun observe(baseline: List<Double>, candidate: List<Double>) =
        Observation.of(baseline, candidate, unit = "ms")

    @Test
    fun `the same input always produces the same interval`() {
        val first = observe(realSamples, realSamples.map { it * 1.1 })
        val second = observe(realSamples, realSamples.map { it * 1.1 })

        // A verdict that changed between two runs of identical data would be indefensible.
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `delta is reported relative to the baseline`() {
        val observation = observe(listOf(100.0, 100.0, 100.0), listOf(110.0, 110.0, 110.0))

        assertThat(observation.deltaPercent).isEqualTo(10.0)
    }

    @Test
    fun `a slower candidate reports slower, a faster one faster`() {
        val slower = observe(listOf(100.0, 100.0, 100.0, 100.0), listOf(200.0, 200.0, 200.0, 200.0))
        val faster = observe(listOf(200.0, 200.0, 200.0, 200.0), listOf(100.0, 100.0, 100.0, 100.0))

        assertThat(slower.direction).isEqualTo(Direction.SLOWER)
        assertThat(faster.direction).isEqualTo(Direction.FASTER)
    }

    @Test
    fun `a difference smaller than the run can resolve is reported as indistinguishable`() {
        // Noisy samples, tiny difference between them.
        val observation = observe(realSamples, realSamples.map { it * 1.005 })

        assertThat(observation.distinguishable).isFalse()
        assertThat(observation.direction).isEqualTo(Direction.INDISTINGUISHABLE)
    }

    @Test
    fun `a large difference on noisy data is still distinguishable`() {
        val observation = observe(realSamples, realSamples.map { it * 1.5 })

        assertThat(observation.distinguishable).isTrue()
        assertThat(observation.direction).isEqualTo(Direction.SLOWER)
    }

    @Test
    fun `more iterations resolve smaller differences`() {
        val few = observe(realSamples.take(3), realSamples.take(3))
        val many = observe(realSamples + realSamples + realSamples, realSamples + realSamples + realSamples)

        assertThat(many.resolvablePercent)
            .describedAs("Resolution should improve with sample size")
            .isLessThan(few.resolvablePercent)
    }

    @Test
    fun `identical data is never distinguishable from itself`() {
        // The A/A case: comparing a result against itself must never look like a change.
        val observation = observe(realSamples, realSamples)

        assertThat(observation.deltaPercent).isEqualTo(0.0)
        assertThat(observation.distinguishable).isFalse()
    }

    @Test
    fun `sample sizes are recorded, because resolution follows from them`() {
        val observation = observe(realSamples.take(2), realSamples.take(5))

        assertThat(observation.baselineSampleSize).isEqualTo(2)
        assertThat(observation.candidateSampleSize).isEqualTo(5)
    }

    @Test
    fun `comparing without measurements is rejected`() {
        assertThatThrownBy { observe(emptyList(), realSamples) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an unmistakable difference is established even when noise cannot be estimated`() {
        // A candidate five times faster, from a real pairing. Refusing to call that
        // distinguishable would withhold something the data plainly shows.
        val observation = observe(listOf(2296.0, 2012.0), listOf(482.0, 444.0, 438.0))

        assertThat(observation.distinguishable).isTrue()
        assertThat(observation.direction).isEqualTo(Direction.FASTER)
        assertThat(observation.resolutionReliable)
            .describedAs("Two and three iterations cannot estimate noise")
            .isFalse()
    }

    @Test
    fun `a marginal difference at tiny sample sizes is not established`() {
        // Barely separated, and at n=2 the interval that separates them is optimistic.
        val observation = observe(listOf(100.0, 101.0), listOf(101.0, 102.0))

        assertThat(observation.distinguishable)
            .describedAs("A wider margin is required when the interval cannot be trusted")
            .isFalse()
    }

    @Test
    fun `warm-up counts are carried through so a result can be reproduced`() {
        val observation = Observation.of(
            baseline = realSamples,
            candidate = realSamples,
            unit = "ms",
            baselineWarmUps = 3,
            candidateWarmUps = 4,
        )

        assertThat(observation.baselineWarmUpCount).isEqualTo(3)
        assertThat(observation.candidateWarmUpCount).isEqualTo(4)
    }
}
