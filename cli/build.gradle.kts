plugins {
    id("gradle-benchmark.kotlin-conventions")
    application
}

description = "Command line interface. Parses arguments, invokes core use cases, maps exit codes."

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    testImplementation(project(":engine"))
    testImplementation(project(":report"))
    implementation(project(":report"))
    implementation(libs.clikt)
}

// run.json records the tool version, so it has to come from the build rather than a
// constant somebody has to remember to bump.
val generateToolVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/toolVersion")
    val toolVersion = providers.gradleProperty("version").getOrElse("0.0.0-dev")
    inputs.property("toolVersion", toolVersion)
    outputs.dir(outputDir)
    doLast {
        val packageDir = outputDir.get().asFile.resolve("dev/gradlebenchmark/cli")
        packageDir.mkdirs()
        packageDir.resolve("ToolVersion.kt").writeText(
            """
            package dev.gradlebenchmark.cli

            /** Generated from the Gradle project version. */
            internal const val TOOL_VERSION: String = "$toolVersion"

            """.trimIndent(),
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateToolVersion)
}

application {
    mainClass.set("dev.gradlebenchmark.cli.MainKt")
    applicationName = "gradle-benchmark"
}

tasks.test {
    // The CLI surface snapshots live in docs/, so tests need the repository root.
    systemProperty("gradleBenchmark.repoRoot", rootProject.projectDir.absolutePath)
    // Regenerate with: ./gradlew :cli:test -PupdateCliSurface
    systemProperty("gradleBenchmark.updateCliSurface", providers.gradleProperty("updateCliSurface").isPresent)
    inputs.dir(rootProject.layout.projectDirectory.dir("docs/cli-surface"))
}
