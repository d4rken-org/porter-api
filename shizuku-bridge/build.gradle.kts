plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.darken.porter.bridge"
    buildFeatures {
        buildConfig = false
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    api(project(":sdk"))
    // Shizuku's newProcess runs on the shell service sdk-extras ships.
    implementation(project(":sdk-extras"))
    // Upstream's own client, unchanged: the bridge feeds it a binder rather than replacing it, so an
    // app and its libraries keep the Shizuku-API classes they were compiled against.
    api("dev.rikka.shizuku:api:13.1.5")

    // The tests stand in for the server, which answers in the protocol's keys.
    testImplementation(project(":protocol"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// The tests deliver a fake server through the SDK's internal entry point and run sdk-extras' shell
// service in-process, so the unit test compilations treat both compile jars as part of this module.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.endsWith("UnitTestKotlin")) return@configureEach
    val variant = name.removePrefix("compile").removeSuffix("UnitTestKotlin").replaceFirstChar { it.lowercase() }
    val bundleTask = "bundleLibCompileToJar" + variant.replaceFirstChar { it.uppercase() }
    for (friend in listOf(":sdk", ":sdk-extras")) {
        friendPaths.from(
            project(friend).layout.buildDirectory.file(
                "intermediates/compile_library_classes_jar/$variant/$bundleTask/classes.jar",
            ),
        )
    }
}
