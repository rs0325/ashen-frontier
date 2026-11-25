plugins {
    id("ashenfrontier.mod")
}

val world = ":world:${project.name}"
evaluationDependsOn(world)
dependencies { implementation(project(world)) }
neoForge.mods.register("ashen_frontier_world") {
    sourceSet(project(world).sourceSets.main.get())
}

dependencies { testRuntimeOnly("com.google.code.gson:gson:2.10.1") }


// 実設備の配置/回収/複製はGameTestだけで統合検査し、配布物の依存には加えない。
val primitiveFixtureProject = ":primitive:${project.name}"
evaluationDependsOn(primitiveFixtureProject)
neoForge.mods.register("ashen_frontier_primitive") {
    sourceSet(project(primitiveFixtureProject).sourceSets.main.get())
}
neoForge.runs.configureEach {
    if (name == "gameTestServer") loadedMods.set(neoForge.mods)
    else loadedMods.set(neoForge.mods.filter { it.name != "ashen_frontier_primitive" })
}
