/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.token;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorTokenTest {

    private static final String ISS = "https://ecs.lab.restlink.et";
    private static final String AUD = "https://camara.lab.restlink.et";
    private static final String MSISDN = "+251911111111";

    private final ECKey ecsSig = Es256Keys.generate(KeyUse.SIGNATURE);
    private final ECKey camaraEnc = Es256Keys.generate(KeyUse.ENCRYPTION);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);

    private OperatorToken.Policy policy(boolean requireEncryption) {
        return new OperatorToken.Policy(Map.of(ISS, List.of(ecsSig.toPublicJWK())), AUD,
                List.of(camaraEnc), requireEncryption, 30);
    }

    private String issue(long ttl) {
        return OperatorToken.issue(ISS, AUD, MSISDN, OperatorToken.EAP_AKA, ttl, ecsSig,
                camaraEnc.toPublicJWK(), clock);
    }

    @Test
    void roundTripThroughNestedJwe() {
        String token = issue(300);
        assertEquals(5, token.split("\\.", -1).length, "JWE compact has five parts");
        OperatorToken.Claims claims = OperatorToken.verify(token, policy(true), clock);
        assertEquals(MSISDN, claims.phoneNumber());
        assertEquals(OperatorToken.EAP_AKA, claims.eapMethod());
        assertEquals(ISS, claims.issuer());
        assertNotNull(claims.jwtId());
        assertFalse(claims.subject().contains("251"), "sub is opaque, never the number");
    }

    @Test
    void numberIsNotReadableInTransit() {
        String token = issue(300);
        for (String part : token.split("\\.")) {
            if (part.isEmpty()) {
                continue;
            }
            String decoded = new String(Base64.getUrlDecoder().decode(part), StandardCharsets.ISO_8859_1);
            assertFalse(decoded.contains("251911111111"), "MSISDN must not be visible in any JWE part");
        }
    }

    @Test
    void bareJwsRefusedWhenEncryptionRequired() {
        String jws = OperatorToken.issue(ISS, AUD, MSISDN, OperatorToken.EAP_AKA, 300, ecsSig, null, clock);
        assertEquals(3, jws.split("\\.").length);
        assertThrows(OperatorToken.RejectedException.class, () -> OperatorToken.verify(jws, policy(true), clock));
        assertEquals(MSISDN, OperatorToken.verify(jws, policy(false), clock).phoneNumber());
    }

    @Test
    void untrustedIssuerRefused() {
        ECKey rogue = Es256Keys.generate(KeyUse.SIGNATURE);
        String token = OperatorToken.issue("https://rogue", AUD, MSISDN, OperatorToken.EAP_AKA, 300,
                rogue, camaraEnc.toPublicJWK(), clock);
        assertThrows(OperatorToken.RejectedException.class, () -> OperatorToken.verify(token, policy(true), clock));
    }

    @Test
    void trustedIssuerNameWithWrongKeyRefused() {
        ECKey rogue = Es256Keys.generate(KeyUse.SIGNATURE);
        String token = OperatorToken.issue(ISS, AUD, MSISDN, OperatorToken.EAP_AKA, 300,
                rogue, camaraEnc.toPublicJWK(), clock);
        assertThrows(OperatorToken.RejectedException.class, () -> OperatorToken.verify(token, policy(true), clock));
    }

    @Test
    void wrongAudienceRefused() {
        String token = OperatorToken.issue(ISS, "https://other-camara", MSISDN, OperatorToken.EAP_AKA, 300,
                ecsSig, camaraEnc.toPublicJWK(), clock);
        assertThrows(OperatorToken.RejectedException.class, () -> OperatorToken.verify(token, policy(true), clock));
    }

    @Test
    void encryptedToAnotherRecipientRefused() {
        ECKey other = Es256Keys.generate(KeyUse.ENCRYPTION);
        String token = OperatorToken.issue(ISS, AUD, MSISDN, OperatorToken.EAP_AKA, 300,
                ecsSig, other.toPublicJWK(), clock);
        assertThrows(OperatorToken.RejectedException.class, () -> OperatorToken.verify(token, policy(true), clock));
    }

    @Test
    void expiredRefused() {
        String token = issue(60);
        Clock later = Clock.offset(clock, java.time.Duration.ofSeconds(200));
        assertThrows(OperatorToken.RejectedException.class, () -> OperatorToken.verify(token, policy(true), later));
    }

    @Test
    void lifetimeCapEnforcedAtIssue() {
        assertThrows(IllegalArgumentException.class, () -> issue(301));
    }

    @Test
    void hs256TokenNeverVerifies() throws Exception {
        // Algorithm confusion: an attacker signs with HS256 using the public key bytes.
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISS).audience(AUD)
                .claim("phone_number", MSISDN).claim("eap_method", "EAP-AKA")
                .issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(60)))
                .jwtID("x").build();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(OperatorToken.TYPE).build(), claims);
        forged.sign(new MACSigner(new byte[32]));
        assertNull(Es256Jws.verify(forged.serialize(), List.of(ecsSig.toPublicJWK()), OperatorToken.TYPE));
        assertThrows(OperatorToken.RejectedException.class,
                () -> OperatorToken.verify(forged.serialize(), policy(false), clock));
    }

    @Test
    void typeConfusionRefused() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISS).audience(AUD).build();
        String accessLike = Es256Jws.sign(claims, ecsSig, new JOSEObjectType("at+jwt"));
        assertNull(Es256Jws.verify(accessLike, List.of(ecsSig.toPublicJWK()), OperatorToken.TYPE));
    }

    @Test
    void tamperedCiphertextRefused() {
        String token = issue(300);
        String[] p = token.split("\\.", -1);
        char c = p[3].charAt(5);
        p[3] = p[3].substring(0, 5) + (c == 'A' ? 'B' : 'A') + p[3].substring(6);
        String tampered = String.join(".", p);
        assertThrows(OperatorToken.RejectedException.class, () -> OperatorToken.verify(tampered, policy(true), clock));
    }

    @Test
    void publicJwksNeverLeaksPrivatePart() {
        String jwks = Es256Keys.publicJwks(List.of(ecsSig));
        assertFalse(jwks.contains("\"d\""), "public JWKS must not carry d");
        assertTrue(jwks.contains(ecsSig.getKeyID()));
        List<ECKey> parsed = Es256Keys.parseSet(jwks, KeyUse.SIGNATURE);
        assertEquals(1, parsed.size());
    }

    @Test
    void privateJwkRoundTrip() {
        ECKey parsed = Es256Keys.parsePrivate(ecsSig.toJSONString(), KeyUse.SIGNATURE);
        assertTrue(parsed.isPrivate());
        assertEquals(ecsSig.getKeyID(), parsed.getKeyID());
        assertThrows(IllegalArgumentException.class,
                () -> Es256Keys.parsePrivate(ecsSig.toPublicJWK().toJSONString(), KeyUse.SIGNATURE));
        assertThrows(IllegalArgumentException.class,
                () -> Es256Keys.parsePrivate(ecsSig.toJSONString(), KeyUse.ENCRYPTION));
    }
}
