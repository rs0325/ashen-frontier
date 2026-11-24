plugins {
    id("ashenfrontier.mod")
}

val primitive = ":primitive:${project.name}"
evaluationDependsOn(primitive)
dependencies { implementation(project(primitive)) }
neoForge.mods.register("ashen_frontier_primitive") {
    sourceSet(project(primitive).sourceSets.main.get())
}
