/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import java.util.List;
import java.util.Set;

/**
 * One CIBA authorization request awaiting token exchange: the identity anchor
 * (normalized {@code +E164} MSISDN, optional IMSI) bound to the granted NV
 * scopes, keyed by {@code auth_req_id}. {@code amr} records how the anchor was
 * authenticated ({@link AuthMethods}) and is copied into the access token.
 */
public record PendingBinding(
        String authReqId,
        String msisdn,
        String imsi,
        Set<String> scopes,
        long issuedEpochSec,
        long expiresEpochSec,
        String clientId,
        List<String> amr) {

    public PendingBinding {
        amr = amr == null ? List.of() : List.copyOf(amr);
    }

    public PendingBinding(String authReqId,
                          String msisdn,
                          String imsi,
                          Set<String> scopes,
                          long issuedEpochSec,
                          long expiresEpochSec,
                          String clientId) {
        this(authReqId, msisdn, imsi, scopes, issuedEpochSec, expiresEpochSec, clientId, List.of());
    }

    public PendingBinding(String authReqId,
                          String msisdn,
                          String imsi,
                          Set<String> scopes,
                          long issuedEpochSec,
                          long expiresEpochSec) {
        this(authReqId, msisdn, imsi, scopes, issuedEpochSec, expiresEpochSec, null);
    }

    /** RFC 7519: current time must be strictly before exp. */
    public boolean expiredAt(long nowEpochSec) {
        return nowEpochSec >= expiresEpochSec;
    }
}
