/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import et.restlink.sas.ras.resolver.InMemoryResolverBackend;
import et.restlink.sas.ras.resolver.ResolverBackend;
import et.restlink.sas.bootstrap.SasBootstrap;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
 *   "srcIp":   "10.20.30.40",   // cellular bearer source IP
 *   "srcPort": 55555,            // bearer source port (CGNAT-safe)
 *   "ts":      1724200000000,    // epoch ms when the tuple was captured
 *   "msisdn":  "+251911111111"   // optional claimed MSISDN
 * }
 * </pre>
 */
@Path("/session-tuple")
public class SessionTupleResource {

    private static final Logger LOG = LogManager.getLogger(SessionTupleResource.class);

    @Inject
    SasBootstrap bootstrap;

    public record TupleRequest(String srcIp, Integer srcPort, Long ts, String msisdn, String imsi) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(TupleRequest body) {
        if (body == null || body.srcIp() == null || body.srcIp().isBlank()) {
            return Response.status(400)
                    .entity(Map.of("code", "VALIDATION.FAILED", "message", "srcIp is required"))
                    .build();
        }
        int srcPort = body.srcPort() != null ? body.srcPort() : 0;
        long ts = body.ts() != null ? body.ts() : System.currentTimeMillis();
        String msisdn = body.msisdn();
        String imsi = body.imsi();

        // Pilot: seed the in-memory resolver so the next /verify can match.
        ResolverBackend backend = bootstrap.resolverBackend();
        if (backend instanceof InMemoryResolverBackend inMemory) {
            inMemory.seed(body.srcIp(), srcPort, msisdn, imsi, 30_000L);
            LOG.info("[SAS] session-tuple registered {}:{} → {}", body.srcIp(), srcPort,
                    msisdn != null ? mask(msisdn) : "discovery");
            return Response.ok(Map.of("registered", true)).build();
        }

        // Real transport: the tuple is forwarded to the operator's PGW/CGNAT
        // integration out-of-band; here we just acknowledge receipt.
        LOG.info("[SAS] session-tuple received {}:{} (non-memory resolver)", body.srcIp(), srcPort);
        return Response.ok(Map.of("registered", true, "note", "forwarded to operator resolver")).build();
    }

    private static String mask(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) return "***";
        return msisdn.substring(0, 4) + "****" + msisdn.substring(msisdn.length() - 2);
    }
}
