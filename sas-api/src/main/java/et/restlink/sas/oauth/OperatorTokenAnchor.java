/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.id.PhoneNumberNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;

import et.restlink.sas.security.RequestValidator;
import et.restlink.sas.token.Es256Keys;
import et.restlink.sas.token.OperatorToken;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CAMARA-side acceptance of {@code operatortoken:<token>} (CAMARA ICM): as a CIBA
 * {@code login_hint}, a JWT-bearer {@code sub}, an {@code /authorize} login hint, or
 * {@code Authorization: Bearer operatortoken:<token>} on the Wi-Fi {@code /verify} path.
 *
 * <h2>Verification (all fail-closed → {@code null})</h2>
 * <ol>
 *   <li>decrypt the JWE with our {@code sas.operator-token.decryption-jwk};</li>
 *   <li>issuer must be in {@code sas.operator-token.issuers-json}; its keys come inline
 *       or from its {@code jwks_uri} through the host's {@code /ras/} fetcher (cached);</li>
 *   <li>ES256 signature, {@code typ}, audience, iat/exp, lifetime ≤ 300 s, EAP-AKA
 *       family ({@link OperatorToken#verify});</li>
 *   <li>durable single-use: {@code (iss, jti)} must be new to the ledger.</li>
 * </ol>
 * The number is taken from the verified claims only; nothing in the request can
 * influence it.
 */
@ApplicationScoped
public class OperatorTokenAnchor implements IdentityAnchor {

    private static final Logger LOG = LogManager.getLogger(OperatorTokenAnchor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    OperatorTokenConfig config;

    @Inject
    OperatorTokenPorts.KeySource keySource;

    @Inject
    OperatorTokenPorts.Ledger ledger;

    Clock clock = Clock.systemUTC();

    /** Minimum spacing of forced JWKS refetches per issuer (unknown-kid path). */
    static final long FORCED_REFRESH_SECONDS = 30L;

    private final Map<String, CachedKeys> uriCache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastForced = new ConcurrentHashMap<>();
    private volatile String parsedFor;
    private volatile Map<String, IssuerEntry> issuers = Map.of();
    private volatile List<ECKey> decryptionKeys = List.of();

    private record IssuerEntry(List<ECKey> inlineKeys, URI jwksUri) {}

    private record CachedKeys(List<ECKey> keys, long fetchedAtSec) {}

    @Override
    public OperatorBinding resolveOperatorToken(String candidate) {
        if (candidate == null || candidate.isBlank() || config == null || !config.enabled()) {
            return null;
        }
        OperatorToken.Claims claims;
        try {
            claims = OperatorToken.verify(candidate, policy(), clock);
        } catch (OperatorToken.RejectedException e) {
            LOG.warn("[SAS] operator token rejected: {}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            LOG.warn("[SAS] operator token rejected: {}", e.getClass().getSimpleName());
            return null;
        }
        String msisdn = PhoneNumberNormalizer.toE164(claims.phoneNumber()).orElse(null);
        if (msisdn == null) {
            LOG.warn("[SAS] operator token rejected: phone_number is not E.164");
            return null;
        }
        if (ledger == null || !ledger.firstUse(claims.issuer(), claims.jwtId(), claims.expiresAt())) {
            LOG.warn("[SAS] operator token rejected: replay (iss={}, jti already used)", claims.issuer());
            return null;
        }
        LOG.info("[SAS] operator token accepted: {}", claims);
        return new OperatorBinding(msisdn, null, claims.eapMethod());
    }

    @Override
    public String extractCandidate(String authorizationHeader, String loginHint,
                                   String operatorTokenHeader) {
        if (authorizationHeader != null) {
            String h = authorizationHeader.trim();
            if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String bearer = h.substring(7).trim();
                if (bearer.regionMatches(true, 0, OPERATOR_SCHEME_PREFIX, 0, OPERATOR_SCHEME_PREFIX.length())) {
                    String token = bearer.substring(OPERATOR_SCHEME_PREFIX.length()).trim();
                    return token.isEmpty() ? null : token;
                }
            }
        }
        String fromHint = IdentityAnchor.parseLoginHint(loginHint);
        if (fromHint != null) {
            return fromHint;
        }
        if (config != null && config.headerEnabled()
                && operatorTokenHeader != null && !operatorTokenHeader.isBlank()) {
            return operatorTokenHeader.trim();
        }
        return null;
    }

    /** Our public encryption key(s), published so an ECS can encrypt to us. */
    public List<ECKey> publicEncryptionKeys() {
        // Config only: publishing our key must never trigger an issuer JWKS fetch.
        if (config == null) {
            return List.of();
        }
        refreshConfig();
        List<ECKey> out = new ArrayList<>();
        for (ECKey k : decryptionKeys) {
            out.add(k.toPublicJWK());
        }
        return out;
    }

    OperatorToken.Policy policy() {
        refreshConfig();
        Map<String, List<ECKey>> trusted = new LinkedHashMap<>();
        for (Map.Entry<String, IssuerEntry> e : issuers.entrySet()) {
            List<ECKey> keys = new ArrayList<>(e.getValue().inlineKeys());
            if (e.getValue().jwksUri() != null) {
                keys.addAll(uriKeys(e.getValue().jwksUri()));
            }
            // A configured issuer stays listed even with no key yet, so that an unknown
            // kid can trigger a refetch instead of "issuer not trusted".
            trusted.put(e.getKey(), List.copyOf(keys));
        }
        return new OperatorToken.Policy(trusted, config.audience(), decryptionKeys,
                config.requireEncryption(), config.clockSkewSeconds(), this::forceRefresh);
    }

    /**
     * Unknown {@code kid} from a trusted issuer: the issuer rotated (or, in the lab,
     * restarted with a fresh key). Refetch its JWKS at most once per
     * {@value #FORCED_REFRESH_SECONDS} s so a stream of bogus kids cannot turn CAMARA into
     * a request amplifier against the ECS.
     */
    private List<ECKey> forceRefresh(String issuer) {
        IssuerEntry entry = issuers.get(issuer);
        if (entry == null || entry.jwksUri() == null) {
            return List.of();
        }
        long now = clock.millis() / 1000L;
        Long last = lastForced.get(issuer);
        if (last != null && now - last < FORCED_REFRESH_SECONDS) {
            return List.of();
        }
        lastForced.put(issuer, now);
        List<ECKey> fetched = keySource == null ? List.of() : keySource.fetch(entry.jwksUri());
        if (fetched == null || fetched.isEmpty()) {
            return List.of();
        }
        uriCache.put(entry.jwksUri().toString(), new CachedKeys(List.copyOf(fetched), now));
        List<ECKey> all = new ArrayList<>(entry.inlineKeys());
        all.addAll(fetched);
        LOG.info("[SAS] issuer {} presented an unknown kid — JWKS refetched ({} key(s))",
                issuer, fetched.size());
        return List.copyOf(all);
    }

    private List<ECKey> uriKeys(URI uri) {
        long now = clock.millis() / 1000L;
        CachedKeys cached = uriCache.get(uri.toString());
        if (cached != null && now - cached.fetchedAtSec() < config.jwksCacheSeconds()) {
            return cached.keys();
        }
        List<ECKey> fetched = keySource == null ? List.of() : keySource.fetch(uri);
        if (fetched == null || fetched.isEmpty()) {
            // Keep serving the last good set for one more cache period rather than
            // flapping to "untrusted" on a transient fetch failure; never invent keys.
            if (cached != null && now - cached.fetchedAtSec() < 2 * config.jwksCacheSeconds()) {
                return cached.keys();
            }
            return List.of();
        }
        uriCache.put(uri.toString(), new CachedKeys(List.copyOf(fetched), now));
        return fetched;
    }

    private void refreshConfig() {
        String raw = config.issuersJson().orElse("") + "\u0000" + config.decryptionJwk().orElse("");
        if (raw.equals(parsedFor)) {
            return;
        }
        synchronized (this) {
            if (raw.equals(parsedFor)) {
                return;
            }
            Map<String, IssuerEntry> parsed = new LinkedHashMap<>();
            config.issuersJson().ifPresent(json -> parsed.putAll(parseIssuers(json)));
            List<ECKey> dec = new ArrayList<>();
            Optional<String> jwk = config.decryptionJwk();
            if (jwk.isPresent()) {
                try {
                    dec.add(Es256Keys.parsePrivate(jwk.get(), KeyUse.ENCRYPTION));
                } catch (IllegalArgumentException e) {
                    LOG.error("[SAS] sas.operator-token.decryption-jwk invalid: {}", e.getMessage());
                }
            }
            issuers = Map.copyOf(parsed);
            decryptionKeys = List.copyOf(dec);
            parsedFor = raw;
        }
    }

    private static Map<String, IssuerEntry> parseIssuers(String json) {
        Map<String, IssuerEntry> out = new LinkedHashMap<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode list = root.isArray() ? root : root.get("issuers");
            if (list == null || !list.isArray()) {
                LOG.error("[SAS] sas.operator-token.issuers-json has no issuers array");
                return out;
            }
            for (JsonNode node : list) {
                String iss = node.path("issuer").asText("").trim();
                if (iss.isEmpty()) {
                    continue;
                }
                List<ECKey> inline = List.of();
                if (node.has("jwks")) {
                    inline = Es256Keys.parseSet(node.get("jwks").toString(), KeyUse.SIGNATURE);
                }
                URI uri = null;
                String uriText = node.path("jwks_uri").asText("").trim();
                if (!uriText.isEmpty()) {
                    uri = URI.create(uriText);
                }
                out.put(iss, new IssuerEntry(inline, uri));
            }
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("[SAS] sas.operator-token.issuers-json invalid: {}", e.getMessage());
        }
        return out;
    }
}
