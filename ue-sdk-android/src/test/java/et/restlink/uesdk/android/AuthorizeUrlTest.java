/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Tests: the authorization request rules the device enforces before sending.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizeUrlTest {

    private static final String OK = "https://sas.example.et/authorize?response_type=code&client_id=bank"
            + "&redirect_uri=https%3A%2F%2Fbank.example%2Fcb&scope=openid%20number-verification%3Averify"
            + "&state=s1&nonce=n1&code_challenge=Q2hhbGxlbmdl&code_challenge_method=S256&prompt=none";

    private static String without(String param) {
        return OK.replaceAll("&" + param + "=[^&]*", "");
    }

    private static String rule(String url) {
        return assertThrows(IllegalArgumentException.class, () -> AuthorizeUrl.parse(url, false)).getMessage();
    }

    @Test
    void validRequestParses() {
        AuthorizeUrl u = AuthorizeUrl.parse(OK, false);
        assertEquals("s1", u.state());
        assertEquals(URI.create("https://bank.example/cb"), u.redirectUri());
        assertFalse(u.hasLoginHint());
        assertEquals("AuthorizeUrl[https://sas.example.et/authorize]", u.toString(), "no query in toString");
    }

    @Test
    void everySilentAndPkceRuleIsEnforced() {
        assertTrue(rule(without("prompt")).contains("prompt=none"));
        assertTrue(rule(OK.replace("prompt=none", "prompt=login")).contains("prompt=none"));
        assertTrue(rule(without("code_challenge")).contains("PKCE"));
        assertTrue(rule(OK.replace("S256", "plain")).contains("PKCE"));
        assertTrue(rule(without("state")).contains("state"));
        assertTrue(rule(without("client_id")).contains("client_id"));
        assertTrue(rule(without("scope")).contains("scope"));
        assertTrue(rule(OK.replace("response_type=code", "response_type=token")).contains("response_type"));
        assertTrue(rule(without("redirect_uri")).contains("redirect_uri"));
        assertTrue(rule(OK.replace("https%3A%2F%2Fbank.example%2Fcb", "cb")).contains("absolute"));
    }

    @Test
    void transportAndShapeRules() {
        assertTrue(rule(OK.replace("https://", "http://")).contains("https"));
        AuthorizeUrl.parse(OK.replace("https://", "http://"), true);   // lab switch
        assertTrue(rule("/authorize?x=1").contains("absolute"));
        assertTrue(rule("").contains("empty"));
        assertTrue(rule(OK + "&redirect_uri=https%3A%2F%2Fevil.example%2Fcb").contains("repeated"));
    }

    @Test
    void errorMessagesNeverEchoTheQuery() {
        String msg = rule(without("prompt") + "&login_hint=operatortoken%3Asecret");
        assertFalse(msg.contains("secret"));
        assertFalse(msg.contains("sas.example.et"));
    }

    @Test
    void operatorTokenIsAppendedEncoded() {
        URI withHint = AuthorizeUrl.parse(OK, false).withOperatorToken("a+b/c=");
        assertTrue(withHint.toString().endsWith("&login_hint=operatortoken%3Aa%2Bb%2Fc%3D"));
        assertEquals("operatortoken:a+b/c=",
                AuthorizeUrl.query(withHint.getRawQuery()).get("login_hint"));
    }

    @Test
    void existingHintIsNotOverridden() {
        AuthorizeUrl u = AuthorizeUrl.parse(OK + "&login_hint=tel%3A%2B251911111111", false);
        assertTrue(u.hasLoginHint());
        assertThrows(IllegalArgumentException.class, () -> u.withOperatorToken("t"));
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizeUrl.parse(OK, false).withOperatorToken(" "));
    }

    @Test
    void redirectMatchIgnoresQueryButNotHostOrPath() {
        AuthorizeUrl u = AuthorizeUrl.parse(OK, false);
        assertTrue(u.isRedirect(URI.create("https://bank.example/cb?code=1&state=s1")));
        assertTrue(u.isRedirect(URI.create("HTTPS://BANK.EXAMPLE/cb")));
        assertFalse(u.isRedirect(URI.create("https://bank.example/cb2?code=1")));
        assertFalse(u.isRedirect(URI.create("https://bank.example.evil/cb?code=1")));
        assertFalse(u.isRedirect(URI.create("http://bank.example/cb?code=1")));
    }

    @Test
    void stateComparisonIsExact() {
        assertTrue(AuthorizeUrl.constantTimeEquals("abc", "abc"));
        assertFalse(AuthorizeUrl.constantTimeEquals("abc", "abd"));
        assertFalse(AuthorizeUrl.constantTimeEquals("abc", null));
        assertFalse(AuthorizeUrl.constantTimeEquals(null, null));
    }
}
