/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple poster. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

/**
 * Opens the connection the SDK uses to reach the SAS.
 *
 * <p>This module is deliberately compiled with plain {@code javac --release 8}
 * and no {@code android.jar}, so it cannot reference {@code android.net.*}
 * directly. Everything Android-specific therefore goes through reflection on
 * objects the host app hands us, and this seam is what carries the bound
 * socket. See {@link CellularBearer} for the implementations.</p>
 */
public interface Connector {

    /** Plain {@link URL#openConnection()} — OS default route, no bearer pinning. */
    Connector DEFAULT = new Connector() {
        @Override
        public URLConnection open(URL url) throws IOException {
            return url.openConnection();
        }
    };

    /**
     * @return an open (not yet connected) connection to {@code url}
     * @throws IOException when the platform cannot produce one
     */
    URLConnection open(URL url) throws IOException;
}
