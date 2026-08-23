/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.ras.resolver;

import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.DiameterLink;
import com.mobius.software.telco.protocols.diameter.DiameterSession;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.app.ClientCCSession;
import com.mobius.software.telco.protocols.diameter.app.gx.ClientListener;
import com.mobius.software.telco.protocols.diameter.app.gx.GxClientSession;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.gx.CreditControlAnswer;
import com.mobius.software.telco.protocols.diameter.commands.gx.CreditControlRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.impl.app.gx.GxProviderImpl;
import com.mobius.software.telco.protocols.diameter.primitives.DiameterUnknownAvp;
import com.mobius.software.telco.protocols.diameter.primitives.DiameterAvpKey;
import com.mobius.software.telco.protocols.diameter.primitives.creditcontrol.CcRequestTypeEnum;

import com.mobius.software.common.dal.timers.WorkerPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.cluster.IDGenerator;
import org.restcomm.cluster.UUIDGenerator;

import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;

import io.netty.buffer.ByteBuf;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * PCRF Sd/Gx binding source (P2 item #1 remainder) — third resolver transport
 * beside PGW RADIUS accounting and CGNAT logs.
 *
 * <p><b>Request choice.</b> Gx has no AA-Request: TS 29.212 carries PCEF →
 * PCRF policy queries in Credit-Control-Request (Command-Code 272,
 * Auth-Application-Id 16777238 = {@link ApplicationIDs#GX}). This backend
 * sends a CCR-I (CC-Request-Type INITIAL_REQUEST) whose only payload beyond
 * the mandated AVPs is Framed-IP-Address (8) set to the queried IP; the lab
 * PCRF answers CCA + Subscription-Id. The answer AVP is a documented lab
 * deviation from TS 29.212 §5.6.2 (Subscription-Id is CCR-side there): it
 * rides as an unknown AVP (code 443, mandatory bit clear) so both corsac
 * stacks tolerate it without configuration.</p>
 *
 * <p><b>Resolution semantics (fail-closed).</b> Result-Code 2001 with exactly
 * one END_USER_E164 Subscription-Id yields a bound result (an END_USER_IMSI
 * entry is captured as IMSI when present); zero subscriptions or any
 * non-success Result-Code yield {@link FallbackReason#NO_BINDING}; more than
 * one distinct E.164 yields {@link FallbackReason#AMBIGUOUS_BINDING};
 * timeout ({@code timeoutMs}, default 500 ms), send error, malformed answer
 * or a stopped stack also yield NO_BINDING — never a soft pass.</p>
 *
 * <p><b>Freshness.</b> A live probe is point-in-time by construction, so
 * successful probes are cached per IP ({@code staleAfterMs}, default 60 s)
 * and repeated resolves within the window do not re-probe; concurrent probes
 * for one IP single-flight through one exchange. Probes only certify
 * "now": a requested timestamp outside ±{@code staleAfterMs} of the current
 * time fails closed with NO_BINDING because live Gx evidence cannot attest a
 * distant instant. Gx binds per IP (no port dimension): {@code srcPort} is
 * accepted for {@link ResolverBackend} compatibility and ignored.</p>
 *
 * <p><b>Identity normalization.</b> MSISDN digits are normalized to canonical
 * {@code +E.164} (leading {@code +} re-added once) to match the format the
 * other resolver backends return.</p>
 */
public final class PcrfSdResolverBackend implements ResolverBackend {

    private static final Logger LOG = LogManager.getLogger(PcrfSdResolverBackend.class);
    private static final String LINK_ID = "sd-sas";

    /** Default per-exchange probe budget. */
    public static final int DEFAULT_TIMEOUT_MS = 500;
    /** Default cache/staleness window. */
    public static final long DEFAULT_STALE_MS = 60_000L;

    private static final long RESULT_SUCCESS = 2001L;
    static final long AVP_SUBSCRIPTION_ID = 443L;
    static final long AVP_SUBSCRIPTION_ID_TYPE = 450L;
    static final long AVP_SUBSCRIPTION_ID_DATA = 444L;
    static final int SUB_ID_TYPE_E164 = 0;
    static final int SUB_ID_TYPE_IMSI = 1;

    private final String peerHost;
    private final int peerPort;
    private final boolean sctp;
    private final int timeoutMs;
    private final long staleAfterMs;
    private volatile LongSupplier clock = System::currentTimeMillis;

    private volatile DiameterStack stack;
    private volatile GxProviderImpl provider;
    private volatile WorkerPool workerPool;
    private final IDGenerator<?> generator = new UUIDGenerator();
    private final GxExchangeCorrelator correlator = new GxExchangeCorrelator();
    private final BindingCache cache = new BindingCache();
    private final ConcurrentHashMap<String, CompletableFuture<ResolverResult>> inflight =
            new ConcurrentHashMap<>();

    /**
     * @param peerHost   PCRF Diameter host or address to dial.
     * @param peerPort   PCRF Diameter port.
     * @param timeoutMs  per-exchange probe budget (fail-closed on expiry).
     * @param staleAfterMs cached-binding freshness window (also bounds the
     *                     point-in-time tolerance around {@code tsEpochMs}).
     */
    public PcrfSdResolverBackend(String peerHost, int peerPort, int timeoutMs, long staleAfterMs) {
        this(peerHost, peerPort, true, timeoutMs, staleAfterMs);
    }

    public PcrfSdResolverBackend(String peerHost, int peerPort) {
        this(peerHost, peerPort, DEFAULT_TIMEOUT_MS, DEFAULT_STALE_MS);
    }

    /** Loopback/lab variant choosing transport; TCP keeps tests off SCTP. */
    public PcrfSdResolverBackend(String peerHost, int peerPort, boolean sctp,
                                 int timeoutMs, long staleAfterMs) {
        this.peerHost = peerHost;
        this.peerPort = peerPort;
        this.sctp = sctp;
        this.timeoutMs = timeoutMs;
        this.staleAfterMs = staleAfterMs;
    }

    /** Test hook: deterministic clock for staleness windows. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** Dial the PCRF link and register the Gx client listener. */
    public synchronized void start() throws Exception {
        if (stack != null) {
            return;
        }
        workerPool = new WorkerPool("SD-SAS");
        workerPool.start(2);
        stack = new DiameterStackImpl(getClass().getClassLoader(), generator, workerPool, 2,
                localHost(), "SAS SD", 0L, 10L,
                10_000L, 2_000L, 5_000L, 5_000L, 5_000L);
        stack.getNetworkManager().addLink(LINK_ID,
                InetAddress.getByName(peerHost), peerPort,
                InetAddress.getByName("0.0.0.0"), 0,
                false, sctp, localHost(), localRealm(),
                destinationHost(), destinationRealm(), false);
        Package commandsPkg = loadPackage(
                "com.mobius.software.telco.protocols.diameter.commands.gx",
                "CreditControlRequest");
        Package implPkg = loadPackage(
                "com.mobius.software.telco.protocols.diameter.impl.commands.gx",
                "CreditControlRequestImpl");
        stack.getNetworkManager().registerApplication(LINK_ID, List.of(), List.of((long) ApplicationIDs.GX),
                List.of(), commandsPkg, implPkg);
        stack.getNetworkManager().startLink(LINK_ID);
        provider = (GxProviderImpl) stack.getProvider((long) ApplicationIDs.GX, commandsPkg);
        provider.setClientListener(generator.generateID(), new ClientListener() {
            @Override
            public void onInitialAnswer(CreditControlAnswer answer,
                    ClientCCSession<CreditControlRequest,
                            com.mobius.software.telco.protocols.diameter.commands.gx.ReAuthAnswer,
                            com.mobius.software.telco.protocols.diameter.commands.gx.AbortSessionAnswer,
                            com.mobius.software.telco.protocols.diameter.commands.gx.SessionTerminationRequest>
                            session,
                    String linkID, AsyncCallback callback) {
                handleAnswer(answer, session);
            }

            @Override
            public void onReauthRequest(
                    com.mobius.software.telco.protocols.diameter.commands.gx.ReAuthRequest request,
                    ClientCCSession<CreditControlRequest,
                            com.mobius.software.telco.protocols.diameter.commands.gx.ReAuthAnswer,
                            com.mobius.software.telco.protocols.diameter.commands.gx.AbortSessionAnswer,
                            com.mobius.software.telco.protocols.diameter.commands.gx.SessionTerminationRequest>
                            session,
                    String linkID, AsyncCallback callback) {
                // server-initiated re-auth never expected on this probe link
            }

            @Override
            public void onSessionTerminationAnswer(
                    com.mobius.software.telco.protocols.diameter.commands.gx.SessionTerminationAnswer answer,
                    ClientCCSession<CreditControlRequest,
                            com.mobius.software.telco.protocols.diameter.commands.gx.ReAuthAnswer,
                            com.mobius.software.telco.protocols.diameter.commands.gx.AbortSessionAnswer,
                            com.mobius.software.telco.protocols.diameter.commands.gx.SessionTerminationRequest>
                            session,
                    String linkID, AsyncCallback callback) {
                // no probe session is ever terminated actively
            }

            @Override
            public void onAbortSessionRequest(
                    com.mobius.software.telco.protocols.diameter.commands.gx.AbortSessionRequest request,
                    ClientCCSession<CreditControlRequest,
                            com.mobius.software.telco.protocols.diameter.commands.gx.ReAuthAnswer,
                            com.mobius.software.telco.protocols.diameter.commands.gx.AbortSessionAnswer,
                            com.mobius.software.telco.protocols.diameter.commands.gx.SessionTerminationRequest>
                            session,
                    String linkID, AsyncCallback callback) {
                LOG.warn("Sd ASR for unknown lab session {} — ignored", session.getID());
            }

            @Override
            public void onTimeout(DiameterRequest request, DiameterSession session) {
                String sessionId = sessionId(request);
                LOG.warn("SD {} timeout session={}",
                        request == null ? "exchange" : request.getClass().getSimpleName(), sessionId);
                correlator.fail(sessionId, new TimeoutException("Gx stack timeout"));
            }

            @Override
            public void onIdleTimeout(DiameterSession session) {
                // stateless probing — nothing to clean up
            }
        });
        LOG.info("PCRF Sd/Gx resolver dialing {}:{} transport={} timeout={}ms stale={}ms",
                peerHost, peerPort, sctp ? "sctp" : "tcp", timeoutMs, staleAfterMs);
    }

    /** Stop the stack cleanly, failing every pending probe. */
    public synchronized void stop() {
        correlator.failAll(new TimeoutException("Sd transport stopped"));
        inflight.clear();
        cache.clear();
        DiameterStack oldStack = stack;
        stack = null;
        provider = null;
        if (oldStack != null) {
            try {
                oldStack.stop();
            } catch (Exception e) {
                LOG.warn("Sd stop", e);
            }
        }
        WorkerPool oldPool = workerPool;
        workerPool = null;
        if (oldPool != null) {
            oldPool.stop();
        }
        LOG.info("PCRF Sd/Gx resolver stopped");
    }

    public boolean isRunning() {
        return stack != null && provider != null;
    }

    /** Cached bindings currently held (admin/dashboard surface). */
    int cachedBindings() {
        return cache.size();
    }

    @Override
    public CompletableFuture<ResolverResult> resolve(String srcIp, int srcPort, long tsEpochMs) {
        long now = clock.getAsLong();
        BindingCache.Entry fresh = cache.fresh(srcIp, now, staleAfterMs);
        if (fresh != null) {
            return CompletableFuture.completedFuture(ResolverResult.bound(
                    fresh.msisdn(), fresh.imsi(), Math.max(0L, now - fresh.fetchedAtMs())));
        }
        if (Math.abs(now - tsEpochMs) > staleAfterMs) {
            // live Gx evidence cannot attest a distant instant — fail closed
            return CompletableFuture.completedFuture(ResolverResult.miss(FallbackReason.NO_BINDING));
        }
        if (!isRunning()) {
            return CompletableFuture.completedFuture(ResolverResult.miss(FallbackReason.NO_BINDING));
        }
        CompletableFuture<ResolverResult> out = new CompletableFuture<>();
        CompletableFuture<ResolverResult> probe =
                inflight.computeIfAbsent(srcIp, ip -> startProbe(ip));
        probe.whenComplete((result, throwable) ->
                out.complete(result == null ? ResolverResult.miss(FallbackReason.NO_BINDING) : result));
        return out;
    }

    /** Single-flight per IP: one live exchange, shared by all waiters. */
    private CompletableFuture<ResolverResult> startProbe(String ip) {
        CompletableFuture<ResolverResult> probe = new CompletableFuture<>();
        try {
            DiameterLink link = stack.getNetworkManager().getLink(LINK_ID);
            CreditControlRequest ccr = provider.getMessageFactory().createCreditControlRequest(
                    link.getLocalHost(), link.getLocalRealm(),
                    link.getDestinationHost(), link.getDestinationRealm(),
                    CcRequestTypeEnum.INITIAL_REQUEST, 0L);
            ccr.setFramedIPAddress(inet4(ip));
            GxClientSession session =
                    (GxClientSession) provider.getSessionFactory().createClientSession(ccr);
            String sessionId = sessionId(ccr);
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalStateException("Gx request without Session-Id");
            }
            CompletableFuture<CreditControlAnswer> stage = correlator.register(sessionId);
            stage.whenComplete((answer, throwable) -> correlator.remove(sessionId));
            stage.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            stage.whenComplete((answer, throwable) -> {
                ResolverResult result = evaluate(answer, throwable);
                if (result.found()) {
                    cache.put(ip, result.msisdn(), result.imsi(), clock.getAsLong());
                }
                probe.complete(result);
                inflight.remove(ip, probe);
            });
            session.sendInitialRequest(ccr, new AsyncCallback() {
                @Override
                public void onSuccess() {
                    correlator.bindHopByHop(sessionId, ccr.getHopByHopIdentifier());
                }

                @Override
                public void onError(DiameterException ex) {
                    LOG.warn("Sd CCR rejected session={} ip={}", sessionId, ip, ex);
                    stage.completeExceptionally(ex);
                }
            });
        } catch (Exception e) {
            LOG.warn("Sd probe failed for {} — NO_BINDING (fail-closed)", ip, e);
            probe.complete(ResolverResult.miss(FallbackReason.NO_BINDING));
            inflight.remove(ip, probe);
        }
        return probe;
    }

    /** Fail-closed evidence evaluation of one CCA. */
    static ResolverResult evaluate(CreditControlAnswer answer, Throwable error) {
        if (error != null || answer == null) {
            if (error != null) {
                LOG.debug("Sd probe error — NO_BINDING (fail-closed)", unwrap(error));
            }
            return ResolverResult.miss(FallbackReason.NO_BINDING);
        }
        long resultCode = answer.getResultCode() == null ? -1L : answer.getResultCode();
        if (resultCode != RESULT_SUCCESS) {
            LOG.debug("Sd CCA result-code={} — NO_BINDING (fail-closed)", resultCode);
            return ResolverResult.miss(FallbackReason.NO_BINDING);
        }
        List<DiameterUnknownAvp> raw = optionalAvps(answer, AVP_SUBSCRIPTION_ID);
        Subscriptions subs = parseSubscriptionIds(raw);
        if (subs.e164().size() > 1) {
            return ResolverResult.miss(FallbackReason.AMBIGUOUS_BINDING);
        }
        if (subs.e164().isEmpty()) {
            return ResolverResult.miss(FallbackReason.NO_BINDING);
        }
        String msisdn = subs.e164().iterator().next();
        return ResolverResult.bound(msisdn, subs.imsi(), 0L);
    }

    /** Correlated answer dispatch — exactly one pending exchange is completed. */
    private void handleAnswer(CreditControlAnswer answer,
            ClientCCSession<CreditControlRequest, ?, ?, ?> session) {
        String sessionId = sessionId(answer);
        if ((sessionId == null || sessionId.isBlank()) && session != null) {
            sessionId = session.getID();
        }
        if (correlator.complete(sessionId, answer)) {
            return;
        }
        Long hopByHopId = answer == null ? null : answer.getHopByHopIdentifier();
        if (correlator.completeByHopByHop(hopByHopId, answer)) {
            LOG.debug("Sd answer matched by Hop-by-Hop Id {}", hopByHopId);
            return;
        }
        LOG.warn("Sd unmatched answer session={} hop={} — dropped, fail-closed",
                sessionId, hopByHopId);
    }

    /**
     * Per-request correlation of Gx answers (RFC 6733 §8.1 Session-Id, §8.2
     * Hop-by-Hop fallback), mirroring the S6a verifier correlator.
     */
    static final class GxExchangeCorrelator {

        private static final class Pending {
            private final CompletableFuture<CreditControlAnswer> future;
            private volatile Long hopByHopId;

            private Pending(CompletableFuture<CreditControlAnswer> future) {
                this.future = future;
            }
        }

        private final ConcurrentHashMap<String, Pending> bySessionId = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, String> sessionByHopByHopId = new ConcurrentHashMap<>();

        CompletableFuture<CreditControlAnswer> register(String sessionId) {
            CompletableFuture<CreditControlAnswer> future = new CompletableFuture<>();
            bySessionId.put(sessionId, new Pending(future));
            return future;
        }

        void bindHopByHop(String sessionId, Long hopByHopId) {
            if (hopByHopId == null) {
                return;
            }
            Pending pending = bySessionId.get(sessionId);
            if (pending != null) {
                pending.hopByHopId = hopByHopId;
                sessionByHopByHopId.put(hopByHopId, sessionId);
            }
        }

        boolean complete(String sessionId, CreditControlAnswer answer) {
            if (sessionId == null) {
                return false;
            }
            Pending pending = bySessionId.remove(sessionId);
            if (pending == null) {
                return false;
            }
            unbind(pending);
            return pending.future.complete(answer);
        }

        boolean completeByHopByHop(Long hopByHopId, CreditControlAnswer answer) {
            if (hopByHopId == null) {
                return false;
            }
            String sessionId = sessionByHopByHopId.remove(hopByHopId);
            return sessionId != null && complete(sessionId, answer);
        }

        boolean fail(String sessionId, Throwable cause) {
            if (sessionId == null) {
                return false;
            }
            Pending pending = bySessionId.remove(sessionId);
            if (pending == null) {
                return false;
            }
            unbind(pending);
            return pending.future.completeExceptionally(cause);
        }

        void failAll(Throwable cause) {
            for (String sessionId : bySessionId.keySet()) {
                fail(sessionId, cause);
            }
        }

        /** Drop an exchange without completing it (budget enforced via orTimeout). */
        void remove(String sessionId) {
            Pending pending = bySessionId.remove(sessionId);
            if (pending != null) {
                unbind(pending);
            }
        }

        int size() {
            return bySessionId.size();
        }

        private void unbind(Pending pending) {
            Long hop = pending.hopByHopId;
            if (hop != null) {
                sessionByHopByHopId.remove(hop);
            }
        }
    }

    /** ip → last successful probe, expiring after the staleness window. */
    static final class BindingCache {

        record Entry(String msisdn, String imsi, long fetchedAtMs) {}

        private final ConcurrentHashMap<String, Entry> byIp = new ConcurrentHashMap<>();

        Entry fresh(String ip, long nowMs, long staleAfterMs) {
            Entry entry = byIp.get(ip);
            if (entry == null) {
                return null;
            }
            if (nowMs - entry.fetchedAtMs() > staleAfterMs) {
                return null;
            }
            return entry;
        }

        void put(String ip, String msisdn, String imsi, long nowMs) {
            byIp.put(ip, new Entry(msisdn, imsi, nowMs));
        }

        int size() {
            return byIp.size();
        }

        void clear() {
            byIp.clear();
        }
    }

    /** Decoded Subscription-Id set: normalized E.164s + first IMSI seen. */
    record Subscriptions(Set<String> e164, String imsi) {}

    /**
     * Static evidence parser: decodes raw unknown Subscription-Id AVPs
     * (grouped value bytes: { Subscription-Id-Type 450, Subscription-Id-Data
     * 444 }) into normalized identities. Malformed entries are skipped;
     * duplicate E.164s collapse into one.
     */
    static Subscriptions parseSubscriptionIds(List<DiameterUnknownAvp> raw) {
        Set<String> e164 = new LinkedHashSet<>();
        String imsi = null;
        if (raw != null) {
            for (DiameterUnknownAvp avp : raw) {
                if (avp == null || avp.getAvpCode() == null
                        || avp.getAvpCode() != AVP_SUBSCRIPTION_ID) {
                    continue;
                }
                ByteBuf value = avp.getValue();
                if (value == null) {
                    continue;
                }
                byte[] grouped = new byte[value.readableBytes()];
                value.getBytes(value.readerIndex(), grouped);
                Integer type = nestedInt(grouped, AVP_SUBSCRIPTION_ID_TYPE);
                String data = nestedString(grouped, AVP_SUBSCRIPTION_ID_DATA);
                if (type == null || data == null || data.isBlank()) {
                    continue;
                }
                if (type == SUB_ID_TYPE_E164) {
                    String normalized = normalizeE164(data);
                    if (normalized != null) {
                        e164.add(normalized);
                    }
                } else if (type == SUB_ID_TYPE_IMSI && imsi == null) {
                    imsi = data.trim();
                }
            }
        }
        return new Subscriptions(e164, imsi);
    }

    /** Canonical {@code +E.164}: digits only, single leading {@code +}. Null when empty. */
    static String normalizeE164(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : "+" + digits;
    }

    private record Nested(int code, byte[] data) {}

    private static List<Nested> nestedAvps(byte[] grouped) {
        List<Nested> out = new ArrayList<>();
        int offset = 0;
        while (offset + 8 <= grouped.length) {
            int code = ((grouped[offset] & 0xff) << 24) | ((grouped[offset + 1] & 0xff) << 16)
                    | ((grouped[offset + 2] & 0xff) << 8) | (grouped[offset + 3] & 0xff);
            boolean vendorBit = (grouped[offset + 4] & 0x80) != 0;
            int length = ((grouped[offset + 5] & 0xff) << 16) | ((grouped[offset + 6] & 0xff) << 8)
                    | (grouped[offset + 7] & 0xff);
            int headerLen = vendorBit ? 12 : 8;
            if (length < headerLen || offset + length > grouped.length) {
                break; // malformed tail — keep what parsed so far
            }
            out.add(new Nested(code,
                    java.util.Arrays.copyOfRange(grouped, offset + headerLen, offset + length)));
            offset += length + ((4 - (length % 4)) % 4);
        }
        return out;
    }

    private static Integer nestedInt(byte[] grouped, long code) {
        for (Nested nested : nestedAvps(grouped)) {
            if (nested.code() == code && nested.data().length >= 4) {
                return ((nested.data()[0] & 0xff) << 24) | ((nested.data()[1] & 0xff) << 16)
                        | ((nested.data()[2] & 0xff) << 8) | (nested.data()[3] & 0xff);
            }
        }
        return null;
    }

    private static String nestedString(byte[] grouped, long code) {
        for (Nested nested : nestedAvps(grouped)) {
            if (nested.code() == code) {
                return new String(nested.data(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        }
        return null;
    }

    private static List<DiameterUnknownAvp> optionalAvps(CreditControlAnswer answer, long avpCode) {
        var raw = answer.getOptionalAvps(new DiameterAvpKey(avpCode));
        return raw == null ? List.of() : raw;
    }

    private static Inet4Address inet4(String dottedQuad) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(dottedQuad);
        if (!(address instanceof Inet4Address v4)) {
            throw new UnknownHostException("not an IPv4 literal: " + dottedQuad);
        }
        return v4;
    }

    private static String localHost() {
        return "sas.restlink.et";
    }

    private static String localRealm() {
        return "restlink.et";
    }

    private static String destinationHost() {
        return "pcrf.restlink.et";
    }

    private static String destinationRealm() {
        return "restlink.et";
    }

    /**
     * Package lookup with forced anchor-class loading — corsac resolves
     * command packages reflectively and {@link Package#getPackage(String)}
     * returns null until at least one class of the package is loaded.
     */
    private static Package loadPackage(String fqn, String anchorClass) throws Exception {
        Class.forName(fqn + "." + anchorClass, true,
                PcrfSdResolverBackend.class.getClassLoader());
        Package pkg = Package.getPackage(fqn);
        if (pkg == null) {
            throw new IllegalStateException("package not loaded: " + fqn);
        }
        return pkg;
    }

    private static String sessionId(DiameterMessage message) {
        if (message == null) {
            return null;
        }
        try {
            return message.getSessionId();
        } catch (DiameterException e) {
            return null;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable.getCause() != null ? throwable.getCause() : throwable;
    }
}
