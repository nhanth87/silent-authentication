/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.persistence;

import et.restlink.sas.model.VerifyResult;

/**
 * P1 persistence interface for completed verify results.
 * Implementations: in-memory (pilot), file-backed (P1), database (P2).
 */
public interface VerifyResultStore {

    /** Store a completed result. Overwrites any existing entry for the same reqId. */
    void store(String reqId, VerifyResult result);

    /** Retrieve a stored result, or null if not found or expired. */
    VerifyResult retrieve(String reqId);

    /** Remove a stored result. */
    void remove(String reqId);

    /** Evict entries older than the TTL. Called periodically. */
    void evictExpired();
}
