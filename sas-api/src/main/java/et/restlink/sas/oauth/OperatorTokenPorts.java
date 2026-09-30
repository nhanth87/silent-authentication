/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.nimbusds.jose.jwk.ECKey;

import io.quarkus.arc.DefaultBean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two seams the operator-token anchor needs from the host binary, with fail-safe
 * defaults for a library-only classpath (unit tests, or a host that wires nothing).
 *
 * <ul>
 *   <li>{@link KeySource} — fetch an issuer's JWKS from its {@code jwks_uri}. That is an
 *       outbound HTTP client, so the real one lives in the host under {@code /ras/}
 *       (gate H24). Default: fetch nothing → issuers configured by URI are untrusted
 *       (fail-closed); issuers configured with inline {@code jwks} still work.</li>
 *   <li>{@link Ledger} — the single-use record of consumed {@code jti}s. The host
 *       overrides this with a durable database table so a restart or a second CAMARA
 *       instance cannot accept a replay. Default: one-process memory.</li>
 * </ul>
 */
public final class OperatorTokenPorts {

    private OperatorTokenPorts() {
    }

    /** Fetch the public keys an issuer publishes. Empty on any failure. */
    public interface KeySource {
        List<ECKey> fetch(URI jwksUri);
    }

    /** Durable single-use ledger. */
    public interface Ledger {
        /**
         * Record {@code (issuer, jti)} as consumed. {@code true} only for the first
         * caller; every later call — on any instance — is a replay.
         */
        boolean firstUse(String issuer, String jti, long expiresEpochSec);
    }

    /** One-process ledger; replaced by the host's durable one. */
    public static final class InMemoryLedger implements Ledger {
        private final Map<String, Long> used = new ConcurrentHashMap<>();

        @Override
        public boolean firstUse(String issuer, String jti, long expiresEpochSec) {
            long now = System.currentTimeMillis() / 1000L;
            used.values().removeIf(exp -> exp < now);
            return used.putIfAbsent(issuer + "\u0000" + jti, expiresEpochSec) == null;
        }
    }

    /** CDI defaults, overridden by any bean of the same type in the host. */
    @ApplicationScoped
    public static class Defaults {

        @Produces
        @DefaultBean
        @ApplicationScoped
        KeySource noFetch() {
            return uri -> List.of();
        }

        @Produces
        @DefaultBean
        @ApplicationScoped
        Ledger inMemoryLedger() {
            return new InMemoryLedger();
        }
    }
}
