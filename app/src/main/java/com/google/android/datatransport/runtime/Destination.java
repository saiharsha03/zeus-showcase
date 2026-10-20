package com.google.android.datatransport.runtime;

/**
 * Zeus telemetry strip (phase 2b). Stand-in for the class of the same name in Google's
 * transport-runtime, which build.gradle.kts excludes. Only the type ML Kit links against.
 */
public interface Destination {
    String getName();

    byte[] getExtras();
}
