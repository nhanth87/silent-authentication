/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sas_cdr_session")
public class SasCdrEntity extends PanacheEntityBase {
    @Id
    public UUID id;

    @Column(name = "recorded_at", nullable = false)
    public Instant recordedAt;

    @Column(name = "correlation_id", nullable = false, length = 128)
    public String correlationId;

    @Column(nullable = false, length = 32)
    public String phase;

    @Column(nullable = false, length = 64)
    public String status;

    @Column(length = 32)
    public String msisdn;

    @Column(name = "operation", length = 16)
    public String operation;

    @Column(length = 1024)
    public String detail;

    @Column(name = "network_id")
    public Integer networkId;

    @Column(name = "tenant_id", length = 128)
    public String tenantId;

    @Column(name = "csv_line", nullable = false, length = 4000)
    public String csvLine;

    @Column(name = "started_at", nullable = false)
    public Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "event_count", nullable = false)
    public int eventCount = 1;

    @Column(name = "events_json", length = 8192)
    public String eventsJson;

    // ---- full-flow columns (V2 — one row per /verify request) -------------

    /** Final CAMARA answer for the request. */
    @Column
    public Boolean verified;

    /** APPROVE / FALLBACK decision label. */
    @Column(length = 16)
    public String decision;

    /** Assurance score 0..100. */
    public Integer score;

    /** Threshold applied for the risk class. */
    public Integer threshold;

    /** Assurance level name (FALLBACK / LOW / HIGH). */
    @Column(name = "assurance_level", length = 24)
    public String assuranceLevel;

    /** Risk class label (LOGIN / TRANSFER / HIGH_VALUE). */
    @Column(name = "risk_class", length = 16)
    public String riskClass;

    /** Access technology (GS_2G3G / LTE / NR / WIFI). */
    @Column(name = "access_tech", length = 12)
    public String accessTech;

    /** Fail-closed reason, null on approval. */
    @Column(name = "fallback_reason", length = 48)
    public String fallbackReason;

    /** Resolver stage outcome (BOUND / NO_BINDING / ... / SKIPPED_WIFI). */
    @Column(name = "resolver_status", length = 32)
    public String resolverStatus;

    /** Verifier evidence source protocol tag (MAP-PSI+SAI / S6A-ULR+Sh-UDR / ...). */
    @Column(name = "evidence_source", length = 32)
    public String evidenceSource;

    /** Factors + weights + stage notes as JSON (msisdn-free). */
    @Column(name = "evidence_json", columnDefinition = "TEXT")
    public String evidenceJson;

    /** End-to-end flow duration in milliseconds. */
    @Column(name = "total_ms")
    public Integer totalMs;
}