/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.entitlement.EntitlementTokenService;
import et.restlink.sas.security.OperatorTokenSupport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * CDI adapter: validates/consumes {@code operatortoken:<tk>} through the
 * security-package {@link OperatorTokenSupport} (single-use, EAP-AKA
 * whitelist enforced there). Null on any failure.
 */
@ApplicationScoped
public class OperatorTokenAnchor implements IdentityAnchor {

    @Inject
    OperatorTokenSupport operatorTokens;

    @Override
    public OperatorBinding resolveOperatorToken(String candidate) {
        EntitlementTokenService.EntitlementRecord record = operatorTokens.resolve(candidate);
        return record == null
                ? null
                : new OperatorBinding(record.msisdn(), record.imsi(), record.eapMethod());
    }

    @Override
    public String extractCandidate(String authorizationHeader,
                                   String loginHint,
                                   String operatorTokenHeader) {
        return operatorTokens.extractCandidate(
                authorizationHeader, loginHint, operatorTokenHeader);
    }
}
