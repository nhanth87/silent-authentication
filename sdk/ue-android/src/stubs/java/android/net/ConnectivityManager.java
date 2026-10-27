/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Compile-only stub of the Android framework class. Excluded from the jar.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package android.net;

/**
 * Signature stub of {@code android.net.ConnectivityManager}, holding only what
 * the SDK calls. The signatures match the framework (API 21+); on a device the
 * framework class is loaded and this file does not exist in the shipped jar.
 * Non-final with a public constructor so JVM unit tests can fake it.
 */
public class ConnectivityManager {

    /** Framework: {@code requestNetwork(NetworkRequest, NetworkCallback)}, API 21. */
    public void requestNetwork(NetworkRequest request, NetworkCallback networkCallback) {
        throw new RuntimeException("Stub!");
    }

    /** Framework: {@code unregisterNetworkCallback(NetworkCallback)}, API 21. */
    public void unregisterNetworkCallback(NetworkCallback networkCallback) {
        throw new RuntimeException("Stub!");
    }

    /** Framework: {@code ConnectivityManager.NetworkCallback}, API 21. */
    public static class NetworkCallback {

        public NetworkCallback() {
        }

        public void onAvailable(Network network) {
        }

        public void onLost(Network network) {
        }

        public void onUnavailable() {
        }
    }
}
