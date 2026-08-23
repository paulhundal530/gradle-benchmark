package dev.gradlebenchmark.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class ScenarioDiscoveryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun scenarioFile(name: String): Path = tempDir.resolve(name).also { it.writeText("placeholder { }\n") }

    @Test
    fun `finds only scenario files, sorted`() {
        scenarioFile("zeta.scenarios")
        scenarioFile("alpha.scenarios")
        tempDir.resolve("README.md").createFile()
        tempDir.resolve("notes.txt").createFile()

        val found = ScenarioDiscovery.findScenarioFiles(tempDir)

        assertThat(found.map { it.fileName.toString() })
            .containsExactly("alpha.scenarios", "zeta.scenarios")
    }

    @Test
    fun `does not descend into subdirectories`() {
        scenarioFile("top.scenarios")
        val nested = tempDir.resolve("nested").also { it.createDirectory() }
        nested.resolve("deep.scenarios").writeText("x { }")

        val found = ScenarioDiscovery.findScenarioFiles(tempDir)

        assertThat(found.map { it.fileName.toString() }).containsExactly("top.scenarios")
    }

    @Test
    fun `a missing directory yields no files rather than throwing`() {
        assertThat(ScenarioDiscovery.findScenarioFiles(tempDir.resolve("absent"))).isEmpty()
    }

    @Test
    fun `selecting nothing explains both ways to select`() {
        val result = ScenarioDiscovery.selectScenarioFile(scenarioDir = null, scenarioFile = null)

        assertThat(result).isInstanceOf(ScenarioFileSelection.Failed::class.java)
        val problem = (result as ScenarioFileSelection.Failed).problem
        assertThat(problem.summary).contains("No scenario file was selected")
        assertThat(problem.detail).contains("--scenario-file").contains("--scenario-dir")
    }

    @Test
    fun `a missing directory names the absolute path it checked`() {
        val absent = tempDir.resolve("absent")

        val result = ScenarioDiscovery.selectScenarioFile(scenarioDir = absent, scenarioFile = null)

        val problem = (result as ScenarioFileSelection.Failed).problem
        assertThat(problem.summary).contains("Scenario directory does not exist")
        assertThat(problem.detail).contains(absent.toAbsolutePath().toString())
    }

    @Test
    fun `an empty directory reports what it checked`() {
        val result = ScenarioDiscovery.selectScenarioFile(scenarioDir = tempDir, scenarioFile = null)

        val problem = (result as ScenarioFileSelection.Failed).problem
        assertThat(problem.summary).contains("No .scenarios files found")
    }

    @Test
    fun `a single scenario file in a directory is unambiguous`() {
        val only = scenarioFile("build.scenarios")

        val result = ScenarioDiscovery.selectScenarioFile(scenarioDir = tempDir, scenarioFile = null)

        assertThat(result).isEqualTo(ScenarioFileSelection.Selected(only))
    }

    @Test
    fun `multiple candidates ask the user to choose and list them`() {
        scenarioFile("build.scenarios")
        scenarioFile("configuration.scenarios")

        val result = ScenarioDiscovery.selectScenarioFile(scenarioDir = tempDir, scenarioFile = null)

        val problem = (result as ScenarioFileSelection.Failed).problem
        assertThat(problem.summary).contains("--scenario-file")
        assertThat(problem.detail).contains("build.scenarios").contains("configuration.scenarios")
    }

    @Test
    fun `a relative scenario file resolves against the scenario directory`() {
        val expected = scenarioFile("build.scenarios")

        val result = ScenarioDiscovery.selectScenarioFile(
            scenarioDir = tempDir,
            scenarioFile = Path.of("build.scenarios"),
        )

        assertThat(result).isEqualTo(ScenarioFileSelection.Selected(expected))
    }

    @Test
    fun `an absolute scenario file wins over the scenario directory`() {
        scenarioFile("build.scenarios")
        val elsewhere = tempDir.resolve("other").also { it.createDirectory() }
        val absolute = elsewhere.resolve("special.scenarios").also { it.writeText("x { }") }

        val result = ScenarioDiscovery.selectScenarioFile(tempDir, absolute)

        assertThat(result).isEqualTo(ScenarioFileSelection.Selected(absolute))
    }

    @Test
    fun `a scenario file used alone needs no directory`() {
        val standalone = scenarioFile("build.scenarios")

        val result = ScenarioDiscovery.selectScenarioFile(scenarioDir = null, scenarioFile = standalone)

        assertThat(result).isEqualTo(ScenarioFileSelection.Selected(standalone))
    }

    @Test
    fun `a missing named file lists what the directory does contain`() {
        scenarioFile("build.scenarios")
        scenarioFile("configuration.scenarios")

        val result = ScenarioDiscovery.selectScenarioFile(tempDir, Path.of("typo.scenarios"))

        val problem = (result as ScenarioFileSelection.Failed).problem
        assertThat(problem.summary).contains("Scenario file does not exist")
        assertThat(problem.detail).contains("build.scenarios").contains("configuration.scenarios")
    }
}
