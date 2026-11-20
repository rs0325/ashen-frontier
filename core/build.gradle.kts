plugins {
    id("ashenfrontier.mod")
}

dependencies {
    compileOnly("mezz.jei:jei-1.21.1-neoforge:19.57.0.444")
    compileOnly("dev.emi:emi-neoforge:1.1.24+1.21.1:api")
    when (providers.gradleProperty("recipeViewer").orElse("none").get()) {
        "jei" -> runtimeOnly("mezz.jei:jei-1.21.1-neoforge:19.57.0.444")
        "emi" -> runtimeOnly("dev.emi:emi-neoforge:1.1.24+1.21.1")
    }
}
