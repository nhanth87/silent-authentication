/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

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
        assertEquals(ID, QrSessionClient.parsePayload("https://bank.example/q?p=silentauth://qr-session/"
                + ID));
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
        assertThrows(IOException.class, () -> QrSessionClient.parsePayload(
                "3f2504e0-4f89-11d3-9a0c-0305e82c3301/extra"));
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
        assertEquals("{\"accessTech\":\"LTE\"}",
                QrSessionClient.claimBody(null, AccessTech.LTE, null));
        assertEquals("{}",
                QrSessionClient.claimBody(null, AccessTech.UNKNOWN, null));
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
                Boolean.FALSE, "SIM_SWAP_SUSPECT").approved(),
                "an explicit false is not an approval");
        assertFalse(new QrSessionClient.ClaimResult(ID, QrSessionClient.QrState.PENDING,
                Boolean.TRUE, null).approved(),
                "a true that is not RESOLVED is not an approval");
        assertFalse(new QrSessionClient.ClaimResult(ID, QrSessionClient.QrState.PENDING,
                null, null).approved(), "pending carries no verdict at all");
        assertFalse(new QrSessionClient.ClaimResult(ID, QrSessionClient.QrState.UNKNOWN,
                Boolean.TRUE, null).approved(), "an unparseable state is not an approval");
    }

    // ---- the JSON reader refuses rather than guesses ----

    @Test
    void readerParsesTheFieldsTheSdkAsksFor() {
        JsonReader.Result r = JsonReader.Result.of(200,
                "{\"sessionId\":\"" + ID + "\",\"state\":\"RESOLVED\","
                        + "\"devicePhoneNumberVerified\":true,\"fallbackReason\":null}");

        assertEquals(ID, r.get("sessionId"));
        assertEquals("RESOLVED", r.get("state"));
        assertEquals(Boolean.TRUE, r.bool("devicePhoneNumberVerified"));
        assertNull(r.get("fallbackReason"));
    }

    @Test
    void readerSeparatesAbsentFromFalse() {
        JsonReader.Result absent = JsonReader.Result.of(200, "{\"state\":\"PENDING\"}");
        assertNull(absent.bool("devicePhoneNumberVerified"),
                "an absent verdict must not be read as false, or as true");

        JsonReader.Result explicitFalse = JsonReader.Result.of(200,
                "{\"devicePhoneNumberVerified\":false}");
        assertEquals(Boolean.FALSE, explicitFalse.bool("devicePhoneNumberVerified"));
    }

    @Test
    void readerRefusesMalformedBodiesInsteadOfGuessing() {
        assertNull(JsonReader.Result.of(200, null).get("state"));
        assertNull(JsonReader.Result.of(200, "").get("state"));
        assertNull(JsonReader.Result.of(200, "not json at all").get("state"));
        assertNull(JsonReader.Result.of(200, "{\"state\":").get("state"));
        assertNull(JsonReader.Result.of(200, "{\"nested\":{\"state\":\"RESOLVED\"}}").get("state"),
                "a nested object is out of scope; the reader must not reach into it");
        assertNull(JsonReader.Result.of(200, "[1,2,3]").get("state"));
    }

    @Test
    void readerUnescapesStrings() {
        JsonReader.Result r = JsonReader.Result.of(200,
                "{\"fallbackReason\":\"a\\\"b\\\\c\\nd\"}");
        assertEquals("a\"b\\c\nd", r.get("fallbackReason"));
    }

    @Test
    void readerReadsNumbersAndDefaultsOnGarbage() {
        assertEquals(120L, JsonReader.Result.of(200, "{\"expiresInSeconds\":120}")
                .number("expiresInSeconds"));
        assertEquals(-1L, JsonReader.Result.of(200, "{\"expiresInSeconds\":\"soon\"}")
                .number("expiresInSeconds"));
        assertEquals(-1L, JsonReader.Result.of(200, "{}").number("expiresInSeconds"));
    }

    // ---- fail-closed on the device ----

    @Test
    void aWifiPhoneCannotClaimWithoutAnOperatorToken() {
        QrSessionClient client = new QrSessionClient();
        assertThrows(CellularRequirement.CellularBearerException.class, () -> client.claim(
                "http://127.0.0.1:8085", ID, AccessTech.WIFI,
                CellularRequirement.CELLULAR, null, null, null, Connector.DEFAULT));
    }

    @Test
    void anOperatorTokenLetsAWifiPhoneProceed() throws Exception {
        // A redeemed TS.43 token is a cryptographic proof, so the cellular gate
        // does not apply: the request is built and the server decides.
        assertEquals("{\"accessTech\":\"WIFI\",\"operatorToken\":\"tk\"}",
                QrSessionClient.claimBody(null, AccessTech.WIFI, "tk"));
    }

    @Test
    void unknownBearerCannotClaim() {
        QrSessionClient client = new QrSessionClient();
        assertThrows(CellularRequirement.CellularBearerException.class, () -> client.claim(
                "http://127.0.0.1:8085", ID, AccessTech.UNKNOWN,
                CellularRequirement.CELLULAR, null, null, null, Connector.DEFAULT));
    }
}
