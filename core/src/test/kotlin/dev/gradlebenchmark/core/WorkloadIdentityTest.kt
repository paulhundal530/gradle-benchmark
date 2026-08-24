package dev.gradlebenchmark.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WorkloadIdentityTest {

    private fun identity(
        name: String = "baseline",
        tasks: String? = "assembleDebug",
        mutators: List<String> = emptyList(),
        args: List<String> = emptyList(),
        jvmArgs: List<String> = emptyList(),
        systemProperties: Map<String, String> = emptyMap(),
    ) = WorkloadIdentity(
        name = name,
        tasks = tasks,
        action = "run tasks",
        cleanup = "do nothing",
        invoker = "ToolingApi",
        mutators = mutators,
        args = args,
        jvmArgs = jvmArgs,
        systemProperties = systemProperties,
    )

    @Test
    fun `the same identity always hashes the same`() {
        assertThat(identity().hash()).isEqualTo(identity().hash())
    }

    @Test
    fun `the hash is a hex sha-256`() {
        assertThat(identity().hash()).matches("[0-9a-f]{64}")
    }

    @Test
    fun `changing the work being done changes the hash`() {
        assertThat(identity(tasks = "assembleRelease").hash())
            .isNotEqualTo(identity(tasks = "assembleDebug").hash())
    }

    @Test
    fun `adding a build argument changes the hash`() {
        // --rerun-tasks changes what work is performed, so these are not the same benchmark.
        assertThat(identity(args = listOf("--rerun-tasks")).hash())
            .isNotEqualTo(identity().hash())
    }

    @Test
    fun `system property order does not affect the hash`() {
        val first = identity(systemProperties = mapOf("a" to "1", "b" to "2"))
        val second = identity(systemProperties = mapOf("b" to "2", "a" to "1"))

        assertThat(first.hash()).isEqualTo(second.hash())
    }

    @Test
    fun `argument order does affect the hash, because it can change behavior`() {
        val first = identity(args = listOf("--offline", "--no-build-cache"))
        val second = identity(args = listOf("--no-build-cache", "--offline"))

        assertThat(first.hash()).isNotEqualTo(second.hash())
    }

    @Test
    fun `list elements cannot bleed into one another`() {
        // Without a real separator these render identically and collide, so two different
        // benchmarks would be treated as the same workload.
        val first = identity(args = listOf("--ab", "--c"))
        val second = identity(args = listOf("--a", "b--c"))

        assertThat(first.hash())
            .describedAs("Distinct argument lists must not share an identity hash")
            .isNotEqualTo(second.hash())
    }

    @Test
    fun `a value cannot impersonate a field boundary`() {
        val first = identity(args = listOf("x"), jvmArgs = listOf("y"))
        val second = identity(args = listOf("x", "y"), jvmArgs = emptyList())

        assertThat(first.hash()).isNotEqualTo(second.hash())
    }

    @Test
    fun `an empty list differs from a list holding an empty string`() {
        assertThat(identity(args = emptyList()).hash())
            .isNotEqualTo(identity(args = listOf("")).hash())
    }

    @Test
    fun `fields cannot bleed into one another`() {
        // Without separators, tasks="ab" + action="c" would hash the same as "a" + "bc".
        val first = identity(name = "ab", tasks = "c")
        val second = identity(name = "a", tasks = "bc")

        assertThat(first.hash()).isNotEqualTo(second.hash())
    }
}

/**
 * The mutator path problem, found by benchmarking a real Android project.
 *
 * Mutator descriptions embed absolute paths. Hashing them unchanged would make the same
 * benchmark fingerprint differently on a laptop and on CI, so every historical comparison
 * would report an incompatible baseline, silently.
 */
class MutatorNormalizationTest {

    private val projectDir = Path.of("/Users/someone/Projects/App")

    /** Verbatim from a real run against an Android project. */
    private val realMutator =
        "ApplyAbiChangeToSourceFileMutator(/Users/someone/Projects/App/./app/src/main/" +
            "java/com/example/data/NumberValidationRepository.kt)"

    @Test
    fun `an absolute path is rewritten relative to the project`() {
        val normalized = WorkloadIdentity.normalizeMutator(realMutator, projectDir)

        assertThat(normalized).isEqualTo(
            "ApplyAbiChangeToSourceFileMutator(app/src/main/java/com/example/data/" +
                "NumberValidationRepository.kt)",
        )
    }

    @Test
    fun `the mutator kind is preserved, because it is part of the identity`() {
        val normalized = WorkloadIdentity.normalizeMutator(realMutator, projectDir)

        assertThat(normalized).startsWith("ApplyAbiChangeToSourceFileMutator(")
        assertThat(normalized).endsWith("NumberValidationRepository.kt)")
    }

    @Test
    fun `which file is mutated still distinguishes two benchmarks`() {
        val other = realMutator.replace("NumberValidationRepository", "SomethingElse")

        assertThat(WorkloadIdentity.normalizeMutator(realMutator, projectDir))
            .isNotEqualTo(WorkloadIdentity.normalizeMutator(other, projectDir))
    }

    @Test
    fun `a relative and an absolute project directory agree`() {
        // --project-dir . produces a "/./" segment that must not change the fingerprint.
        val withDotSegment = realMutator
        val withoutDotSegment = realMutator.replace("/App/./app/", "/App/app/")

        assertThat(WorkloadIdentity.normalizeMutator(withDotSegment, projectDir))
            .isEqualTo(WorkloadIdentity.normalizeMutator(withoutDotSegment, projectDir))
    }

    @Test
    fun `a path outside the project is left alone rather than mangled`() {
        val outside = "SomeMutator(/etc/shared/config.txt)"

        assertThat(WorkloadIdentity.normalizeMutator(outside, projectDir)).isEqualTo(outside)
    }

    @Test
    fun `a mutator with no path is unchanged`() {
        val noPath = "ClearBuildCacheMutator"

        assertThat(WorkloadIdentity.normalizeMutator(noPath, projectDir)).isEqualTo(noPath)
    }

    @Test
    fun `without a project directory nothing is rewritten`() {
        assertThat(WorkloadIdentity.normalizeMutator(realMutator, projectDir = null))
            .isEqualTo(realMutator)
    }

    @Test
    fun `THE INVARIANT - the same benchmark on two machines hashes identically`() {
        val laptopProject = Path.of("/Users/someone/Projects/App")
        val ciProject = Path.of("/home/runner/work/App/App")

        val laptop = WorkloadIdentity(
            name = "assemble_incremental",
            tasks = "assembleDebug",
            mutators = listOf(
                WorkloadIdentity.normalizeMutator(
                    "ApplyAbiChangeToSourceFileMutator(/Users/someone/Projects/App/./app/src/Repo.kt)",
                    laptopProject,
                ),
            ),
        )
        val ci = WorkloadIdentity(
            name = "assemble_incremental",
            tasks = "assembleDebug",
            mutators = listOf(
                WorkloadIdentity.normalizeMutator(
                    "ApplyAbiChangeToSourceFileMutator(/home/runner/work/App/App/app/src/Repo.kt)",
                    ciProject,
                ),
            ),
        )

        assertThat(laptop.hash())
            .describedAs("A laptop run and a CI run of the same benchmark must be comparable")
            .isEqualTo(ci.hash())
    }

    @Test
    fun `THE INVARIANT - a Gradle upgrade does not change workload identity`() {
        // Gradle version lives in WorkloadConfiguration, so it cannot reach the hash at all.
        val identity = WorkloadIdentity(name = "clean-build", tasks = "assembleDebug")

        val before = WorkloadConfiguration(gradleVersion = "9.1", buildJvmVersion = "21.0.8")
        val after = WorkloadConfiguration(gradleVersion = "9.2", buildJvmVersion = "25.0.1")

        assertThat(identity.hash()).isEqualTo(identity.hash())
        assertThat(before).isNotEqualTo(after)
    }
}
