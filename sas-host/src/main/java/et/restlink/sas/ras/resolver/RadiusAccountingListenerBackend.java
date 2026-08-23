/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real RADIUS accounting listener backend per RFC 2866 (P2 missing item #1).
 *
 * <p>Binds a UDP socket (default 1813) and consumes Accounting-Request
 * (code=4) records from the PGW/GGSN. Every request authenticator is verified
 * as {@code MD5(packet-with-auth-zeroed + shared-secret)}; packets that fail,
 * or that are malformed, are dropped with a warn log and never answered —
 * fail-closed.</p>
 *
 * <p>Attributes consumed: Framed-IP-Address (8), Calling-Station-Id (31,
 * MSISDN; User-Name (1) is the fallback), Acct-Status-Type (40: Start=1,
 * Stop=2, Interim-Update=3) and the 3GPP vendor-specific IMSI
 * (vendor-id 10415, sub-type 1). Start/Interim upsert the ip → msisdn/imsi
 * binding stamped with the receive time; Stop removes it.</p>
 *
 * <p>Resolution semantics (CGNAT, fail-closed): bindings older than
 * {@code staleAfterMs} yield {@link FallbackReason#NO_BINDING}; more than one
 * distinct candidate MSISDN on one IP yields
 * {@link FallbackReason#AMBIGUOUS_BINDING}; none yields
 * {@link FallbackReason#NO_BINDING}. A binding first seen after the requested
 * timestamp was not yet active at that point in time and is excluded.
 * RADIUS accounting exposes no port dimension, so {@code srcPort} is accepted
 * for {@link ResolverBackend} compatibility and acts as a wildcard — including
 * the explicit wildcard value 0; either way the IP must resolve to exactly one
 * MSISDN or the lookup fails closed.</p>
 */
public final class RadiusAccountingListenerBackend implements ResolverBackend {

    private static final Logger LOG = LogManager.getLogger(RadiusAccountingListenerBackend.class);

    /** Default RADIUS accounting port (RFC 2866 §2). */
    public static final int DEFAULT_PORT = 1813;
    /** Default binding staleness budget. */
    public static final long DEFAULT_STALE_MS = 60_000L;

    private static final int CODE_ACCOUNTING_REQUEST = 4;
    private static final int CODE_ACCOUNTING_RESPONSE = 5;
    private static final int HEADER_LEN = 20;
    private static final int AUTH_OFFSET = 4;
    private static final int AUTH_LEN = 16;
    private static final int MAX_PACKET_LEN = 4096;

    private static final int ATTR_USER_NAME = 1;
    private static final int ATTR_FRAMED_IP_ADDRESS = 8;
    private static final int ATTR_VENDOR_SPECIFIC = 26;
    private static final int ATTR_CALLING_STATION_ID = 31;
    private static final int ATTR_ACCT_STATUS_TYPE = 40;

    private static final int VENDOR_ID_3GPP = 10415;
    private static final int VSA_3GPP_IMSI = 1;

    private static final int STATUS_START = 1;
    private static final int STATUS_STOP = 2;
    private static final int STATUS_INTERIM = 3;

    private record Binding(String msisdn, String imsi, long firstSeenEpochMs, long lastSeenEpochMs) {}

    private record Parsed(int statusType, String framedIp, String msisdn, String imsi) {}

    private final int port;
    private final byte[] secret;
    private final long staleAfterMs;
    private final Map<String, List<Binding>> bindingsByIp = new ConcurrentHashMap<>();

    private DatagramSocket socket;
    private Thread receiveThread;
    private volatile boolean running;

    /** Listener on udp/{@link #DEFAULT_PORT} with an empty secret and default staleness. */
    public RadiusAccountingListenerBackend() {
        this(DEFAULT_PORT, "", DEFAULT_STALE_MS);
    }

    /**
     * @param port         UDP port to bind (0 = ephemeral, tests/lab).
     * @param secret       RFC 2866 shared secret for authenticator validation.
     * @param staleAfterMs max age of a binding before resolution fails closed.
     */
    public RadiusAccountingListenerBackend(int port, String secret, long staleAfterMs) {
        this.port = port;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.staleAfterMs = staleAfterMs;
    }

    /** Bind the UDP socket and start the dedicated daemon receive thread. */
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            socket = new DatagramSocket(port);
        } catch (SocketException e) {
            running = false;
            throw new IllegalStateException("RADIUS accounting listener bind failed on udp/" + port, e);
        }
        running = true;
        receiveThread = new Thread(this::receiveLoop, "radius-accounting-listener");
        receiveThread.setDaemon(true);
        receiveThread.start();
        LOG.info("RADIUS accounting listener bound udp/{} staleAfterMs={}", localPort(), staleAfterMs);
    }

    /** Close the socket cleanly and stop the receive thread. */
    public synchronized void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (receiveThread != null) {
            try {
                receiveThread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            receiveThread = null;
        }
        LOG.info("RADIUS accounting listener stopped");
    }

    /** Bound local port, or -1 when not running. */
    public synchronized int localPort() {
        return running && socket != null && !socket.isClosed() ? socket.getLocalPort() : -1;
    }

    public boolean isRunning() {
        return running;
    }

    /** Number of live bindings across all IPs (admin/dashboard surface). */
    public int activeBindings() {
        int n = 0;
        for (List<Binding> list : bindingsByIp.values()) {
            synchronized (list) {
                n += list.size();
            }
        }
        return n;
    }

    private void receiveLoop() {
        while (running) {
            byte[] buf = new byte[MAX_PACKET_LEN];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running) {
                    LOG.warn("RADIUS accounting receive failed: {}", e.toString());
                }
                continue;
            }
            try {
                handleDatagram(packet.getData(), packet.getLength(), packet.getSocketAddress());
            } catch (RuntimeException e) {
                LOG.warn("RADIUS packet processing error from {} — dropped: {}",
                        packet.getSocketAddress(), e.toString());
            }
        }
    }

    private void handleDatagram(byte[] data, int len, SocketAddress from) {
        if (len < HEADER_LEN || len > MAX_PACKET_LEN) {
            LOG.warn("RADIUS packet too short/long ({}) from {} — dropped", len, from);
            return;
        }
        int code = data[0] & 0xff;
        if (code != CODE_ACCOUNTING_REQUEST) {
            LOG.debug("Ignoring non-Accounting-Request code={} from {}", code, from);
            return;
        }
        int declaredLen = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        if (declaredLen != len) {
            LOG.warn("RADIUS length mismatch (declared={}, actual={}) from {} — dropped", declaredLen, len, from);
            return;
        }
        int id = data[1] & 0xff;
        byte[] requestAuthenticator = Arrays.copyOfRange(data, AUTH_OFFSET, AUTH_OFFSET + AUTH_LEN);
        byte[] zeroed = Arrays.copyOf(data, len);
        Arrays.fill(zeroed, AUTH_OFFSET, AUTH_OFFSET + AUTH_LEN, (byte) 0);
        byte[] expected = md5(concat(zeroed, secret));
        if (!MessageDigest.isEqual(expected, requestAuthenticator)) {
            LOG.warn("RADIUS Accounting-Request id={} bad authenticator from {} — dropped (fail-closed)", id, from);
            return;
        }

        InetSocketAddress peer = (InetSocketAddress) from;
        Parsed parsed = parseAttributes(data, declaredLen);
        if (parsed == null) {
            LOG.warn("RADIUS Accounting-Request id={} malformed attributes from {} — dropped", id, from);
            return;
        }
        if (!apply(parsed)) {
            return;
        }
        sendResponse(id, requestAuthenticator, peer);
    }

    private Parsed parseAttributes(byte[] data, int len) {
        Integer statusType = null;
        String framedIp = null;
        String callingStationId = null;
        String userName = null;
        String imsi = null;
        int off = HEADER_LEN;
        while (off < len) {
            if (off + 2 > len) {
                return null;
            }
            int type = data[off] & 0xff;
            int attrLen = data[off + 1] & 0xff;
            if (attrLen < 2 || off + attrLen > len) {
                return null;
            }
            int vOff = off + 2;
            int vLen = attrLen - 2;
            switch (type) {
                case ATTR_ACCT_STATUS_TYPE -> {
                    if (vLen != 4) {
                        return null;
                    }
                    statusType = ((data[vOff] & 0xff) << 24) | ((data[vOff + 1] & 0xff) << 16)
                            | ((data[vOff + 2] & 0xff) << 8) | (data[vOff + 3] & 0xff);
                }
                case ATTR_FRAMED_IP_ADDRESS -> {
                    if (vLen != 4) {
                        return null;
                    }
                    framedIp = ipv4ToString(data, vOff);
                }
                case ATTR_CALLING_STATION_ID -> callingStationId = text(data, vOff, vLen);
                case ATTR_USER_NAME -> userName = text(data, vOff, vLen);
                case ATTR_VENDOR_SPECIFIC -> {
                    String vsaImsi = parseVsaImsi(data, vOff, vLen);
                    if (vsaImsi != null) {
                        imsi = vsaImsi;
                    }
                }
                default -> { /* unrecognised attributes are skipped per RFC 2866 §5 */ }
            }
            off += attrLen;
        }
        if (framedIp == null || statusType == null) {
            return null;
        }
        String msisdn = callingStationId != null ? callingStationId : userName;
        return new Parsed(statusType, framedIp, blankToNull(msisdn), blankToNull(imsi));
    }

    private String parseVsaImsi(byte[] data, int off, int len) {
        if (len < 5) {
            return null;
        }
        int vendorId = ((data[off] & 0xff) << 24) | ((data[off + 1] & 0xff) << 16)
                | ((data[off + 2] & 0xff) << 8) | (data[off + 3] & 0xff);
        if (vendorId != VENDOR_ID_3GPP) {
            return null;
        }
        int i = off + 4;
        int end = off + len;
        while (i + 2 <= end) {
            int subType = data[i] & 0xff;
            int subLen = data[i + 1] & 0xff;
            if (subLen < 2 || i + subLen > end) {
                return null;
            }
            if (subType == VSA_3GPP_IMSI) {
                return text(data, i + 2, subLen - 2);
            }
            i += subLen;
        }
        return null;
    }

    private boolean apply(Parsed parsed) {
        switch (parsed.statusType()) {
            case STATUS_START, STATUS_INTERIM -> upsert(parsed.framedIp(), parsed.msisdn(), parsed.imsi());
            case STATUS_STOP -> remove(parsed.framedIp(), parsed.msisdn());
            default -> {
                LOG.warn("RADIUS unsupported Acct-Status-Type={} for {} — dropped (fail-closed)",
                        parsed.statusType(), parsed.framedIp());
                return false;
            }
        }
        return true;
    }

    private void upsert(String ip, String msisdn, String imsi) {
        if (msisdn == null) {
            LOG.warn("RADIUS Start/Interim without MSISDN for {} — dropped (fail-closed)", ip);
            return;
        }
        long now = System.currentTimeMillis();
        List<Binding> list = bindingsByIp.computeIfAbsent(ip, k -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            Binding existing = findByMsisdn(list, msisdn);
            if (existing != null) {
                Binding refreshed = new Binding(existing.msisdn(),
                        imsi != null ? imsi : existing.imsi(), existing.firstSeenEpochMs(), now);
                list.set(list.indexOf(existing), refreshed);
            } else {
                list.add(new Binding(msisdn, imsi, now, now));
            }
        }
        LOG.debug("RADIUS binding upsert: {} → {}", ip, msisdn);
    }

    private void remove(String ip, String msisdn) {
        List<Binding> list = bindingsByIp.get(ip);
        if (list == null) {
            return;
        }
        synchronized (list) {
            if (msisdn == null) {
                list.clear();
            } else {
                list.removeIf(b -> b.msisdn().equals(msisdn));
            }
            if (list.isEmpty()) {
                bindingsByIp.remove(ip, list);
            }
        }
        LOG.debug("RADIUS Stop {} → {}", ip, msisdn);
    }

    private void sendResponse(int id, byte[] requestAuthenticator, InetSocketAddress peer) {
        byte[] digestInput = new byte[4 + AUTH_LEN + secret.length];
        digestInput[0] = CODE_ACCOUNTING_RESPONSE;
        digestInput[1] = (byte) id;
        digestInput[2] = 0;
        digestInput[3] = HEADER_LEN;
        System.arraycopy(requestAuthenticator, 0, digestInput, 4, AUTH_LEN);
        System.arraycopy(secret, 0, digestInput, 4 + AUTH_LEN, secret.length);
        byte[] response = new byte[HEADER_LEN];
        response[0] = CODE_ACCOUNTING_RESPONSE;
        response[1] = (byte) id;
        response[2] = 0;
        response[3] = HEADER_LEN;
        System.arraycopy(md5(digestInput), 0, response, 4, AUTH_LEN);
        try {
            socket.send(new DatagramPacket(response, response.length, peer.getAddress(), peer.getPort()));
        } catch (IOException e) {
            LOG.warn("Accounting-Response to {} failed (best effort): {}", peer, e.toString());
        }
    }

    @Override
    public CompletableFuture<ResolverResult> resolve(String srcIp, int srcPort, long tsEpochMs) {
        return CompletableFuture.supplyAsync(() -> {
            long now = System.currentTimeMillis();
            List<Binding> all = bindingsByIp.getOrDefault(srcIp, List.of());
            List<Binding> candidates = new ArrayList<>();
            for (Binding b : all) {
                if (now - b.lastSeenEpochMs() > staleAfterMs) {
                    continue;
                }
                if (b.firstSeenEpochMs() > tsEpochMs) {
                    continue;
                }
                candidates.add(b);
            }
            if (candidates.isEmpty()) {
                return ResolverResult.miss(FallbackReason.NO_BINDING);
            }
            long distinct = candidates.stream().map(Binding::msisdn).distinct().count();
            if (distinct > 1) {
                return ResolverResult.miss(FallbackReason.AMBIGUOUS_BINDING);
            }
            Binding b = candidates.get(candidates.size() - 1);
            long ageAtTs = Math.max(0L, Math.min(tsEpochMs, now) - b.firstSeenEpochMs());
            return ResolverResult.bound(b.msisdn(), b.imsi(), ageAtTs);
        });
    }

    private static Binding findByMsisdn(List<Binding> list, String msisdn) {
        for (Binding b : list) {
            if (b.msisdn().equals(msisdn)) {
                return b;
            }
        }
        return null;
    }

    private static String ipv4ToString(byte[] data, int off) {
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(data, off, off + 4)).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(byte[] data, int off, int len) {
        return new String(data, off, len, StandardCharsets.UTF_8).trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
