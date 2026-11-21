pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.10"
}

rootProject.name = "ashen-frontier"

val minecraftVersions = listOf("1.21.1")
val featureModules = listOf("world")

stonecutter {
    create(rootProject) {
        branch("core") { versions(minecraftVersions) }
        featureModules.forEach { branch(it) { versions(minecraftVersions) } }
    }
}

// 機能Modはmodules/配下に置く。Stonecutterは既定でルート直下に配置するため、版プロジェクトごと移す。
featureModules.forEach { name ->
    project(":$name").apply {
        projectDir = file("modules/$name")
        children.forEach { it.projectDir = projectDir.resolve("versions/${it.name}").apply { mkdirs() } }
    }
}
