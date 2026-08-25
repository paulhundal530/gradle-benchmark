// Deliberately trivial. Integration tests exercise the Gradle Profiler integration, not
// Gradle itself, and every second here is paid on every CI run.
tasks.register("work") {
    val output = layout.buildDirectory.file("work.txt")
    outputs.file(output)
    doLast { output.get().asFile.writeText("work\n") }
}

// A reliably slower variant, so comparison tests have a difference large enough to resolve
// without depending on machine noise. The delay is the point; keep it small.
tasks.register("slowWork") {
    val output = layout.buildDirectory.file("slow-work.txt")
    outputs.file(output)
    // Without this the task is up to date after the first build and the delay never runs,
    // which is exactly the trap that made this fixture useless the first time round.
    outputs.upToDateWhen { false }
    doLast {
        Thread.sleep(250)
        output.get().asFile.writeText("slow work\n")
    }
}
