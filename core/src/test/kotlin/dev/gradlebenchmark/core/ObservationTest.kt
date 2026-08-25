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
}

/**
 * The three-way rule is the heart of the model, so each branch is pinned.
 *
 * A straight threshold comparison would assert things the data does not support in both
 * directions: passing a delta the run could not resolve, and flagging a regression it could
 * not distinguish.
 */
class RegressionPolicyTest {

    private val policy = ComparisonPolicy(regressionThresholdPercent = 5.0)

    private fun observation(
        delta: Double,
        resolvable: Double,
        distinguishable: Boolean = delta > resolvable,
        n: Int = 5,
    ) = Observation(
        baselineValue = 100.0,
        candidateValue = 100.0 + delta,
        unit = "ms",
        deltaPercent = delta,
        direction = if (distinguishable) Direction.SLOWER else Direction.INDISTINGUISHABLE,
        resolvablePercent = resolvable,
        distinguishable = distinguishable,
        baselineSampleSize = n,
        candidateSampleSize = n,
        resolutionReliable = n >= Observation.MINIMUM_RELIABLE_SAMPLES,
    )

    @Test
    fun `a clear regression beyond the threshold regresses`() {
        val verdict = RegressionPolicy.judge(observation(delta = 14.2, resolvable = 4.1), policy)

        assertThat(verdict.status).isEqualTo(ScenarioStatus.REGRESSION)
        assertThat(verdict.explanation).contains("14.2%").contains("5.0%")
    }

    @Test
    fun `a small delta on a precise run passes`() {
        // 1% observed, 2% resolvable: even the worst case is 3%, inside a 5% threshold.
        val verdict = RegressionPolicy.judge(observation(delta = 1.0, resolvable = 2.0), policy)

        assertThat(verdict.status).isEqualTo(ScenarioStatus.PASS)
    }

    @Test
    fun `the same small delta on an imprecise run is inconclusive, not a pass`() {
        // 1% observed but 8.8% resolvable: the true difference could be 9%.
        val verdict = RegressionPolicy.judge(observation(delta = 1.0, resolvable = 8.8), policy)

        assertThat(verdict.status)
            .describedAs("Passing here would assert something the experiment did not establish")
            .isEqualTo(ScenarioStatus.INCONCLUSIVE)
    }

    @Test
    fun `a delta above the threshold the run cannot distinguish is not a regression`() {
        val verdict = RegressionPolicy.judge(
            observation(delta = 6.0, resolvable = 8.8, distinguishable = false),
            policy,
        )

        assertThat(verdict.status).isEqualTo(ScenarioStatus.INCONCLUSIVE)
    }

    @Test
    fun `an inconclusive verdict still reports the numbers and says why`() {
        // Enough iterations to trust the interval, but the delta sits inside it.
        val verdict = RegressionPolicy.judge(observation(delta = 2.1, resolvable = 8.8, n = 6), policy)

        assertThat(verdict.explanation)
            .contains("+2.1%")
            .contains("8.8%")
            .contains("not evidence of no change")
            .contains("6")
    }

    @Test
    fun `an improvement passes rather than regressing`() {
        val verdict = RegressionPolicy.judge(
            observation(delta = -18.6, resolvable = 2.0, distinguishable = true),
            policy,
        )

        assertThat(verdict.status).isEqualTo(ScenarioStatus.PASS)
    }

    @Test
    fun `the threshold boundary is exclusive and uses the unrounded value`() {
        // Precise run, so resolution is not what decides these.
        val justUnder = RegressionPolicy.judge(observation(delta = 4.9, resolvable = 0.05), policy)
        val exactly = RegressionPolicy.judge(observation(delta = 5.0, resolvable = 0.0), policy)
        val justOver = RegressionPolicy.judge(observation(delta = 5.001, resolvable = 0.0), policy)

        assertThat(justUnder.status).isEqualTo(ScenarioStatus.PASS)
        assertThat(exactly.status).isEqualTo(ScenarioStatus.PASS)
        assertThat(justOver.status)
            .describedAs("5.001% regresses even though it displays as 5.00%")
            .isEqualTo(ScenarioStatus.REGRESSION)
    }

    @Test
    fun `the policy that produced a verdict is recorded with it`() {
        val custom = ComparisonPolicy(regressionThresholdPercent = 10.0)

        val verdict = RegressionPolicy.judge(observation(delta = 6.0, resolvable = 1.0), custom)

        assertThat(verdict.policy).isEqualTo(custom)
        assertThat(verdict.status).isEqualTo(ScenarioStatus.PASS)
    }

    @Test
    fun `a run too small to estimate noise never passes`() {
        // 1% delta, tiny apparent interval - but at n=2 that interval cannot be trusted.
        val verdict = RegressionPolicy.judge(
            observation(delta = 1.0, resolvable = 0.5, n = 2),
            policy,
        )

        assertThat(verdict.status)
            .describedAs("A pass here would rest on the one number known to be unreliable")
            .isEqualTo(ScenarioStatus.INCONCLUSIVE)
        assertThat(verdict.explanation).contains("too few to estimate how noisy")
    }

    @Test
    fun `a clear regression is still reported even when noise cannot be estimated`() {
        // Too little data to rule a change out is not too little to notice a large one.
        val verdict = RegressionPolicy.judge(
            observation(delta = 60.0, resolvable = 5.0, distinguishable = true, n = 2),
            policy,
        )

        assertThat(verdict.status).isEqualTo(ScenarioStatus.REGRESSION)
    }

    @Test
    fun `resolution is trusted once there are enough iterations`() {
        val verdict = RegressionPolicy.judge(
            observation(delta = 1.0, resolvable = 0.5, n = 4),
            policy,
        )

        assertThat(verdict.status).isEqualTo(ScenarioStatus.PASS)
    }
}
