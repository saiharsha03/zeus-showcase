package com.google.android.datatransport.cct;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.runtime.Destination;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Zeus telemetry strip (phase 2b). Replaces Google's CCT backend destination (transport-backend-cct is
 * excluded in build.gradle.kts). It names no endpoint and carries no API key; TransportRuntime's stub
 * drops anything addressed to it.
 */
public final class CCTDestination implements Destination {
    public static final CCTDestination INSTANCE = new CCTDestination();
    public static final CCTDestination LEGACY_INSTANCE = INSTANCE;

    private static final Set<Encoding> ENCODINGS;

    static {
        Set<Encoding> s = new HashSet<>();
        s.add(Encoding.of("proto"));
        s.add(Encoding.of("json"));
        ENCODINGS = Collections.unmodifiableSet(s);
    }

    private CCTDestination() {}

    @Override
    public String getName() {
        return "none";
    }

    @Override
    public byte[] getExtras() {
        return null;
    }

    public Set<Encoding> getSupportedEncodings() {
        return ENCODINGS;
    }
}
