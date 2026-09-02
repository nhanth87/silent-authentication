/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.impl.primitives.s6a.SubscriptionDataImpl;
import com.mobius.software.telco.protocols.diameter.primitives.s6a.SubscriberStatusEnum;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import org.junit.jupiter.api.Test;

/**
 * Fail-closed evidence mapping for synthetic ULA/UDR results (gates H4/H5).
 * Pure JUnit, no Diameter link.
 */
class S6aEvidenceTest {

    private static final long SUCCESS = ResultCodes.DIAMETER_SUCCESS;
    private static final long LIMITED = ResultCodes.DIAMETER_LIMITED_SUCCESS;
    private static final long USER_UNKNOWN = 5001L;

    @Test
    void successRequiresSuccessSeriesResultCode() {
        assertTrue(S6aEvidence.isSuccess(SUCCESS, null));
        assertTrue(S6aEvidence.isSuccess(LIMITED, null));
        assertFalse(S6aEvidence.isSuccess(-1L, null));
        assertFalse(S6aEvidence.isSuccess(5012L, null));
    }

    @Test
    void experimentalErrorCodeFailsClosed() {
        assertTrue(S6aEvidence.isSuccess(SUCCESS, SUCCESS));
        assertFalse(S6aEvidence.isSuccess(SUCCESS, USER_UNKNOWN));
        assertFalse(S6aEvidence.isSuccess(LIMITED, USER_UNKNOWN));
    }

    @Test
    void ulaSuccessGrantsReachableAndPlausible() {
        VerificationEvidence ev = S6aEvidence.fromUla(SUCCESS, null, false);
        assertFalse(ev.failed());
        assertTrue(ev.reachable());
        assertTrue(ev.locationPlausible());
        assertEquals("S6A-ULR", ev.protocol());
    }

    @Test
    void ulaOperatorDeterminedBarringFailsClosed() {
        VerificationEvidence ev = S6aEvidence.fromUla(SUCCESS, null, true);
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
    }

    @Test
    void ulaErrorResultCodeFailsClosed() {
        VerificationEvidence ev = S6aEvidence.fromUla(USER_UNKNOWN, USER_UNKNOWN, false);
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
    }

    @Test
    void udrStableBindingMeansFreshCredential() {
        VerificationEvidence ev = S6aEvidence.fromUdr(SUCCESS, null, true);
        assertFalse(ev.failed());
        assertTrue(ev.notSimSwapped());
        assertEquals("Sh-UDR", ev.protocol());
    }

    @Test
    void udrUnstableBindingMeansSwapSuspect() {
        VerificationEvidence ev = S6aEvidence.fromUdr(SUCCESS, null, false);
        assertFalse(ev.failed());
        assertFalse(ev.notSimSwapped());
        assertEquals("Sh-UDR", ev.protocol());
    }

    @Test
    void udrErrorResultCodeFailsClosed() {
        VerificationEvidence ev = S6aEvidence.fromUdr(USER_UNKNOWN, USER_UNKNOWN, true);
        assertTrue(ev.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, ev.failure());
    }

    @Test
    void combineOrsStageBooleansUnderOneTag() {
        VerificationEvidence combined = S6aEvidence.combine(
                S6aEvidence.fromUla(SUCCESS, null, false),
                S6aEvidence.fromUdr(SUCCESS, null, true),
                "S6A-ULR+Sh-UDR");
        assertFalse(combined.failed());
        assertTrue(combined.reachable());
        assertTrue(combined.notSimSwapped());
        assertTrue(combined.locationPlausible());
        assertEquals("S6A-ULR+Sh-UDR", combined.protocol());
    }

    @Test
    void combinePropagatesSecondStageFailure() {
        VerificationEvidence combined = S6aEvidence.combine(
                S6aEvidence.fromUla(SUCCESS, null, false),
                S6aEvidence.fromUdr(USER_UNKNOWN, USER_UNKNOWN, true),
                "S6A-ULR+Sh-UDR");
        assertTrue(combined.failed());
        assertEquals(FallbackReason.VERIFY_ERROR, combined.failure());
        assertEquals("Sh-UDR", combined.protocol());
    }

    @Test
    void subscriberStatusBarringIsDetected() {
        SubscriptionDataImpl granted = new SubscriptionDataImpl();
        granted.setSubscriberStatus(SubscriberStatusEnum.SERVICE_GRANTED);
        assertFalse(S6aEvidence.subscriberBarred(granted));
        assertFalse(S6aEvidence.subscriberBarred(null));

        SubscriptionDataImpl barred = new SubscriptionDataImpl();
        barred.setSubscriberStatus(SubscriberStatusEnum.OPERATOR_DETERMINED_BARRING);
        assertTrue(S6aEvidence.subscriberBarred(barred));
    }

    @Test
    void visitedPlmnEncodingMatchesTs24301() {
        assertArrayEquals(new byte[]{0x36, (byte) 0xF6, 0x10},
                S6aEvidence.visitedPlmnTbcd("63601"));
        assertArrayEquals(new byte[]{0x13, 0x00, 0x71},
                S6aEvidence.visitedPlmnTbcd("310170"));
    }

    @Test
    void visitedPlmnRejectsMalformedDigits() {
        assertThrows(IllegalArgumentException.class, () -> S6aEvidence.visitedPlmnTbcd("636"));
        assertThrows(IllegalArgumentException.class, () -> S6aEvidence.visitedPlmnTbcd("6360117"));
        assertThrows(IllegalArgumentException.class, () -> S6aEvidence.visitedPlmnTbcd("63a01"));
        assertThrows(IllegalArgumentException.class, () -> S6aEvidence.visitedPlmnTbcd(null));
    }
}
