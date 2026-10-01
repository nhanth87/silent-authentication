/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.sbbs;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;

import et.restlink.sas.events.SubscriberQueryEvent;
import et.restlink.sas.events.SubscriberQueryEvent.Operation;
import et.restlink.sas.exposure.ExposureCoordinator;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.ras.binding.SubscriberBinding;
import et.restlink.sas.ras.binding.command.LookupBindingCommand;
import et.restlink.sas.ras.mapverifier.InMemoryMapVerifierBackend;
import et.restlink.sas.ras.mapverifier.command.AbortMapCommand;
import et.restlink.sas.ras.mapverifier.command.MapVerifyCommand;
import et.restlink.sas.ras.resolver.InMemoryResolverBackend;
import et.restlink.sas.ras.resolver.ResolverResourceAdaptor;
import et.restlink.sas.ras.resolver.command.ResolveCommand;
import et.restlink.sas.subscriber.ExposureApi;
import et.restlink.sas.subscriber.ExposureContext;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.dto.IpAddr;
import et.restlink.sas.subscriber.dto.MonitoringEventReport;
import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.OperatorTokenRedeemReq;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.SimChangeReq;
import et.restlink.sas.subscriber.dto.UeIdInfo;
import et.restlink.sas.subscriber.dto.UeIdReq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Subscriber Services: evidence or a contract problem, never a negative-but-successful answer. */
class SubscriberSbbTest {

    private static final String OBSERVED = "2026-10-01T06:00:00Z";

    private ExposureCoordinator coordinator;
    private final List<OutboundCommand> sent = new ArrayList<>();
    private SubscriberSbb.SimChangeSource simChange = msisdn -> Optional.empty();

    @BeforeEach
    void setUp() {
        coordinator = new ExposureCoordinator();
        sent.clear();
    }

    private SubscriberSbb sbb(boolean mapNeedsImsi) {
        return new SubscriberSbb(coordinator, new SubscriberSbb.Sources("LAB_RESOLVER", false,
                mapNeedsImsi, mapNeedsImsi ? "MAP_PSI" : "LAB_HLR", "LAB_HSS",
                msisdn -> simChange.latest(msisdn)), null);
    }

    private static void bind(SubscriberSbb sbb, String field, RaCommandPort port) throws Exception {
        Field f = SubscriberSbb.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(sbb, port);
    }

    @SuppressWarnings("unchecked")
    private <T> ExposureOutcome<T> run(SubscriberSbb sbb, Operation op, Object req, int budgetMs)
            throws Exception {
        String key = op + "-" + System.nanoTime();
        CompletableFuture<ExposureOutcome<?>> f = coordinator.register(key).future();
        sbb.onEvent(new SubscriberQueryEvent("act-" + key, key, op,
                new ExposureContext("req-00000001", budgetMs), req, "sas-camara"), null);
        return (ExposureOutcome<T>) f.get(5, TimeUnit.SECONDS);
    }

    private static UeIdReq ueId(String ip) {
        return new UeIdReq("sas-camara", new IpAddr(ip, null), 55555, OBSERVED, null);
    }

    private static MonitoringEventSubscription monitoring(String msisdn) {
        return new MonitoringEventSubscription(msisdn, ExposureApi.UE_REACHABILITY,
                List.of(ExposureApi.LOCATION_REPORTING), null, 1, true);
    }

    /** The real resolver RA over the lab backend. */
    private RaCommandPort labResolver() {
        InMemoryResolverBackend backend = new InMemoryResolverBackend();
        long ts = Instant.parse(OBSERVED).toEpochMilli();
        backend.seed("10.20.30.40", 55555, "+251911111111", "655010000000001", 30_000L);
        ResolverResourceAdaptor ra = new ResolverResourceAdaptor();
        ra.setBackend(backend);
        ra.raActive();
        return cmd -> {
            sent.add(cmd);
            ra.resolve((ResolveCommand) cmd);
        };
    }

    private RaCommandPort labMap() {
        InMemoryMapVerifierBackend backend = new InMemoryMapVerifierBackend();
        backend.seed("+251911111111", "655010000000001", true,
                System.currentTimeMillis() - 10L * 86_400_000L, "AA");
        return cmd -> {
            sent.add(cmd);
            if (cmd instanceof MapVerifyCommand m) {
                backend.verify(m.msisdn(), m.imsi(), m.accessTech(), System.currentTimeMillis())
                        .thenAccept(m.reply()::complete);
            }
        };
    }

    // ---- UE ID ---------------------------------------------------------------

    @Test
    void ueIdAnswersTheGpsiOfTheBinding() throws Exception {
        SubscriberSbb sbb = sbb(false);
        bind(sbb, "resolverRa", labResolver());
        ExposureOutcome<UeIdInfo> out = run(sbb, Operation.UE_ID, ueId("10.20.30.40"), 1000);
        assertTrue(out.ok(), String.valueOf(out.problem()));
        assertEquals("msisdn-251911111111", out.value().gpsi());
        assertEquals("LAB_RESOLVER", out.value().evidenceSource());
    }

    @Test
    void ueIdMissesMapToContractCauses() throws Exception {
        SubscriberSbb sbb = sbb(false);
        bind(sbb, "resolverRa", labResolver());
        assertEquals("NO_BINDING", run(sbb, Operation.UE_ID, ueId("10.99.99.99"), 1000).cause());
        for (var c : List.of(new Object[] {FallbackReason.AMBIGUOUS_BINDING, "AMBIGUOUS_BINDING"},
                new Object[] {FallbackReason.RESOLVER_TIMEOUT, "NETWORK_TIMEOUT"},
                new Object[] {FallbackReason.RESOLVER_ERROR, "NETWORK_ERROR"})) {
            bind(sbb, "resolverRa", cmd -> ((ResolveCommand) cmd).reply()
                    .complete(ResolverResult.miss((FallbackReason) c[0])));
            assertEquals(c[1], run(sbb, Operation.UE_ID, ueId("10.20.30.40"), 1000).cause());
        }
    }

    @Test
    void silentResolverIsATimeoutWithinTheBudget() throws Exception {
        SubscriberSbb sbb = sbb(false);
        bind(sbb, "resolverRa", cmd -> sent.add(cmd));
        long t0 = System.nanoTime();
        assertEquals("NETWORK_TIMEOUT", run(sbb, Operation.UE_ID, ueId("10.20.30.40"), 120).cause());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1000, "bounded by the budget");
    }

    @Test
    void budgetTooSmallSignalsNothing() throws Exception {
        SubscriberSbb sbb = sbb(false);
        bind(sbb, "resolverRa", labResolver());
        bind(sbb, "mapVerifierRa", labMap());
        assertEquals("BUDGET_EXHAUSTED", run(sbb, Operation.UE_ID, ueId("10.20.30.40"), 5).cause());
        assertEquals("BUDGET_EXHAUSTED",
                run(sbb, Operation.MONITORING, monitoring("251911111111"), 50).cause());
        assertTrue(sent.isEmpty(), "no command sent");
    }

    @Test
    void noRaBoundIsTransportUnavailable() throws Exception {
        assertEquals("TRANSPORT_UNAVAILABLE",
                run(sbb(false), Operation.UE_ID, ueId("10.20.30.40"), 1000).cause());
    }

    // ---- monitoring ----------------------------------------------------------

    @Test
    void monitoringReportsReachabilityAndLocationMetadataOnly() throws Exception {
        SubscriberSbb sbb = sbb(false);
        bind(sbb, "mapVerifierRa", labMap());
        ExposureOutcome<MonitoringEventReports> out =
                run(sbb, Operation.MONITORING, monitoring("251911111111"), 2500);
        assertTrue(out.ok(), String.valueOf(out.problem()));
        assertEquals("LAB_HLR", out.value().evidenceSource());
        MonitoringEventReport reach = out.value().monitoringEventReports().get(0);
        assertEquals(true, reach.ueReachable());
        assertNull(reach.locationInfo());
        MonitoringEventReport where = out.value().monitoringEventReports().get(1);
        assertEquals("636", where.plmnId().mcc());
        assertEquals(5, where.locationInfo().ageOfLocationInfo());
        assertNull(where.ueReachable());
    }

    @Test
    void unknownSubscriberIsANetworkErrorNotAnUnreachableAnswer() throws Exception {
        SubscriberSbb sbb = sbb(false);
        bind(sbb, "mapVerifierRa", labMap());
        assertEquals("NETWORK_ERROR", run(sbb, Operation.MONITORING, monitoring("251900000000"), 2500).cause());
    }

    @Test
    void liveMapAsksSendImsiFirstAndStopsWhenTheHlrDoesNotKnowTheNumber() throws Exception {
        SubscriberSbb sbb = sbb(true);
        bind(sbb, "bindingRa", cmd -> ((LookupBindingCommand) cmd).reply()
                .complete(SubscriberBinding.unresolved(null, "map-smi")));
        bind(sbb, "mapVerifierRa", labMap());
        assertEquals("SUBSCRIBER_NOT_FOUND",
                run(sbb, Operation.MONITORING, monitoring("251900000000"), 2500).cause());
        assertTrue(sent.isEmpty(), "no PSI without an IMSI");
    }

    @Test
    void liveMapUsesTheImsiTheHlrConfirmed() throws Exception {
        SubscriberSbb sbb = sbb(true);
        List<String> asked = new ArrayList<>();
        bind(sbb, "bindingRa", cmd -> {
            LookupBindingCommand l = (LookupBindingCommand) cmd;
            asked.add(l.claimedMsisdn());
            l.reply().complete(SubscriberBinding.resolved("655010000000001", l.claimedMsisdn(), "map-smi"));
        });
        bind(sbb, "mapVerifierRa", labMap());
        ExposureOutcome<MonitoringEventReports> out =
                run(sbb, Operation.MONITORING, monitoring("251911111111"), 2500);
        assertTrue(out.ok(), String.valueOf(out.problem()));
        assertEquals(List.of("+251911111111"), asked);
        assertEquals("655010000000001", ((MapVerifyCommand) sent.get(0)).imsi());
        assertEquals("MAP_PSI", out.value().evidenceSource());
    }

    @Test
    void silentVerifierIsATimeoutAndTheDialogIsAborted() throws Exception {
        SubscriberSbb sbb = sbb(false);
        bind(sbb, "mapVerifierRa", cmd -> sent.add(cmd));
        assertEquals("NETWORK_TIMEOUT", run(sbb, Operation.MONITORING, monitoring("251911111111"), 300).cause());
        assertTrue(sent.stream().anyMatch(c -> c instanceof AbortMapCommand), "dialog aborted");
    }

    // ---- SIM change / redeem -------------------------------------------------

    @Test
    void simChangeEvidenceOrNoEvidence() throws Exception {
        SubscriberSbb sbb = sbb(false);
        assertEquals("NO_EVIDENCE", run(sbb, Operation.SIM_CHANGE,
                new SimChangeReq("sas-camara", "251911111111"), 1000).cause());
        Instant at = Instant.parse("2026-09-01T10:00:00Z");
        simChange = msisdn -> "+251911111111".equals(msisdn)
                ? Optional.of(new SubscriberSbb.SimChange(at, "LAB_HLR")) : Optional.empty();
        ExposureOutcome<SimChangeInfo> out = run(sbb, Operation.SIM_CHANGE,
                new SimChangeReq("sas-camara", "251911111111"), 1000);
        assertTrue(out.ok());
        assertEquals("2026-09-01T10:00:00Z", out.value().latestSimChange());
        assertEquals("LAB_HLR", out.value().evidenceSource());
    }

    @Test
    void redeemWithoutATokenServiceIsRefused() throws Exception {
        assertEquals("INVALID_TOKEN", run(sbb(false), Operation.REDEEM,
                new OperatorTokenRedeemReq("sas-camara", "eyJlcGsiOnsia3R5IjoiRUMi", "bank"), 1000).cause());
    }
}
