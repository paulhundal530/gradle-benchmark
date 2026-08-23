plugins {
    id("gradle-benchmark.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
}

description = "Scenario discovery, gradle-profiler invocation, and benchmark.json normalization."

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
}
