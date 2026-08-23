/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.events;

import et.restlink.sas.fsm.AssurancePolicy;
import et.restlink.sas.model.AccessTech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VerifyRequestEvent constructor back-compat: legacy 6-arg and 7-arg
 * call sites keep compiling with {@code claimedImsi == null}; the full
 * overload carries the TS.43 entitlement-token IMSI (B2).
 */
class VerifyRequestEventTest {

    private static final String REQ = "req-1";
    private static final String IP = "10.20.30.40";
    private static final String MSISDN = "+251911222333";
    private static final String IMSI = "655010000000001";
    private static final long TS = 1_724_000_000_000L;

    @Test
    void legacySixArgCtor_claimedImsiNull() {
        VerifyRequestEvent evt = new VerifyRequestEvent(
                REQ, IP, 55555, TS, MSISDN, AccessTech.WIFI);
        assertEquals(REQ, evt.reqId());
        assertEquals(MSISDN, evt.claimedMsisdn());
        assertNull(evt.claimedImsi(), "legacy ctor leaves claimed IMSI absent");
        assertNull(evt.riskClass());
    }

    @Test
    void legacySevenArgCtor_claimedImsiNull_riskClassKept() {
        VerifyRequestEvent evt = new VerifyRequestEvent(
                REQ, IP, 55555, TS, MSISDN, AccessTech.LTE,
                AssurancePolicy.RiskClass.TRANSFER);
        assertEquals(AssurancePolicy.RiskClass.TRANSFER, evt.riskClass());
        assertNull(evt.claimedImsi(), "legacy ctor leaves claimed IMSI absent");
    }

    @Test
    void fullOverload_carriesClaimedImsi() {
        VerifyRequestEvent evt = new VerifyRequestEvent(
                REQ, IP, 55555, TS, MSISDN, IMSI, AccessTech.WIFI,
                AssurancePolicy.RiskClass.HIGH_VALUE);
        assertEquals(IMSI, evt.claimedImsi());
        assertEquals(MSISDN, evt.claimedMsisdn());
        assertEquals(AccessTech.WIFI, evt.accessTech());
        assertEquals(TS, evt.tsEpochMs());
    }

    @Test
    void fullOverload_acceptsNullClaimedImsi() {
        VerifyRequestEvent evt = new VerifyRequestEvent(
                REQ, IP, 55555, TS, null, null, AccessTech.GS_2G3G, null);
        assertNull(evt.claimedMsisdn());
        assertNull(evt.claimedImsi());
        assertNull(evt.riskClass());
    }

    @Test
    void tenantOverload_carriesBillingTenant_legacyLeavesNull() {
        VerifyRequestEvent evt = new VerifyRequestEvent(
                REQ, IP, 55555, TS, MSISDN, null, AccessTech.WIFI,
                AssurancePolicy.RiskClass.HIGH_VALUE, "bankA");
        assertEquals("bankA", evt.tenantId());
        assertEquals(MSISDN, evt.claimedMsisdn());
        assertEquals(AccessTech.WIFI, evt.accessTech());

        VerifyRequestEvent legacy = new VerifyRequestEvent(
                REQ, IP, 55555, TS, MSISDN, AccessTech.WIFI);
        assertNull(legacy.tenantId(), "legacy ctor leaves the billing tenant absent");
    }
}
