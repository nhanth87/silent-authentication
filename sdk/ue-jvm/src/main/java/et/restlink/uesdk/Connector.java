/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

/**
 * Opens the outbound connection the SDK uses to talk to the SAS.
 *
 * <p>Why this is a seam: a JVM has no supported way to pin a socket to a radio
 * bearer. Mobile platforms do — Android exposes
 * {@code android.net.Network.openConnection(URL)}, which returns a connection
 * bound to that specific network, and iOS can constrain a Network.framework
 * path with {@code NWParameters.requiredInterfaceType}. The core SDK stays
 * platform-agnostic and simply asks for "a connection to this URL";
 * {@code ue-sdk-android} supplies the cellular-bound implementation so the
 * tuple the SAS receives is provably the one the cellular bearer emitted.</p>
 */
@FunctionalInterface
public interface Connector {

    /** Plain {@link URL#openConnection()} — default route, no bearer pinning. */
    Connector DEFAULT = URL::openConnection;

    /**
     * @return an open (not yet connected) connection to {@code url}
     * @throws IOException when the platform cannot produce one
     */
    URLConnection open(URL url) throws IOException;
}
