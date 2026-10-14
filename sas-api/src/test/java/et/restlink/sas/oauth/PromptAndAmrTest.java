/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * Tests: OIDC prompt handling at /authorize and the amr set per authentication.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAndAmrTest {

    @Test
    void silentPromptsProceed() {
        assertNull(AuthorizeResource.interactionError(null));
        assertNull(AuthorizeResource.interactionError(""));
        assertNull(AuthorizeResource.interactionError("none"));
        assertNull(AuthorizeResource.interactionError(" none "));
    }

    @Test
    void promptsThatNeedAPageGetTheOidcError() {
        assertEquals("login_required", AuthorizeResource.interactionError("login"));
        assertEquals("consent_required", AuthorizeResource.interactionError("consent"));
        assertEquals("account_selection_required", AuthorizeResource.interactionError("select_account"));
        assertEquals("login_required", AuthorizeResource.interactionError("consent login"));
        assertEquals("invalid_request", AuthorizeResource.interactionError("none login"),
                "OIDC Core 3.1.2.1: none with any other value is an error");
        assertEquals("invalid_request", AuthorizeResource.interactionError("bogus"));
    }

    @Test
    void amrSetsSayWhoAuthenticated() {
        assertEquals(List.of("mno"), AuthMethods.NETWORK);
        assertTrue(AuthMethods.SIM.contains("mno") && AuthMethods.SIM.contains("hwk"));
        assertTrue(AuthMethods.NONE.isEmpty());
        assertEquals(AuthMethods.NETWORK, AuthorizeResource.AMR_NETWORK);
        assertEquals(List.of(), new PendingBinding("r", "+251911111111", null, java.util.Set.of(), 0, 1, "c").amr());
    }
}
