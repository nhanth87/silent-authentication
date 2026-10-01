/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

/**
 * The single-use ledger of the tokens this ECS issued (layered ADR D7: the issuer owns
 * it). {@link #consume} is atomic: of any number of concurrent calls for one {@code jti},
 * exactly one returns {@code true}.
 */
public interface OperatorTokenLedger {

    /**
     * Record {@code jti} as spent.
     *
     * @param expiresAtEpochSec the token's {@code exp}; the entry may be dropped after it
     * @return true the first time, false if it was already spent — or if the ledger cannot
     *         answer (fail closed)
     */
    boolean consume(String issuer, String jti, long expiresAtEpochSec);

    /** True when this ledger survives a restart and is shared by every ECS instance. */
    boolean durable();
}
