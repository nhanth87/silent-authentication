/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

/**
 * Assurance outcome. Fail-closed: a LOW score is a FALLBACK outcome, never an
 * approval. See {@code docs/design/silent-auth-standard-flow.md} §6.
 */
public enum AssuranceLevel {
    FALLBACK,
    LOW,
    HIGH
}