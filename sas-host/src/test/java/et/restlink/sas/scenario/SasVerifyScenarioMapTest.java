/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.scenario;

import static org.junit.jupiter.api.Assertions.*;

import et.restlink.sas.fsm.AssurancePolicy;
import et.restlink.sas.fsm.VerificationFsm;
import et.restlink.sas.model.AccessTech;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.model.VerifyResult;
import et.restlink.sas.ras.mapverifier.InMemoryMapVerifierBackend;
import et.restlink.sas.ras.mapverifier.MapVerifierResourceAdaptor;
import et.restlink.sas.ras.mapverifier.command.MapVerifyCommand;
import et.restlink.sas.ras.resolver.InMemoryResolverBackend;
import et.restlink.sas.ras.resolver.ResolverResourceAdaptor;
import et.restlink.sas.ras.resolver.command.ResolveCommand;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The {@code docs/design/silent-auth-standard-flow.md} scenario written as sequential
 * test steps, MAP verify first:
 *
 * <pre>
 *   Step 1  RESOLVING — IP:port:ts → MSISDN/IMSI (Resolver RA)
 *   Step 2  VERIFYING  — 2G/3G MAP PSI + SAI, never ATI (MAP Verifier RA)
 *   Step 3  SCORING    — VerificationFsm → APPROVED / FALLBACK (fail-closed)
 * </pre>
 *
 * <p>Each fail-closed branch (no binding, CGNAT ambiguity, claim mismatch,
 * unknown/detached subscriber, SIM-swap suspect, low assurance) is asserted as
 * its own test step so a regression in any stage is isolated immediately.</p>
 */
class SasVerifyScenarioMapTest {

    private static final String REQ = "req-0001";
    private static final String IP = "10.20.30.40";
    private static final int PORT = 55555;
    private static final String MSISDN = "+251911111111";
    private static final String IMSI = "655010000000001";
    private static final long BEARER_AGE_MS = 30_000L;

    private final VerificationFsm fsm = new VerificationFsm(AssurancePolicy.defaults());

    // ---- fixtures (mirror the SasBootstrap pilot seeds) ------------------

    private static InMemoryResolverBackend resolverBackend() {
        InMemoryResolverBackend b = new InMemoryResolverBackend(0L);
        b.seed(IP, PORT, MSISDN, IMSI, BEARER_AGE_MS);
        return b;
    }

    private static InMemoryMapVerifierBackend mapBackend() {
        InMemoryMapVerifierBackend b = new InMemoryMapVerifierBackend(0L, "AA");
        b.seed(MSISDN, IMSI, true, daysAgo(10), "AA");
        return b;
    }

    private static MapVerifierResourceAdaptor mapRa(InMemoryMapVerifierBackend b) {
        MapVerifierResourceAdaptor ra = new MapVerifierResourceAdaptor();
        ra.setBackend(b);
        ra.raActive();
        return ra;
    }

    // ---- stage drivers ---------------------------------------------------

    private long now() {
        return System.currentTimeMillis();
    }

    private static ResolverResult resolve(InMemoryResolverBackend backend, String reqId,
                                          String ip, int port, long ts) throws Exception {
        ResolverResourceAdaptor ra = new ResolverResourceAdaptor();
        ra.setBackend(backend);
        ra.raActive();
        CompletableFuture<ResolverResult> reply = new CompletableFuture<>();
        ra.resolve(new ResolveCommand(reqId, ip, port, ts, reply));
        return reply.get(1, TimeUnit.SECONDS);
    }

    private VerificationEvidence verifyMap(InMemoryMapVerifierBackend backend, ResolverResult rr)
            throws Exception {
        CompletableFuture<VerificationEvidence> reply = new CompletableFuture<>();
        mapRa(backend).verify(new MapVerifyCommand(REQ, rr.msisdn(), rr.imsi(),
                AccessTech.GS_2G3G, reply));
        return reply.get(3, TimeUnit.SECONDS);
    }

    // ---- Step 1 — RESOLVING ----------------------------------------------

    @Test
    void step1_resolverBindsSessionTuple() throws Exception {
        ResolverResult rr = resolve(resolverBackend(), REQ, IP, PORT, now());
        assertTrue(rr.found());
        assertEquals(MSISDN, rr.msisdn());
        assertEquals(IMSI, rr.imsi());
        assertEquals(BEARER_AGE_MS, rr.bearerAgeMs());
    }

    // ---- Step 2 — VERIFYING (MAP PSI + SAI, never ATI) -------------------

    @Test
    void step2_mapVerifyProducesPsiSaiEvidence() throws Exception {
        ResolverResult rr = ResolverResult.bound(MSISDN, IMSI, BEARER_AGE_MS);
        VerificationEvidence ev = verifyMap(mapBackend(), rr);
        assertFalse(ev.failed());
        assertTrue(ev.reachable());
        assertTrue(ev.notSimSwapped());
        assertTrue(ev.locationPlausible());
        assertEquals("MAP-PSI+SAI", ev.protocol());
        assertFalse(ev.protocol().toUpperCase().contains("ATI"));
    }

    // ---- Step 3 — SCORING → APPROVED --------------------------------------

    @Test
    void step3_fullScenarioApproves() throws Exception {
        ResolverResult rr = resolve(resolverBackend(), REQ, IP, PORT, now());
        VerificationEvidence ev = verifyMap(mapBackend(), rr);
        VerifyResult result = fsm.decide(REQ, rr, ev, null);
        assertTrue(result.match());
        assertEquals(MSISDN, result.msisdn());
    }

    @Test
    void step3_claimMatchesApproves() throws Exception {
        ResolverResult rr = resolve(resolverBackend(), REQ, IP, PORT, now());
        VerificationEvidence ev = verifyMap(mapBackend(), rr);
        VerifyResult result = fsm.decide(REQ, rr, ev, MSISDN);
        assertTrue(result.match());
    }

    // ---- Fail-closed branches -------------------------------------------

    @Test
    void noBindingAtResolverFallsBack() throws Exception {
        ResolverResult rr = resolve(resolverBackend(), REQ, "10.99.99.99", PORT, now());
        assertFalse(rr.found());
        VerifyResult result = fsm.decide(REQ, rr, null, null);
        assertFalse(result.match());
        assertEquals(FallbackReason.NO_BINDING, result.fallbackReason());
    }

    @Test
    void cgnatAmbiguityFallsBack() throws Exception {
        InMemoryResolverBackend b = resolverBackend();
        b.seed(IP, PORT, "+251922222222", "655010000000002", BEARER_AGE_MS); // 2nd MSISDN
        ResolverResult rr = resolve(b, REQ, IP, PORT, now());
        assertFalse(rr.found());
        assertEquals(FallbackReason.AMBIGUOUS_BINDING, rr.miss());
        assertFalse(fsm.decide(REQ, rr, null, null).match());
    }

    @Test
    void claimedMsisdnMismatchFallsBack() throws Exception {
        ResolverResult rr = resolve(resolverBackend(), REQ, IP, PORT, now());
        VerificationEvidence ev = verifyMap(mapBackend(), rr);
        VerifyResult result = fsm.decide(REQ, rr, ev, "+251922222222");
        assertFalse(result.match());
        assertEquals(FallbackReason.MSISDN_MISMATCH, result.fallbackReason());
    }

    @Test
    void missingSubscriberFallsBackVerifyError() throws Exception {
        ResolverResult rr = ResolverResult.bound(MSISDN, IMSI, BEARER_AGE_MS);
        VerificationEvidence ev = verifyMap(new InMemoryMapVerifierBackend(0L, "AA"), rr);
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
        assertFalse(fsm.decide(REQ, rr, ev, null).match());
    }

    @Test
    void detachedSubscriberFallsBackPurged() throws Exception {
        ResolverResult rr = ResolverResult.bound(MSISDN, IMSI, BEARER_AGE_MS);
        InMemoryMapVerifierBackend b = new InMemoryMapVerifierBackend(0L, "AA");
        b.seed(MSISDN, IMSI, false, daysAgo(10), "AA"); // not attached
        VerificationEvidence ev = verifyMap(b, rr);
        assertFalse(ev.failed());
        assertFalse(ev.reachable());
        VerifyResult result = fsm.decide(REQ, rr, ev, null);
        assertEquals(FallbackReason.PURGED, result.fallbackReason());
    }

    @Test
    void simSwapSuspectFallsBack() throws Exception {
        ResolverResult rr = ResolverResult.bound(MSISDN, IMSI, BEARER_AGE_MS);
        InMemoryMapVerifierBackend b = new InMemoryMapVerifierBackend(0L, "AA");
        b.seed(MSISDN, IMSI, true, now(), "AA"); // SAI: fresh IMSI change
        VerificationEvidence ev = verifyMap(b, rr);
        assertFalse(ev.failed());
        assertFalse(ev.notSimSwapped());
        VerifyResult result = fsm.decide(REQ, rr, ev, null);
        assertEquals(FallbackReason.SIM_SWAP_SUSPECT, result.fallbackReason());
    }

    @Test
    void lowAssuranceFallsBack() throws Exception {
        // Stale bearer (0 IP-fresh credit) + location implausible
        // ⇒ 0.00 + 0.30 + 0.30 + 0.00 = 0.60 ⇒ score 60 < 70 ⇒ FALLBACK.
        ResolverResult stale = ResolverResult.bound(MSISDN, IMSI,
                AssurancePolicy.STALE_BEARER_MS + 1L);
        InMemoryMapVerifierBackend b = new InMemoryMapVerifierBackend(0L, "AA");
        b.seed(MSISDN, IMSI, true, daysAgo(10), "BB");
        VerificationEvidence ev = verifyMap(b, stale);
        assertFalse(ev.failed());
        assertFalse(ev.locationPlausible());
        VerifyResult result = fsm.decide(REQ, stale, ev, null);
        assertEquals(FallbackReason.LOW_ASSURANCE, result.fallbackReason());
    }

    private static long daysAgo(long days) {
        return System.currentTimeMillis() - days * 24L * 3600L * 1000L;
    }
}