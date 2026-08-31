plugins {
    alias(libs.plugins.lombok)
}

description = "Platform-agnostic core of Catalog: Modrinth client, jar index, tracking and update logic."

dependencies {
    implementation(libs.okaeri.yaml.snakeyaml)
    implementation(libs.lamp.common)

    // Both Paper and Velocity ship Gson, so it must not be bundled or relocated.
    compileOnly(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.gson)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    // The live-API tests are opt-in: `gradlew :common:integrationTest`.
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
