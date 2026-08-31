plugins {
    alias(libs.plugins.lombok)
}

description = "Platform-agnostic core of Catalog: Modrinth client, jar index, tracking and update logic."

dependencies {
    // Nothing is bundled here on purpose. The core parses no YAML — SnakeYAML crosses a major
    // version boundary across supported Paper releases — and Gson is provided by both platforms,
    // so it must not be shaded or relocated.
    compileOnly(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.gson)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    // The live-API tests are opt-in: `gradlew :core:integrationTest`.
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs the tests that call the live Modrinth API."
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    useJUnitPlatform {
        includeTags("integration")
    }

    // Always re-run: the point is to check the API still behaves as expected.
    outputs.upToDateWhen { false }
}
