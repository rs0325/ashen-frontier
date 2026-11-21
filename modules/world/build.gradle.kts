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
