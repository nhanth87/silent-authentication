/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.s6averifier;

import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.primitives.s6a.SubscriberStatusEnum;
import com.mobius.software.telco.protocols.diameter.primitives.s6a.SubscriptionData;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

/**
 * Pure evidence mapping for S6a answers (TS 29.272). Static + primitive so it
 * is unit-testable without a Diameter link.
 *
 * <p><strong>Result-code mapping (fail-closed).</strong> An exchange succeeds
 * only when Result-Code is 2001 (DIAMETER_SUCCESS) or 2002
 * (DIAMETER_LIMITED_SUCCESS, RFC 6733 §7.1) AND, when an Experimental-Result
 * is present, its code is also in the success series (3GPP vendor errors such
 * as DIAMETER_ERROR_USER_UNKNOWN 5001 ride Experimental-Result per
 * TS 29.272 §7.2.2). Everything else fails closed:</p>
 *
 * <ul>
 *   <li>ULA (316, TS 29.272 §5.2.2.2/§7.2.4): success ⇒ reachable +
 *       location-plausible; Subscriber-Status OPERATOR_DETERMINED_BARRING
 *       (§7.3.30) ⇒ fail-closed.</li>
 *   <li>UDR/SNR (TS 29.328/29.329 Sh, read-only): a subscriber-data read that
 *       carries the current MSISDN↔IMSI binding age ⇒ notSimSwapped. No EPS
 *       vectors are minted and no attach/location update is triggered.</li>
 * </ul>
 *
 * <p><strong>AIR/AIA and IDR/IDA are deliberately absent from the verify
 * path.</strong> AIR consumes a real EPS vector set and advances the AuC SQN
 * (risk of MAC-failure re-sync on the live USIM); IDR is an HSS→MME push, the
 * wrong direction for a verifier query.</p>
 */
public final class S6aEvidence {

    private S6aEvidence() {
    }

    /** Success iff Result-Code and any Experimental-Result-Code are 2001/2002. */
    public static boolean isSuccess(long resultCode, Long experimentalResultCode) {
        boolean base = resultCode == ResultCodes.DIAMETER_SUCCESS
                || resultCode == ResultCodes.DIAMETER_LIMITED_SUCCESS;
        boolean experimentalOk = experimentalResultCode == null
                || experimentalResultCode == ResultCodes.DIAMETER_SUCCESS
                || experimentalResultCode == ResultCodes.DIAMETER_LIMITED_SUCCESS;
        return base && experimentalOk;
    }

    /**
     * ULA stage outcome: success + non-barred subscriber ⇒ reachable and
     * location-plausible (HSS accepted our serving-node registration).
     */
    public static VerificationEvidence fromUla(long resultCode,
                                               Long experimentalResultCode,
                                               boolean operatorDeterminedBarring) {
        if (!isSuccess(resultCode, experimentalResultCode)) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A-ULR");
        }
        if (operatorDeterminedBarring) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "S6A-ULR-barred");
        }
        return VerificationEvidence.ok(true, false, true, "S6A-ULR");
    }

    /**
     * Read-only Sh UDR/SNR stage outcome (TS 29.328/29.329): a subscriber-data
     * read carrying the current MSISDN↔IMSI binding age. {@code bindingStable}
     * means the resolved IMSI matches the HSS's current binding and is older
     * than the SIM-swap cooldown ⇒ not SIM-swapped. No EPS vectors are minted
     * and no attach/location update is triggered — unlike AIR.
     */
    public static VerificationEvidence fromUdr(long resultCode,
                                               Long experimentalResultCode,
                                               boolean bindingStable) {
        if (!isSuccess(resultCode, experimentalResultCode)) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "Sh-UDR");
        }
        return VerificationEvidence.ok(false, bindingStable, false, "Sh-UDR");
    }

    /**
     * Merge two passed stages under one protocol audit tag. Each stage
     * contributes its own evidence dimension (ULR ⇒ reachable/plausible,
     * UDR ⇒ notSimSwapped), so dimensions are OR-ed while any stage failure
     * propagates unchanged.
     */
    public static VerificationEvidence combine(VerificationEvidence first,
                                               VerificationEvidence second,
                                               String protocol) {
        if (first.failed()) {
            return first;
        }
        if (second.failed()) {
            return second;
        }
        return VerificationEvidence.ok(
                first.reachable() || second.reachable(),
                first.notSimSwapped() || second.notSimSwapped(),
                first.locationPlausible() || second.locationPlausible(),
                protocol);
    }

    /** True when Subscription-Data bars the subscriber (OPERATOR_DETERMINED_BARRING). */
    public static boolean subscriberBarred(SubscriptionData subscriptionData) {
        return subscriptionData != null
                && subscriptionData.getSubscriberStatus() == SubscriberStatusEnum.OPERATOR_DETERMINED_BARRING;
    }

    /**
     * PLMN digit string ("MCCMNC", 5–6 digits) to the 3-octet Visited-PLMN-Id
     * wire encoding of TS 24.301 §9.9.3.3a (referenced by TS 29.272 §7.3.9):
     * octet1 = MCC d2 d1, octet2 = MNC d3 (or F) | MCC d3, octet3 = MNC d2 d1.
     * Example "63601" → {@code 36 F6 10}.
     */
    public static byte[] visitedPlmnTbcd(String digits) {
        if (digits == null || !digits.matches("\\d{5,6}")) {
            throw new IllegalArgumentException("visited PLMN must be 5-6 numeric digits");
        }
        int m1 = digits.charAt(0) - '0';
        int m2 = digits.charAt(1) - '0';
        int m3 = digits.charAt(2) - '0';
        int n1 = digits.charAt(3) - '0';
        int n2 = digits.charAt(4) - '0';
        int n3 = digits.length() == 6 ? digits.charAt(5) - '0' : 0x0F;
        return new byte[]{
                (byte) ((m2 << 4) | m1),
                (byte) ((n3 << 4) | m3),
                (byte) ((n2 << 4) | n1)
        };
    }
}
