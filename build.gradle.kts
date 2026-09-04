plugins {
    alias(libs.plugins.lombok) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.pluginyml) apply false
}

subprojects {

    apply(plugin = "java-library")

    group = "top.vulpine"
    version = "0.5.0"

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.tcoded.com/releases")
        maven("https://repo.extendedclip.com/releases/")
        maven("https://repo.okaeri.cloud/releases")
        maven("https://repo.vulpine.top/repository/maven-open/")
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile> {
        // Lamp resolves command parameter names by reflection.
        options.compilerArgs.add("-parameters")
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

}
