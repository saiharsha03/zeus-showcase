pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        // Hestia: the Google Home APIs SDK is not on Google's Maven; Google ships it as a zip to host locally
        // (docs/HESTIA.md). Only these two modules come from here, and only when it is present.
        if (file("app/libs/home-sdk").isDirectory) maven {
            url = uri("app/libs/home-sdk")
            content {
                includeModule("com.google.android.gms", "play-services-home")
                includeModule("com.google.android.gms", "play-services-home-types")
            }
        }
    }
}
rootProject.name = "Athena"
include(":app")
// Iaso on the Galaxy Watch: one Tile, talking to the phone over the Wearable Data Layer.
include(":wear")
