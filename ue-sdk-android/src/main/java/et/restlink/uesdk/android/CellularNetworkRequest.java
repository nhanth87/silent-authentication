/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * CAMARA Number Verification v2 device side. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;

import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Brings up the cellular data network <em>while Wi-Fi is the default route</em>
 * and hands it back as a {@link CellularBearer}.
 *
 * <p><strong>Why a request, not a lookup.</strong> When Wi-Fi is connected,
 * Android normally keeps mobile data down, so
 * {@code getActiveNetwork()}/{@code getAllNetworks()} find no cellular network at
 * all and {@link CellularBearer#bind} has to fail. CAMARA Number Verification v2
 * needs the authorization request to leave over the operator's bearer even then.
 * {@code ConnectivityManager.requestNetwork()} with
 * {@code TRANSPORT_CELLULAR + NET_CAPABILITY_INTERNET} asks the platform to bring
 * that bearer up for this app only, alongside Wi-Fi. The deprecated
 * {@code getActiveNetworkForType(TYPE_MOBILE)} that circulates in write-ups does
 * not do this: it only reports a mobile network that is already up.</p>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  request() ─► requestNetwork(cellular+internet, callback)
 *     ├─ onAvailable(network) within timeout ─► radio check ─► CellularBearer
 *     │      (the callback stays registered: unregistering lets the platform drop
 *     │       the bearer, so it is released only by CellularBearer.close())
 *     └─ timeout / onUnavailable ─► unregister ─► CellularUnavailableException
 *  onLost(network) after that ─► the bearer refuses to open connections
 * </pre>
 *
 * <p>Needs {@code android.permission.CHANGE_NETWORK_STATE} (a normal permission,
 * granted at install) and {@code ACCESS_NETWORK_STATE}. A missing permission
 * surfaces as a {@code SecurityException} from the framework and is reported as
 * an unavailable bearer — fail closed, never a Wi-Fi retry.</p>
 *
 * <p>Uses the two-argument {@code requestNetwork} (API 21) and waits on a latch,
 * so it works on minSdk 24; the timeout variant is API 26. Call it off the main
 * thread: it blocks for up to {@code timeoutMs}.</p>
 */
final class CellularNetworkRequest {

    private CellularNetworkRequest() {
    }

    /**
     * @param context   an {@code android.content.Context}
     * @param need      the bearer the bank demands
     * @param timeoutMs how long to wait for the platform to bring cellular up
     * @return a bearer over the requested network; close it to release the request
     * @throws CellularUnavailableException when no cellular network came up in
     *         time, the permission is missing, or the radio does not meet {@code need}
     */
    static CellularBearer request(Object context, CellularRequirement need, long timeoutMs)
            throws CellularUnavailableException {
        Object service = context == null ? null : CellularBearer.call(
                context, "android.content.Context", "getSystemService",
                new Class<?>[] {String.class}, new Object[] {"connectivity"});
        if (!(service instanceof ConnectivityManager)) {
            throw new CellularUnavailableException(need, CellularBearer.detectAccessTech(context));
        }
        return request((ConnectivityManager) service, context, need, timeoutMs);
    }

    /** Same, against an explicit manager (the seam the unit tests use). */
    static CellularBearer request(final ConnectivityManager cm, Object context,
                                  CellularRequirement need, long timeoutMs)
            throws CellularUnavailableException {
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(CellularBearer.TRANSPORT_CELLULAR)
                .addCapability(CellularBearer.NET_CAPABILITY_INTERNET)
                .build();
        final Callback callback = new Callback();
        try {
            cm.requestNetwork(request, callback);
        } catch (RuntimeException deniedOrUnsupported) {
            // SecurityException without CHANGE_NETWORK_STATE, or a platform that
            // refuses the request: there is no cellular bearer for us.
            throw new CellularUnavailableException(need, CellularBearer.detectAccessTech(context));
        }
        Network network = callback.await(timeoutMs);
        if (network == null) {
            unregister(cm, callback);
            throw new CellularUnavailableException(need, CellularBearer.detectAccessTech(context));
        }
        AccessTech tech = CellularBearer.radioTech(context);
        if (!tech.cellular()) {
            // The platform granted a cellular transport but the radio generation
            // is unreadable: conservative 2G/3G, never a fabricated LTE/NR.
            tech = AccessTech.GS_2G3G;
        }
        try {
            need.check(tech);
        } catch (CellularUnavailableException tooOld) {
            unregister(cm, callback);
            throw tooOld;
        }
        return CellularBearer.requested(network, context, tech, new Closeable() {
            @Override
            public void close() {
                unregister(cm, callback);
            }
        }, new CellularBearer.Liveness() {
            @Override
            public boolean lost() {
                return callback.lost;
            }
        });
    }

    private static void unregister(ConnectivityManager cm, Callback callback) {
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (RuntimeException alreadyUnregistered) {
            // IllegalArgumentException when it was never registered: nothing to undo
        }
    }

    /** Receives the first available cellular network; framework threads call it. */
    static final class Callback extends ConnectivityManager.NetworkCallback {

        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Network network;
        volatile boolean lost;

        @Override
        public void onAvailable(Network available) {
            if (network == null) {
                network = available;
            }
            done.countDown();
        }

        @Override
        public void onLost(Network gone) {
            if (gone != null && gone.equals(network)) {
                lost = true;
            }
        }

        @Override
        public void onUnavailable() {
            done.countDown();
        }

        /** The granted network, or null when none came up within the timeout. */
        Network await(long timeoutMs) {
            try {
                done.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            return lost ? null : network;
        }
    }
}
