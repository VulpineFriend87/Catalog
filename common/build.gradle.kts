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
