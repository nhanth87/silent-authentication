/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.commands.gx.CreditControlAnswer;
import com.mobius.software.telco.protocols.diameter.impl.commands.gx.CreditControlAnswerImpl;
import com.mobius.software.telco.protocols.diameter.primitives.DiameterAvpKey;
import com.mobius.software.telco.protocols.diameter.primitives.DiameterUnknownAvp;
import com.mobius.software.telco.protocols.diameter.primitives.creditcontrol.CcRequestTypeEnum;
import com.mobius.software.telco.protocols.diameter.primitives.creditcontrol.SubscriptionIdTypeEnum;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.ras.resolver.PcrfSdResolverBackend.BindingCache;
import et.restlink.sas.ras.resolver.PcrfSdResolverBackend.GxExchangeCorrelator;
import et.restlink.sas.ras.resolver.PcrfSdResolverBackend.Subscriptions;

import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure evidence tests for {@link PcrfSdResolverBackend}: synthetic CCA
 * primitives, Subscription-Id decoding, E.164 normalization, staleness cache
 * and per-Session-Id correlation — no network involved.
 */
class PcrfSdResolverBackendTest {

    private static final long GX_APP_ID = 16777238L;

    // --- evidence evaluation from synthetic CCAs ---------------------------

    @Test
    void successfulCcaWithSingleE164YieldsBoundResult() {
        CreditControlAnswer cca = cca(ResultCodes.DIAMETER_SUCCESS,
                subscriptionId(SubscriptionIdTypeEnum.END_USER_E164, "+251911111111"));
        ResolverResult result = PcrfSdResolverBackend.evaluate(cca, null);
        assertTrue(result.found());
        assertEquals("+251911111111", result.msisdn());
        assertNull(result.imsi());
        assertEquals(0L, result.bearerAgeMs());
        assertNull(result.miss());
    }

    @Test
    void msisdnDigitsAreNormalizedToPlusE164() {
        Subscriptions subs = PcrfSdResolverBackend.parseSubscriptionIds(List.of(
                subscriptionId(SubscriptionIdTypeEnum.END_USER_E164, "251911111111")));
        assertEquals(1, subs.e164().size());
        assertTrue(subs.e164().contains("+251911111111"));
    }

    @Test
    void imsiEntryIsCapturedAlongsideMsisdn() {
        CreditControlAnswer cca = cca(ResultCodes.DIAMETER_SUCCESS,
                subscriptionId(SubscriptionIdTypeEnum.END_USER_E164, "+251911111111"),
                subscriptionId(SubscriptionIdTypeEnum.END_USER_IMSI, "655010000000001"));
        ResolverResult result = PcrfSdResolverBackend.evaluate(cca, null);
        assertTrue(result.found());
        assertEquals("+251911111111", result.msisdn());
        assertEquals("655010000000001", result.imsi());
    }

    @Test
    void twoDistinctE164sAreAmbiguous() {
        CreditControlAnswer cca = cca(ResultCodes.DIAMETER_SUCCESS,
                subscriptionId(SubscriptionIdTypeEnum.END_USER_E164, "+251911111111"),
                subscriptionId(SubscriptionIdTypeEnum.END_USER_E164, "+251922222222"));
        ResolverResult result = PcrfSdResolverBackend.evaluate(cca, null);
        assertFalse(result.found());
        assertEquals(FallbackReason.AMBIGUOUS_BINDING, result.miss());
    }

    @Test
    void duplicateE164EntriesCollapseToOne() {
        CreditControlAnswer cca = cca(ResultCodes.DIAMETER_SUCCESS,
                subscriptionId(SubscriptionIdTypeEnum.END_USER_E164, "+251911111111"),
                subscriptionId(SubscriptionIdTypeEnum.END_USER_E164, "251911111111"));
        ResolverResult result = PcrfSdResolverBackend.evaluate(cca, null);
        assertTrue(result.found());
        assertEquals("+251911111111", result.msisdn());
    }

    @Test
    void successWithoutSubscriptionFailsClosed() {
        CreditControlAnswer cca = cca(ResultCodes.DIAMETER_SUCCESS);
        ResolverResult result = PcrfSdResolverBackend.evaluate(cca, null);
        assertFalse(result.found());
        assertEquals(FallbackReason.NO_BINDING, result.miss());
    }

    @Test
    void userUnknownResultCodeFailsClosed() {
        CreditControlAnswer cca = cca(5030L,
                subscriptionId(SubscriptionIdTypeEnum.END_USER_E164, "+251911111111"));
        ResolverResult result = PcrfSdResolverBackend.evaluate(cca, null);
        assertFalse(result.found());
        assertEquals(FallbackReason.NO_BINDING, result.miss());
    }

    @Test
    void errorAndNullAnswersFailClosed() {
        assertEquals(FallbackReason.NO_BINDING,
                PcrfSdResolverBackend.evaluate(null, new RuntimeException("send failed")).miss());
        assertEquals(FallbackReason.NO_BINDING,
                PcrfSdResolverBackend.evaluate(cca(ResultCodes.DIAMETER_SUCCESS), new RuntimeException()).miss());
    }

    @Test
    void malformedSubscriptionValueIsSkippedNotFatal() {
        CreditControlAnswer cca = cca(ResultCodes.DIAMETER_SUCCESS,
                unknown(new byte[]{0x01, 0x02, 0x03})); // too short for one AVP header
        assertEquals(FallbackReason.NO_BINDING,
                PcrfSdResolverBackend.evaluate(cca, null).miss());

        // length field claims 64 bytes but only 8 exist — parser keeps nothing
        byte[] overlong = new byte[16];
        overlong[5] = 0x00;
        overlong[6] = 0x00;
        overlong[7] = 0x40;
        assertEquals(FallbackReason.NO_BINDING,
                PcrfSdResolverBackend.evaluate(
                        cca(ResultCodes.DIAMETER_SUCCESS, unknown(overlong)), null).miss());
    }

    // --- normalization ------------------------------------------------------

    @Test
    void normalizeE164CoversEdgeCases() {
        assertEquals("+251911111111", PcrfSdResolverBackend.normalizeE164("+251911111111"));
        assertEquals("+251911111111", PcrfSdResolverBackend.normalizeE164("251911111111"));
        assertEquals("+251911111111", PcrfSdResolverBackend.normalizeE164(" +251 911-111-111 "));
        assertNull(PcrfSdResolverBackend.normalizeE164(""));
        assertNull(PcrfSdResolverBackend.normalizeE164("   "));
        assertNull(PcrfSdResolverBackend.normalizeE164("no-digits"));
        assertNull(PcrfSdResolverBackend.normalizeE164(null));
    }

    // --- staleness cache ----------------------------------------------------

    @Test
    void cacheServesWithinWindowAndExpiresAfterIt() {
        BindingCache cache = new BindingCache();
        long now = 10_000L;
        cache.put("10.20.30.40", "+251911111111", "655010000000001", now);

        assertNotNull(cache.fresh("10.20.30.40", now + 59_999L, 60_000L));
        assertNull(cache.fresh("10.20.30.40", now + 60_001L, 60_000L), "stale entry must expire");
        assertNull(cache.fresh("other-ip", now, 60_000L));

        cache.put("10.20.30.40", "+251922222222", null, now + 70_000L);
        BindingCache.Entry refreshed =
                cache.fresh("10.20.30.40", now + 70_000L, 60_000L);
        assertEquals("+251922222222", refreshed.msisdn(), "upsert must replace");
        assertEquals(1, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }

    // --- correlation --------------------------------------------------------

    @Test
    void correlatorCompletesOnlyTheAddressedExchange() throws Exception {
        GxExchangeCorrelator correlator = new GxExchangeCorrelator();
        CompletableFuture<CreditControlAnswer> first = correlator.register("session-1");
        CompletableFuture<CreditControlAnswer> second = correlator.register("session-2");
        assertEquals(2, correlator.size());

        assertTrue(correlator.complete("session-2",
                ccaOnSession(ResultCodes.DIAMETER_SUCCESS, "session-2")));

        assertFalse(first.isDone(), "unrelated exchange must stay pending");
        assertTrue(second.isDone());
        second.get(0, TimeUnit.MILLISECONDS);
        assertEquals(1, correlator.size());
    }

    @Test
    void correlatorRejectsUnmatchedAndSupportsHopByHopFallback() throws Exception {
        GxExchangeCorrelator correlator = new GxExchangeCorrelator();
        CompletableFuture<CreditControlAnswer> pending = correlator.register("session-1");

        assertFalse(correlator.complete(null, cca(ResultCodes.DIAMETER_SUCCESS)));
        assertFalse(correlator.complete("unknown", cca(ResultCodes.DIAMETER_SUCCESS)));
        assertFalse(correlator.completeByHopByHop(null, cca(ResultCodes.DIAMETER_SUCCESS)));
        assertFalse(pending.isDone());

        correlator.bindHopByHop("session-1", 42L);
        assertTrue(correlator.completeByHopByHop(42L, cca(ResultCodes.DIAMETER_SUCCESS)));
        assertTrue(pending.isDone());
        assertEquals(0, correlator.size());

        CompletableFuture<CreditControlAnswer> other = correlator.register("session-3");
        assertTrue(correlator.fail("session-3", new TimeoutException("budget")));
        assertTrue(other.isCompletedExceptionally());
        correlator.failAll(new IllegalStateException("stop"));
        assertEquals(0, correlator.size());
    }

    // --- fail-closed without transport --------------------------------------

    @Test
    void resolveBeforeStartMissesImmediately() {
        PcrfSdResolverBackend backend =
                new PcrfSdResolverBackend("127.0.0.1", 1, false, 500, 60_000);
        assertFalse(backend.isRunning());
        ResolverResult result = backend.resolve("10.20.30.40", 5555,
                System.currentTimeMillis()).join();
        assertFalse(result.found());
        assertEquals(FallbackReason.NO_BINDING, result.miss());
    }

    @Test
    void distantTimestampFailsClosedEvenWhenStopped() throws Exception {
        PcrfSdResolverBackend backend =
                new PcrfSdResolverBackend("127.0.0.1", 1, false, 500, 60_000);
        backend.start();
        backend.stop();
        assertFalse(backend.isRunning());
        assertEquals(FallbackReason.NO_BINDING, backend.resolve("10.20.30.40", 0,
                System.currentTimeMillis()).join().miss());
        assertEquals(FallbackReason.NO_BINDING, backend.resolve("10.20.30.40", 0,
                System.currentTimeMillis() - 3_600_000L).join().miss(),
                "distant instant must fail closed even with transport available");
    }

    // --- helpers ------------------------------------------------------------

    /** Synthetic CCA carrying the given raw optional AVPs. */
    private static CreditControlAnswer cca(long resultCode, DiameterUnknownAvp... avps) {
        try {
            CreditControlAnswerImpl answer = new CreditControlAnswerImpl(
                    "pcrf.restlink.et", "restlink.et", false, resultCode, "session-test",
                    GX_APP_ID, CcRequestTypeEnum.INITIAL_REQUEST, 0L);
            for (DiameterUnknownAvp avp : avps) {
                answer.addOptionalAvp(new DiameterAvpKey(avp.getAvpCode()), avp);
            }
            return answer;
        } catch (Exception e) {
            throw new IllegalStateException("synthetic CCA rejected", e);
        }
    }

    private static CreditControlAnswer ccaOnSession(long resultCode, String sessionId) {
        try {
            return new CreditControlAnswerImpl(
                    "pcrf.restlink.et", "restlink.et", false, resultCode, sessionId,
                    GX_APP_ID, CcRequestTypeEnum.INITIAL_REQUEST, 0L);
        } catch (Exception e) {
            throw new IllegalStateException("synthetic CCA rejected", e);
        }
    }

    /** Encodes { Subscription-Id-Type 450, Subscription-Id-Data 444 } into an unknown AVP. */
    private static DiameterUnknownAvp subscriptionId(SubscriptionIdTypeEnum type, String data) {
        int typeCode = type.getValue();
        byte[] typeLeaf = leaf(450, new byte[]{
                (byte) (typeCode >>> 24), (byte) (typeCode >>> 16),
                (byte) (typeCode >>> 8), (byte) typeCode});
        byte[] dataLeaf = leaf(444, data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] grouped = new byte[typeLeaf.length + dataLeaf.length];
        System.arraycopy(typeLeaf, 0, grouped, 0, typeLeaf.length);
        System.arraycopy(dataLeaf, 0, grouped, typeLeaf.length, dataLeaf.length);
        return unknown(grouped);
    }

    private static DiameterUnknownAvp unknown(byte[] value) {
        return new com.mobius.software.telco.protocols.diameter.impl.primitives.DiameterUnknownAvpImpl(
                null, 443L, Unpooled.wrappedBuffer(value));
    }

    /** One padded nested AVP: code(4) flags(1) len(3) data + padding. */
    private static byte[] leaf(int avpCode, byte[] data) {
        int length = 8 + data.length;
        byte[] out = new byte[length + ((4 - (data.length % 4)) % 4)];
        out[0] = (byte) (avpCode >>> 24);
        out[1] = (byte) (avpCode >>> 16);
        out[2] = (byte) (avpCode >>> 8);
        out[3] = (byte) avpCode;
        out[5] = (byte) (length >>> 16);
        out[6] = (byte) (length >>> 8);
        out[7] = (byte) length;
        System.arraycopy(data, 0, out, 8, data.length);
        return out;
    }
}
