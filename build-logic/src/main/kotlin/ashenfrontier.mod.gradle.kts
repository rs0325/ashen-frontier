import java.util.Properties

// Stonecutterの版プロジェクト（例: :core:1.21.1）へ適用する共通設定。
// プロジェクト名がMinecraftバージョン、親プロジェクト名がモジュール名になる。
plugins {
    `java-library`
    id("net.neoforged.moddev")
}

val minecraftVersion = project.name
val module = parent!!.name
val modId = "ashen_frontier_$module"
val mc = Properties().apply {
    rootDir.resolve("gradle/versions/$minecraftVersion.properties").reader().use(::load)
}

// 版プロジェクト名は全モジュールで同じため、groupでモジュールを区別しないと依存解決で衝突する。
group = "${property("mod_group")}.$module"
version = "${property("mod_version")}+mc$minecraftVersion"
base.archivesName = modId

java.toolchain.languageVersion = JavaLanguageVersion.of(mc.getProperty("java_version"))
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

neoForge {
    version = mc.getProperty("neoforge_version")
    parchment {
        minecraftVersion = mc.getProperty("parchment_minecraft_version")
        mappingsVersion = mc.getProperty("parchment_mappings_version")
    }
    runs {
        register("client") { client() }
        register("server") {
            server()
            programArgument("--nogui")
        }
    }
    mods {
        register(modId) { sourceSet(sourceSets.main.get()) }
    }
}

// 機能Modはすべて同じMinecraftバージョンのcoreへ依存する。
if (module != "core") {
    val core = ":core:$minecraftVersion"
    evaluationDependsOn(core)
    dependencies { implementation(project(core)) }
    neoForge.mods.register("ashen_frontier_core") {
        sourceSet(project(core).sourceSets.main.get())
    }
}

val metadata = mapOf(
    "mod_id" to modId,
    "mod_version" to version.toString(),
    "mod_authors" to property("mod_authors"),
    "mod_license" to property("mod_license"),
    "neoforge_version_range" to mc.getProperty("neoforge_version_range"),
    "minecraft_version_range" to mc.getProperty("minecraft_version_range"),
)

tasks.processResources {
    // 構成キャッシュはスクリプト参照を保存できないため、ローカル変数へ取り出してから渡す。
    val properties = metadata
    inputs.properties(properties)
    filteringCharset = "UTF-8"
    filesMatching("META-INF/neoforge.mods.toml") { expand(properties) }
}
