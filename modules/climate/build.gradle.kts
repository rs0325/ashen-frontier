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
