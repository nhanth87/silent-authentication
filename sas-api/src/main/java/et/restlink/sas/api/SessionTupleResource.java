/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import et.restlink.sas.ras.resolver.InMemoryResolverBackend;
import et.restlink.sas.ras.resolver.ResolverBackend;
import et.restlink.sas.security.ApiKeyAuthenticator;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * UE session-tuple collector (P2 missing item #2).
 *
 * <p>Thin server-side hook for the UE SDK. The SDK on the device collects the
 * cellular bearer 5-tuple ({@code srcIp}, {@code srcPort}, {@code ts}) and
 * posts it here so the Resolver has a fresh binding to match against. This is
 * the CGNAT disambiguation path — without an accurate source-port + timestamp
 * the Resolver cannot distinguish subscribers behind the same NAT.</p>
 *
 * <p>This endpoint does NOT perform authentication; it only registers the
 * tuple. The actual {@code /verify} call still goes through the bank backend
 * with mTLS + OIDC.</p>
 *
 * <p>SDK contract (what the device SDK must send):</p>
 * <pre>
 * POST /session-tuple
 * {
 *   "srcIp":      "10.20.30.40",    // cellular bearer source IP
 *   "srcPort":    55555,            // bearer source port (CGNAT-safe)
 *   "ts":         1724200000000,    // epoch ms when the tuple was captured
 *   "msisdn":     "+251911111111",  // optional claimed MSISDN
 *   "accessTech": "LTE"             // optional bearer declaration: GS_2G3G | LTE | NR
 * }
 * </pre>
 *
 * <p><strong>Cellular-only bindings.</strong> This table feeds the IP-match
 * Resolver, whose premise is that a PGW/GGSN assigned {@code srcIp} to a
 * subscriber. A tuple the device says it captured over Wi-Fi (or fixed access)
 * is refused with {@code 400 ACCESS_TECH_NOT_CELLULAR} rather than allowed to
 * masquerade as a cellular binding — the TS.43/Wi-Fi path anchors on the
 * operator token at {@code /verify}, not here. The declaration is advisory (a
 * client can lie about it), so it is used to <em>exclude</em> known-bad tuples
 * and for CDR correlation, never to raise assurance: what raises assurance is
 * what the network itself attests. A tuple with no declaration at all is
 * accepted for backward compatibility and logged as {@code undeclared}.</p>
 *
 * <p><strong>Authentication</strong>: this endpoint seeds IP→MSISDN bindings
 * and MUST NOT be open in production. Clients send
 * {@code X-Api-Key: <key>}. Enforcement is controlled by
 * {@code sas.security.enforce-api-keys} (default false = lab) against the
 * comma-separated {@code sas.security.api-key} list; when enforced, a missing
 * or mismatched key is rejected with {@code 401 UNAUTHENTICATED} (fail-closed,
 * see {@link ApiKeyAuthenticator}).</p>
 */
@Path("/session-tuple")
public class SessionTupleResource {

    private static final Logger LOG = LogManager.getLogger(SessionTupleResource.class);

    @Inject
    SasVerifyEngine bootstrap;

    @Inject
    ApiKeyAuthenticator apiKeys;

    public record TupleRequest(String srcIp, Integer srcPort, Long ts, String msisdn,
                               String imsi, String accessTech) {}

    /** Sentinel: the client declared a bearer that cannot attest an MSISDN. */
    static final String NOT_CELLULAR = "\u0000not-cellular";
    /** Sentinel: the client declared something that is not in the contract. */
    static final String INVALID = "\u0000invalid";

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(TupleRequest body, @HeaderParam("X-Api-Key") String apiKey) {
        String keyError = apiKeys.validate(apiKey);
        if (keyError != null) {
            return Response.status(401)
                    .entity(Map.of("code", "UNAUTHENTICATED", "message", keyError))
                    .build();
        }
        if (body == null || body.srcIp() == null || body.srcIp().isBlank()) {
            return Response.status(400)
                    .entity(Map.of("code", "VALIDATION.FAILED", "message", "srcIp is required"))
                    .build();
        }
        // Bearer declaration gate. Absent = legacy client (accepted, logged as
        // undeclared); Wi-Fi/fixed or nonsense = refused, because this table is
        // the cellular IP->MSISDN binding source.
        String declared = declaredAccessTech(body.accessTech());
        if (NOT_CELLULAR.equals(declared)) {
            return Response.status(400)
                    .entity(Map.of("code", "ACCESS_TECH_NOT_CELLULAR",
                            "message", "only cellular bearer bindings are registered here;"
                                    + " a Wi-Fi/fixed address cannot be attested to an MSISDN"
                                    + " by the PGW - use the operator-token path at /verify"))
                    .build();
        }
        if (INVALID.equals(declared)) {
            return Response.status(400)
                    .entity(Map.of("code", "VALIDATION.FAILED",
                            "message", "accessTech must be one of GS_2G3G, LTE, NR"))
                    .build();
        }
        int srcPort = body.srcPort() != null ? body.srcPort() : 0;
        long ts = body.ts() != null ? body.ts() : System.currentTimeMillis();
        String msisdn = body.msisdn();
        String imsi = body.imsi();
        String declaredForLog = declared == null ? "undeclared" : declared;

        // Pilot: seed the in-memory resolver so the next /verify can match.
        ResolverBackend backend = bootstrap.resolverBackend();
        if (backend instanceof InMemoryResolverBackend inMemory) {
            inMemory.seed(body.srcIp(), srcPort, msisdn, imsi, 30_000L);
            LOG.info("[SAS] session-tuple registered {}:{} → {} accessTech={}",
                    body.srcIp(), srcPort,
                    msisdn != null ? mask(msisdn) : "discovery", declaredForLog);
            return Response.ok(Map.of("registered", true)).build();
        }

        // Real transport: the tuple is forwarded to the operator's PGW/CGNAT
        // integration out-of-band; here we just acknowledge receipt.
        LOG.info("[SAS] session-tuple received {}:{} accessTech={} (non-memory resolver)",
                body.srcIp(), srcPort, declaredForLog);
        return Response.ok(Map.of("registered", true, "note", "forwarded to operator resolver")).build();
    }

    /**
     * Normalises the device's bearer declaration to a cellular name.
     *
     * @return the declared name, {@code null} when nothing was declared, or one
     *         of the {@link #NOT_CELLULAR} / {@link #INVALID} sentinels
     */
    static String declaredAccessTech(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "GS_2G3G", "LTE", "NR" -> value;
            case "WIFI", "WLAN", "IWLAN", "FIXED", "ETHERNET" -> NOT_CELLULAR;
            default -> INVALID;
        };
    }

    private static String mask(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) return "***";
        return msisdn.substring(0, 4) + "****" + msisdn.substring(msisdn.length() - 2);
    }
}
