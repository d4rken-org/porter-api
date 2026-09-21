plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

val withLegacySdk = providers.gradleProperty("withLegacySdk").getOrElse("false").toBoolean()
val withCompat = providers.gradleProperty("withCompat").getOrElse("false").toBoolean()
val withExtras = providers.gradleProperty("withExtras").getOrElse("false").toBoolean()
val sdkVersion = providers.gradleProperty("sdkVersion").getOrElse("0.2.0")

android {
    namespace = "eu.darken.porter.sdk.consumer"
    compileSdk = 36
    defaultConfig {
        applicationId = "eu.darken.porter.sdk.consumer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    if (withLegacySdk) {
        sourceSets["main"].java.srcDir("src/legacy/java")
    }
    // Only these two configurations ship moe.shizuku.api.BinderContainer, which the Shizuku
    // provider refuses to attach without.
    if (withCompat || withLegacySdk) {
        for (buildType in listOf("debug", "release")) {
            sourceSets[buildType].manifest.srcFile("src/shizuku/AndroidManifest.xml")
        }
    }
    if (withExtras) {
        sourceSets["main"].java.srcDir("src/extras/java")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation("com.github.d4rken-org.porter-api:sdk:$sdkVersion")
    if (withCompat) {
        implementation("com.github.d4rken-org.porter-api:shizuku-compat:$sdkVersion")
    }
    if (withLegacySdk) {
        implementation("dev.rikka.shizuku:api:13.1.5")
        implementation("dev.rikka.shizuku:provider:13.1.5")
    }
    if (withExtras) {
        implementation("com.github.d4rken-org.porter-api:sdk-extras:$sdkVersion")
    }
}
