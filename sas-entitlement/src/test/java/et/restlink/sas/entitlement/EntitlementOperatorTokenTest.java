/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;

import et.restlink.sas.token.Es256Keys;
import et.restlink.sas.token.OperatorToken;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ECS side of the split (sas.entitlement.token-format=operator-jwe): the token the
 * entitlement server issues verifies at a CAMARA server with nothing but the ECS's
 * public signing key and CAMARA's own decryption key.
 */
class EntitlementOperatorTokenTest {

    private static final String ECS = "https://ecs.lab.restlink.et";
    private static final String CAMARA = "https://camara.lab.restlink.et";
    private static final String MSISDN = "+251911111111";

    private final ECKey ecsSig = Es256Keys.generate(KeyUse.SIGNATURE);
    private final ECKey camaraEnc = Es256Keys.generate(KeyUse.ENCRYPTION);

    private EntitlementTokenService service;
    private EcsTokenKeys keys;

    @BeforeEach
    void setUp() throws Exception {
        EntitlementConfig c = new EntitlementConfig();
        set(c, "enabled", true);
        set(c, "tokenTtlSeconds", 300L);
        set(c, "requireSigned", true);
        set(c, "hmacSecret", Optional.empty());
        set(c, "tokenFormat", "operator-jwe");
        set(c, "ecsIssuer", Optional.of(ECS));
        set(c, "tokenAudience", Optional.of(CAMARA));
        keys = new EcsTokenKeys();
        keys.config = c;
        keys.use(ecsSig, camaraEnc.toPublicJWK());
        service = new EntitlementTokenService();
        service.config = c;
        service.ecsKeys = keys;
    }

    @Test
    void issuedTokenVerifiesAtTheCamaraServer() {
        String token = service.issueToken(MSISDN, "636010000000001", "EAP-AKA");
        assertEquals(5, token.split("\\.", -1).length, "encrypted to CAMARA (JWE)");
        OperatorToken.Policy camara = new OperatorToken.Policy(
                Map.of(ECS, List.of(ecsSig.toPublicJWK())), CAMARA, List.of(camaraEnc), true, 30);
        OperatorToken.Claims claims = OperatorToken.verify(token, camara, Clock.systemUTC());
        assertEquals(MSISDN, claims.phoneNumber());
        assertEquals("EAP-AKA", claims.eapMethod());
    }

    @Test
    void legacyLocalExchangeStillSingleUse() {
        String token = service.issueToken(MSISDN, "636010000000001", "EAP-AKA'");
        assertTrue(service.isValid(token));
        EntitlementTokenService.EntitlementRecord r = service.exchange(token);
        assertNotNull(r);
        assertEquals("EAP-AKA'", r.eapMethod());
        assertNull(service.exchange(token));
    }

    @Test
    void refusesToIssueReadableTokensWithoutARecipientKey() {
        keys.use(ecsSig, null);
        assertThrows(IllegalStateException.class,
                () -> service.issueToken(MSISDN, "636010000000001", "EAP-AKA"));
    }

    @Test
    void refusesWithoutASigningKey() {
        keys.use(null, camaraEnc.toPublicJWK());
        assertThrows(IllegalStateException.class,
                () -> service.issueToken(MSISDN, "636010000000001", "EAP-AKA"));
    }

    @Test
    void nonAkaMethodNeverMinted() {
        assertThrows(IllegalArgumentException.class,
                () -> service.issueToken(MSISDN, "636010000000001", "EAP-TLS"));
    }

    @Test
    void jwksPublishesOnlyThePublicSigningKey() {
        String jwks = keys.publicJwksJson();
        assertTrue(jwks.contains(ecsSig.getKeyID()));
        assertTrue(!jwks.contains("\"d\""));
    }

    private static void set(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
