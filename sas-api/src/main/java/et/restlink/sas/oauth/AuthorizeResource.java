/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.id.PhoneNumberNormalizer;
import et.restlink.sas.security.RequestValidator;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OIDC authorization endpoint ({@code GET /authorize}) — the front-channel leg of the
 * Authorization Code flow CAMARA Number Verification uses.
 *
 * <h2>Sequence</h2>
 * <pre>
 *  device ──(cellular)── GET /authorize?response_type=code&amp;client_id&amp;redirect_uri
 *                                  &amp;scope&amp;state&amp;nonce&amp;code_challenge&amp;code_challenge_method=S256
 *     SAS: client + exact redirect_uri check ─► PKCE present ─► scope policy
 *          ─► network-based authentication: source IP:port:now ─► Resolver ─► one MSISDN
 *             (or login_hint=operatortoken:&lt;tk&gt; for the TS.43 / Wi-Fi path)
 *  ◄── 302 redirect_uri?code=&lt;one-time&gt;&amp;state
 *  client backend ── POST /token grant_type=authorization_code + code_verifier + client auth
 * </pre>
 *
 * <h2>Error handling (RFC 6749 §4.1.2.1)</h2>
 * An unknown client or a redirect URI that is not registered verbatim is answered
 * directly with {@code 400} — never redirected, so the endpoint cannot be turned into
 * an open redirector. Every later failure redirects back with {@code error} and the
 * caller's {@code state}.
 *
 * <h2>Source address</h2>
 * The subscriber is identified from the <em>connection</em> source (the post-CGNAT
 * public address and port the network sees). {@code X-Sas-Src-Ip/Port} are honoured
 * only when {@code sas.oauth.authorize.trust-src-header=true}, i.e. behind an edge
 * proxy that overwrites them; trusting them otherwise would let any caller claim any
 * subscriber's address.
 */
@Path(OAuthMetadataService.AUTHORIZATION_PATH)
public class AuthorizeResource {

    private static final Logger LOG = LogManager.getLogger(AuthorizeResource.class);

    /** RFC 8176 has no value for network-based (bearer IP) authentication; the SAS
     *  resource servers accept {@code mno} as mobile-network authentication. */
    public static final List<String> AMR_NETWORK = List.of("mno");

    @Inject
    OAuthClientRegistry registry;

    @Inject
    AuthorizationRequestService networkAuth;

    @Inject
    AuthorizationCodeService codes;

    @Inject
    IdentityAnchor operatorAnchor;

    @Inject
    @ConfigProperty(name = "sas.oauth.authorize.trust-src-header", defaultValue = "false")
    boolean trustSrcHeader;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response authorize(@QueryParam("response_type") String responseType,
                              @QueryParam("client_id") String clientId,
                              @QueryParam("redirect_uri") String redirectUri,
                              @QueryParam("scope") String scope,
                              @QueryParam("state") String state,
                              @QueryParam("nonce") String nonce,
                              @QueryParam("code_challenge") String codeChallenge,
                              @QueryParam("code_challenge_method") String codeChallengeMethod,
                              @QueryParam("login_hint") String loginHint,
                              @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                              @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                              @Context HttpServerRequest request) {
        // 1-2. Client and redirect URI — failures here are NOT redirected.
        Optional<OAuthClient> found = registry == null ? Optional.empty() : registry.find(clientId);
        if (found.isEmpty()) {
            return direct("invalid_request", "unknown client_id");
        }
        OAuthClient client = found.get();
        if (redirectUri == null || !client.redirectUris().contains(redirectUri)) {
            return direct("invalid_request", "redirect_uri is not registered for this client");
        }

        try {
            // 3. Protocol checks — failures redirect with error + state.
            if (!"code".equals(responseType)) {
                return redirectError(redirectUri, state, "unsupported_response_type",
                        "only response_type=code is supported");
            }
            client.authorizeGrant(TokenResource.AUTHORIZATION_CODE_GRANT_TYPE);
            if (!"S256".equals(codeChallengeMethod) || !AuthorizationCodeService.validChallenge(codeChallenge)) {
                return redirectError(redirectUri, state, "invalid_request",
                        "PKCE with code_challenge_method=S256 is required");
            }
            OAuthScopePolicy.ScopeGrant grant = OAuthScopePolicy.parse(scope);
            client.authorizeScope(grant, false);

            // 4. Authenticate the subscriber.
            String msisdn;
            List<String> amr;
            String operatorToken = IdentityAnchor.parseLoginHint(loginHint);
            if (operatorToken != null) {
                IdentityAnchor.OperatorBinding binding = operatorAnchor == null ? null
                        : operatorAnchor.resolveOperatorToken(operatorToken);
                if (binding == null || binding.msisdn() == null) {
                    return redirectError(redirectUri, state, "access_denied",
                            "operator token is invalid, expired or already used");
                }
                msisdn = binding.msisdn();
                amr = List.of();
            } else if (loginHint != null && !loginHint.isBlank()) {
                return redirectError(redirectUri, state, "invalid_request",
                        "login_hint must be operatortoken:<token> or absent");
            } else {
                Source source = source(srcIpHeader, srcPortHeader, request);
                IdentityAnchor.OperatorBinding binding =
                        networkAuth.resolveNetworkAnchor(source.ip(), source.port());
                msisdn = binding.msisdn();
                amr = AMR_NETWORK;
            }
            String e164 = PhoneNumberNormalizer.toE164(msisdn).orElse(null);
            if (e164 == null) {
                return redirectError(redirectUri, state, "access_denied",
                        "authenticated identity is not an E.164 subscriber");
            }

            // 5. Issue the code.
            String code = codes.mint(client.clientId(), redirectUri, grant.responseScopes(),
                    grant.openid(), e164, codeChallenge, nonce, amr);
            LOG.info("[SAS] /authorize issued a code for client {} ({} auth)", client.clientId(),
                    amr.isEmpty() ? "operator-token" : "network");
            UriBuilder target = UriBuilder.fromUri(redirectUri).queryParam("code", code);
            if (state != null) {
                target.queryParam("state", state);
            }
            return Response.status(Response.Status.FOUND).location(target.build()).build();
        } catch (OAuthException e) {
            LOG.warn("[SAS] /authorize refused for client {}: {} ({})", clientId, e.error(), e.getMessage());
            return redirectError(redirectUri, state, e.error(), e.getMessage());
        }
    }

    record Source(String ip, int port) {}

    Source source(String ipHeader, String portHeader, HttpServerRequest request) {
        if (trustSrcHeader && ipHeader != null && !ipHeader.isBlank()) {
            return new Source(ipHeader.trim(), parsePort(portHeader));
        }
        SocketAddress remote = request == null ? null : request.remoteAddress();
        if (remote == null || remote.hostAddress() == null) {
            throw OAuthException.accessDenied("connection source address is unavailable");
        }
        return new Source(remote.hostAddress(), remote.port());
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int p = Integer.parseInt(raw.trim());
            return p < 0 || p > 65535 ? 0 : p;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Response direct(String error, String description) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", error, "error_description", description))
                .build();
    }

    private static Response redirectError(String redirectUri, String state, String error,
                                          String description) {
        UriBuilder target = UriBuilder.fromUri(URI.create(redirectUri))
                .queryParam("error", error)
                .queryParam("error_description", description);
        if (state != null) {
            target.queryParam("state", state);
        }
        return Response.status(Response.Status.FOUND).location(target.build()).build();
    }
}
