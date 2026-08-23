plugins {
    id("gradle-benchmark.kotlin-conventions")
    application
}

description = "Command line interface. Parses arguments, invokes core use cases, maps exit codes."

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":report"))
    implementation(libs.clikt)
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
