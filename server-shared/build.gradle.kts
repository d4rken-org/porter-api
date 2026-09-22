plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine")
}

android {
    namespace = "rikka.shizuku.server"
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Test sources only: the round trip against the SDK is written in Kotlin.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation(project(":sdk"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("androidx.annotation:annotation:1.3.0")
    implementation("androidx.core:core:1.16.0")
    implementation("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")

    api(project(":aidl"))
    api(project(":shared"))
    api(project(":porsh"))
    api(project(":protocol"))
    api(project(":manager-protocol"))

    implementation(libs.refine.runtime)
    implementation(libs.hidden.compat)
    compileOnly(libs.hidden.stub)
}

// The round trip drives the SDK through its internal delivery and codec, as the SDK's own tests
// do, so the unit test compilations treat the SDK's compile jar as part of their own module.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.endsWith("UnitTestKotlin")) return@configureEach
    val variant = name.removePrefix("compile").removeSuffix("UnitTestKotlin").replaceFirstChar { it.lowercase() }
    val bundleTask = "bundleLibCompileToJar" + variant.replaceFirstChar { it.uppercase() }
    friendPaths.from(
        project(":sdk").layout.buildDirectory.file(
            "intermediates/compile_library_classes_jar/$variant/$bundleTask/classes.jar",
        ),
    )
}
