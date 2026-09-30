/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.impl.primitives.cxdx.SIPAuthDataItemImpl;
import com.mobius.software.telco.protocols.diameter.primitives.cxdx.SIPAuthDataItem;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.Test;

/**
 * Fail-closed evidence mapping for synthetic MAA/SAA/PPA results (TS 29.273).
 * Pure JUnit, no Diameter link.
 */
class SwxEvidenceTest {

    private static final long SUCCESS = ResultCodes.DIAMETER_SUCCESS;
    private static final long LIMITED = ResultCodes.DIAMETER_LIMITED_SUCCESS;
    private static final long NO_SUBSCRIPTION = 5450L;

    @Test
    void successRequiresSuccessSeriesResultCode() {
        assertTrue(SwxEvidence.isSuccess(SUCCESS, null));
        assertTrue(SwxEvidence.isSuccess(LIMITED, null));
        assertFalse(SwxEvidence.isSuccess(-1L, null));
        assertFalse(SwxEvidence.isSuccess(SUCCESS, NO_SUBSCRIPTION));
    }

    @Test
    void maaWithEapAkaVectorsMeansFreshCredential() {
        VerificationEvidence ev = SwxEvidence.fromMaa(SUCCESS, null, 1, "EAP-AKA");
        assertFalse(ev.failed());
        assertTrue(ev.notSimSwapped());
        assertEquals("SWX-MAR", ev.protocol());
    }

    @Test
    void maaWithoutSchemeAvpKeepsEapAkaDefault() {
        VerificationEvidence ev = SwxEvidence.fromMaa(SUCCESS, null, 2, null);
        assertFalse(ev.failed());
        assertTrue(ev.notSimSwapped());
    }

    @Test
    void maaEmptyVectorSetFailsClosed() {
        VerificationEvidence ev = SwxEvidence.fromMaa(SUCCESS, null, 0, "EAP-AKA");
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
        assertEquals("SWX-MAR-empty", ev.protocol());
    }

    @Test
    void maaDigestOnlySchemeFailsClosed() {
        VerificationEvidence ev = SwxEvidence.fromMaa(SUCCESS, null, 1, "Digest");
        assertTrue(ev.failed());
        assertEquals("SWX-MAR-scheme", ev.protocol());
    }

    @Test
    void maaErrorResultCodeFailsClosed() {
        VerificationEvidence ev = SwxEvidence.fromMaa(NO_SUBSCRIPTION, NO_SUBSCRIPTION, 1, "EAP-AKA");
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
    }

    @Test
    void saaRegistrationGrantsReachableAndServerNamePlausible() {
        VerificationEvidence ev = SwxEvidence.fromSaa(SUCCESS, null, true, true);
        assertFalse(ev.failed());
        assertTrue(ev.reachable());
        assertTrue(ev.locationPlausible());
        assertEquals("SWX-SAR", ev.protocol());

        VerificationEvidence bare = SwxEvidence.fromSaa(SUCCESS, null, false, false);
        assertFalse(bare.failed());
        assertFalse(bare.reachable());
        assertFalse(bare.locationPlausible());
    }

    @Test
    void saaErrorResultCodeFailsClosed() {
        VerificationEvidence ev = SwxEvidence.fromSaa(NO_SUBSCRIPTION, NO_SUBSCRIPTION, false, false);
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
    }

    @Test
    void ppaProbeMustSucceedButAddsNoBoolean() {
        VerificationEvidence ok = SwxEvidence.fromPpa(SUCCESS, null);
        assertFalse(ok.failed());
        assertFalse(ok.reachable());
        VerificationEvidence bad = SwxEvidence.fromPpa(5012L, 5012L);
        assertTrue(bad.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, bad.failure());
    }

    @Test
    void combineAndsStageBooleansUnderOneTag() {
        VerificationEvidence combined = SwxEvidence.combine(
                SwxEvidence.fromMaa(SUCCESS, null, 1, "EAP-AKA"),
                SwxEvidence.fromSaa(SUCCESS, null, true, true),
                "SWX-MAR+SAR");
        assertFalse(combined.failed());
        assertTrue(combined.reachable());
        assertTrue(combined.notSimSwapped());
        assertTrue(combined.locationPlausible());
        assertEquals("SWX-MAR+SAR", combined.protocol());

        VerificationEvidence failed = SwxEvidence.combine(
                SwxEvidence.fromMaa(SUCCESS, null, 0, "EAP-AKA"),
                SwxEvidence.fromSaa(SUCCESS, null, true, true),
                "SWX-MAR+SAR");
        assertTrue(failed.failed());
    }

    @Test
    void authenticatorCountsItemsWithUsableAuthenticators() throws Exception {
        assertEquals(0, SwxEvidence.authenticatorCount(null));

        SIPAuthDataItem empty = new SIPAuthDataItemImpl();
        assertEquals(0, SwxEvidence.authenticatorCount(java.util.List.of(empty)));
        assertEquals(0, SwxEvidence.authenticatorCount(java.util.Arrays.asList((SIPAuthDataItem) null)));

        SIPAuthDataItem eapAka = new SIPAuthDataItemImpl();
        eapAka.setSIPAuthenticate(Unpooled.wrappedBuffer(new byte[32]));
        SIPAuthDataItem digest = new SIPAuthDataItemImpl();
        digest.setSIPDigestAuthenticate(
                new com.mobius.software.telco.protocols.diameter.impl.primitives.cxdx.SIPDigestAuthenticateImpl(
                        "restlink.et"));
        assertEquals(2, SwxEvidence.authenticatorCount(java.util.List.of(eapAka, digest)));
    }

    @Test
    void firstSchemeSkipsBlankEntries() {
        SIPAuthDataItem blank = new SIPAuthDataItemImpl();
        blank.setSIPAuthenticationScheme("");
        SIPAuthDataItem eapAka = new SIPAuthDataItemImpl();
        eapAka.setSIPAuthenticationScheme("EAP-AKA");
        assertEquals("EAP-AKA",
                SwxEvidence.firstScheme(java.util.List.of(blank, eapAka)));
        assertEquals(null, SwxEvidence.firstScheme(java.util.List.of(blank)));
        assertEquals(null, SwxEvidence.firstScheme(null));
    }
}
