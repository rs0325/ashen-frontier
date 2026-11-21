plugins {
    id("ashenfrontier.mod")
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
