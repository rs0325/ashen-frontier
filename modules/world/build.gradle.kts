plugins {
    id("ashenfrontier.mod")
}

val screenshots = sourceSets.create("screenshots") {
    java.srcDir(rootDir.resolve("tools/screenshots/src/main/java"))
    resources.srcDir(rootDir.resolve("tools/screenshots/src/main/resources"))
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[screenshots.implementationConfigurationName].extendsFrom(configurations.implementation.get())
sourceSets.test {
    java.srcDir(rootDir.resolve("tools/screenshots/src/test/java"))
    compileClasspath += screenshots.output
    runtimeClasspath += screenshots.output
}
tasks.named("check") { dependsOn(screenshots.classesTaskName) }

val planPath = providers.gradleProperty("plan").orElse("").get()
val planFile = rootDir.resolve(planPath).absoluteFile
val screenshotOutput = rootDir.resolve("build/screenshots/${planFile.nameWithoutExtension}")
val prepareScreenshotRun = tasks.register("prepareScreenshotRun") {
    val plan = planPath
    val input = planFile
    val clientDir = screenshotOutput.resolve("client")
    doLast {
        require(plan.isNotBlank() && input.isFile) { "-Pplan=<撮影計画JSON>を指定してください" }
        require(input.nameWithoutExtension.matches(Regex("[a-zA-Z0-9_-]+"))) { "計画ファイル名には英数字・_・-を使ってください" }
        clientDir.resolve("config").mkdirs()
        clientDir.resolve("config/fml.toml").writeText("earlyWindowControl = false\n")
        clientDir.resolve("options.txt").writeText("fullscreen:false\npauseOnLostFocus:false\nonboardAccessibility:false\n")
    }
}

neoForge {
    addModdingDependenciesTo(screenshots)
    val captureMod = mods.register("ashen_frontier_screenshots") { sourceSet(screenshots) }
    runs.configureEach {
        if (name != "screenshots") {
            loadedMods.set(provider { mods.filter { it.name != captureMod.name } })
        }
    }
    runs.register("screenshots") {
        client()
        disableIdeRun()
        sourceSet.set(screenshots)
        gameDirectory.set(screenshotOutput.resolve("client"))
        taskBefore(prepareScreenshotRun)
        systemProperty("ashen_frontier.screenshotPlan", planFile.absolutePath)
        systemProperty("ashen_frontier.screenshotOutput", screenshotOutput.absolutePath)
    }
}

neoForge {
    unitTest {
        enable()
        testedMod.set(mods.getByName("ashen_frontier_world"))
    }
}

dependencies {
    testImplementation("com.mojang:datafixerupper:8.0.16")
}

val undergroundTests = sourceSets.create("undergroundTest") {
    java.srcDir(projectDir.parentFile.parentFile.resolve("src/undergroundTest/java"))
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
    runtimeClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
}

neoForge.mods.named("ashen_frontier_world") {
    sourceSet(undergroundTests)
}

val structures = sourceSets.create("structures") {
    java.srcDir(rootDir.resolve("tools/structures/src/main/java"))
    resources.srcDir(rootDir.resolve("tools/structures/src/main/resources"))
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val structureGameTest = sourceSets.create("structureGameTest") {
    java.srcDir(rootDir.resolve("tools/structures/src/gametest/java"))
    resources.srcDir(rootDir.resolve("tools/structures/src/gametest/resources"))
    resources.srcDir(rootDir.resolve("tools/structures/fixtures/converted"))
    resources.srcDir(rootDir.resolve("tools/structures/fixtures/test-resources"))
    resources.exclude("conversion-report.json")
    compileClasspath += sourceSets.main.get().output + structures.output
    runtimeClasspath += sourceSets.main.get().output + structures.output
}
configurations[structures.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[structureGameTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
sourceSets.test {
    java.srcDir(rootDir.resolve("tools/structures/src/test/java"))
    resources.srcDir(rootDir.resolve("tools/structures/fixtures"))
    compileClasspath += structures.output
    runtimeClasspath += structures.output
}
tasks.test {
    systemProperty("ashen_frontier.structureFixturesPath", rootDir.resolve("tools/structures/fixtures").absolutePath)
}
neoForge {
    addModdingDependenciesTo(structures)
    addModdingDependenciesTo(structureGameTest)
    mods.named("ashen_frontier_world") {
        sourceSet(structures)
        sourceSet(structureGameTest)
    }
    runs.register("structureConvert") {
        data()
        systemProperty("ashen_frontier.structureConfig", providers.gradleProperty("structureConfig").orElse("").get())
        programArguments.addAll("--mod", "ashen_frontier_world", "--all",
                "--output", layout.buildDirectory.dir("structure-datagen").get().asFile.absolutePath)
    }
    runs.register("structureFixtures") {
        data()
        systemProperty("ashen_frontier.structureFixtures", rootDir.resolve("tools/structures/fixtures").absolutePath)
        programArguments.addAll("--mod", "ashen_frontier_world", "--all",
                "--output", layout.buildDirectory.dir("fixture-datagen").get().asFile.absolutePath)
    }
}

sourceSets.test {
    resources.srcDir(rootDir.resolve("tools/fixed-map/fixtures"))
}
