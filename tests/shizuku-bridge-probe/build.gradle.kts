plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.darken.porter.probe.bridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "eu.darken.porter.probe.shizukubridge"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("targetApk"))
    // porterOnly has no Shizuku provider; coexist adds upstream's, so a real Shizuku server can
    // deliver too.
    flavorDimensions += "shizuku"
    productFlavors {
        create("porterOnly") { dimension = "shizuku" }
        create("coexist") { dimension = "shizuku" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

// The APK the probe installs through Ackpine, shipped as an asset so the device needs nothing pushed.
val copyTargetApk by tasks.registering(Copy::class) {
    dependsOn(":tests:shizuku-bridge-target:assembleDebug")
    from(project(":tests:shizuku-bridge-target").layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename { "target.apk" }
    }
    into(layout.buildDirectory.dir("targetApk"))
}
tasks.named("preBuild") { dependsOn(copyTargetApk) }

dependencies {
    implementation(project(":shizuku-bridge"))
    implementation("ru.solrudev.ackpine:ackpine-core:0.25.4")
    implementation("ru.solrudev.ackpine:ackpine-ktx:0.25.4")
    implementation("ru.solrudev.ackpine:ackpine-shizuku:0.25.4")
    implementation("ru.solrudev.ackpine:ackpine-shizuku-ktx:0.25.4")
    "coexistImplementation"("dev.rikka.shizuku:provider:13.1.5")
    // Ackpine exempts its hidden API through androidx.startup, which runs in the main process only.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
