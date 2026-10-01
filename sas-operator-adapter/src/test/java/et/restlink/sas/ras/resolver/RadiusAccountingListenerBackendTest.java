/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC 2866 wire-level tests for {@link RadiusAccountingListenerBackend}:
 * real binary Accounting-Request packets over loopback UDP, authenticator
 * validation, Start/Interim/Stop lifecycle and fail-closed resolution.
 */
class RadiusAccountingListenerBackendTest {

    private static final String SECRET = "s3cr3t-X";
    private static final long STALE_MS = 60_000L;
    private static final int STATUS_START = 1;
    private static final int STATUS_STOP = 2;
    private static final int STATUS_INTERIM = 3;
    private static final String MSISDN = "+251911111111";
    private static final String MSISDN2 = "+251922222222";
    private static final String IMSI = "655010000000001";

    private RadiusAccountingListenerBackend backend;

    @BeforeEach
    void setUp() {
        backend = new RadiusAccountingListenerBackend(0, SECRET, STALE_MS);
        backend.start();
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.stop();
            backend = null;
        }
    }

    @Test
    void bindsEphemeralPortAndStopsCleanly() {
        assertTrue(backend.isRunning());
        assertTrue(backend.localPort() > 0);
        backend.stop();
        assertFalse(backend.isRunning());
        assertEquals(-1, backend.localPort());
        backend = new RadiusAccountingListenerBackend(0, SECRET, STALE_MS);
        backend.start();
        assertTrue(backend.localPort() > 0);
    }

    @Test
    void startInterimStopLifecycle() throws Exception {
        String ip = "10.20.30.40";
        send(request(7, STATUS_START, ip, MSISDN, "user1", IMSI));
        ResolverResult r = resolveUntil(ip, ResolverResult::found);
        assertEquals(MSISDN, r.msisdn());
        assertEquals(IMSI, r.imsi());
        assertTrue(r.bearerAgeMs() >= 0);

        send(request(8, STATUS_INTERIM, ip, MSISDN, "user1", null));
        r = resolveUntil(ip, ResolverResult::found);
        assertEquals(MSISDN, r.msisdn());

        send(request(9, STATUS_STOP, ip, MSISDN, "user1", null));
        r = resolveUntil(ip, res -> res.miss() == FallbackReason.NO_BINDING);
        assertEquals(FallbackReason.NO_BINDING, r.miss());
    }

    @Test
    void accountingResponseWellFormed() throws Exception {
        String ip = "10.0.0.1";
        byte[] req = request(21, STATUS_START, ip, MSISDN, "user1", null);
        DatagramPacket resp = sendAndReceive(req);
        assertNotNull(resp, "expected an Accounting-Response");
        byte[] data = Arrays.copyOf(resp.getData(), resp.getLength());
        assertEquals(20, data.length);
        assertEquals(5, data[0] & 0xff);
        assertEquals(req[1], data[1]);
        assertEquals(20, ((data[2] & 0xff) << 8) | (data[3] & 0xff));
        byte[] digestInput = concat(new byte[]{5, data[1], 0, 20},
                Arrays.copyOfRange(req, 4, 20),
                SECRET.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(md5(digestInput), Arrays.copyOfRange(data, 4, 20));
    }

    @Test
    void badAuthenticatorDroppedAndUnanswered() throws Exception {
        String ip = "10.0.0.2";
        byte[] pkt = request(11, STATUS_START, ip, MSISDN, "user1", null);
        pkt[5] ^= (byte) 0xff; // corrupt the request authenticator
        assertNull(sendAndReceive(pkt), "bad authenticator must not be answered");
        ResolverResult r = resolveUntil(ip, res -> res.miss() == FallbackReason.NO_BINDING);
        assertEquals(FallbackReason.NO_BINDING, r.miss());

        send(request(12, STATUS_START, ip, MSISDN, "user1", IMSI));
        assertTrue(resolveUntil(ip, ResolverResult::found).found());
    }

    @Test
    void malformedPacketsDroppedAndUnanswered() throws Exception {
        assertNull(sendAndReceive(new byte[10]), "short packet must not be answered");

        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        attr(attrs, 8, ipv4("10.0.0.3"));
        byte[] overrun = new byte[20 + attrs.size()];
        overrun[0] = 4;
        overrun[1] = 30;
        overrun[2] = (byte) (overrun.length >>> 8);
        overrun[3] = (byte) overrun.length;
        System.arraycopy(attrs.toByteArray(), 0, overrun, 20, attrs.size());
        overrun[21] = (byte) 200; // Framed-IP-Address claims more value bytes than exist
        assertNull(sendAndReceive(seal(overrun)), "attribute overrun must not be answered");

        byte[] badLength = request(14, STATUS_START, "10.0.0.3", MSISDN, "user1", null);
        badLength[3] += 1; // declared length no longer matches the datagram size
        assertNull(sendAndReceive(badLength), "length mismatch must not be answered");

        String ip = "10.0.0.3";
        assertEquals(FallbackReason.NO_BINDING,
                backend.resolve(ip, 0, System.currentTimeMillis()).join().miss());
        send(request(15, STATUS_START, ip, MSISDN, "user1", IMSI));
        assertTrue(resolveUntil(ip, ResolverResult::found).found());
    }

    @Test
    void unsupportedStatusDropped() throws Exception {
        String ip = "10.0.0.4";
        assertNull(sendAndReceive(request(16, 7, ip, MSISDN, "user1", null)));
        Thread.sleep(200);
        assertEquals(FallbackReason.NO_BINDING,
                backend.resolve(ip, 0, System.currentTimeMillis()).join().miss());
    }

    @Test
    void identityLessStartAcknowledgedButNotBound() throws Exception {
        String ip = "10.0.0.5";
        assertNotNull(sendAndReceive(request(17, STATUS_START, ip, null, null, null)));
        Thread.sleep(200);
        assertEquals(FallbackReason.NO_BINDING,
                backend.resolve(ip, 0, System.currentTimeMillis()).join().miss());
    }

    @Test
    void userNameUsedWhenCallingStationIdAbsent() throws Exception {
        String ip = "10.0.0.6";
        send(request(18, STATUS_START, ip, null, MSISDN2, IMSI));
        ResolverResult r = resolveUntil(ip, ResolverResult::found);
        assertEquals(MSISDN2, r.msisdn());
        assertEquals(IMSI, r.imsi());
    }

    @Test
    void staleBindingYieldsNoBinding() throws Exception {
        backend.stop();
        backend = new RadiusAccountingListenerBackend(0, SECRET, 250L);
        backend.start();
        String ip = "10.30.0.1";
        send(request(31, STATUS_START, ip, MSISDN, "user1", null));
        assertTrue(resolveUntil(ip, ResolverResult::found).found());
        Thread.sleep(500);
        assertEquals(FallbackReason.NO_BINDING,
                backend.resolve(ip, 0, System.currentTimeMillis()).join().miss());
    }

    @Test
    void twoMsisdnsOnOneIpAreAmbiguous() throws Exception {
        String ip = "10.40.0.1";
        send(request(41, STATUS_START, ip, MSISDN, "user1", null));
        send(request(42, STATUS_START, ip, MSISDN2, "user2", null));
        ResolverResult r = resolveUntil(ip, res -> res.miss() == FallbackReason.AMBIGUOUS_BINDING);
        assertEquals(FallbackReason.AMBIGUOUS_BINDING, r.miss());

        send(request(43, STATUS_STOP, ip, MSISDN2, "user2", null));
        r = resolveUntil(ip, ResolverResult::found);
        assertEquals(MSISDN, r.msisdn());
    }

    @Test
    void bindingNotYetActiveAtRequestedTimestampExcluded() throws Exception {
        String ip = "10.50.0.1";
        send(request(51, STATUS_START, ip, MSISDN, "user1", IMSI));
        assertTrue(resolveUntil(ip, ResolverResult::found).found());
        long pastTs = System.currentTimeMillis() - 3_600_000L;
        assertEquals(FallbackReason.NO_BINDING,
                backend.resolve(ip, 0, pastTs).join().miss());
    }

    private void send(byte[] pkt) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.send(new DatagramPacket(pkt, pkt.length,
                    InetAddress.getLoopbackAddress(), backend.localPort()));
        }
    }

    /** Sends the packet and waits for the reply; null on timeout (nothing answered). */
    private DatagramPacket sendAndReceive(byte[] pkt) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1_500);
            s.send(new DatagramPacket(pkt, pkt.length,
                    InetAddress.getLoopbackAddress(), backend.localPort()));
            byte[] buf = new byte[512];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            try {
                s.receive(resp);
                return resp;
            } catch (SocketTimeoutException e) {
                return null;
            }
        }
    }

    private ResolverResult resolveUntil(String ip, Predicate<ResolverResult> predicate)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        ResolverResult r = null;
        while (System.currentTimeMillis() < deadline) {
            r = backend.resolve(ip, 0, System.currentTimeMillis()).join();
            if (predicate.test(r)) {
                return r;
            }
            Thread.sleep(10);
        }
        return backend.resolve(ip, 0, System.currentTimeMillis()).join();
    }

    /**
     * Builds a binary RFC 2866 Accounting-Request with a correct request
     * authenticator: MD5(code+id+len+zeroed-auth+attributes+secret).
     */
    private static byte[] request(int id, int statusType, String framedIp,
                                  String callingStationId, String userName, String imsi) {
        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        if (userName != null) {
            attr(attrs, 1, userName.getBytes(StandardCharsets.UTF_8));
        }
        attr(attrs, 8, ipv4(framedIp));
        attr(attrs, 40, new byte[]{
                (byte) (statusType >>> 24), (byte) (statusType >>> 16),
                (byte) (statusType >>> 8), (byte) statusType});
        if (callingStationId != null) {
            attr(attrs, 31, callingStationId.getBytes(StandardCharsets.UTF_8));
        }
        if (imsi != null) {
            ByteArrayOutputStream vsaValue = new ByteArrayOutputStream();
            vsaValue.writeBytes(new byte[]{0x00, 0x00, 0x28, (byte) 0xAF}); // vendor-id 10415
            attr(vsaValue, 1, imsi.getBytes(StandardCharsets.UTF_8));
            attr(attrs, 26, vsaValue.toByteArray());
        }
        byte[] attributes = attrs.toByteArray();
        int total = 20 + attributes.length;
        byte[] pkt = new byte[total];
        pkt[0] = 4;
        pkt[1] = (byte) id;
        pkt[2] = (byte) (total >>> 8);
        pkt[3] = (byte) total;
        System.arraycopy(attributes, 0, pkt, 20, attributes.length);
        return seal(pkt);
    }

    /** Writes a correct RFC 2866 request authenticator into the packet. */
    private static byte[] seal(byte[] pkt) {
        byte[] authenticator = md5(concat(pkt, SECRET.getBytes(StandardCharsets.UTF_8)));
        System.arraycopy(authenticator, 0, pkt, 4, 16);
        return pkt;
    }

    private static void attr(ByteArrayOutputStream out, int type, byte[] value) {
        out.write(type);
        out.write(value.length + 2);
        out.writeBytes(value);
    }

    private static byte[] ipv4(String dotted) {
        String[] parts = dotted.split("\\.");
        return new byte[]{
                (byte) Integer.parseInt(parts[0]), (byte) Integer.parseInt(parts[1]),
                (byte) Integer.parseInt(parts[2]), (byte) Integer.parseInt(parts[3])};
    }

    private static byte[] concat(byte[]... chunks) {
        int total = Arrays.stream(chunks).mapToInt(c -> c.length).sum();
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, out, off, c.length);
            off += c.length;
        }
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
