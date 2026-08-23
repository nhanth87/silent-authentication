/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Input-validation and replay-key derivation helper tests (CAMARA NV body
 * schema shapes + lab-mode idempotency keys).
 */
class RequestValidatorTest {

    // ---- E.164 ----

    @Test
    void e164_valid() {
        assertTrue(RequestValidator.isE164("+251911111111"));
        assertTrue(RequestValidator.isE164("+123456789"));
    }

    @Test
    void e164_minAndMaxLength() {
        assertTrue(RequestValidator.isE164("+12"));         // shortest: + then 2 digits
        assertTrue(RequestValidator.isE164("+123456789012345")); // 15 digits max
        assertFalse(RequestValidator.isE164("+1234567890123456")); // 16 digits
        assertFalse(RequestValidator.isE164("+"));          // no digits
    }

    @Test
    void e164_invalidShapes() {
        assertFalse(RequestValidator.isE164(null));
        assertFalse(RequestValidator.isE164(""));
        assertFalse(RequestValidator.isE164("251911111111"));   // missing +
        assertFalse(RequestValidator.isE164("+0123456789"));    // leading 0 after +
        assertFalse(RequestValidator.isE164("+251 911111111")); // embedded space
        assertFalse(RequestValidator.isE164("+25191a1111"));    // non-digit
        assertFalse(RequestValidator.isE164(" +251911111111")); // leading space
    }

    // ---- SHA-256 hex ----

    @Test
    void sha256Hex_valid() {
        assertTrue(RequestValidator.isSha256Hex(
                "32f67ab4e4312618b09cd23ed8ce41b13e095fe52b73b2e8da8ef49830e50dba"));
        assertTrue(RequestValidator.isSha256Hex(
                "32F67AB4E4312618B09CD23ED8CE41B13E095FE52B73B2E8DA8EF49830E50DBA")); // upper
    }

    @Test
    void sha256Hex_invalid() {
        assertFalse(RequestValidator.isSha256Hex(null));
        assertFalse(RequestValidator.isSha256Hex(""));
        assertFalse(RequestValidator.isSha256Hex(
                "32f67ab4e4312618b09cd23ed8ce41b13e095fe52b73b2e8da8ef49830e50db"));  // 63
        assertFalse(RequestValidator.isSha256Hex(
                "32f67ab4e4312618b09cd23ed8ce41b13e095fe52b73b2e8da8ef49830e50dba0")); // 65
        assertFalse(RequestValidator.isSha256Hex(
                "g2f67ab4e4312618b09cd23ed8ce41b13e095fe52b73b2e8da8ef49830e50dba")); // non-hex
    }

    // ---- F6 — E.164 normalization (single northbound normalization point) ----

    @Test
    void normalizeE164_alreadyCanonical_unchanged() {
        assertEquals(java.util.Optional.of("+251911111111"),
                RequestValidator.normalizeE164("+251911111111"));
        assertEquals(java.util.Optional.of("+123456789"),
                RequestValidator.normalizeE164("+123456789"));
    }

    @Test
    void normalizeE164_missingPlus_added() {
        assertEquals(java.util.Optional.of("+251911111111"),
                RequestValidator.normalizeE164("251911111111"));
    }

    @Test
    void normalizeE164_doublePlus_collapsedToOne() {
        assertEquals(java.util.Optional.of("+251911111111"),
                RequestValidator.normalizeE164("++251911111111"));
        assertEquals(java.util.Optional.of("+251911111111"),
                RequestValidator.normalizeE164("+ +251911111111"));
    }

    @Test
    void normalizeE164_separatorsAndPunctuation_stripped() {
        assertEquals(java.util.Optional.of("+251911111111"),
                RequestValidator.normalizeE164("+251 911 111 111"));
        assertEquals(java.util.Optional.of("+251911111111"),
                RequestValidator.normalizeE164("251-911-111-111"));
        assertEquals(java.util.Optional.of("+251911111111"),
                RequestValidator.normalizeE164("(+251)-911.111.111"));
        assertEquals(java.util.Optional.of("+251911111111"),
                RequestValidator.normalizeE164("\t+251911111111\n"));
    }

    @Test
    void normalizeE164_invalidShapes_empty() {
        assertFalse(RequestValidator.normalizeE164(null).isPresent());
        assertFalse(RequestValidator.normalizeE164("").isPresent());
        assertFalse(RequestValidator.normalizeE164("   ").isPresent());
        assertFalse(RequestValidator.normalizeE164("+").isPresent());            // no digits
        assertFalse(RequestValidator.normalizeE164("++").isPresent());           // no digits
        assertFalse(RequestValidator.normalizeE164("+0123456789").isPresent()); // leading 0
        assertFalse(RequestValidator.normalizeE164("+25191a1111").isPresent());  // letter
        assertFalse(RequestValidator.normalizeE164("abc").isPresent());
        // 16 digits after cleaning → over E.164 maximum
        assertFalse(RequestValidator.normalizeE164("+1234567890123456").isPresent());
    }

    @Test
    void normalizeE164_maxLength_stillValid() {
        assertEquals(java.util.Optional.of("+123456789012345"),
                RequestValidator.normalizeE164("123456789 012345"));
    }

    @Test
    void hashInput_isNormalizedNumber_sha256OfPlusDigits() {
        // The hashedPhoneNumber contract: sha256 of "+E164" — the normalized
        // value already carries the '+', so hash(normalize(x)) is the digest.
        String normalized = RequestValidator.normalizeE164("251911111111").orElseThrow();
        assertEquals(
                RequestValidator.sha256Hex("+251911111111"),
                RequestValidator.sha256Hex(normalized));
    }

    // ---- SHA-256 derivation ----

    @Test
    void sha256Hex_knownVector() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                RequestValidator.sha256Hex("abc"));
    }

    @Test
    void labReqId_deterministic_perIdenticalRequest() {
        String a1 = RequestValidator.deriveLabReqId("tokkey", "corr-1");
        String a2 = RequestValidator.deriveLabReqId("tokkey", "corr-1");
        assertEquals(a1, a2);
        assertNotEquals(a1, RequestValidator.deriveLabReqId("tokkey", "corr-2"));
        assertNotEquals(a1, RequestValidator.deriveLabReqId("other", "corr-1"));
    }

    @Test
    void labReqId_nullCorrelator_normalised() {
        assertEquals(
                RequestValidator.deriveLabReqId("tokkey", null),
                RequestValidator.deriveLabReqId("tokkey", ""));
    }

    @Test
    void retrieveReqId_namespacedFromVerifyKey() {
        String verify = RequestValidator.sha256Hex("tokkey");
        String retrieve = RequestValidator.deriveRetrieveReqId("tokkey", "corr-1");
        assertNotEquals(verify, retrieve);
        assertEquals(retrieve, RequestValidator.deriveRetrieveReqId("tokkey", "corr-1"));
        assertNotEquals(retrieve, RequestValidator.deriveRetrieveReqId("tokkey", "corr-2"));
    }
}
