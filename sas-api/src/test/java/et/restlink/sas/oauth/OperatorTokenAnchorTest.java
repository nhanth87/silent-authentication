/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;

import et.restlink.sas.token.Es256Keys;
import et.restlink.sas.token.OperatorToken;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAMARA side of the split: an operator token minted exactly as sas-ecs mints it
 * (sas-token profile) is accepted once, by a CAMARA server that only knows the ECS's
 * public key and its own decryption key.
 */
class OperatorTokenAnchorTest {

    private static final String ECS = "https://ecs.lab.restlink.et";
    private static final String CAMARA = "https://camara.lab.restlink.et";
    private static final String MSISDN = "+251911111111";

    private final ECKey ecsSig = Es256Keys.generate(KeyUse.SIGNATURE);
    private final ECKey camaraEnc = Es256Keys.generate(KeyUse.ENCRYPTION);

    private OperatorTokenAnchor anchor;
    private Cfg cfg;
    private final List<URI> fetched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cfg = new Cfg();
        cfg.issuers = "{\"issuers\":[{\"issuer\":\"" + ECS + "\",\"jwks\":"
                + Es256Keys.publicJwks(List.of(ecsSig)) + "}]}";
        anchor = new OperatorTokenAnchor();
        anchor.config = cfg;
        anchor.ledger = new OperatorTokenPorts.InMemoryLedger();
        anchor.keySource = uri -> {
            fetched.add(uri);
            return List.of(ecsSig.toPublicJWK());
        };
    }

    private String mint(String audience) {
        return OperatorToken.issue(ECS, audience, MSISDN, OperatorToken.EAP_AKA, 300, ecsSig,
                camaraEnc.toPublicJWK(), Clock.systemUTC());
    }

    @Test
    void acceptedOnceThenReplayRefused() {
        String token = mint(CAMARA);
        IdentityAnchor.OperatorBinding first = anchor.resolveOperatorToken(token);
        assertNotNull(first);
        assertEquals(MSISDN, first.msisdn());
        assertEquals(OperatorToken.EAP_AKA, first.eapMethod());
        assertNull(first.imsi(), "CAMARA never learns the IMSI");
        assertNull(anchor.resolveOperatorToken(token), "single-use: second presentation refused");
    }

    @Test
    void wrongAudienceRefused() {
        assertNull(anchor.resolveOperatorToken(mint("https://another-camara")));
    }

    @Test
    void untrustedIssuerRefused() {
        cfg.issuers = "{\"issuers\":[]}";
        assertNull(anchor.resolveOperatorToken(mint(CAMARA)));
    }

    @Test
    void jwksUriIssuerFetchedThroughTheHostSeamAndCached() {
        cfg.issuers = "{\"issuers\":[{\"issuer\":\"" + ECS + "\",\"jwks_uri\":\"" + ECS + "/jwks.json\"}]}";
        assertNotNull(anchor.resolveOperatorToken(mint(CAMARA)));
        assertNotNull(anchor.resolveOperatorToken(mint(CAMARA)));
        assertEquals(1, fetched.size(), "JWKS is cached, not fetched per token");
        assertEquals(URI.create(ECS + "/jwks.json"), fetched.get(0));
    }

    @Test
    void rotatedIssuerKeyIsPickedUpWithoutWaitingForTheCache() {
        cfg.issuers = "{\"issuers\":[{\"issuer\":\"" + ECS + "\",\"jwks_uri\":\"" + ECS + "/jwks.json\"}]}";
        assertNotNull(anchor.resolveOperatorToken(mint(CAMARA)), "first key, fetched once");
        ECKey rotated = Es256Keys.generate(KeyUse.SIGNATURE);
        anchor.keySource = uri -> {
            fetched.add(uri);
            return List.of(rotated.toPublicJWK());
        };
        String withNewKey = OperatorToken.issue(ECS, CAMARA, MSISDN, OperatorToken.EAP_AKA, 300,
                rotated, camaraEnc.toPublicJWK(), Clock.systemUTC());
        assertNotNull(anchor.resolveOperatorToken(withNewKey), "unknown kid forces one refetch");
        assertEquals(2, fetched.size());
        // A second unknown kid inside the 30 s window must NOT refetch again.
        ECKey bogus = Es256Keys.generate(KeyUse.SIGNATURE);
        String bogusToken = OperatorToken.issue(ECS, CAMARA, MSISDN, OperatorToken.EAP_AKA, 300,
                bogus, camaraEnc.toPublicJWK(), Clock.systemUTC());
        assertNull(anchor.resolveOperatorToken(bogusToken));
        assertEquals(2, fetched.size(), "forced refetch is rate-limited");
    }

    @Test
    void failedJwksFetchMeansUntrusted() {
        cfg.issuers = "{\"issuers\":[{\"issuer\":\"" + ECS + "\",\"jwks_uri\":\"" + ECS + "/jwks.json\"}]}";
        anchor.keySource = uri -> List.of();
        assertNull(anchor.resolveOperatorToken(mint(CAMARA)));
    }

    @Test
    void disabledServiceRefusesEverything() {
        cfg.on = false;
        assertNull(anchor.resolveOperatorToken(mint(CAMARA)));
    }

    @Test
    void ledgerFailureFailsClosed() {
        anchor.ledger = (iss, jti, exp) -> false;
        assertNull(anchor.resolveOperatorToken(mint(CAMARA)));
    }

    @Test
    void candidateExtractionPrecedence() {
        assertEquals("tk1", anchor.extractCandidate("Bearer operatortoken:tk1", "operatortoken:tk2", "tk3"));
        assertEquals("tk2", anchor.extractCandidate("Bearer abc", "operatortoken:tk2", "tk3"));
        assertNull(anchor.extractCandidate(null, null, "tk3"), "header ignored unless enabled");
        cfg.header = true;
        assertEquals("tk3", anchor.extractCandidate(null, null, "tk3"));
    }

    @Test
    void publishesOnlyThePublicEncryptionKey() {
        List<ECKey> pub = anchor.publicEncryptionKeys();
        assertEquals(1, pub.size());
        assertFalse(pub.get(0).isPrivate());
        assertTrue(pub.get(0).getKeyUse().equals(KeyUse.ENCRYPTION));
    }

    private final class Cfg extends OperatorTokenConfig {
        String issuers;
        boolean on = true;
        boolean header;

        @Override
        public boolean enabled() {
            return on;
        }

        @Override
        public String audience() {
            return CAMARA;
        }

        @Override
        public Optional<String> issuersJson() {
            return Optional.ofNullable(issuers);
        }

        @Override
        public Optional<String> decryptionJwk() {
            return Optional.of(camaraEnc.toJSONString());
        }

        @Override
        public boolean headerEnabled() {
            return header;
        }
    }
}
