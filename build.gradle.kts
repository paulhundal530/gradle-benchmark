plugins {
    base
    // Applied at the root as well so the root build scripts are style-checked too; the
    // convention plugin only covers the modules. The Kotlin plugin is declared but not
    // applied purely to put its classes on the classpath, which ktlint-gradle requires.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlint.get())
    ignoreFailures.set(false)
}

// Aggregates per-module integration tests. Deliberately resolved lazily: no module
// declares an integrationTest task until Milestone 3, and an eager lookup would fail
// at configuration time.
tasks.register("integrationTest") {
    group = "verification"
    description = "Runs integration tests that exercise the real gradle-profiler."
    dependsOn(
        provider {
            subprojects.flatMap { project -> project.tasks.matching { it.name == "integrationTest" } }
        },
    )
}
