/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Tests: requestNetwork() lifecycle — what is asked for, fail-closed timeouts,
 * and that the callback is unregistered exactly once.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CellularNetworkRequestTest {

    static final class CellNet extends Network {
        @Override
        public URLConnection openConnection(URL url) throws IOException {
            return url.openConnection();
        }
    }

    /** Grants (or withholds) a network the way the framework would, on request. */
    static final class FakeCm extends ConnectivityManager {
        final Network grant;
        final RuntimeException refuse;
        NetworkRequest asked;
        NetworkCallback registered;
        int unregistered;

        FakeCm(Network grant, RuntimeException refuse) {
            this.grant = grant;
            this.refuse = refuse;
        }

        @Override
        public void requestNetwork(NetworkRequest request, NetworkCallback callback) {
            if (refuse != null) {
                throw refuse;
            }
            asked = request;
            registered = callback;
            if (grant != null) {
                callback.onAvailable(grant);
            }
        }

        @Override
        public void unregisterNetworkCallback(NetworkCallback callback) {
            if (callback != registered) {
                throw new IllegalArgumentException("not registered");
            }
            unregistered++;
        }
    }

    @Test
    void asksForACellularInternetNetworkAndPinsToIt() throws Exception {
        CellNet net = new CellNet();
        FakeCm cm = new FakeCm(net, null);

        CellularBearer bearer = CellularNetworkRequest.request(cm, null, CellularRequirement.CELLULAR, 100);

        assertEquals(Collections.singletonList(CellularBearer.TRANSPORT_CELLULAR), cm.asked.stubTransports());
        assertEquals(Arrays.asList(CellularBearer.NET_CAPABILITY_INTERNET), cm.asked.stubCapabilities());
        assertTrue(bearer.bound());
        assertEquals(AccessTech.GS_2G3G, bearer.accessTech(),
                "unreadable radio stays conservative, never a fabricated LTE");
        assertEquals(0, cm.unregistered, "the request must stay registered while the bearer is used");

        bearer.close();
        bearer.close();
        assertEquals(1, cm.unregistered, "released exactly once");
    }

    @Test
    void noNetworkInTimeFailsClosedAndUnregisters() {
        FakeCm cm = new FakeCm(null, null);
        CellularUnavailableException ex = assertThrows(CellularUnavailableException.class,
                () -> CellularNetworkRequest.request(cm, null, CellularRequirement.CELLULAR, 50));
        assertEquals(CellularRequirement.CELLULAR, ex.requirement());
        assertEquals(1, cm.unregistered);
    }

    @Test
    void missingPermissionIsAnUnavailableBearer() {
        FakeCm cm = new FakeCm(null, new SecurityException("CHANGE_NETWORK_STATE"));
        assertThrows(CellularUnavailableException.class,
                () -> CellularNetworkRequest.request(cm, null, CellularRequirement.CELLULAR, 50));
    }

    @Test
    void requirementTooStrictForTheRadioReleasesTheRequest() {
        FakeCm cm = new FakeCm(new CellNet(), null);
        assertThrows(CellularUnavailableException.class,
                () -> CellularNetworkRequest.request(cm, null, CellularRequirement.CELLULAR_4G_PLUS, 50));
        assertEquals(1, cm.unregistered);
    }

    @Test
    void lostNetworkRefusesToOpen() throws Exception {
        CellNet net = new CellNet();
        FakeCm cm = new FakeCm(net, null);
        CellularBearer bearer = CellularNetworkRequest.request(cm, null, CellularRequirement.CELLULAR, 100);

        cm.registered.onLost(net);

        assertThrows(IOException.class, () -> bearer.open(new URL("http://127.0.0.1:9/")));
        bearer.close();
        assertEquals(1, cm.unregistered);
    }

    @Test
    void contextWithoutConnectivityFailsClosed() {
        assertThrows(CellularUnavailableException.class,
                () -> CellularNetworkRequest.request((Object) null, CellularRequirement.CELLULAR, 10));
        assertThrows(CellularUnavailableException.class,
                () -> CellularNetworkRequest.request(new Object(), CellularRequirement.CELLULAR, 10));
    }
}
