plugins {
    id("gradle-benchmark.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
}

description = "Scenario discovery, gradle-profiler invocation, and benchmark.json normalization."

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(project(":core"))
                implementation(libs.assertj)
            }
            targets.configureEach {
                testTask.configure {
                    // Exercises a real gradle-profiler process against a real Gradle build,
                    // so mocks cannot quietly diverge from the tool's actual behavior.
                    systemProperty(
                        "gradleBenchmark.fixtureDir",
                        rootProject.layout.projectDirectory.dir("integration-test/fixture").asFile.absolutePath,
                    )
                }
            }
        }
    }
}
