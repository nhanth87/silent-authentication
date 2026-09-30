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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Authorization Server's ES256 signing key and the public keys it publishes at
 * {@code /jwks.json}. One place decides which key signs access and ID tokens, so the
 * token issuer, the resource-server validator and the JWKS endpoint can never
 * disagree.
 *
 * <p>Fail-closed: with {@code sas.oauth.signing-alg=ES256} and no
 * {@code sas.oauth.signing-jwk}, no key exists and issuance throws — unless
 * {@code sas.oauth.allow-ephemeral-signing-key=true} (lab), in which case a random key
 * is minted once per process. Preflight refuses that flag in prod.</p>
 */
@ApplicationScoped
public class OAuthSigningKeys {

    private static final Logger LOG = LogManager.getLogger(OAuthSigningKeys.class);

    @Inject
    OAuthServerConfig config;

    private volatile boolean loaded;
    private volatile ECKey signingKey;
    private volatile List<ECKey> verificationKeys = List.of();

    /** True when access/ID tokens are ES256-signed. */
    public boolean es256() {
        return config != null && "ES256".equals(config.signingAlg());
    }

    /** Private signing key, or empty when ES256 is off or no key is available. */
    public Optional<ECKey> signingKey() {
        load();
        return Optional.ofNullable(signingKey);
    }

    /** Public keys a resource server may verify our tokens with (current + retired). */
    public List<ECKey> verificationKeys() {
        load();
        return verificationKeys;
    }

    /** Public JWKS entries, as serialisable maps, for {@code /jwks.json}. */
    public List<java.util.Map<String, Object>> publicJwkMaps() {
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (ECKey key : verificationKeys()) {
            out.add(key.toPublicJWK().toJSONObject());
        }
        return out;
    }

    /** Test/lab seam. */
    void use(ECKey key) {
        synchronized (this) {
            signingKey = key;
            verificationKeys = key == null ? List.of() : List.of(key.toPublicJWK());
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
            ECKey key = null;
            List<ECKey> verify = new ArrayList<>();
            if (es256()) {
                Optional<String> jwk = config.signingJwk();
                if (jwk.isPresent()) {
                    try {
                        key = Es256Keys.parsePrivate(jwk.get(), KeyUse.SIGNATURE);
                    } catch (IllegalArgumentException e) {
                        LOG.error("[SAS] sas.oauth.signing-jwk is not a private P-256 JWK — "
                                + "no tokens will be issued: {}", e.getMessage());
                    }
                } else if (config.allowEphemeralSigningKey()) {
                    key = Es256Keys.generate(KeyUse.SIGNATURE);
                    LOG.warn("[SAS] LAB: ephemeral ES256 signing key kid={} — tokens do not "
                            + "survive a restart; never use this in prod", key.getKeyID());
                } else {
                    LOG.error("[SAS] sas.oauth.signing-alg=ES256 but no sas.oauth.signing-jwk — "
                            + "token issuance fails closed");
                }
                if (key != null) {
                    verify.add(key.toPublicJWK());
                }
                config.previousJwks().ifPresent(json -> {
                    try {
                        verify.addAll(Es256Keys.parseSet(json, KeyUse.SIGNATURE));
                    } catch (IllegalArgumentException e) {
                        LOG.error("[SAS] sas.oauth.previous-jwks ignored: {}", e.getMessage());
                    }
                });
            }
            signingKey = key;
            verificationKeys = List.copyOf(verify);
            loaded = true;
        }
    }
}
