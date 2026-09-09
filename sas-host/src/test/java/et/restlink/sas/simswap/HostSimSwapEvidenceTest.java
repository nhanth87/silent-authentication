/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.simswap;

import et.restlink.sas.bootstrap.SasBootstrap;
import et.restlink.sas.ras.mapverifier.InMemoryMapVerifierBackend;
import et.restlink.sas.ras.s6averifier.InMemoryS6aVerifierBackend;
import et.restlink.sas.ras.swxverifier.InMemorySwxVerifierBackend;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SIM-change evidence adapter: reads the binding age the Verifier already
 * scores as {@code notSimSwapped}, in the order MAP → S6a (read-only Sh UDR) →
 * SWx, and fails closed (empty ⇒ northbound {@code 404 IDENTIFIER_NOT_FOUND})
 * when no source knows the subscriber or no source is wired at all.
 */
class HostSimSwapEvidenceTest {

    private static final String MSISDN = "+251911111111";
    private static final String IMSI = "655010000000001";

    @Test
    void noBackendWired_failsClosedEmpty() {
        HostSimSwapEvidence evidence = new HostSimSwapEvidence();
        evidence.bootstrap = new SasBootstrap();
        assertTrue(evidence.lastSimChange(MSISDN).isEmpty());
    }

    @Test
    void noBootstrapAtAll_failsClosedEmpty() {
        HostSimSwapEvidence evidence = new HostSimSwapEvidence();
        assertTrue(evidence.lastSimChange(MSISDN).isEmpty());
    }

    @Test
    void blankOrUnknownIdentifier_failsClosedEmpty() {
        SasBootstrap bootstrap = new SasBootstrap();
        setField(bootstrap, "inMemoryS6aBackend", seededS6a(daysAgo(10)));
        HostSimSwapEvidence evidence = withBootstrap(bootstrap);

        assertTrue(evidence.lastSimChange(null).isEmpty());
        assertTrue(evidence.lastSimChange("  ").isEmpty());
        assertTrue(evidence.lastSimChange("+251999999999").isEmpty());
    }

    @Test
    void mapEvidenceWins_overS6aAndSwx() {
        SasBootstrap bootstrap = new SasBootstrap();
        setField(bootstrap, "inMemoryMapBackend", seededMap(daysAgo(3)));
        setField(bootstrap, "inMemoryS6aBackend", seededS6a(daysAgo(10)));
        setField(bootstrap, "inMemorySwxBackend", seededSwx(daysAgo(20)));

        Optional<Instant> last = withBootstrap(bootstrap).lastSimChange(MSISDN);

        assertTrue(last.isPresent());
        assertEquals(daysAgo(3) / 1000L, last.get().getEpochSecond());
    }

    @Test
    void fallsThroughToS6a_thenSwx_whenTheEarlierSourceIsSilent() {
        SasBootstrap s6aOnly = new SasBootstrap();
        setField(s6aOnly, "inMemoryS6aBackend", seededS6a(daysAgo(10)));
        assertEquals(daysAgo(10) / 1000L,
                withBootstrap(s6aOnly).lastSimChange(MSISDN).orElseThrow().getEpochSecond());

        SasBootstrap swxOnly = new SasBootstrap();
        setField(swxOnly, "inMemorySwxBackend", seededSwx(daysAgo(1)));
        assertEquals(daysAgo(1) / 1000L,
                withBootstrap(swxOnly).lastSimChange(MSISDN).orElseThrow().getEpochSecond());
    }

    @Test
    void freshSwapIsReported_notSuppressed() {
        SasBootstrap bootstrap = new SasBootstrap();
        setField(bootstrap, "inMemoryS6aBackend", seededS6a(minutesAgo(5)));

        Optional<Instant> last = withBootstrap(bootstrap).lastSimChange(MSISDN);

        assertTrue(last.isPresent());
        assertTrue(last.get().isAfter(Instant.now().minusSeconds(600)),
                "a 5-minute-old SIM change must surface so /check answers swapped=true");
    }

    // ---- fixtures ----

    private static HostSimSwapEvidence withBootstrap(SasBootstrap bootstrap) {
        HostSimSwapEvidence evidence = new HostSimSwapEvidence();
        evidence.bootstrap = bootstrap;
        return evidence;
    }

    private static InMemoryMapVerifierBackend seededMap(long lastImsiChangeEpochMs) {
        InMemoryMapVerifierBackend b = new InMemoryMapVerifierBackend();
        b.seed(MSISDN, IMSI, true, lastImsiChangeEpochMs, "AA");
        return b;
    }

    private static InMemoryS6aVerifierBackend seededS6a(long lastImsiChangeEpochMs) {
        InMemoryS6aVerifierBackend b = new InMemoryS6aVerifierBackend();
        b.seed(MSISDN, IMSI, true, lastImsiChangeEpochMs, "AA");
        return b;
    }

    private static InMemorySwxVerifierBackend seededSwx(long lastImsiChangeEpochMs) {
        InMemorySwxVerifierBackend b = new InMemorySwxVerifierBackend();
        b.seed(MSISDN, IMSI, true, lastImsiChangeEpochMs, "AA");
        return b;
    }

    private static long daysAgo(long days) {
        return System.currentTimeMillis() - days * 24L * 3600L * 1000L;
    }

    private static long minutesAgo(long minutes) {
        return System.currentTimeMillis() - minutes * 60L * 1000L;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
