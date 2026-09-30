/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;

import et.restlink.sas.coordinator.EntitlementCoordinator;
import et.restlink.sas.coordinator.EntitlementSessions;
import et.restlink.sas.entitlement.EntitlementConfig;
import et.restlink.sas.entitlement.EntitlementTokenService;
import et.restlink.sas.entitlement.eap.EapAkaServer;
import et.restlink.sas.entitlement.eap.EapPacket;
import et.restlink.sas.events.Ts43RequestEvent;
import et.restlink.sas.fsm.SasTimeouts;
import et.restlink.sas.model.Ts43Result;
import et.restlink.sas.ras.authvector.AuthVector;
import et.restlink.sas.ras.authvector.command.AbortAuthVectorCommand;
import et.restlink.sas.ras.authvector.command.FetchVectorCommand;
import et.restlink.sas.ras.authvector.command.ResyncVectorCommand;
import et.restlink.sas.ras.binding.SubscriberBinding;
import et.restlink.sas.ras.binding.command.AbortBindingCommand;
import et.restlink.sas.ras.binding.command.LookupBindingCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The TS.43 entitlement SBB — D6/Shape S, the one surface allowed to terminate
 * EAP-AKA. Everything it does happens inside the micro-jainslee container: the REST
 * layer submits an event and awaits (H24).
 *
 * <pre>
 * CHALLENGE hop:  VECTORING ──vector──► CHALLENGE_SENT
 * RESPOND  hop:    CHALLENGE_SENT ──AT_MAC ok, AT_RES ok, binding ok──► COMPLETE
 *                       │                    │
 *                       │                    └─► resync (AT_AUTS) ──fresh vector──► CHALLENGE_SENT
 *                       └─► REFUSED (any missing evidence, timeout, mismatch)
 * </pre>
 *
 * <p><strong>Ordering is normative.</strong> {@code AT_MAC} is verified before
 * {@code AT_RES} (RFC 4187 §10.15) and the number is confirmed against the network
 * only after the SIM has proved possession. A peer that cannot produce a valid MAC
 * never causes a signalling dialog at all.</p>
 *
 * <p><strong>Fail-closed.</strong> A refused vector, a timeout, a bad MAC, a bad RES,
 * an unconfirmed number — every one of them ends in {@link Ts43Result.Outcome#FAILURE}.
 * The only way to get a token out of this SBB is to have a network-sourced vector
 * <em>and</em> a matching response <em>and</em> a number the network agrees with.</p>
 *
 * <p><strong>Privacy (H8).</strong> The IMSI is used to fetch the vector and is
 * carried into the token; it never appears in the {@link Ts43Result}. The MSISDN in
 * the token is the one the network confirmed, not the one the caller asked for.</p>
 *
 * <p><strong>Zeroization.</strong> Every path that leaves this SBB with a live key
 * wipes it: verified, refused, timed out, or released.</p>
 */
public final class EntitlementSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(EntitlementSbb.class);

    /** Vector fetch budget. One MAP/SS7 dialog, aborted on expiry — no leak. */
    private static final long VECTOR_BUDGET_MS = SasTimeouts.MAP_MS + 100L;

    /** Number-binding budget. Same reasoning: one dialog, bounded. */
    private static final long BINDING_BUDGET_MS = SasTimeouts.MAP_MS + 100L;

    private final EntitlementCoordinator coordinator;
    private final EntitlementSessions sessions;
    private final EntitlementTokenService tokens;
    private final EntitlementConfig config;

    @InjectRa(name = "auth-vector-ra")
    private volatile RaCommandPort authVectorRa;

    @InjectRa(name = "subscriber-binding-ra")
    private volatile RaCommandPort bindingRa;

    public EntitlementSbb(EntitlementCoordinator coordinator, EntitlementSessions sessions,
                          EntitlementTokenService tokens, EntitlementConfig config) {
        this.coordinator = coordinator;
        this.sessions = sessions;
        this.tokens = tokens;
        this.config = config;
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) throws Exception {
        if (!(event instanceof Ts43RequestEvent evt)) {
            return;
        }
        Ts43Result result = drive(evt);
        coordinator.complete(evt.reqId(), evt.phase(), result);
        LOG.info("[ts43] hop={} reqId={} outcome={}", evt.phase(), evt.reqId(), result.outcome());
    }

    private Ts43Result drive(Ts43RequestEvent evt) {
        return evt.phase() == Ts43RequestEvent.Phase.CHALLENGE
                ? challenge(evt)
                : respond(evt);
    }

    // ---- hop 1: Challenge --------------------------------------------------

    private Ts43Result challenge(Ts43RequestEvent evt) {
        if (isBlank(evt.imsi())) {
            return Ts43Result.failure(evt.reqId(), "IMSI is required");
        }
        AuthVector vector = fetchVector(evt, false);
        if (vector == null) {
            return Ts43Result.failure(evt.reqId(), "no authentication vector available");
        }
        if (!vector.hasSessionKeys()) {
            // GSM triplet: cannot ground EAP-AKA. Refuse, never downgrade.
            vector.wipe();
            return Ts43Result.failure(evt.reqId(), "network vector cannot ground EAP-AKA");
        }
        boolean akaPrime = AuthVector.EAP_AKA_PRIME.equals(vector.scheme());
        EapAkaServer server = EapAkaServer.begin(identity(evt.imsi()), vector.ck(), vector.ik(),
                vector.rand(), vector.autn(), vector.xres(), new byte[0]);
        vector.wipe();
        if (server.state() != EapAkaServer.State.CHALLENGE_SENT) {
            server.wipe();
            return Ts43Result.failure(evt.reqId(),
                    server.failureReason().orElse("cannot start an EAP-AKA session"));
        }
        // Identifier 1 is the first Challenge of the activity; a resync reuses it.
        EapPacket packet = server.buildChallenge(1, akaPrime);
        sessions.put(new EntitlementSessions.Session(evt.reqId(), evt.imsi(),
                normaliseMsisdn(evt.claimedMsisdn()), server, akaPrime, System.currentTimeMillis()));
        return Ts43Result.challenge(evt.reqId(), Base64.getEncoder().encodeToString(packet.encode()),
                "challenge issued");
    }

    // ---- hop 2: Response ---------------------------------------------------

    private Ts43Result respond(Ts43RequestEvent evt) {
        long now = System.currentTimeMillis();
        Optional<EntitlementSessions.Session> maybe = sessions.take(evt.reqId(), now);
        if (maybe.isEmpty()) {
            // No live session: unknown id, expired activity, or a replayed response.
            return Ts43Result.failure(evt.reqId(), "no outstanding challenge for this reqId");
        }
        EntitlementSessions.Session session = maybe.get();
        EapAkaServer server = session.server();
        try {
            if (!session.imsi().equals(evt.imsi() == null ? null : evt.imsi().trim())) {
                // The Response must be answered by the SIM the Challenge was built
                // for; a different IMSI on the same reqId is not a near-miss.
                return Ts43Result.failure(evt.reqId(), "IMSI does not match the challenge");
            }
            EapPacket packet = parse(evt.responseB64());
            if (packet == null) {
                return Ts43Result.failure(evt.reqId(), "response is not a decodable EAP packet");
            }
            EapAkaServer.Result result = server.acceptResponse(packet, session.akaPrime());
            if (result == EapAkaServer.Result.SEND_CHALLENGE) {
                return resync(evt, session, server);
            }
            if (result != EapAkaServer.Result.SUCCESS) {
                return Ts43Result.failure(evt.reqId(),
                        server.failureReason().orElse("EAP verification failed"));
            }
            // SIM possession proven. Now — and only now — confirm the number.
            String confirmed = confirmNumber(evt, session.imsi());
            if (confirmed == null) {
                return Ts43Result.failure(evt.reqId(),
                        "network did not confirm the claimed number");
            }
            String token = tokens.issueToken(confirmed, session.imsi(),
                    session.akaPrime() ? EntitlementTokenService.EAP_AKA_PRIME
                            : EntitlementTokenService.EAP_AKA);
            LOG.info("[ts43] EAP verified, number confirmed, token minted reqId={}", evt.reqId());
            return Ts43Result.success(evt.reqId(), token, config.tokenTtlSeconds());
        } finally {
            // Every exit path — success, refusal, malformed input — wipes the keys.
            server.wipe();
        }
    }

    /**
     * Resync: the peer sent {@code AT_AUTS}, so the old vector is spent. Fetch a fresh
     * one, hand it to the same session, and re-Challenge. The resync cap inside
     * {@link EapAkaServer} is what stops an AuC sequence desync from looping forever.
     */
    private Ts43Result resync(Ts43RequestEvent evt, EntitlementSessions.Session session,
                              EapAkaServer server) {
        byte[] auts = server.auts();
        byte[] rand = null;
        try {
            if (auts != null) {
                rand = java.util.Arrays.copyOf(auts, Math.min(8, auts.length));
            }
            AuthVector fresh = fetchVector(evt, true, rand, auts);
            if (fresh == null) {
                return Ts43Result.failure(evt.reqId(), "resynchronisation refused: no vector");
            }
            server.beginResync(fresh.xres(), fresh.rand(), fresh.autn());
            fresh.wipe();
            EapPacket packet = server.buildChallenge(1, session.akaPrime());
            sessions.put(new EntitlementSessions.Session(session.reqId(), session.imsi(),
                    session.claimedMsisdn(), server, session.akaPrime(),
                    System.currentTimeMillis()));
            return Ts43Result.challenge(evt.reqId(),
                    Base64.getEncoder().encodeToString(packet.encode()), "resynchronisation");
        } catch (RuntimeException e) {
            LOG.warn("[ts43] resync failed reqId={}", evt.reqId(), e);
            return Ts43Result.failure(evt.reqId(), "resynchronisation refused");
        }
    }

    // ---- network legs ------------------------------------------------------

    private AuthVector fetchVector(Ts43RequestEvent evt, boolean resync) {
        return fetchVector(evt, resync, null, null);
    }

    private AuthVector fetchVector(Ts43RequestEvent evt, boolean resync,
                                   byte[] rand, byte[] auts) {
        RaCommandPort port = authVectorRa;
        if (port == null) {
            LOG.warn("[ts43] no auth-vector RA bound — fail-closed");
            return null;
        }
        String scheme = AuthVector.EAP_AKA;
        CompletableFuture<AuthVector> reply = new CompletableFuture<>();
        try {
            if (resync && auts != null) {
                port.sendCommand(new ResyncVectorCommand(evt.reqId(), evt.imsi(), scheme,
                        rand, auts, reply));
            } else {
                port.sendCommand(new FetchVectorCommand(evt.reqId(), evt.imsi(), scheme, reply));
            }
            return reply.get(VECTOR_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // The RA aborts on its own budget; this covers a stalled backend and
            // still guarantees one dialog is not left open (H7).
            port.sendCommand(new AbortAuthVectorCommand(evt.reqId(), "ts43:" + evt.reqId()));
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            port.sendCommand(new AbortAuthVectorCommand(evt.reqId(), "ts43-interrupted"));
            return null;
        } catch (ExecutionException ee) {
            LOG.warn("[ts43] vector fetch refused reqId={}: {}", evt.reqId(),
                    ee.getCause() == null ? null : ee.getCause().getMessage());
            return null;
        }
    }

    /**
     * Confirm the claimed number against the network. Returns the E.164 the network
     * confirmed, or {@code null} when it would not.
     *
     * <p>Which question gets asked is the source's business: a discovery source
     * ({@code sh-udr}, {@code swx-sar}) is asked for the number from the IMSI, while
     * a number-driven source (MAP {@code SendIMSI}) is asked whether the claim holds.
     * The RA decides, and a source that cannot answer is simply not consulted.</p>
     */
    private String confirmNumber(Ts43RequestEvent evt, String imsi) {
        RaCommandPort port = bindingRa;
        if (port == null) {
            LOG.warn("[ts43] no subscriber-binding RA bound — fail-closed");
            return null;
        }
        // A null claim is passed through on purpose: a discovery source (sh-udr,
        // SWx/SAR) can answer it, a number-driven one (MAP SendIMSI) answers
        // unresolved, and the result decides.
        String claim = normaliseMsisdn(evt.claimedMsisdn());
        CompletableFuture<SubscriberBinding> reply = new CompletableFuture<>();
        port.sendCommand(new LookupBindingCommand(evt.reqId(), imsi, claim, reply));
        try {
            SubscriberBinding binding = reply.get(BINDING_BUDGET_MS, TimeUnit.MILLISECONDS);
            if (!binding.resolved()) {
                LOG.info("[ts43] number binding unresolved reqId={} source={}",
                        evt.reqId(), binding.source());
                return null;
            }
            if (binding.imsi() != null && !binding.imsi().equals(imsi)) {
                LOG.warn("[ts43] binding is for a different SIM — refusing reqId={}", evt.reqId());
                return null;
            }
            String msisdn = binding.msisdn();
            return msisdn == null || msisdn.isBlank() ? null : msisdn;
        } catch (TimeoutException te) {
            port.sendCommand(new AbortBindingCommand(evt.reqId(), "ts43:" + evt.reqId()));
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException ee) {
            return null;
        }
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * EAP identity: the IMSI in ASCII, per RFC 4187 §2.2 (an NAI of the form
     * {@code 0<IMSI>@wlan.mnc...mcc...3gppnetwork.org} is a valid alternative, but
     * the bare IMSI keeps the lab and the operator's AAA in agreement on the
     * {@code AT_MAC} coverage).
     */
    private static byte[] identity(String imsi) {
        return imsi.getBytes(StandardCharsets.US_ASCII);
    }

    private static EapPacket parse(String responseB64) {
        if (isBlank(responseB64)) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(responseB64.trim());
            return EapPacket.parse(raw, 0);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normaliseMsisdn(String msisdn) {
        if (isBlank(msisdn)) {
            return null;
        }
        String value = msisdn.trim();
        return value.startsWith("+") ? value : "+" + value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}