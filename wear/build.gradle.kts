import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Iaso on the Galaxy Watch: one Tile (next event, top task, water and weight buttons). No network of its own:
// it talks to the phone over the Wearable Data Layer. Same applicationId as the phone app, and the same
// signing key (the debug key for debug builds; a release key must be shared by both), so the Data Layer pairs them.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.phonerecall.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phonerecall"
        // Wear OS 3 and later (every Galaxy Watch since the Watch4).
        minSdk = 30
        targetSdk = 35
        versionCode = 130
        versionName = "1.3.0-wear"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
