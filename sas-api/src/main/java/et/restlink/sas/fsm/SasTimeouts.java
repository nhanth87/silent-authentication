/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.fsm;

/**
 * Stage budgets (dialog-anchored). Every stage is strictly less than
 * {@link #TOTAL_MS}; the FSM aborts on expiry and fails closed.
 *
 * <p>Source of truth: {@code harness/gates.yaml} {@code budgets}.</p>
 */
public final class SasTimeouts {

    /** Resolver lookup budget. */
    public static final long RESOLVER_MS = 300L;

    /** MAP PSI/ATI/SAI dialog budget (TC dialog timer). */
    public static final long MAP_MS = 2000L;

    /** Diameter S6a ULR/ULA + read-only Sh UDR budget. */
    public static final long DIAMETER_MS = 2000L;

    /** Total SAS budget — after this the bank shows normal login. */
    public static final long TOTAL_MS = 3000L;

    private SasTimeouts() {
    }
}