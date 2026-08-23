plugins {
    id("gradle-benchmark.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
}

description = "Domain model and comparison engine. No I/O, no process execution, no CI concepts."

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
