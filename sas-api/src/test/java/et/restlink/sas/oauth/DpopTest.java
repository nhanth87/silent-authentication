/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * Tests: DPoP (RFC 9449) proof verification, token binding at /token, the
 * resource filter, and the CAMARA behaviour table.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DpopTest {

    private static final String HTU = "https://sas.example.com/token";
    private static final Set<String> HTUS = Set.of(HTU);

    private DpopProofVerifier verifier;
    private DpopKeys key;
    private long now;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new DpopProofVerifier();
        key = DpopKeys.ec();
        now = System.currentTimeMillis() / 1000L;
    }

    private String reason(String proof, String method, String token) {
        return assertThrows(DpopProofVerifier.InvalidProof.class,
                () -> verifier.verify(proof, method, HTUS, token, now)).getMessage();
    }

    // ------------------------------------------------------------ proof rules

    @Test
    void validProofYieldsTheRfc7638Thumbprint() throws Exception {
        assertEquals(key.thumbprint(), verifier.verify(key.proof("POST", HTU, now, null), "POST", HTUS, null, now));
        DpopKeys rsa = DpopKeys.rsa();
        assertEquals(rsa.thumbprint(), verifier.verify(rsa.proof("POST", HTU, now, null), "POST", HTUS, null, now));
    }

    @Test
    void rfc7638ExampleThumbprint() throws Exception {
        // RFC 7638 §3.1 example key and its published thumbprint.
        String jwk = "{\"kty\":\"RSA\",\"n\":\"0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw\",\"e\":\"AQAB\",\"alg\":\"RS256\",\"kid\":\"2011-04-29\"}";
        assertEquals("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs",
                DpopProofVerifier.thumbprint(new ObjectMapper().readTree(jwk), "RSA"));
    }

    @Test
    void everyCheckRefuses() throws Exception {
        String token = "header.payload.sig";
        assertTrue(reason(null, "POST", null).contains("missing"));
        assertTrue(reason(key.proof("POST", HTU, now, null) + "," + key.proof("POST", HTU, now, null), "POST", null)
                .contains("exactly one"));
        assertTrue(reason("not-a-jws", "POST", null).contains("not a JWS"));
        assertTrue(reason(key.proof("JWT", "ES256", key.jwk, "POST", HTU, now, null, "j1"), "POST", null).contains("typ"));
        assertTrue(reason(key.proof("dpop+jwt", "HS256", key.jwk, "POST", HTU, now, null, "j2"), "POST", null).contains("alg"));
        assertTrue(reason(key.proof("dpop+jwt", "none", key.jwk, "POST", HTU, now, null, "j3"), "POST", null).contains("alg"));
        String withPrivate = key.jwk.replace("}", ",\"d\":\"AAAA\"}");
        assertTrue(reason(key.proof("dpop+jwt", "ES256", withPrivate, "POST", HTU, now, null, "j4"), "POST", null)
                .contains("public"));
        String otherKey = DpopKeys.ec().jwk;
        assertTrue(reason(key.proof("dpop+jwt", "ES256", otherKey, "POST", HTU, now, null, "j5"), "POST", null)
                .contains("signature"));
        assertTrue(reason(key.proof("GET", HTU, now, null), "POST", null).contains("htm"));
        assertTrue(reason(key.proof("POST", "https://evil.example/token", now, null), "POST", null).contains("htu"));
        assertTrue(reason(key.proof("POST", HTU, now - 120, null), "POST", null).contains("iat"));
        assertTrue(reason(key.proof("POST", HTU, now + 120, null), "POST", null).contains("iat"));
        assertTrue(reason(key.proof("POST", HTU, now, "other.token.x"), "POST", token).contains("ath"));
        assertTrue(reason(key.proof("POST", HTU, now, null), "POST", token).contains("ath"));
    }

    @Test
    void htuIgnoresQueryFragmentCaseAndDefaultPort() throws Exception {
        assertNotNull(verifier.verify(key.proof("POST", "HTTPS://SAS.example.com:443/token?x=1#f", now, null),
                "POST", HTUS, null, now));
        assertEquals("http://[2001:db8::1]:8085/a", DpopProofVerifier.normalizeHtu("http://[2001:db8::1]:8085/a?b"));
        assertNull(DpopProofVerifier.normalizeHtu("/token"));
    }

    @Test
    void aReplayedJtiIsRefused() throws Exception {
        String proof = key.proof("POST", HTU, now, null);
        verifier.verify(proof, "POST", HTUS, null, now);
        assertTrue(reason(proof, "POST", null).contains("already used"));
    }

    // ------------------------------------------------------------ /token

    private TokenResource tokenResource(OAuthClient client, AccessTokenService tokens) throws Exception {
        TokenResource resource = new TokenResource();
        set(resource, "accessTokens", tokens);
        set(resource, "dpop", verifier);
        OAuthMetadataService metadata = new OAuthMetadataService() {
            @Override
            public String tokenEndpoint(UriInfo uriInfo) {
                return HTU;
            }

            @Override
            public Set<String> tokenEndpointAudiences(UriInfo uriInfo) {
                return Set.of(HTU);
            }
        };
        set(resource, "metadata", metadata);
        set(resource, "clientAuthenticator", new OAuthClientAuthenticator() {
            @Override
            public OAuthClient authenticate(String clientId, String type, String assertion,
                                            Set<String> audiences, boolean required) {
                return client;
            }
        });
        return resource;
    }

    private static final class RecordingTokens extends AccessTokenService {
        String jkt = "unset";

        @Override
        public String issueClientToken(String clientId, Set<String> scopes, String boundJkt) {
            jkt = boundJkt;
            return "tok";
        }
    }

    private static OAuthClient client(boolean dpopBound) {
        return new OAuthClient("bank", "none", new JsonWebKeySet(List.of()),
                Set.of(TokenResource.CLIENT_CREDENTIALS_GRANT_TYPE), Set.of(),
                Set.of("sim-swap:check"), Set.of("dpv:FraudPreventionAndDetection"), dpopBound);
    }

    private Response clientCredentials(TokenResource r, String proof) {
        return r.token(TokenResource.CLIENT_CREDENTIALS_GRANT_TYPE, null,
                "dpv:FraudPreventionAndDetection sim-swap:check", "bank", null, null, null, null, null, null,
                proof, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Response r) {
        return (Map<String, Object>) r.getEntity();
    }

    @Test
    void camaraTable_proofGivesADpopToken() throws Exception {
        RecordingTokens tokens = new RecordingTokens();
        Response r = clientCredentials(tokenResource(client(false), tokens), key.proof("POST", HTU, now, null));
        assertEquals(200, r.getStatus());
        assertEquals("DPoP", body(r).get("token_type"));
        assertEquals(key.thumbprint(), tokens.jkt);
    }

    @Test
    void camaraTable_noProofGivesABearerToken() throws Exception {
        RecordingTokens tokens = new RecordingTokens();
        Response r = clientCredentials(tokenResource(client(false), tokens), null);
        assertEquals("Bearer", body(r).get("token_type"));
        assertNull(tokens.jkt);
    }

    @Test
    void camaraTable_boundClientWithoutProofIs400() throws Exception {
        Response r = clientCredentials(tokenResource(client(true), new RecordingTokens()), null);
        assertEquals(400, r.getStatus());
        assertEquals("invalid_dpop_proof", ((OAuthErrorResponse) r.getEntity()).error());
    }

    @Test
    void camaraTable_badProofIs400() throws Exception {
        Response r = clientCredentials(tokenResource(client(false), new RecordingTokens()),
                key.proof("POST", "https://sas.example.com/other", now, null));
        assertEquals(400, r.getStatus());
        assertEquals("invalid_dpop_proof", ((OAuthErrorResponse) r.getEntity()).error());
    }

    @Test
    void camaraTable_serverWithoutDpopIgnoresTheProof() throws Exception {
        verifier.enabled = Optional.of(false);
        RecordingTokens tokens = new RecordingTokens();
        Response r = clientCredentials(tokenResource(client(true), tokens), key.proof("POST", HTU, now, null));
        assertEquals("Bearer", body(r).get("token_type"));
        assertNull(tokens.jkt);
    }

    // ------------------------------------------------------------ resource filter

    private static String jwt(String payloadJson) {
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        return e.encodeToString("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + e.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    /** A ContainerRequestContext stand-in; records abortWith / setProperty. */
    private static final class Request {
        final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        final Map<String, Object> properties = new HashMap<>();
        Response aborted;
        final ContainerRequestContext ctx;

        Request(String method, String url) {
            URI uri = URI.create(url);
            UriInfo uriInfo = (UriInfo) Proxy.newProxyInstance(UriInfo.class.getClassLoader(),
                    new Class<?>[] {UriInfo.class}, (p, m, a) -> switch (m.getName()) {
                        case "getAbsolutePath" -> uri;
                        case "getPath" -> uri.getPath();
                        default -> null;
                    });
            ctx = (ContainerRequestContext) Proxy.newProxyInstance(ContainerRequestContext.class.getClassLoader(),
                    new Class<?>[] {ContainerRequestContext.class}, (p, m, a) -> switch (m.getName()) {
                        case "getHeaderString" -> headers.getFirst((String) a[0]);
                        case "getHeaders" -> headers;
                        case "getMethod" -> method;
                        case "getUriInfo" -> uriInfo;
                        case "setProperty" -> properties.put((String) a[0], a[1]);
                        case "abortWith" -> {
                            aborted = (Response) a[0];
                            yield null;
                        }
                        default -> null;
                    });
        }
    }

    private DpopResourceFilter filter() throws Exception {
        DpopResourceFilter f = new DpopResourceFilter();
        set(f, "dpop", verifier);
        return f;
    }

    @Test
    void filterAcceptsABoundTokenWithItsProof() throws Exception {
        String url = "https://sas.example.com/camara/number-verification/v2/verify";
        String token = jwt("{\"sub\":\"+251911111111\",\"cnf\":{\"jkt\":\"" + key.thumbprint() + "\"}}");
        Request req = new Request("POST", url);
        req.headers.putSingle("Authorization", "DPoP " + token);
        req.headers.putSingle("DPoP", key.proof("POST", url, System.currentTimeMillis() / 1000L, token));

        filter().filter(req.ctx);

        assertNull(req.aborted);
        assertEquals(key.thumbprint(), req.properties.get(DpopResourceFilter.VERIFIED_JKT));
    }

    @Test
    void filterRefusesEveryMispairing() throws Exception {
        String url = "https://sas.example.com/camara/number-verification/v2/verify";
        long t = System.currentTimeMillis() / 1000L;
        String bound = jwt("{\"cnf\":{\"jkt\":\"" + key.thumbprint() + "\"}}");
        String plain = jwt("{\"sub\":\"x\"}");

        Request stolen = new Request("POST", url);              // bound token as Bearer
        stolen.headers.putSingle("Authorization", "Bearer " + bound);
        filter().filter(stolen.ctx);
        assertEquals(401, stolen.aborted.getStatus());
        assertTrue(stolen.aborted.getHeaderString("WWW-Authenticate").contains("invalid_dpop_proof"));

        Request noProof = new Request("POST", url);
        noProof.headers.putSingle("Authorization", "DPoP " + bound);
        filter().filter(noProof.ctx);
        assertEquals(401, noProof.aborted.getStatus());

        Request otherKey = new Request("POST", url);            // proof by another key
        otherKey.headers.putSingle("Authorization", "DPoP " + bound);
        otherKey.headers.putSingle("DPoP", DpopKeys.ec().proof("POST", url, t, bound));
        filter().filter(otherKey.ctx);
        assertEquals(401, otherKey.aborted.getStatus());

        Request unbound = new Request("POST", url);             // DPoP scheme, plain token
        unbound.headers.putSingle("Authorization", "DPoP " + plain);
        unbound.headers.putSingle("DPoP", key.proof("POST", url, t, plain));
        filter().filter(unbound.ctx);
        assertEquals(401, unbound.aborted.getStatus());

        Request ordinary = new Request("POST", url);            // plain Bearer: untouched
        ordinary.headers.putSingle("Authorization", "Bearer " + plain);
        filter().filter(ordinary.ctx);
        assertNull(ordinary.aborted);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException next) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }
}
