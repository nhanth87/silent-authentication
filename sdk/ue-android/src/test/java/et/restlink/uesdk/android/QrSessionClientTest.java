/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Tests: QR payload parsing, claim body, verdict reading, fail-closed claim.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrSessionClientTest {

    private static final String ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    // ---- payload parsing: this string authorises the claim ----

    @Test
    void parsesTheCanonicalScheme() throws Exception {
        assertEquals(ID, QrSessionClient.parsePayload("silentauth://qr-session/" + ID));
        assertEquals(ID, QrSessionClient.parsePayload("SILENTAUTH://QR-SESSION/" + ID));
    }

    @Test
    void parsesABareSessionId() throws Exception {
        assertEquals(ID, QrSessionClient.parsePayload(ID));
        assertEquals(ID, QrSessionClient.parsePayload("  " + ID + "  "));
    }

    @Test
    void parsesAPayloadCarriedInAUrl() throws Exception {
        assertEquals(ID, QrSessionClient.parsePayload("https://bank.example/q?p=silentauth://qr-session/" + ID));
    }

    @Test
    void refusesAnythingThatIsNotAUuid() {
        assertThrows(IOException.class, () -> QrSessionClient.parsePayload(null));
        assertThrows(IOException.class, () -> QrSessionClient.parsePayload(""));
        assertThrows(IOException.class, () -> QrSessionClient.parsePayload("   "));
        assertThrows(IOException.class, () -> QrSessionClient.parsePayload("silentauth://qr-session/"));
        assertThrows(IOException.class, () -> QrSessionClient.parsePayload("not-a-session"));
        assertThrows(IOException.class,
                () -> QrSessionClient.parsePayload("silentauth://qr-session/../../etc/passwd"));
        assertThrows(IOException.class, () -> QrSessionClient.parsePayload(ID + "/extra"));
    }

    @Test
    void refusesAUuidShapedStringWithTheWrongSeparators() {
        // Right length, wrong dashes: a sloppy scan must not claim the session.
        assertThrows(IOException.class,
                () -> QrSessionClient.parsePayload("3f2504e04f8911d39a0c0305e82c33010"));
        assertFalse(QrSessionClient.isSessionId("3f2504e0_4f89-11d3-9a0c-0305e82c3301"));
    }

    // ---- claim body ----

    @Test
    void claimBodyOmitsAbsentFields() {
        assertEquals("{\"accessTech\":\"LTE\"}", QrSessionClient.claimBody(null, AccessTech.LTE, null));
        assertEquals("{}", QrSessionClient.claimBody(null, AccessTech.UNKNOWN, null));
        assertEquals("{\"msisdn\":\"+251911111111\",\"accessTech\":\"NR\"}",
                QrSessionClient.claimBody("+251911111111", AccessTech.NR, null));
    }

    @Test
    void claimBodyCarriesTheOperatorTokenForTheWifiVariant() {
        assertEquals("{\"accessTech\":\"WIFI\",\"operatorToken\":\"tk-123\"}",
                QrSessionClient.claimBody(null, AccessTech.WIFI, "tk-123"));
    }

    @Test
    void claimBodyEscapesTheToken() {
        String body = QrSessionClient.claimBody(null, AccessTech.WIFI, "a\"b");
        assertTrue(body.contains("\\\"b"), "a quote in the token must be escaped, not dropped");
    }

    // ---- verdict reading: silence is never consent ----

    @Test
    void approvedRequiresAnExplicitTrueAndResolved() {
        assertTrue(new QrSessionClient.ClaimResult(ID, QrSessionClient.QrState.RESOLVED,
                Boolean.TRUE, null).approved());
        assertFalse(new QrSessionClient.ClaimResult(ID, QrSessionClient.QrState.RESOLVED,
                Boolean.FALSE, "SIM_SWAP_SUSPECT").approved(), "an explicit false is not an approval");
        assertFalse(new QrSessionClient.ClaimResult(ID, QrSessionClient.QrState.PENDING,
                Boolean.TRUE, null).approved(), "a true that is not RESOLVED is not an approval");
        assertFalse(new QrSessionClient.ClaimResult(ID, QrSessionClient.QrState.PENDING,
                null, null).approved(), "pending carries no verdict at all");
        assertFalse(new QrSessionClient.ClaimResult(ID, QrSessionClient.QrState.UNKNOWN,
                Boolean.TRUE, null).approved(), "an unparseable state is not an approval");
    }

    // ---- the JSON reader refuses rather than guesses ----

    @Test
    void readerParsesTheFieldsTheSdkAsksFor() {
        QrSessionClient.Flat r = QrSessionClient.Flat.of("{\"sessionId\":\"" + ID + "\",\"state\":\"RESOLVED\","
                + "\"devicePhoneNumberVerified\":true,\"fallbackReason\":null}");
        assertEquals(ID, r.string("sessionId"));
        assertEquals("RESOLVED", r.string("state"));
        assertEquals(Boolean.TRUE, r.bool("devicePhoneNumberVerified"));
        assertNull(r.string("fallbackReason"));
    }

    @Test
    void readerSeparatesAbsentFromFalse() {
        assertNull(QrSessionClient.Flat.of("{\"state\":\"PENDING\"}").bool("devicePhoneNumberVerified"),
                "an absent verdict must not be read as false, or as true");
        assertEquals(Boolean.FALSE,
                QrSessionClient.Flat.of("{\"devicePhoneNumberVerified\":false}").bool("devicePhoneNumberVerified"));
    }

    @Test
    void readerRefusesMalformedBodiesInsteadOfGuessing() {
        assertNull(QrSessionClient.Flat.of(null).string("state"));
        assertNull(QrSessionClient.Flat.of("").string("state"));
        assertNull(QrSessionClient.Flat.of("not json at all").string("state"));
        assertNull(QrSessionClient.Flat.of("{\"state\":").string("state"));
        assertNull(QrSessionClient.Flat.of("{\"nested\":{\"state\":\"RESOLVED\"}}").string("state"),
                "a nested object is out of scope; the reader must not reach into it");
        assertNull(QrSessionClient.Flat.of("[1,2,3]").string("state"));
    }

    @Test
    void readerUnescapesStrings() {
        assertEquals("a\"b\\c\nd",
                QrSessionClient.Flat.of("{\"fallbackReason\":\"a\\\"b\\\\c\\nd\"}").string("fallbackReason"));
    }

    @Test
    void readerReadsNumbersAndDefaultsOnGarbage() {
        assertEquals(120L, QrSessionClient.Flat.of("{\"expiresInSeconds\":120}").number("expiresInSeconds"));
        assertEquals(-1L, QrSessionClient.Flat.of("{\"expiresInSeconds\":\"soon\"}").number("expiresInSeconds"));
        assertEquals(-1L, QrSessionClient.Flat.of("{}").number("expiresInSeconds"));
    }

    // ---- fail-closed on the device ----

    @Test
    void noBearerCannotClaimWithoutAnOperatorToken() {
        QrSessionClient client = new QrSessionClient();
        assertThrows(CellularUnavailableException.class, () -> client.claim(
                "http://127.0.0.1:8085", ID, null, CellularRequirement.CELLULAR, null, null, null));
    }

    @Test
    void unboundBearerCannotClaimWithoutAnOperatorToken() {
        // Off-device the radio reads UNKNOWN: an unpinned route must never claim.
        QrSessionClient client = new QrSessionClient();
        assertThrows(CellularUnavailableException.class, () -> client.claim(
                "http://127.0.0.1:8085", ID, CellularBearer.unbound(null), null, null, null, null));
    }
}
