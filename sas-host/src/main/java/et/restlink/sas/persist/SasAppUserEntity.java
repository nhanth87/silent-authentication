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
@Table(name = "sas_app_user")
public class SasAppUserEntity extends PanacheEntityBase {
    @Id
    @Column(nullable = false, length = 64)
    public String username;

    @Column(name = "tenant_id", nullable = false, length = 128)
    public String tenantId;

    @Column(name = "network_id", nullable = false)
    public int networkId;

    @Column(name = "api_key_hash", nullable = false, length = 128)
    public String apiKeyHash;

    @Column(name = "api_key_fp", length = 8)
    public String apiKeyFp;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}