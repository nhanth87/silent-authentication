/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Compile-only stub of the Android framework class. Excluded from the jar.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package android.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Signature stub of {@code android.net.NetworkRequest} and its {@code Builder}
 * (API 21). Unlike the framework class it remembers what was asked for, so JVM
 * unit tests can assert the SDK requested a cellular, internet-capable network.
 */
public class NetworkRequest {

    private final List<Integer> transports;
    private final List<Integer> capabilities;

    NetworkRequest(List<Integer> transports, List<Integer> capabilities) {
        this.transports = Collections.unmodifiableList(new ArrayList<Integer>(transports));
        this.capabilities = Collections.unmodifiableList(new ArrayList<Integer>(capabilities));
    }

    /** Stub-only accessor for tests; not part of the framework API. */
    public List<Integer> stubTransports() {
        return transports;
    }

    /** Stub-only accessor for tests; not part of the framework API. */
    public List<Integer> stubCapabilities() {
        return capabilities;
    }

    /** Framework: {@code NetworkRequest.Builder}, API 21. */
    public static class Builder {

        private final List<Integer> transports = new ArrayList<Integer>();
        private final List<Integer> capabilities = new ArrayList<Integer>();

        public Builder() {
        }

        public Builder addTransportType(int transportType) {
            transports.add(transportType);
            return this;
        }

        public Builder addCapability(int capability) {
            capabilities.add(capability);
            return this;
        }

        public NetworkRequest build() {
            return new NetworkRequest(transports, capabilities);
        }
    }
}
