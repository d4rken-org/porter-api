import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

val sdkVersion = providers.gradleProperty("version")
    .orElse(providers.environmentVariable("VERSION")).getOrElse("0.2.0")
val publishedModules = listOf("protocol", "sdk", "sdk-extras", "shizuku-compat")
val licenseDetails = mapOf(
    "Apache-2.0" to ("Apache License 2.0" to "https://www.apache.org/licenses/LICENSE-2.0"),
    "MIT" to ("MIT License" to "https://opensource.org/license/mit"),
)
// sdk and sdk-extras carry MIT next to Apache-2.0 because some of their classes derive
// from the upstream Shizuku API; shizuku-compat is upstream code and is MIT alone.
val moduleLicenses = mapOf(
    "protocol" to listOf("Apache-2.0"),
    "sdk" to listOf("Apache-2.0", "MIT"),
    "sdk-extras" to listOf("Apache-2.0", "MIT"),
    "shizuku-compat" to listOf("MIT"),
)

allprojects {
    group = "com.github.d4rken-org.porter-api"
    version = sdkVersion
}

// The published modules carry a committed ABI dump; apiCheck fails on a signature change that
// apiDump has not been run for.
apiValidation {
    ignoredProjects += subprojects.map { it.name }.filter { it !in publishedModules }
    // The shell service's AIDL: generated Java, public only because a stub has to be.
    ignoredPackages += "eu.darken.porter.sdk.extras.internal"
}

subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 36
            buildToolsVersion = "36.0.0"
            ndkVersion = "29.0.13113456"
            defaultConfig {
                minSdk = 24
                targetSdk = 36
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        if (name in publishedModules) extensions.configure<KotlinAndroidProjectExtension> { explicitApi() }
    }
    plugins.withId("com.android.library") {
        if (name !in publishedModules) return@withId
        pluginManager.apply("maven-publish")
        extensions.configure<LibraryExtension> {
            publishing {
                singleVariant("release") { withSourcesJar() }
            }
        }
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        pom {
                            name = "Porter SDK - ${project.name}"
                            description = "Porter Android SDK"
                            url = "https://github.com/d4rken-org/porter-api"
                            licenses {
                                val ids = moduleLicenses[project.name]
                                    ?: throw GradleException("No moduleLicenses entry for published module '${project.name}'")
                                ids.forEach { id ->
                                    val (licenseName, licenseUrl) = licenseDetails[id]
                                        ?: throw GradleException("No licenseDetails entry for licence id '$id'")
                                    license {
                                        name = licenseName
                                        url = licenseUrl
                                    }
                                }
                            }
                            scm {
                                url = "https://github.com/d4rken-org/porter-api"
                                connection = "scm:git:https://github.com/d4rken-org/porter-api.git"
                            }
                        }
                    }
                }
            }
        }
    }
}

tasks.register<Delete>("clean") { delete(rootProject.layout.buildDirectory) }
