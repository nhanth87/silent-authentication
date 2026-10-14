/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies DPoP proofs (RFC 9449 §4.3). The token endpoint uses it to bind an
 * access token to the client's key; the resource filter uses it to prove that
 * the caller holds that key on every API call.
 *
 * <p>CAMARA (security and interoperability profile) makes DPoP optional for the
 * API consumer. A provider that supports it issues a DPoP token when a valid
 * proof comes with the token request, a Bearer token otherwise, and answers
 * {@code 400 invalid_dpop_proof} when a client registered with
 * {@code dpop_bound_access_tokens} sends none.</p>
 *
 * <h2>Checks, in order</h2>
 * <ol>
 *   <li>exactly one proof, a JWS with {@code typ=dpop+jwt}</li>
 *   <li>{@code alg} is ES256 or RS256 — never {@code none} or a MAC</li>
 *   <li>{@code jwk} is a public EC P-256 / RSA key (no private members), and the
 *       signature verifies with it</li>
 *   <li>{@code htm} equals the request method, {@code htu} one of the accepted target
 *       URIs (scheme + authority + path; query and fragment ignored, §4.3 #9)</li>
 *   <li>{@code iat} within the acceptance window (default 60 s back, clock skew forward)</li>
 *   <li>{@code ath} = base64url(SHA-256(access token)) when a token is presented</li>
 *   <li>{@code jti} not seen before within the window (replay cache, CAMARA: MUST reject reuse)</li>
 * </ol>
 * The result is the RFC 7638 JWK thumbprint ({@code jkt}) that the access token's
 * {@code cnf} claim carries.
 */
@ApplicationScoped
public class DpopProofVerifier {

    /** Algorithms advertised as {@code dpop_signing_alg_values_supported}. */
    public static final List<String> SUPPORTED_ALGS = List.of("ES256", "RS256");

    /** Bound on the replay cache; full and nothing expired = refuse (fail closed). */
    static final int MAX_REPLAY_ENTRIES = 100_000;

    @ConfigProperty(name = "sas.oauth.dpop.enabled", defaultValue = "true")
    Optional<Boolean> enabled;

    @ConfigProperty(name = "sas.oauth.dpop.max-age-seconds", defaultValue = "60")
    Optional<Long> maxAgeSeconds;

    @ConfigProperty(name = "sas.oauth.dpop.clock-skew-seconds", defaultValue = "5")
    Optional<Long> clockSkewSeconds;

    /** jti → forget-after epoch second. */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /** A proof that cannot be accepted; the caller maps it to its error format. */
    public static final class InvalidProof extends RuntimeException {
        public InvalidProof(String reason) {
            super(reason);
        }
    }

    public boolean enabled() {
        return enabled == null || enabled.orElse(true);
    }

    /**
     * @param proof        the {@code DPoP} header value
     * @param method       the HTTP method of this request
     * @param acceptedHtus target URIs this request may be addressed as (behind a proxy
     *                     the public URL and the local one differ)
     * @param accessToken  the access token presented with the proof, or null at /token
     * @return the JWK thumbprint of the proof key
     * @throws InvalidProof naming the first failed check
     */
    public String verify(String proof, String method, Collection<String> acceptedHtus, String accessToken) {
        return verify(proof, method, acceptedHtus, accessToken, System.currentTimeMillis() / 1000L);
    }

    String verify(String proof, String method, Collection<String> acceptedHtus, String accessToken,
                  long nowSec) {
        if (proof == null || proof.isBlank()) {
            throw new InvalidProof("DPoP proof is missing");
        }
        if (proof.indexOf(',') >= 0) {
            throw new InvalidProof("exactly one DPoP proof is allowed");
        }
        JsonWebSignature jws;
        try {
            jws = JsonWebSignature.parse(proof.trim());
        } catch (OAuthException malformed) {
            throw new InvalidProof("DPoP proof is not a JWS");
        }
        if (!"dpop+jwt".equals(text(jws.header(), "typ"))) {
            throw new InvalidProof("DPoP proof typ must be dpop+jwt");
        }
        String alg = jws.algorithm();
        if (alg == null || !SUPPORTED_ALGS.contains(alg)) {
            throw new InvalidProof("DPoP proof alg must be one of " + SUPPORTED_ALGS);
        }
        JsonNode jwkNode = jws.header().get("jwk");
        if (jwkNode == null || !jwkNode.isObject()) {
            throw new InvalidProof("DPoP proof header carries no jwk");
        }
        for (String privateMember : List.of("d", "p", "q", "dp", "dq", "qi", "k")) {
            if (jwkNode.has(privateMember)) {
                throw new InvalidProof("DPoP proof jwk must be a public key");
            }
        }
        JsonWebKey key;
        try {
            key = JsonWebKey.from(jwkNode);
        } catch (RuntimeException invalid) {
            throw new InvalidProof("DPoP proof jwk is not a usable public key");
        }
        if ("EC".equals(key.kty()) && !"P-256".equals(text(jwkNode, "crv"))) {
            throw new InvalidProof("DPoP proof EC key must be P-256");
        }
        if (!key.supports(alg) || !jws.verify(key)) {
            throw new InvalidProof("DPoP proof signature does not verify");
        }

        String jti = jws.stringClaim("jti");
        if (jti == null || jti.length() > 256) {
            throw new InvalidProof("DPoP proof jti is missing");
        }
        if (method == null || !method.equals(jws.stringClaim("htm"))) {
            throw new InvalidProof("DPoP proof htm does not match the request method");
        }
        String htu = normalizeHtu(jws.stringClaim("htu"));
        boolean htuMatches = htu != null && acceptedHtus.stream()
                .map(DpopProofVerifier::normalizeHtu)
                .anyMatch(htu::equals);
        if (!htuMatches) {
            throw new InvalidProof("DPoP proof htu does not match the request URI");
        }
        Long iat = jws.longClaim("iat");
        long maxAge = maxAgeSeconds == null ? 60L : maxAgeSeconds.orElse(60L);
        long skew = clockSkewSeconds == null ? 5L : clockSkewSeconds.orElse(5L);
        if (iat == null || iat > nowSec + skew || iat < nowSec - maxAge) {
            throw new InvalidProof("DPoP proof iat is outside the acceptance window");
        }
        if (accessToken != null) {
            String ath = jws.stringClaim("ath");
            if (ath == null || !MessageDigest.isEqual(
                    ath.getBytes(StandardCharsets.US_ASCII),
                    b64(sha256(accessToken.getBytes(StandardCharsets.US_ASCII))).getBytes(StandardCharsets.US_ASCII))) {
                throw new InvalidProof("DPoP proof ath does not match the access token");
            }
        }
        String jkt = thumbprint(jwkNode, key.kty());
        remember(jkt + ":" + jti, nowSec + maxAge + skew, nowSec);
        return jkt;
    }

    /** Records a jti; a second sighting inside the window is a replay. */
    private void remember(String key, long forgetAfter, long nowSec) {
        if (seen.size() >= MAX_REPLAY_ENTRIES) {
            evict(nowSec);
            if (seen.size() >= MAX_REPLAY_ENTRIES) {
                throw new InvalidProof("DPoP replay cache is full");
            }
        }
        if (seen.putIfAbsent(key, forgetAfter) != null) {
            Long until = seen.get(key);
            if (until != null && until > nowSec) {
                throw new InvalidProof("DPoP proof jti was already used");
            }
            seen.put(key, forgetAfter);
        }
    }

    private void evict(long nowSec) {
        for (Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() <= nowSec) {
                it.remove();
            }
        }
    }

    /**
     * RFC 7638 thumbprint: the required members in lexicographic order, no
     * whitespace, SHA-256, base64url.
     */
    static String thumbprint(JsonNode jwk, String kty) {
        String canonical = switch (kty) {
            case "EC" -> "{\"crv\":\"" + text(jwk, "crv") + "\",\"kty\":\"EC\",\"x\":\""
                    + text(jwk, "x") + "\",\"y\":\"" + text(jwk, "y") + "\"}";
            case "RSA" -> "{\"e\":\"" + text(jwk, "e") + "\",\"kty\":\"RSA\",\"n\":\"" + text(jwk, "n") + "\"}";
            default -> throw new InvalidProof("DPoP proof jwk kty must be EC or RSA");
        };
        return b64(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /** Scheme + authority + path, lower-cased scheme/host, default port dropped. */
    static String normalizeHtu(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(raw.trim());
            String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
            if (scheme == null || host == null) {
                return null;
            }
            int port = u.getPort();
            boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443)
                    || ("http".equals(scheme) && port == 80);
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    static String accessTokenHash(String accessToken) {
        return b64(sha256(accessToken.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }
}
