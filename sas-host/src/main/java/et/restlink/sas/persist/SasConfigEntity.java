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

@Entity
@Table(name = "sas_config")
public class SasConfigEntity extends PanacheEntityBase {
    @Id
    @Column(name = "config_key", length = 128)
    public String configKey;

    @Column(name = "config_value", length = 65536, nullable = false)
    public String configValue;
}