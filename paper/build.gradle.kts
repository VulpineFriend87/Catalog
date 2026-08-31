plugins {
    alias(libs.plugins.lombok)
    alias(libs.plugins.shadow)
    alias(libs.plugins.pluginyml)
}

description = "A free and open source Modrinth plugin manager with automatic tracking, updates and dependency management."

dependencies {
    implementation(project(":common"))

    implementation(libs.okaeri.yaml.bukkit)
    implementation(libs.okaeri.serdes.bukkit)
    implementation(libs.bstats.bukkit)
    implementation(libs.folialib)
    implementation(libs.commons)
    implementation(libs.lamp.common)
    implementation(libs.lamp.bukkit)
    implementation(libs.inventoryframework)

    compileOnly(libs.paper)
    compileOnly(libs.papi)
    compileOnly(libs.gson)
}

tasks {

    jar {
        enabled = false
    }

    shadowJar {
        archiveFileName.set("Catalog-Paper-${project.version}.jar")

        val basePackage = "top.vulpine.catalog.libs"
        fun shade(original: String, shaded: String) {
            relocate(original, "${basePackage}.${shaded}")
        }

        shade("eu.okaeri", "okaeri")
        shade("org.bstats", "bstats")
        shade("com.tcoded.folialib", "folialib")
        shade("top.vulpine.commons", "commons")
        shade("revxrsal.commands", "lamp")
        shade("com.github.stefvanschie.inventoryframework", "inventoryframework")
    }

    build {
        dependsOn(shadowJar)
    }

}

bukkit {
    name = "Catalog"
    description = project.description
    version = project.version.toString()
    apiVersion = "1.18"
    main = "top.vulpine.catalog.paper.CatalogPaper"

    author = "VulpineFriend87"
    website = "https://vulpine.top"
    foliaSupported = true

    softDepend = listOf("PlaceholderAPI")
}
