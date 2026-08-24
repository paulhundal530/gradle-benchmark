// Deliberately trivial. Integration tests exercise the Gradle Profiler integration, not
// Gradle itself, and every second here is paid on every CI run.
tasks.register("work") {
    val output = layout.buildDirectory.file("work.txt")
    outputs.file(output)
    doLast { output.get().asFile.writeText("work\n") }
}
