/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.security.TokenValidator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OAuthScopePolicyTest {

    private static final String VERIFY = TokenValidator.SCOPE_NUMBER_VERIFICATION_VERIFY;
    private static final String SIM_SWAP = TokenValidator.SCOPE_SIM_SWAP_CHECK;

    @Test
    void parsesCamaraApiScopes() {
        OAuthScopePolicy.ScopeGrant grant = OAuthScopePolicy.parse(VERIFY + " " + SIM_SWAP);
        assertEquals(List.of(VERIFY, SIM_SWAP), List.copyOf(grant.apiScopes()));
        assertNull(grant.purpose());
        assertFalse(grant.openid());
        assertFalse(grant.offlineAccess());
        assertEquals(List.of(VERIFY, SIM_SWAP), List.copyOf(grant.responseScopes()));
    }

    @Test
    void acceptsOpenidPurposeAndOfflineAccess() {
        OAuthScopePolicy.ScopeGrant grant = OAuthScopePolicy.parse(
                "openid dpv:FraudPreventionAndDetection offline_access " + VERIFY);
        assertTrue(grant.openid());
        assertTrue(grant.offlineAccess());
        assertEquals("dpv:FraudPreventionAndDetection", grant.purpose());
        assertEquals(List.of("dpv:FraudPreventionAndDetection", VERIFY),
                List.copyOf(grant.responseScopes()));
    }

    @Test
    void requiresAtLeastOneApiScope() {
        CibaException e = assertThrows(CibaException.class,
                () -> OAuthScopePolicy.parse("openid dpv:FraudPreventionAndDetection"));
        assertEquals("invalid_scope", e.error());
    }

    @Test
    void rejectsMultiplePurposes() {
        CibaException e = assertThrows(CibaException.class,
                () -> OAuthScopePolicy.parse("dpv:FraudPreventionAndDetection dpv:Security " + VERIFY));
        assertEquals("invalid_scope", e.error());
    }

    @Test
    void rejectsBlankPurposeValueAndUnknownScopes() {
        assertThrows(CibaException.class, () -> OAuthScopePolicy.parse("dpv: " + VERIFY));
        CibaException e = assertThrows(CibaException.class,
                () -> OAuthScopePolicy.parse(VERIFY + " unknown-scope"));
        assertEquals("invalid_scope", e.error());
    }

    @Test
    void blankScopeIsInvalidRequest() {
        CibaException e = assertThrows(CibaException.class, () -> OAuthScopePolicy.parse("  "));
        assertEquals("invalid_request", e.error());
    }
}
