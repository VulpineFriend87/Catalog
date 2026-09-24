import net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default

plugins {
    alias(libs.plugins.lombok)
    alias(libs.plugins.shadow)
    alias(libs.plugins.pluginyml)
}

description = "A free and open source Modrinth plugin manager with automatic tracking, updates and dependency management."

dependencies {
    implementation(project(":core"))

    implementation(libs.okaeri.yaml.bukkit)
    implementation(libs.okaeri.serdes.bukkit)
    implementation(libs.bstats.bukkit)
    implementation(libs.folialib)
    implementation(libs.commons)
    implementation(libs.lamp.common)
    implementation(libs.lamp.bukkit)

    compileOnly(libs.paper)

    testImplementation(libs.junit)
    testImplementation(libs.paper)
    testRuntimeOnly(libs.junit.launcher)
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

    val commands = linkedMapOf(
        "about" to "Shows the version and credits.",
        "help" to "Lists all commands.",
        "list" to "Lists managed plugins and the history.",
        "info" to "Shows a plugin's details, versions and dependencies.",
        "search" to "Searches Modrinth.",
        "install" to "Installs plugins.",
        "update" to "Downloads and cancels updates.",
        "uninstall" to "Moves plugins to the trash.",
        "trash" to "Opens the trash, restores and deletes removals.",
        "settings" to "Changes a plugin's settings.",
        "channel" to "Changes a plugin's release channel.",
        "hold" to "Holds and unholds plugins.",
        "untrack" to "Tracks and untracks plugins.",
        "reload" to "Reloads the configuration."
    )

    permissions {
        commands.forEach { (command, text) ->
            register("catalog.command.$command") {
                description = text
                default = Default.OP
            }
        }

        register("catalog.admin") {
            description = "Grants every Catalog permission."
            default = Default.OP
            children = commands.keys.map { "catalog.command.$it" }
        }
    }
}
