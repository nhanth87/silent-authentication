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
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layered ADR D3 + R10: the ECS redeems its own tokens, once, keyed on (jti, request id). */
class OperatorTokenRedemptionTest {

    private static final String ECS = "https://ecs.lab.restlink.et";
    private static final String MSISDN = "+251911111111";

    private final ECKey ecsSig = Es256Keys.generate(KeyUse.SIGNATURE);
    private final ECKey ecsEnc = Es256Keys.generate(KeyUse.ENCRYPTION);

    private EntitlementConfig config;
    private EntitlementTokenService tokens;
    private OperatorTokenRedemption redemption;

    @BeforeEach
    void setUp() throws Exception {
        config = new EntitlementConfig();
        set(config, "enabled", true);
        set(config, "tokenTtlSeconds", 300L);
        set(config, "requireSigned", true);
        set(config, "hmacSecret", Optional.empty());
        set(config, "tokenFormat", "operator-jwe");
        set(config, "ecsIssuer", Optional.of(ECS));
        set(config, "tokenAudience", Optional.of("https://camara.lab.restlink.et"));
        set(config, "redeemAtEcs", true);
        EcsTokenKeys keys = new EcsTokenKeys();
        keys.config = config;
        keys.use(ecsSig, null, ecsEnc);
        tokens = new EntitlementTokenService();
        tokens.config = config;
        tokens.ecsKeys = keys;
        redemption = new OperatorTokenRedemption();
        redemption.config = config;
        redemption.keys = keys;
        redemption.tokens = tokens;
        redemption.use(new InMemoryOperatorTokenLedger(), Clock.systemUTC());
    }

    @Test
    void issuedForTheEcsItselfAndEncryptedToItsOwnKey() {
        String token = tokens.issueToken(MSISDN, "655010000000001", "EAP-AKA");
        assertEquals(5, token.split("\\.", -1).length, "JWE");
        OperatorToken.Claims claims = OperatorToken.verify(token, new OperatorToken.Policy(
                java.util.Map.of(ECS, java.util.List.of(ecsSig.toPublicJWK())), ECS,
                java.util.List.of(ecsEnc), true, 30), Clock.systemUTC());
        assertEquals(ECS, claims.audience(), "aud is the ECS when it redeems (D3)");
    }

    @Test
    void redeemedOnceAndTheSameRequestRetryGetsTheSameAnswer() {
        String token = tokens.issueToken(MSISDN, "655010000000001", "EAP-AKA");
        OperatorTokenRedemption.Redeemed first = redemption.redeem(token, "req-0001").orElseThrow();
        assertEquals("251911111111", first.msisdn(), "internal digit form");
        assertEquals("EAP-AKA", first.eapMethod());

        assertEquals(Optional.of(first), redemption.redeem(token, "req-0001"),
                "a retried redemption is not a burn");
        assertTrue(redemption.redeem(token, "req-0002").isEmpty(),
                "a different request cannot spend it again");
    }

    @Test
    void reusedRequestIdWithAnotherTokenGetsNoReplay() {
        String a = tokens.issueToken(MSISDN, "655010000000001", "EAP-AKA");
        String b = tokens.issueToken("+251911111112", "655010000000002", "EAP-AKA");
        assertEquals("251911111111", redemption.redeem(a, "req-shared").orElseThrow().msisdn());
        assertEquals("251911111112", redemption.redeem(b, "req-shared").orElseThrow().msisdn(),
                "the key is (jti, request id), not the request id alone");
    }

    @Test
    void foreignOrTamperedTokensAreRefused() throws Exception {
        ECKey otherSig = Es256Keys.generate(KeyUse.SIGNATURE);
        String foreign = OperatorToken.issue(ECS, ECS, MSISDN, "EAP-AKA", 300, otherSig,
                ecsEnc.toPublicJWK(), Clock.systemUTC());
        assertTrue(redemption.redeem(foreign, "req-0003").isEmpty(), "not signed by this ECS");

        String forCamara = OperatorToken.issue(ECS, "https://camara.lab.restlink.et", MSISDN,
                "EAP-AKA", 300, ecsSig, ecsEnc.toPublicJWK(), Clock.systemUTC());
        assertTrue(redemption.redeem(forCamara, "req-0004").isEmpty(), "wrong audience");

        String token = tokens.issueToken(MSISDN, "655010000000001", "EAP-AKA");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertTrue(redemption.redeem(tampered, "req-0005").isEmpty());
        assertTrue(redemption.redeem("not-a-token-at-all", "req-0006").isEmpty());
    }

    @Test
    void expiredTokenIsRefused() {
        Clock past = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-30));
        String old = OperatorToken.issue(ECS, ECS, MSISDN, "EAP-AKA", 300, ecsSig,
                ecsEnc.toPublicJWK(), past);
        assertTrue(redemption.redeem(old, "req-0007").isEmpty());
    }

    @Test
    void onlyTheImplementedMechanismIsAttested() {
        String prime = OperatorToken.issue(ECS, ECS, MSISDN, "EAP-AKA'", 300, ecsSig,
                ecsEnc.toPublicJWK(), Clock.systemUTC());
        assertTrue(redemption.redeem(prime, "req-0008").isEmpty(), "EAP-AKA' is not offered (R5)");
    }

    @Test
    void ledgerThatCannotAnswerFailsClosed() {
        redemption.use(new OperatorTokenLedger() {
            @Override
            public boolean consume(String issuer, String jti, long exp) {
                return false;
            }

            @Override
            public boolean durable() {
                return true;
            }
        }, Clock.systemUTC());
        String token = tokens.issueToken(MSISDN, "655010000000001", "EAP-AKA");
        assertTrue(redemption.redeem(token, "req-0009").isEmpty());
    }

    @Test
    void offUnlessThisEcsRedeems() throws Exception {
        String token = tokens.issueToken(MSISDN, "655010000000001", "EAP-AKA");
        set(config, "redeemAtEcs", false);
        assertTrue(redemption.redeem(token, "req-0010").isEmpty());
        assertFalse(redemption.durableLedger(), "in-memory ledger is not durable");
    }

    private static void set(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
