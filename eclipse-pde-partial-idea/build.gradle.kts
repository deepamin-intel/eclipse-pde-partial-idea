plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":common"))
}

intellij {
    version = "2021.1.1"
    setPlugins("java", "org.jetbrains.kotlin")

    downloadSources = true
}

tasks {
    patchPluginXml {
        sinceBuild("211")
        untilBuild("")

        val projectPath = rootProject.projectDir.path
        pluginDescription(File("$projectPath/DESCRIPTION.html").readText(Charsets.UTF_8))
        changeNotes(File("$projectPath/CHANGES.html").readText(Charsets.UTF_8))
    }

    publishPlugin {
        val ideaPluginToken: String by rootProject.extra
        token(ideaPluginToken)
    }

    runIde {
        jvmArgs("-Xmx4096m", "--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED")
    }

    buildSearchableOptions {
        jvmArgs("--add-exports", "java.base/jdk.internal.vm=ALL-UNNAMED")
    }

    compileJava {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }

    compileKotlin {
        kotlinOptions {
            jvmTarget = "11"
            apiVersion = "1.5"
            languageVersion = "1.5"
        }
    }
}
