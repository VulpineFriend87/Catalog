plugins {
    alias(libs.plugins.lombok)
    alias(libs.plugins.shadow)
}

description = "Velocity frontend for Catalog."

dependencies {
    implementation(project(":core"))

    implementation(libs.okaeri.yaml.snakeyaml)
    implementation(libs.commons)
    implementation(libs.lamp.common)
    implementation(libs.lamp.velocity)
    implementation(libs.lamp.brigadier)

    compileOnly(libs.velocity)
    compileOnly(libs.gson)
}

tasks {

    jar {
        enabled = false
    }

    processResources {
        val version = project.version.toString()
        inputs.property("version", version)
        filesMatching("velocity-plugin.json") {
            expand("version" to version)
        }
    }

    shadowJar {
        archiveFileName.set("Catalog-Velocity-${project.version}.jar")

        val basePackage = "top.vulpine.catalog.libs"
        fun shade(original: String, shaded: String) {
            relocate(original, "${basePackage}.${shaded}")
        }

        shade("eu.okaeri", "okaeri")
        shade("org.yaml.snakeyaml", "snakeyaml")
        shade("top.vulpine.commons", "commons")
        shade("revxrsal.commands", "lamp")
    }

    build {
        dependsOn(shadowJar)
    }

}
