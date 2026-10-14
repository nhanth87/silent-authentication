/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import et.restlink.sas.camara.CamaraErrorInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource-server half of DPoP (RFC 9449 §7): every API call that carries an
 * access token passes here before any resource sees it.
 *
 * <pre>
 *  Authorization: DPoP &lt;token&gt;  + one DPoP proof
 *      token must be DPoP-bound (cnf.jkt) ─ proof verifies (htm, htu, iat, jti, ath)
 *      ─ proof key thumbprint == cnf.jkt ─► continue
 *  Authorization: Bearer &lt;token with cnf.jkt&gt;  ─► 401 (a stolen bound token is useless)
 *  Authorization: Bearer &lt;plain token&gt;          ─► continue (DPoP is optional in CAMARA)
 *  anything else                                  ─► untouched
 * </pre>
 *
 * Refusals answer {@code 401} with the CAMARA error body and
 * {@code WWW-Authenticate: DPoP error="invalid_dpop_proof"}. The token's signature,
 * expiry and scopes are still checked afterwards by the resource's own validation;
 * here the token payload is only read for {@code cnf}. Reading it unverified is safe:
 * a forged token fails the signature check later, and a genuine one carries a
 * genuine {@code cnf}.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class DpopResourceFilter implements ContainerRequestFilter {

    private static final Logger LOG = LogManager.getLogger(DpopResourceFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Request property holding the verified proof key thumbprint. */
    public static final String VERIFIED_JKT = "sas.dpop.jkt";

    @Inject
    DpopProofVerifier dpop;

    @Inject
    OAuthServerConfig oauthConfig;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String authorization = ctx.getHeaderString("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return;
        }
        String trimmed = authorization.trim();
        int space = trimmed.indexOf(' ');
        if (space <= 0) {
            return;
        }
        String scheme = trimmed.substring(0, space);
        String token = trimmed.substring(space + 1).trim();

        if ("DPoP".equalsIgnoreCase(scheme)) {
            if (dpop == null || !dpop.enabled()) {
                abort(ctx, "DPoP is not enabled on this server");
                return;
            }
            String bound = cnfJkt(token);
            if (bound == null) {
                abort(ctx, "the DPoP scheme needs a DPoP-bound access token");
                return;
            }
            List<String> proofs = ctx.getHeaders().get("DPoP");
            if (proofs == null || proofs.size() != 1) {
                abort(ctx, "exactly one DPoP proof is required");
                return;
            }
            String jkt;
            try {
                jkt = dpop.verify(proofs.get(0), ctx.getMethod(), htus(ctx), token);
            } catch (DpopProofVerifier.InvalidProof e) {
                abort(ctx, e.getMessage());
                return;
            }
            if (!jkt.equals(bound)) {
                abort(ctx, "the DPoP proof key is not the key the token is bound to");
                return;
            }
            ctx.setProperty(VERIFIED_JKT, jkt);
            return;
        }
        if ("Bearer".equalsIgnoreCase(scheme) && cnfJkt(token) != null) {
            abort(ctx, "a DPoP-bound access token must be sent with the DPoP scheme and a proof");
        }
    }

    /** The request URL as received and, behind a proxy, as the client addressed it. */
    private Set<String> htus(ContainerRequestContext ctx) {
        Set<String> htus = new LinkedHashSet<>();
        htus.add(ctx.getUriInfo().getAbsolutePath().toString());
        String base = oauthConfig == null ? null : oauthConfig.publicBaseUrl().orElse(null);
        if (base != null) {
            String path = ctx.getUriInfo().getPath();
            htus.add(base.replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path));
        }
        return htus;
    }

    /** {@code cnf.jkt} of a JWT, or null when absent or not a JWT. */
    static String cnfJkt(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return cnfJktOfPayload(new String(Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    /** {@code cnf.jkt} of a decoded JWT payload, or null. */
    public static String cnfJktOfPayload(String payloadJson) {
        if (payloadJson == null) {
            return null;
        }
        try {
            JsonNode jkt = MAPPER.readTree(payloadJson).path("cnf").path("jkt");
            return jkt.isTextual() && !jkt.asText().isBlank() ? jkt.asText() : null;
        } catch (Exception notJson) {
            return null;
        }
    }

    private static void abort(ContainerRequestContext ctx, String reason) {
        LOG.warn("[SAS] DPoP refused on {} {}: {}", ctx.getMethod(), ctx.getUriInfo().getPath(), reason);
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .header("WWW-Authenticate", "DPoP error=\"invalid_dpop_proof\", algs=\""
                        + String.join(" ", DpopProofVerifier.SUPPORTED_ALGS) + "\"")
                .entity(new CamaraErrorInfo(401, "UNAUTHENTICATED", reason))
                .build());
    }
}
