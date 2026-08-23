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

@Entity
@Table(name = "sas_tenant")
public class SasTenantEntity extends PanacheEntityBase {
    @Id
    @Column(name = "tenant_id", length = 128)
    public String tenantId;

    @Column(name = "display_name", length = 256)
    public String displayName;

    @Column(name = "network_id", nullable = false)
    public int networkId;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "admin_api_key", length = 256)
    public String adminApiKey;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}