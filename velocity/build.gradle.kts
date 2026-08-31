plugins {
    alias(libs.plugins.lombok)
    alias(libs.plugins.shadow)
}

description = "Velocity frontend for Catalog."

dependencies {
    implementation(project(":core"))

    implementation(libs.okaeri.yaml.snakeyaml)
    implementation(libs.bstats.velocity)
    implementation(libs.lamp.common)
    implementation(libs.lamp.velocity)

    // Velocity generates velocity-plugin.json from the @Plugin annotation.
    compileOnly(libs.velocity)
    annotationProcessor(libs.velocity)

    compileOnly(libs.gson)
}

tasks {

    jar {
        enabled = false
    }

    shadowJar {
        archiveFileName.set("Catalog-Velocity-${project.version}.jar")

        val basePackage = "top.vulpine.catalog.libs"
        fun shade(original: String, shaded: String) {
            relocate(original, "${basePackage}.${shaded}")
        }

        shade("eu.okaeri", "okaeri")
        shade("org.bstats", "bstats")
        shade("revxrsal.commands", "lamp")
    }

    build {
        dependsOn(shadowJar)
    }

}
