/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import java.util.Set;

/**
 * One CIBA authorization request awaiting token exchange: the identity anchor
 * (normalized {@code +E164} MSISDN, optional IMSI) bound to the granted NV
 * scopes, keyed by {@code auth_req_id}.
 */
public record PendingBinding(
        String authReqId,
        String msisdn,
        String imsi,
        Set<String> scopes,
        long issuedEpochSec,
        long expiresEpochSec) {

    /** RFC 7519: current time must be strictly before exp. */
    public boolean expiredAt(long nowEpochSec) {
        return nowEpochSec >= expiresEpochSec;
    }
}
