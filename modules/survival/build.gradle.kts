plugins { id("ashenfrontier.mod") }

for (module in listOf("world", "climate")) {
    val dependency = ":$module:${project.name}"
    evaluationDependsOn(dependency)
    dependencies { implementation(project(dependency)) }
    neoForge.mods.register("ashen_frontier_$module") { sourceSet(project(dependency).sourceSets.main.get()) }
}

val primitiveFixtureProject = ":primitive:${project.name}"
evaluationDependsOn(primitiveFixtureProject)
neoForge.mods.register("ashen_frontier_primitive") { sourceSet(project(primitiveFixtureProject).sourceSets.main.get()) }
neoForge.runs.configureEach {
    if (name == "gameTestServer") loadedMods.set(neoForge.mods)
    else loadedMods.set(neoForge.mods.filter { it.name != "ashen_frontier_primitive" })
}
