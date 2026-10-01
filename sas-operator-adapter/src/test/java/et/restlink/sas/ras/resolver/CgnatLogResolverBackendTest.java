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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tail + point-in-time tests for {@link CgnatLogResolverBackend} against a
 * temp CSV log: initial load, incremental append, ts-window filtering,
 * malformed-line skipping and truncate/rotate recovery.
 */
class CgnatLogResolverBackendTest {

    private static final String IP = "196.188.10.5";
    private static final int PORT = 55_000;
    private static final String MSISDN = "+251911111111";
    private static final String IMSI = "655010000000001";

    @TempDir
    Path tmp;

    private Path log;
    private CgnatLogResolverBackend backend;

    @BeforeEach
    void setUp() {
        log = tmp.resolve("cgnat.log");
        backend = null;
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.stop();
            backend = null;
        }
    }

    @Test
    void initialLoadSkipsMalformedLines() throws IOException {
        write(
                "# comment",
                "garbage-no-commas",
                "abc," + IP + ",55000," + MSISDN + "," + IMSI + ",50",
                row(1000, IP, PORT, MSISDN, IMSI, 42),
                row(2000, "10.9.9.9", 2000, "+251933333333", "655010000000003", 7));
        backend = new CgnatLogResolverBackend(log);
        backend.reload();

        ResolverResult r = backend.resolve(IP, PORT, 1000L).join();
        assertTrue(r.found());
        assertEquals(MSISDN, r.msisdn());
        assertEquals(IMSI, r.imsi());
        assertEquals(42L, r.bearerAgeMs());

        r = backend.resolve("10.9.9.9", 2000, 2000L).join();
        assertEquals("+251933333333", r.msisdn());

        assertEquals(FallbackReason.NO_BINDING,
                backend.resolve("203.0.113.99", 1234, 1000L).join().miss());
    }

    @Test
    void tailerPicksUpAppends() throws Exception {
        long t0 = System.currentTimeMillis();
        write(row(t0, IP, PORT, MSISDN, IMSI, 10));
        backend = new CgnatLogResolverBackend(log, 30L, 60_000L);
        backend.start();

        ResolverResult r = resolveUntil(IP, PORT, ResolverResult::found);
        assertEquals(MSISDN, r.msisdn());

        append(row(System.currentTimeMillis(), "10.1.1.8", 40_000, "+251922222222",
                "655010000000002", 5));
        r = resolveUntil("10.1.1.8", 40_000, ResolverResult::found);
        assertEquals("+251922222222", r.msisdn());

        backend.stop();
    }

    @Test
    void pointInTimeWindowFiltering() throws IOException {
        write(
                row(1000, IP, PORT, MSISDN, IMSI, 42),
                row(5000, IP, PORT, MSISDN, IMSI, 46));
        backend = new CgnatLogResolverBackend(log, 60_000L, 60_000L);
        backend.reload();

        // future row (ts=5000) excluded at ts=3000 — only the ts=1000 row counts
        ResolverResult r = backend.resolve(IP, PORT, 3000L).join();
        assertTrue(r.found());
        assertEquals(MSISDN, r.msisdn());
        assertEquals(42L, r.bearerAgeMs());

        // latest row within the window wins at ts=6000
        r = backend.resolve(IP, PORT, 6000L).join();
        assertTrue(r.found());
        assertEquals(46L, r.bearerAgeMs());

        // both rows older than the staleness window at ts=66000 → fail closed
        assertEquals(FallbackReason.NO_BINDING,
                backend.resolve(IP, PORT, 66_000L).join().miss());
    }

    @Test
    void ambiguousWhenDistinctMsisdnsShareIpPort() throws IOException {
        write(
                row(1000, IP, PORT, MSISDN, IMSI, 42),
                row(1100, IP, PORT, "+251922222222", "655010000000002", 3),
                row(1150, IP, PORT + 1, "+251944444444", "655010000000004", 1));
        backend = new CgnatLogResolverBackend(log, 60_000L, 60_000L);
        backend.reload();

        assertEquals(FallbackReason.AMBIGUOUS_BINDING,
                backend.resolve(IP, PORT, 1200L).join().miss());
    }

    @Test
    void truncationTriggersFullRereadAndRecovers() throws Exception {
        long t0 = System.currentTimeMillis();
        write(row(t0, IP, PORT, MSISDN, IMSI, 42));
        backend = new CgnatLogResolverBackend(log, 30L, 60_000L);
        backend.reload();
        assertTrue(backend.resolve(IP, PORT, t0).join().found());

        // rotate/truncate: shorter content with a different binding set
        Files.write(log, (row(System.currentTimeMillis(), "10.5.5.5", 5555,
                "+251922222222", "655010000000002", 3) + "\n").getBytes(StandardCharsets.UTF_8));
        backend.start();

        resolveUntil(IP, PORT, r -> r.miss() == FallbackReason.NO_BINDING);
        ResolverResult r = resolveUntil("10.5.5.5", 5555, ResolverResult::found);
        assertEquals("+251922222222", r.msisdn());

        append(row(System.currentTimeMillis(), "10.7.7.7", 7777, "+251955555555",
                "655010000000005", 1));
        r = resolveUntil("10.7.7.7", 7777, ResolverResult::found);
        assertEquals("+251955555555", r.msisdn());

        backend.stop();
    }

    @Test
    void incompleteTrailingLineBufferedUntilNewline() throws IOException {
        Files.writeString(log, row(1000, IP, PORT, MSISDN, IMSI, 9)); // no newline yet
        backend = new CgnatLogResolverBackend(log, 60_000L, 60_000L);
        backend.reload();

        assertEquals(FallbackReason.NO_BINDING,
                backend.resolve(IP, PORT, 1000L).join().miss());

        Files.writeString(log, "\n", StandardOpenOption.APPEND);
        backend.reload();

        ResolverResult r = backend.resolve(IP, PORT, 1000L).join();
        assertTrue(r.found());
        assertEquals(MSISDN, r.msisdn());
    }

    private static String row(long ts, String ip, int port, String msisdn, String imsi, long bearerAgeMs) {
        return ts + "," + ip + "," + port + "," + msisdn + "," + imsi + "," + bearerAgeMs;
    }

    private void write(String... lines) throws IOException {
        Files.write(log, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void append(String line) throws IOException {
        Files.writeString(log, line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private ResolverResult resolveUntil(String ip, int port, Predicate<ResolverResult> predicate)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        ResolverResult r = null;
        while (System.currentTimeMillis() < deadline) {
            r = backend.resolve(ip, port, System.currentTimeMillis()).join();
            if (predicate.test(r)) {
                return r;
            }
            Thread.sleep(10);
        }
        return backend.resolve(ip, port, System.currentTimeMillis()).join();
    }
}
