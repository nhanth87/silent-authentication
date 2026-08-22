/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.model;

/**
 * Terminal FALLBACK reasons. Mirrors the failure-mode table in
 * {@code proposal/chapters/06_sas_fsm_timeouts.md} §6.9 and the fail-closed
 * invariant: missing/ambiguous/expired evidence never approves.
 */
public enum FallbackReason {
    /** Resolver returned zero bindings for IP:port:ts. */
    NO_BINDING,
    /** CGNAT: resolver returned more than one MSISDN (require IP+port+ts). */
    AMBIGUOUS_BINDING,
    /** Resolver stage exceeded its 300 ms budget. */
    RESOLVER_TIMEOUT,
    /** Resolver backend error. */
    RESOLVER_ERROR,
    /** claimedMSISDN present and resolved != claimed. */
    MSISDN_MISMATCH,
    /** Verifier stage exceeded its 2 s budget (dialog aborted). */
    VERIFY_TIMEOUT,
    /** MAP/Diameter error or malformed response. */
    VERIFY_ERROR,
    /** Subscriber purged / not reachable (TS 29.272 PUR). */
    PURGED,
    /** SAI/AIR detected a fresh IMSI change (SIM-swap suspect). */
    SIM_SWAP_SUSPECT,
    /** Assurance score below threshold. */
    LOW_ASSURANCE,
    /** Wi-Fi request but TS.43/SWx verifier not wired yet (P1 — fail closed). */
    WIFI_NOT_READY,
    /** Total SAS budget (3 s) exceeded. */
    SAS_TIMEOUT,
    /** Northbound request failed schema / auth validation. */
    INVALID_REQUEST
}