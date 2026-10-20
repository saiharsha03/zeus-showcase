package com.google.android.datatransport.runtime;

import android.content.Context;
import android.util.Log;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transformer;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;
import com.google.android.datatransport.TransportScheduleCallback;

/**
 * Zeus telemetry strip (phase 2b). Replaces Google's transport-runtime (excluded in build.gradle.kts).
 * ML Kit GenAI calls initialize, getInstance and newFactory to log usage to firebaselogging. Here every
 * transport drops its events: nothing is queued, scheduled, stored or sent, and no job service,
 * alarm receiver or network code exists in the app. The model itself runs in AICore, a separate
 * system process, and does not use this.
 */
public final class TransportRuntime {
    private static final String TAG = "Zeus-NetGate";
    private static final TransportRuntime INSTANCE = new TransportRuntime();
    private static volatile boolean logged = false;
    private static volatile long dropped = 0;

    private TransportRuntime() {}

    public static void initialize(Context context) {
        if (!logged) {
            logged = true;
            Log.i(TAG, "ML Kit telemetry stub active: datatransport events are dropped, nothing is sent");
        }
    }

    public static TransportRuntime getInstance() {
        return INSTANCE;
    }

    /** How many telemetry events ML Kit tried to log in this process (all dropped). */
    public static long droppedCount() {
        return dropped;
    }

    public TransportFactory newFactory(String backendName) {
        return FACTORY;
    }

    public TransportFactory newFactory(Destination destination) {
        return FACTORY;
    }

    private static final TransportFactory FACTORY = new TransportFactory() {
        @Override
        public <T> Transport<T> getTransport(String name, Class<T> payloadType, Transformer<T, byte[]> transformer) {
            return drop();
        }

        @Override
        public <T> Transport<T> getTransport(String name, Class<T> payloadType, Encoding encoding, Transformer<T, byte[]> transformer) {
            return drop();
        }
    };

    private static <T> Transport<T> drop() {
        return new Transport<T>() {
            @Override
            public void send(Event<T> event) {
                dropped++;
            }

            @Override
            public void schedule(Event<T> event, TransportScheduleCallback callback) {
                dropped++;
                if (callback != null) callback.onSchedule(null);
            }
        };
    }
}
