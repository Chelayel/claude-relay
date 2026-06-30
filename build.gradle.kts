plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "com.charbel.claudecode"
version = "1.0.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Target IntelliJ IDEA Community. Works in all JetBrains IDEs that
        // include the platform module (PyCharm, WebStorm, GoLand, etc.).
        create("IC", "2024.2.5")
    }
    // Bundled into the plugin distribution so we don't rely on the IDE's
    // internal Gson copy.
    implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
    // No Java UI forms / @NotNull bytecode instrumentation in this plugin.
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            // No upper bound — stay compatible with future IDE builds.
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}
