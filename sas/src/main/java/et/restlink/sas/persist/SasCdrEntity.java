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
}