plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serializationGradlePlugin)
    implementation(libs.ktlint.gradlePlugin)
}
