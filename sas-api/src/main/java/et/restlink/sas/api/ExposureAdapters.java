/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.api;

import et.restlink.sas.id.PhoneNumberNormalizer;
import et.restlink.sas.model.FallbackReason;
import et.restlink.sas.model.LocationEvidence;
import et.restlink.sas.model.ResolverResult;
import et.restlink.sas.model.VerificationEvidence;
import et.restlink.sas.ras.resolver.ResolverBackend;
import et.restlink.sas.subscriber.ExposureApi;
import et.restlink.sas.subscriber.ExposureCause;
import et.restlink.sas.subscriber.ExposureContext;
import et.restlink.sas.subscriber.ExposureOutcome;
import et.restlink.sas.subscriber.SubscriberService;
import et.restlink.sas.subscriber.dto.IpAddr;
import et.restlink.sas.subscriber.dto.MonitoringEventReport;
import et.restlink.sas.subscriber.dto.MonitoringEventReports;
import et.restlink.sas.subscriber.dto.MonitoringEventSubscription;
import et.restlink.sas.subscriber.dto.SimChangeInfo;
import et.restlink.sas.subscriber.dto.UeIdInfo;
import et.restlink.sas.subscriber.dto.UeIdReq;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The one place the CAMARA gateway turns exposure answers into its own verification
 * model (layered ADR §7.3). Resolver and verifier stages, the OAuth network anchor and
 * SIM Swap all go through here, so a cause is mapped the same way everywhere and nothing
 * but evidence ever counts in the subscriber's favour.
 */
public final class ExposureAdapters {

    /** SIM change younger than this is a swap suspect (same window the verifiers used). */
    public static final Duration SWAP_COOLDOWN = Duration.ofHours(24);

    /** Resolver stage: what the gateway waits, and the budget it hands the ECS. */
    public static final int UE_ID_BUDGET_MS = 400;
    /** Verifier stage (monitoring ∥ SIM change). */
    public static final int EVIDENCE_BUDGET_MS = 2200;

    /**
     * CAMARA policy on location evidence metadata (ADR R8): the subscriber counts as
     * plausibly located when served by the home PLMN with a location update no older
     * than {@code maxAgeMinutes}.
     */
    public record LocationPolicy(String homeMcc, String homeMnc, int maxAgeMinutes) {

        public static LocationPolicy of(String homePlmn, int maxAgeMinutes) {
            String v = homePlmn == null ? "" : homePlmn.trim();
            if (!v.matches("[0-9]{5,6}")) {
                throw new IllegalArgumentException("home PLMN must be MCC+MNC digits, e.g. 63601");
            }
            return new LocationPolicy(v.substring(0, 3), v.substring(3), maxAgeMinutes);
        }

        boolean plausible(MonitoringEventReport where) {
            return where != null && where.plmnId() != null && where.locationInfo() != null
                    && homeMcc.equals(where.plmnId().mcc()) && homeMnc.equals(where.plmnId().mnc())
                    && where.locationInfo().ageOfLocationInfo() != null
                    && where.locationInfo().ageOfLocationInfo() <= maxAgeMinutes;
        }
    }

    private ExposureAdapters() {
    }

    public static ExposureContext context(String prefix, int budgetMs) {
        return new ExposureContext(prefix + "-" + UUID.randomUUID(), budgetMs);
    }

    // ---- resolver ------------------------------------------------------------

    public static UeIdReq ueIdRequest(String afId, String srcIp, int srcPort, long tsEpochMs) {
        String ip = srcIp == null ? "" : srcIp.trim();
        IpAddr addr = ip.indexOf(':') >= 0 ? new IpAddr(null, ip) : new IpAddr(ip, null);
        return new UeIdReq(afId, addr, srcPort, Instant.ofEpochMilli(tsEpochMs).toString(), null);
    }

    /** UE ID answer → the resolver result the FSM already understands. IMSI stays unknown. */
    public static ResolverResult resolverResult(ExposureOutcome<UeIdInfo> outcome, long tsEpochMs) {
        if (outcome == null) {
            return ResolverResult.miss(FallbackReason.RESOLVER_ERROR);
        }
        if (!outcome.ok()) {
            return ResolverResult.miss(switch (cause(outcome)) {
                case NO_BINDING -> FallbackReason.NO_BINDING;
                case AMBIGUOUS_BINDING -> FallbackReason.AMBIGUOUS_BINDING;
                case NETWORK_TIMEOUT, BUDGET_EXHAUSTED -> FallbackReason.RESOLVER_TIMEOUT;
                default -> FallbackReason.RESOLVER_ERROR;
            });
        }
        String msisdn = PhoneNumberNormalizer.toE164(outcome.value().gpsi()).orElse(null);
        if (msisdn == null) {
            return ResolverResult.miss(FallbackReason.RESOLVER_ERROR);
        }
        long age = 0L;
        try {
            age = Math.max(0L, tsEpochMs - Instant.parse(outcome.value().bindingObservedAt()).toEpochMilli());
        } catch (DateTimeParseException | NullPointerException e) {
            // age unknown: scored as fresh by the policy's own cap
        }
        return ResolverResult.bound(msisdn, null, age);
    }

    /** A {@link ResolverBackend} over the exposure UE ID operation (OAuth network anchor). */
    public static ResolverBackend resolver(SubscriberService service, String afId) {
        return (ip, port, ts) -> {
            if (service == null) {
                return CompletableFuture.completedFuture(ResolverResult.miss(FallbackReason.RESOLVER_ERROR));
            }
            return service.retrieveUeId(context("ueid", UE_ID_BUDGET_MS), ueIdRequest(afId, ip, port, ts))
                    .thenApply(o -> resolverResult(o, ts))
                    .exceptionally(e -> ResolverResult.miss(FallbackReason.RESOLVER_ERROR));
        };
    }

    // ---- verifier ------------------------------------------------------------

    public static MonitoringEventSubscription monitoringRequest(String msisdnE164) {
        return new MonitoringEventSubscription(digits(msisdnE164), ExposureApi.UE_REACHABILITY,
                List.of(ExposureApi.LOCATION_REPORTING), ExposureApi.LAST_KNOWN_LOCATION, 1, true);
    }

    /**
     * Monitoring + SIM change → verifier evidence. A monitoring problem is a verifier
     * failure; a missing or unusable SIM-change answer means "not proven unswapped".
     */
    public static VerificationEvidence evidence(ExposureOutcome<MonitoringEventReports> monitoring,
                                                ExposureOutcome<SimChangeInfo> simChange,
                                                Instant now, LocationPolicy policy) {
        if (monitoring == null || !monitoring.ok()) {
            ExposureCause c = monitoring == null ? ExposureCause.INTERNAL_ERROR : cause(monitoring);
            return VerificationEvidence.fail(c == ExposureCause.NETWORK_TIMEOUT
                    || c == ExposureCause.BUDGET_EXHAUSTED
                    ? FallbackReason.VERIFY_TIMEOUT : FallbackReason.VERIFY_ERROR, "EXPOSURE");
        }
        MonitoringEventReport reach = report(monitoring.value(), ExposureApi.UE_REACHABILITY);
        MonitoringEventReport where = report(monitoring.value(), ExposureApi.LOCATION_REPORTING);
        boolean reachable = reach != null && Boolean.TRUE.equals(reach.ueReachable());
        boolean located = policy != null && policy.plausible(where);
        boolean notSwapped = false;
        String simSource = "NO_SIM_EVIDENCE";
        if (simChange != null && simChange.ok()) {
            simSource = simChange.value().evidenceSource();
            try {
                Instant at = Instant.parse(simChange.value().latestSimChange());
                notSwapped = !at.isAfter(now.minus(SWAP_COOLDOWN));
            } catch (DateTimeParseException e) {
                notSwapped = false;
            }
        }
        LocationEvidence metadata = where == null ? null : new LocationEvidence(
                where.locationInfo() == null ? null : where.locationInfo().ageOfLocationInfo(),
                where.plmnId() == null ? null : where.plmnId().mcc(),
                where.plmnId() == null ? null : where.plmnId().mnc());
        // protocol = the internal evidence sources, for the CDR only (ADR R4)
        return VerificationEvidence.ok(reachable, notSwapped, located,
                monitoring.value().evidenceSource() + "+" + simSource).withLocation(metadata);
    }

    private static MonitoringEventReport report(MonitoringEventReports reports, String type) {
        if (reports.monitoringEventReports() == null) {
            return null;
        }
        return reports.monitoringEventReports().stream()
                .filter(r -> type.equals(r.monitoringType())).findFirst().orElse(null);
    }

    // ---- SIM swap ------------------------------------------------------------

    /** SIM change answer → the instant SIM Swap reports; null = no evidence (fail closed). */
    public static Instant latestSimChange(ExposureOutcome<SimChangeInfo> outcome) {
        if (outcome == null || !outcome.ok()) {
            return null;
        }
        try {
            return Instant.parse(outcome.value().latestSimChange());
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    // ---- helpers -------------------------------------------------------------

    public static String digits(String number) {
        return PhoneNumberNormalizer.toDigits(number).orElseThrow(
                () -> new IllegalArgumentException("not a phone number"));
    }

    static ExposureCause cause(ExposureOutcome<?> outcome) {
        try {
            return ExposureCause.valueOf(outcome.cause());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ExposureCause.INTERNAL_ERROR;
        }
    }
}
