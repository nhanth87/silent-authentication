/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.swxverifier;

import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.primitives.cxdx.SIPAuthDataItem;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.VerificationEvidence;

import java.util.List;

/**
 * Pure evidence mapping for SWx answers (TS 29.273). Static + primitive so it
 * is unit-testable without a Diameter link.
 *
 * <p><strong>Result-code mapping (fail-closed).</strong> Same success rule as
 * the S6a verifier: Result-Code 2001/2002 and, when present, an
 * Experimental-Result-Code also in the success series; anything else fails
 * closed.</p>
 *
 * <ul>
 *   <li>MAA (§6.2.2/§8.1.2): success with ≥1 SIP-Auth-Data-Item carrying a
 *       SIP-Authenticate/SIP-Digest-Authenticate ⇒ HSS minted a fresh EAP-AKA
 *       vector set ⇒ notSimSwapped. An explicit non-EAP-AKA auth scheme
 *       (e.g. Digest) cannot ground SIM-based silent auth ⇒ fail-closed;
 *       absent scheme AVP keeps the SWx/EAP-AKA default. Empty vector set ⇒
 *       fail-closed (missing evidence never soft-passes).</li>
 *   <li>SAA (§6.3.2/§8.2.2): success ⇒ reachable when Non-3GPP-User-Data is
 *       returned; 3GPP-AAAServerName presence ⇒ location-plausible.</li>
 *   <li>PPA probe (§6.6.2/§8.4.2): pass-through — must succeed, contributes
 *       no boolean on its own.</li>
 * </ul>
 */
public final class SwxEvidence {

    private SwxEvidence() {
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
     * MAA stage outcome: fresh non-empty EAP-AKA vector set ⇒ credential live
     * / not SIM-swapped (mirrors the InMemory freshness semantics).
     */
    public static VerificationEvidence fromMaa(long resultCode,
                                               Long experimentalResultCode,
                                               int authenticatorCount,
                                               String authenticationScheme) {
        if (!isSuccess(resultCode, experimentalResultCode)) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-MAR");
        }
        if (authenticatorCount <= 0) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-MAR-empty");
        }
        if (authenticationScheme != null && !authenticationScheme.toUpperCase().contains("EAP-AKA")) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-MAR-scheme");
        }
        return VerificationEvidence.ok(false, true, false, "SWX-MAR");
    }

    /**
     * SAA stage outcome: server-name registration accepted by the own HSS ⇒
     * reachable when Non-3GPP-User-Data came back; AAA-server-name presence
     * is the coarse location signal over SWx.
     */
    public static VerificationEvidence fromSaa(long resultCode,
                                               Long experimentalResultCode,
                                               boolean userDataPresent,
                                               boolean aaaServerNamePresent) {
        if (!isSuccess(resultCode, experimentalResultCode)) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-SAR");
        }
        return VerificationEvidence.ok(userDataPresent, false,
                aaaServerNamePresent, "SWX-SAR");
    }

    /** PPR-probe stage outcome: must succeed; carries no boolean contribution. */
    public static VerificationEvidence fromPpa(long resultCode, Long experimentalResultCode) {
        if (!isSuccess(resultCode, experimentalResultCode)) {
            return VerificationEvidence.fail(FallbackReason.VERIFY_ERROR, "SWX-PPR");
        }
        return VerificationEvidence.ok(false, false, false, "SWX-PPR");
    }

    /**
     * Merge two passed stages under one protocol audit tag. Each stage
     * contributes its own evidence dimension (MAR ⇒ notSimSwapped,
     * SAR ⇒ reachable/plausible), so dimensions are OR-ed while any stage
     * failure propagates unchanged.
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

    /** Items with a usable authenticator (SIP-Authenticate or digest), null-safe. */
    public static int authenticatorCount(List<SIPAuthDataItem> items) {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (SIPAuthDataItem item : items) {
            if (item != null && (item.getSIPAuthenticate() != null
                    || item.getSIPDigestAuthenticate() != null)) {
                count++;
            }
        }
        return count;
    }

    /** First non-blank SIP-Authentication-Scheme of the item list, or null. */
    public static String firstScheme(List<SIPAuthDataItem> items) {
        if (items == null) {
            return null;
        }
        for (SIPAuthDataItem item : items) {
            if (item == null || item.getSIPAuthenticationScheme() == null
                    || item.getSIPAuthenticationScheme().isBlank()) {
                continue;
            }
            return item.getSIPAuthenticationScheme();
        }
        return null;
    }
}
