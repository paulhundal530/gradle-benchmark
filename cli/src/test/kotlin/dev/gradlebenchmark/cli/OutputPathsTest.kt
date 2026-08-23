package dev.gradlebenchmark.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class OutputPathsTest {

    @Test
    fun `defaults to build gradle-benchmark when unset`() {
        assertThat(OutputPaths.resolve(null).outputDir).isEqualTo(Path.of("build", "gradle-benchmark"))
    }

    @Test
    fun `honours an explicit output directory`() {
        val explicit = Path.of("/tmp", "gb-out")

        assertThat(OutputPaths.resolve(explicit).outputDir).isEqualTo(explicit)
    }

    @Test
    fun `artifact names are stable and raw output is nested separately`() {
        val paths = OutputPaths.resolve(Path.of("out"))

        assertThat(paths.runJson).isEqualTo(Path.of("out", "run.json"))
        assertThat(paths.comparisonJson).isEqualTo(Path.of("out", "comparison.json"))
        assertThat(paths.reportHtml).isEqualTo(Path.of("out", "report.html"))
        assertThat(paths.rawDir).isEqualTo(Path.of("out", "raw"))
    }
}
