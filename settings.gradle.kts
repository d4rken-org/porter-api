pluginManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
    plugins {
        id("com.android.application") version "8.11.1"
        id("com.android.library") version "8.11.1"
        id("org.jetbrains.kotlin.android") version "2.2.20"
        id("dev.rikka.tools.refine") version "4.4.0"
        id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
    versionCatalogs {
        create("libs") {
            version("hidden-api", "4.4.0")
            library("hidden-compat", "dev.rikka.hidden", "compat").versionRef("hidden-api")
            library("hidden-stub", "dev.rikka.hidden", "stub").versionRef("hidden-api")

            version("refine", "4.4.0")
            library("refine-runtime", "dev.rikka.tools.refine", "runtime").versionRef("refine")
        }
    }
}

rootProject.name = "porter-api"
include(":aidl", ":shared", ":shizuku-compat", ":protocol", ":manager-protocol", ":sdk", ":sdk-extras")

val includeShell = providers.gradleProperty("includeShell").getOrElse("false").toBoolean()

if (includeShell) {
    include(":porsh", ":server-shared")
}
