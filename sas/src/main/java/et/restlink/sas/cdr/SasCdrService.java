/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.cdr;

import et.restlink.sas.persist.SasCdrEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Silent Auth SAS call-detail-record ledger.
 *
 * <p>Writes one durable row per correlation id. {@link #accepted} opens the
 * ledger and {@link #completed}/{@link #failed} close it with a terminal
 * status. Each event also goes to the {@code SAS_CDR} log4j logger as an
 * RFC-4180 CSV line and is enqueued into the in-memory DB flusher.</p>
 */
@ApplicationScoped
public class SasCdrService {

    private static final Logger CDR = LogManager.getLogger("SAS_CDR");

    /** CDR CSV columns — header order of every SAS_CDR line. */
    public static final String CSV_HEADER =
            "time,correlationId,msisdn,operation,status,detail,user,connector,tenantId";

    private static final String DEFAULT_USER = "http";
    private static final String DEFAULT_CONNECTOR = "http";

    private final ConcurrentHashMap<String, Attribution> attribution = new ConcurrentHashMap<>();

    @Inject
    CdrDbFlusher flusher;

    @ConfigProperty(name = "sas.cdr.enabled", defaultValue = "true")
    boolean enabled = true;

    @ConfigProperty(name = "sas.cdr.db.enabled", defaultValue = "true")
    boolean dbEnabled = true;

    private record Attribution(String msisdn, String operation, String user, String connector,
                               String tenantId, int networkId) {}

    /**
     * Full-flow detail of one {@code /verify} request (privacy: no raw
     * MSISDN/IMSI — the service masks the number before persisting).
     *
     * @param verified       final answer recorded for the flow
     * @param decision       APPROVE / FALLBACK
     * @param score          assurance score 0..100 (nullable when not computed)
     * @param threshold      threshold applied (nullable)
     * @param assuranceLevel FALLBACK / LOW / HIGH
     * @param riskClass      LOGIN / TRANSFER / HIGH_VALUE
     * @param accessTech     GS_2G3G / LTE / NR / WIFI
     * @param fallbackReason fail-closed reason, null on approval
     * @param resolverStatus BOUND / NO_BINDING / ... / SKIPPED_WIFI
     * @param evidenceSource verifier protocol tag (MAP-PSI+SAI / ...)
     * @param evidenceJson   factors + weights + stage notes (msisdn-free)
     * @param totalMs        end-to-end flow duration
     */
    public record FlowDetail(
            boolean verified,
            String decision,
            Integer score,
            Integer threshold,
            String assuranceLevel,
            String riskClass,
            String accessTech,
            String fallbackReason,
            String resolverStatus,
            String evidenceSource,
            String evidenceJson,
            int totalMs) {}

    /** Open a ledger row for a new SAS request. */
    public void accepted(String correlationId, String msisdn, String operation,
                         String user, String connector, String tenantId, int networkId) {
        if (!enabled || correlationId == null || correlationId.isBlank()) {
            return;
        }
        attribution.put(correlationId, new Attribution(
                msisdn, operation, blankTo(user, DEFAULT_USER), blankTo(connector, DEFAULT_CONNECTOR),
                tenantId, networkId));
        write(correlationId, "ACCEPTED", "NEW", "accepted");
    }

    /** Close a ledger row with a successful outcome. */
    public void completed(String correlationId, String status) {
        write(correlationId, blankTo(status, "COMPLETED"), "COMPLETED", "completed");
    }

    /** Close a ledger row with a failed outcome. */
    public void failed(String correlationId, String status) {
        write(correlationId, blankTo(status, "FAILED"), "FAILED", "failed");
    }

    /**
     * Full-flow record for one {@code /verify} request — written ONCE at the
     * SBB terminal point. Privacy: the MSISDN is masked ({@code 2519****11}
     * style) before it touches the row, the CSV line or any log; the IMSI is
     * never passed in at all.
     */
    public void recordFlow(String correlationId, String msisdn, FlowDetail detail) {
        if (!enabled || correlationId == null || correlationId.isBlank() || detail == null) {
            return;
        }
        Instant now = Instant.now();
        String masked = maskMsisdn(msisdn);

        SasCdrEntity row = new SasCdrEntity();
        row.id = UUID.randomUUID();
        row.recordedAt = now;
        row.correlationId = correlationId;
        row.phase = "FLOW";
        row.status = detail.decision() == null ? "UNKNOWN" : detail.decision();
        row.msisdn = masked;
        row.operation = "VERIFY";
        row.detail = "score=" + detail.score()
                + " threshold=" + detail.threshold()
                + " level=" + detail.assuranceLevel()
                + " risk=" + detail.riskClass()
                + " resolver=" + detail.resolverStatus()
                + " evidence=" + detail.evidenceSource()
                + " totalMs=" + detail.totalMs();
        row.networkId = 0;
        row.tenantId = null;
        row.csvLine = csvLine(now, correlationId, masked, "VERIFY", row.status,
                row.detail, DEFAULT_USER, DEFAULT_CONNECTOR, null);
        row.startedAt = now;
        row.updatedAt = now;
        row.eventCount = 1;
        row.eventsJson = "FLOW:" + row.status;

        row.verified = detail.verified();
        row.decision = detail.decision();
        row.score = detail.score();
        row.threshold = detail.threshold();
        row.assuranceLevel = detail.assuranceLevel();
        row.riskClass = detail.riskClass();
        row.accessTech = detail.accessTech();
        row.fallbackReason = detail.fallbackReason();
        row.resolverStatus = detail.resolverStatus();
        row.evidenceSource = detail.evidenceSource();
        row.evidenceJson = detail.evidenceJson();
        row.totalMs = detail.totalMs();

        CDR.info(row.csvLine);
        if (dbEnabled) {
            flusher.enqueue(row);
        }
    }

    /** Most recent persisted/queued ledger rows (newest first). */
    public List<SasCdrEntity> recent(int limit) {
        return flusher.recent(limit);
    }

    /**
     * Masks an MSISDN like the existing operator logs ({@code 2519****11}).
     * Anything too short to mask safely yields {@code null} — never raw.
     */
    static String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) {
            return null;
        }
        return msisdn.substring(0, 4) + "****" + msisdn.substring(msisdn.length() - 2);
    }

    private void write(String correlationId, String status, String phase, String detail) {
        if (!enabled || correlationId == null || correlationId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        boolean terminal = !"ACCEPTED".equals(status);
        Attribution a = terminal ? attribution.remove(correlationId) : attribution.get(correlationId);
        String user = a == null ? DEFAULT_USER : a.user();
        String connector = a == null ? DEFAULT_CONNECTOR : a.connector();
        String msisdn = a == null ? null : a.msisdn();
        String operation = a == null ? null : a.operation();
        String tenantId = a == null ? null : a.tenantId();
        int networkId = a == null ? 0 : a.networkId();

        SasCdrEntity row = new SasCdrEntity();
        row.id = UUID.randomUUID();
        row.recordedAt = now;
        row.correlationId = correlationId;
        row.phase = phase;
        row.status = status;
        row.msisdn = msisdn;
        row.operation = operation;
        row.detail = detail;
        row.networkId = networkId;
        row.tenantId = tenantId;
        row.csvLine = csvLine(now, correlationId, msisdn, operation, status, detail,
                user, connector, tenantId);
        row.startedAt = now;
        row.updatedAt = now;
        row.eventCount = 1;
        row.eventsJson = phase + ":" + status;

        CDR.info(row.csvLine);
        if (dbEnabled) {
            flusher.enqueue(row);
        }
    }

    /** RFC-4180 CSV: comma-separated fields; fields containing special chars quoted. */
    static String csvLine(Instant time, String correlationId, String msisdn, String operation,
                          String status, String detail, String user, String connector, String tenantId) {
        return csv(time.toString()) + "," + csv(correlationId) + "," + csv(msisdn) + ","
                + csv(operation) + "," + csv(status) + "," + csv(detail) + ","
                + csv(user) + "," + csv(connector) + "," + csv(tenantId);
    }

    static String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}