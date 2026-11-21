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
