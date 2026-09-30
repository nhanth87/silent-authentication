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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * The entitlement server's operator-token keys: its own ES256 signing key (published
 * at {@code /jwks.json} so any CAMARA server can verify it) and the recipient CAMARA
 * server's public encryption key. Loaded once; misconfiguration fails closed — no key,
 * no token.
 */
@ApplicationScoped
public class EcsTokenKeys {

    private static final Logger LOG = LogManager.getLogger(EcsTokenKeys.class);

    @Inject
    EntitlementConfig config;

    private volatile boolean loaded;
    private volatile ECKey signingKey;
    private volatile ECKey recipientKey;

    public Optional<ECKey> signingKey() {
        load();
        return Optional.ofNullable(signingKey);
    }

    /** CAMARA's public encryption key, or empty (then only a lab bare-JWS is possible). */
    public Optional<ECKey> recipientKey() {
        load();
        return Optional.ofNullable(recipientKey);
    }

    /** Public JWKS JSON of the signing key, for {@code GET /jwks.json}. */
    public String publicJwksJson() {
        return Es256Keys.publicJwks(signingKey().map(List::of).orElse(List.of()));
    }

    /** Test seam. */
    void use(ECKey signing, ECKey recipient) {
        synchronized (this) {
            signingKey = signing;
            recipientKey = recipient;
            loaded = true;
        }
    }

    private void load() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            ECKey sign = null;
            Optional<String> jwk = config.ecsSigningJwk();
            if (jwk.isPresent()) {
                try {
                    sign = Es256Keys.parsePrivate(jwk.get(), KeyUse.SIGNATURE);
                } catch (IllegalArgumentException e) {
                    LOG.error("[ECS] sas.ecs.signing-jwk is not a private P-256 JWK: {}", e.getMessage());
                }
            } else if (config.allowEphemeralSigningKey()) {
                sign = Es256Keys.generate(KeyUse.SIGNATURE);
                LOG.warn("[ECS] LAB: ephemeral operator-token signing key kid={} — CAMARA must "
                        + "fetch it from /jwks.json; never use this in prod", sign.getKeyID());
            } else {
                LOG.error("[ECS] no sas.ecs.signing-jwk — operator tokens cannot be issued");
            }
            ECKey recipient = null;
            Optional<String> enc = config.recipientEncJwk();
            if (enc.isPresent()) {
                try {
                    recipient = Es256Keys.parsePublic(enc.get(), KeyUse.ENCRYPTION);
                } catch (IllegalArgumentException e) {
                    LOG.error("[ECS] sas.ecs.token.recipient-enc-jwk is not a P-256 JWK: {}", e.getMessage());
                }
            }
            signingKey = sign;
            recipientKey = recipient;
            loaded = true;
        }
    }
}
