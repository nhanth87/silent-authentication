/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Device-side session-tuple poster. Java 8 / Android minSdk 24.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;

/**
 * A {@link Connector} whose sockets are pinned to a <strong>cellular</strong>
 * data bearer, so the source IP the SAS Resolver sees is the PGW-assigned one
 * that can actually be attested to an MSISDN.
 *
 * <p><strong>Why not {@code ConnectivityManager.bindProcessToNetwork()}?</strong>
 * It is process-wide: it hijacks every other socket in the bank app (push,
 * analytics, other SDKs) onto mobile data for as long as the binding lasts, it
 * needs {@code CHANGE_NETWORK_STATE}, and it is easy to leak. This class uses
 * {@code Network.openConnection(URL)} instead, which binds <em>only</em> the
 * connection handed back. Nothing else in the process is affected and there is
 * no callback left registered.</p>
 *
 * <p><strong>Why reflection?</strong> This artifact is built with plain
 * {@code javac --release 8} and no {@code android.jar} so it stays unit-testable
 * on a JVM. {@code ConnectivityManager.NetworkCallback} is an abstract class, so
 * a reflective {@code requestNetwork()} would need a generated subclass — which
 * is why {@link #bind(Object, CellularRequirement)} walks
 * {@code getActiveNetwork()}/{@code getAllNetworks()} instead. When the app needs
 * an explicit {@code requestNetwork()} (for example to pin a specific SIM on a
 * dual-SIM phone), pass that {@code Network} to {@link #fromNetwork(Object,
 * AccessTech)} from its own {@code NetworkCallback.onAvailable()}.</p>
 */
public final class CellularBearer implements Connector, Closeable {

    private static final String CM = "android.net.ConnectivityManager";
    private static final String NET = "android.net.Network";
    private static final String CAPS = "android.net.NetworkCapabilities";
    private static final String CTX = "android.content.Context";
    private static final String TM = "android.telephony.TelephonyManager";

    /**
     * Platform constants are resolved reflectively at class-init: this artifact
     * compiles without {@code android.jar}, so a hardcoded integer would be an
     * unverifiable guess that silently mis-routes every bearer decision. The
     * literals below are only off-device fallbacks (they keep the JVM unit tests
     * deterministic); on a real device the value comes from the framework.
     */
    static final int TRANSPORT_CELLULAR =
            intConstant("android.net.NetworkCapabilities", "TRANSPORT_CELLULAR", 0);
    /** {@code NetworkCapabilities.TRANSPORT_WIFI}. */
    static final int TRANSPORT_WIFI =
            intConstant("android.net.NetworkCapabilities", "TRANSPORT_WIFI", 1);
    /** {@code NetworkCapabilities.NET_CAPABILITY_INTERNET}. */
    static final int NET_CAPABILITY_INTERNET =
            intConstant("android.net.NetworkCapabilities", "NET_CAPABILITY_INTERNET", 12);

    /** Reads a public static int constant from a platform class, if present. */
    static int intConstant(String owner, String field, int fallback) {
        try {
            return Class.forName(owner).getField(field).getInt(null);
        } catch (Throwable notOnThisPlatform) {
            return fallback;
        }
    }

    /** The {@code android.net.Network} connections are opened on; null = default route. */
    private final Object network;
    /** The {@code android.content.Context} used for radio lookups; may be null. */
    private final Object context;
    private final AccessTech accessTech;
    private final String description;
    private boolean closed;

    private CellularBearer(Object network, Object context, AccessTech accessTech,
                           String description) {
        this.network = network;
        this.context = context;
        this.accessTech = accessTech == null ? AccessTech.UNKNOWN : accessTech;
        this.description = description;
    }

    /** The radio the SDK observed. Never {@code null}. */
    public AccessTech accessTech() {
        return accessTech;
    }

    /** Short human-readable bearer identity for logs — no subscriber data. */
    public String description() {
        return description;
    }

    /** True when this bearer pins to a platform {@code Network} object. */
    public boolean bound() {
        return network != null;
    }

    /**
     * Default-route bearer: no pinning, radio still best-effort detected. Only
     * valid with {@link CellularRequirement#ANY} (lab / TS.43 Wi-Fi trials).
     */
    public static CellularBearer unbound(Object contextNullable) {
        AccessTech tech = detectAccessTech(contextNullable);
        return new CellularBearer(null, contextNullable, tech, "unbound/" + tech.name());
    }

    /**
     * Finds a live cellular network and pins subsequent connections to it.
     *
     * @param context an {@code android.content.Context}; typed {@code Object}
     *                because this module compiles without {@code android.jar}
     * @throws CellularUnavailableException when no usable cellular bearer exists
     *         (mobile data off / airplane mode). Never falls back to Wi-Fi.
     */
    public static CellularBearer bind(Object context, CellularRequirement requirement)
            throws CellularUnavailableException {
        CellularRequirement need =
                requirement == null ? CellularRequirement.CELLULAR : requirement;
        Object cm = context == null ? null : call(
                context, CTX, "getSystemService",
                new Class<?>[] {String.class}, new Object[] {CONNECTIVITY_SERVICE});
        Object candidate = cm == null ? null : firstCellular(cm, activeNetwork(cm));
        String via = "activeNetwork";
        if (candidate == null) {
            candidate = cm == null ? null : firstCellular(cm, allNetworks(cm));
            via = "allNetworks";
        }
        if (candidate == null) {
            throw new CellularUnavailableException(need, detectAccessTech(context));
        }
        AccessTech tech = radioTech(context);
        if (!tech.cellular()) {
            // Transport says cellular, the radio said otherwise: stay conservative.
            tech = AccessTech.GS_2G3G;
        }
        need.check(tech);
        return new CellularBearer(candidate, context, tech,
                "cellular via " + via + "#" + System.identityHashCode(candidate)
                        + " tech=" + tech.name());
    }

    /**
     * Wraps an {@code android.net.Network} the app obtained itself — typically
     * from {@code ConnectivityManager.requestNetwork()} with a
     * {@code NetworkSpecifier}/{@code subscriptionId} so a chosen SIM's bearer
     * is used. Recommended on dual-SIM devices, where walking the network list
     * cannot tell which subscription the socket will leave on.
     *
     * @param network the {@code Network} from the app's callback; not null
     * @param accessTech radio observed by the app, or null for a conservative
     *                   {@link AccessTech#GS_2G3G}
     */
    public static CellularBearer fromNetwork(Object network, AccessTech accessTech)
            throws CellularUnavailableException {
        if (network == null) {
            throw new CellularUnavailableException(
                    CellularRequirement.CELLULAR, AccessTech.UNKNOWN);
        }
        AccessTech tech = accessTech == null ? AccessTech.GS_2G3G : accessTech;
        if (!tech.cellular()) {
            throw new CellularUnavailableException(CellularRequirement.CELLULAR, tech);
        }
        return new CellularBearer(network, null, tech,
                "cellular via requestNetwork#" + System.identityHashCode(network)
                        + " tech=" + tech.name());
    }

    // ---------------------------------------------------------------- Connector

    /**
     * Opens a connection on the pinned cellular network. An {@link #unbound(Object)}
     * bearer (no {@code Network}) falls through to the platform default route.
     */
    @Override
    public URLConnection open(URL url) throws IOException {
        if (closed) {
            throw new IOException("cellular bearer already closed");
        }
        if (network == null) {
            return url.openConnection();
        }
        Object conn = call(network, NET, "openConnection",
                new Class<?>[] {URL.class}, new Object[] {url});
        if (!(conn instanceof URLConnection)) {
            throw new IOException("Network.openConnection returned " + conn + ", not a URLConnection");
        }
        return (URLConnection) conn;
    }

    /**
     * Nothing to release: this bearer never registers a {@code NetworkCallback}
     * and never calls {@code bindProcessToNetwork}, so there is no process-wide
     * state to unwind and no dialog to leak.
     */
    @Override
    public void close() {
        closed = true;
    }

    @Override
    public String toString() {
        return "CellularBearer[" + description + "]";
    }

    // ------------------------------------------------------- reflective plumbing

    private static final String CONNECTIVITY_SERVICE = "connectivity";
    private static final String TELEPHONY_SERVICE = "phone";

    /**
     * Reflective call against the platform class that declares {@code method},
     * falling back to the target's own class when that owner is not loadable
     * (older ROMs, renamed holders, and JVM unit tests with fakes). Any failure
     * yields {@code null} so callers fail closed explicitly instead of guessing
     * from an exception.
     */
    static Object call(Object target, String owner, String method,
                       Class<?>[] types, Object[] args) {
        if (target == null) {
            return null;
        }
        try {
            Method m = resolve(target, owner, method, types);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable notAvailable) {
            return null;
        }
    }

    private static Method resolve(Object target, String owner, String method, Class<?>[] types) {
        try {
            return Class.forName(owner).getMethod(method, types);
        } catch (Throwable ownerAbsent) {
            try {
                return target.getClass().getMethod(method, types);
            } catch (Throwable alsoAbsent) {
                return null;
            }
        }
    }

    private static Object activeNetwork(Object cm) {
        return call(cm, CM, "getActiveNetwork", new Class<?>[0], new Object[0]);
    }

    private static Object[] allNetworks(Object cm) {
        Object raw = call(cm, CM, "getAllNetworks", new Class<?>[0], new Object[0]);
        return raw instanceof Object[] ? (Object[]) raw : new Object[0];
    }

    private static Object firstCellular(Object cm, Object candidate) {
        if (candidate == null) {
            return null;
        }
        return usable(cm, candidate) ? candidate : null;
    }

    private static Object firstCellular(Object cm, Object[] candidates) {
        for (Object candidate : candidates) {
            if (candidate != null && usable(cm, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A network is usable for silent auth when it is cellular and has internet. */
    private static boolean usable(Object cm, Object network) {
        Object caps = capabilities(cm, network);
        if (caps == null) {
            return false;
        }
        return Boolean.TRUE.equals(hasTransport(caps, TRANSPORT_CELLULAR))
                && Boolean.TRUE.equals(call(caps, CAPS, "hasCapability",
                        new Class<?>[] {int.class}, new Object[] {NET_CAPABILITY_INTERNET}));
    }

    private static Object capabilities(Object cm, Object network) {
        return call(cm, CM, "getNetworkCapabilities",
                new Class<?>[] {networkClass()}, new Object[] {network});
    }

    private static Object hasTransport(Object caps, int transport) {
        return call(caps, CAPS, "hasTransport",
                new Class<?>[] {int.class}, new Object[] {transport});
    }

    private static Class<?> networkClass() {
        try {
            return Class.forName(NET);
        } catch (Throwable absent) {
            return Object.class;
        }
    }

    /**
     * Best-effort access technology of the <em>default</em> route. The transport
     * check needs only {@code ACCESS_NETWORK_STATE}; reading the radio generation
     * additionally needs {@code READ_PHONE_STATE} on some releases, and when it
     * is denied the answer degrades to {@link AccessTech#GS_2G3G} — still
     * cellular, never a fabricated LTE/NR.
     */
    static AccessTech detectAccessTech(Object context) {
        if (context == null) {
            return AccessTech.UNKNOWN;
        }
        Object cm = call(context, CTX, "getSystemService",
                new Class<?>[] {String.class}, new Object[] {CONNECTIVITY_SERVICE});
        Object active = activeNetwork(cm);
        Object caps = capabilities(cm, active);
        if (caps == null) {
            return AccessTech.UNKNOWN;
        }
        if (!Boolean.TRUE.equals(hasTransport(caps, TRANSPORT_CELLULAR))) {
            // Any non-cellular transport: Wi-Fi is the only one worth naming,
            // because a TS.43 attempt over it is still a legitimate login.
            return Boolean.TRUE.equals(hasTransport(caps, TRANSPORT_WIFI))
                    ? AccessTech.WIFI : AccessTech.UNKNOWN;
        }
        AccessTech radio = radioTech(context);
        return radio.cellular() ? radio : AccessTech.GS_2G3G;
    }

    /**
     * {@code TelephonyManager.getDataNetworkType()} (API 29) when present, else
     * the deprecated {@code getNetworkType()}. Both are swallowed to null on a
     * SecurityException so a permission-less build still works.
     */
    private static AccessTech radioTech(Object context) {
        Object tm = call(context, CTX, "getSystemService",
                new Class<?>[] {String.class}, new Object[] {TELEPHONY_SERVICE});
        if (tm == null) {
            return AccessTech.UNKNOWN;
        }
        Object type = call(tm, TM, "getDataNetworkType", new Class<?>[0], new Object[0]);
        if (type == null) {
            type = call(tm, TM, "getNetworkType", new Class<?>[0], new Object[0]);
        }
        if (!(type instanceof Integer)) {
            return AccessTech.UNKNOWN;
        }
        return classifyRadio(((Integer) type).intValue());
    }

    /**
     * Turns a {@code NETWORK_TYPE_*} value into a bearer. The three values that
     * change the security decision are resolved from the framework (never
     * guessed), so a ROM that renumbered them cannot make Wi-Fi calling look
     * cellular or 5G look like 2G; everything else falls back to the documented
     * ranges in {@link AccessTech#fromTelephonyNetworkType(int)}.
     */
    static AccessTech classifyRadio(int type) {
        if (type == intConstant(TM, "NETWORK_TYPE_NR", 20)) {
            return AccessTech.NR;
        }
        if (type == intConstant(TM, "NETWORK_TYPE_LTE", 13)) {
            return AccessTech.LTE;
        }
        if (type == intConstant(TM, "NETWORK_TYPE_IWLAN", 18)) {
            return AccessTech.WIFI;   // Wi-Fi calling: not an operator-data IP
        }
        return AccessTech.fromTelephonyNetworkType(type);
    }
}

