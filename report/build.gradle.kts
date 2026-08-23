plugins {
    id("gradle-benchmark.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
}

description = "JSON serialization and HTML rendering of the normalized model."

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.html)
}
