import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

// Third-party SDKs are not committed. Spotify App Remote is fetched from Spotify's official GitHub release and
// checked against a pinned SHA-256. The Google Home APIs SDK is not on a public Maven: drop Google's zip contents
// into app/libs/home-sdk to build the real Hestia backend; without it a stub backend is compiled (docs/HESTIA.md).
val spotifyAar: File = file("libs/spotify-app-remote-release-0.8.0.aar")
val spotifyUrl = "https://github.com/spotify/android-sdk/releases/download/v0.8.0-appremote_v2.1.0-auth/spotify-app-remote-release-0.8.0.aar"
val spotifySha256 = "b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198"
fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
if (!spotifyAar.isFile || sha256(spotifyAar) != spotifySha256) {
    spotifyAar.parentFile.mkdirs()
    val tmp = File(spotifyAar.path + ".part")
    URI(spotifyUrl).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val got = sha256(tmp)
    if (got != spotifySha256) { tmp.delete(); throw GradleException("Spotify App Remote AAR SHA-256 mismatch: $got") }
    tmp.renameTo(spotifyAar)
}
val homeSdk = file("libs/home-sdk").isDirectory

// AGP 9 compiles Kotlin itself, so there is no kotlin-android plugin here.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.phonerecall"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.phonerecall"
        minSdk = 31
        targetSdk = 35
        versionCode = 210
        versionName = "2.1.0"
        // The Fold is arm64. MediaPipe and LiteRT-LM ship large native libraries for four ABIs; one is enough.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        // The phone's daily build: not debuggable, so ART compiles it (a debuggable build runs much of Compose in the
        // interpreter, several times slower). Same debug key, so it installs over a debug build and keeps his data.
        // No R8: Jackson, the Anthropic SDK and MediaPipe use reflection and would need keep rules tested on the phone.
        create("fast") {
            initWith(getByName("debug"))
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "debug"
        }
    }
    // The debug hooks (watchdog, DUMP-only receiver, Clio export over adb) ride along in the fast build too.
    sourceSets.getByName("fast") {
        java.srcDir("src/debug/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }

    buildFeatures { compose = true; buildConfig = true }
    // Screenshot path: Robolectric renders the Home previews to PNG (app/build/shots). Test-only.
    testOptions { unitTests.isIncludeAndroidResources = true }
    // The Anthropic SDK pulls Jackson, OkHttp and Kotlin jars that each ship these files; none are needed at runtime.
    packaging {
        jniLibs { pickFirsts.add("**/liblitertlm_jni.so") }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/FastDoubleParser-*",
                "META-INF/thirdparty-LICENSE", "META-INF/bigint-LICENSE",
            )
        }
    }
    // Hestia's Google Home backend: the real one with the SDK, a stub that reports "not connected" without it.
    sourceSets["main"].kotlin.srcDir(if (homeSdk) "src/homeSdk/java" else "src/homeStub/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Phase 2c: MediaPipe's tasks-retrieval pulls litertlm-android, built with Kotlin metadata 2.4.
        // PhoneRecall and Google's Gallery skip the same check.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

// Phase 2b telemetry strip. ML Kit GenAI hard-wires Google's datatransport logger (firebaselogging via the CCT
// backend). The two modules that schedule and upload events are removed from every configuration; the
// classes ML Kit links against are replaced by no-op stubs in app/src/main/java/com/google/android/datatransport
// (same names, nothing sent). transport-api stays: it is only interfaces and value types.
configurations.configureEach {
    exclude(group = "com.google.android.datatransport", module = "transport-runtime")
    exclude(group = "com.google.android.datatransport", module = "transport-backend-cct")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    // NotificationCompat for the Now Bar.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Shapes for blocks and stat tiles (RoundedPolygon), pure geometry, no network.
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Fold posture (hinge position when half open).
    implementation("androidx.window:window:1.3.0")
    // Phase 2a: Athena's brain. The official Anthropic Java SDK (OkHttp inside). Every request goes through NetGate.
    implementation("com.anthropic:anthropic-java:2.73.0")
    // Phase 2b: Gemini Nano on device (ML Kit GenAI Prompt API; the model runs in AICore's own process).
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // Odyssey: the odometer from a dashboard photo (ML Kit text recognition, unbundled: the model lives in Play services).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // Nano is foreground-only: ProcessLifecycleOwner tells us when Zeus is on screen.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    // Batched triage and task hygiene in the background. No foreground service.
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // Phase 2c: store-arrival geofences. Fused location runs inside Google Play services; only "near store X" leaves the phone.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Phase 2c: Alexandria's embeddings (EmbeddingGemma 2 through MediaPipe, as PhoneRecall). Used only when the model file is on the phone.
    implementation("com.google.mediapipe:tasks-retrieval:1.1.0")
    // Phase 3: Lemnos (the VPS) over mTLS with a Keystore key, and Hetzner's API. OkHttp is already inside the
    // Anthropic SDK; named here because Zeus calls it directly. Every request passes NetGate.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Phase 3: FCM wake-ups only. The data carries {"t":"sync"} and nothing else; the content is fetched from
    // Lemnos over mTLS. Its datatransport logging lands in the same dropping stubs as ML Kit's (see above).
    implementation("com.google.firebase:firebase-messaging:24.1.1")
    // Music: Spotify App Remote 0.8.0, the official AAR from github.com/spotify/android-sdk (release
    // v0.8.0-appremote_v2.1.0-auth, fetched and hash-checked above). IPC to the Spotify app; its default JSON mapper is Gson.
    implementation(files(spotifyAar))
    implementation("com.google.code.gson:gson:2.11.0")
    // Spotify's PKCE sign-in page in a Custom Tab (the browser's process).
    implementation("androidx.browser:browser:1.8.0")
    // Iaso: Health Connect (Samsung Health syncs the Galaxy Watch into it). 1.1.0-beta01 is the last release
    // that builds against compileSdk 35. Reads steps, sleep, heart rate, workouts, weight, active calories;
    // writes only the weights and meals Zeus logs.
    implementation("androidx.health.connect:connect-client:1.1.0-beta01")
    // Iaso: the Wearable Data Layer to the :wear tile (Play services IPC, no host of ours).
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // Hestia: Google Home APIs 1.11 (Device and Structure APIs; AiDot lights and plugs linked into Google Home).
    // Runs through Google Play services, so it adds no host to Zeus's own process. Since 1.11 the SDK needs
    // the coroutines libraries named directly (docs/HESTIA.md).
    if (homeSdk) {
        implementation("com.google.android.gms:play-services-home:17.1.0")
        implementation("com.google.android.gms:play-services-home-types:17.1.0")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    // Chronos: Google Calendar birthdays. AuthorizationClient (Google Identity Services) asks for the one scope
    // calendar.events on the owner's account; Play services holds the token. Already on the classpath through Hestia's SDK.
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    // Mnemosyne: the recovery phrase as a QR (ZXing core: pure Java, encoder only, no network) and Google's code
    // scanner to read it back on a new phone (runs in Play services' own UI, so Zeus needs no camera permission).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Moments: "snap the box". ML Kit text recognition (Latin). Runs on the phone; the photo and its text never
    // leave it. Its datatransport logging lands in the same no-op stubs as ML Kit GenAI's (see above).
    // (Moments uses the same Play-services text recognition as the odometer reader above.)
}
