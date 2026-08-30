/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Type declarations for the session-tuple poster.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

export interface SessionTupleClientOptions {
    /** SAS base URL; a single trailing slash is trimmed. */
    baseUrl: string;
    /** Sent as X-Api-Key only when non-blank. */
    apiKey?: string | null;
    /** Invoked on transport failure/timeout before the promise rejects. */
    onError?: ((error: unknown) => void) | null;
    /** Overall timeout in ms (default 3000). */
    timeoutMs?: number;
    /**
     * Refuse to send at all unless the browser can confirm a cellular bearer.
     * Default false (lab). A page cannot pin traffic to the radio, so this can
     * only fail closed — never force 4G/5G.
     */
    requireCellular?: boolean;
    /** Navigator probe override, mainly for tests. */
    navigator?: Navigator | undefined;
}

export interface SessionTupleSendOptions {
    /** Optional app-supplied MSISDN; omitted from the body when absent. */
    msisdn?: string | null;
}

export interface SessionTupleSendResult {
    status: number;
}

/** Access technologies the SAS understands (mirrors et.restlink.sas.model.AccessTech). */
export declare const AccessTech: {
    readonly GS_2G3G: 'GS_2G3G';
    readonly LTE: 'LTE';
    readonly NR: 'NR';
    readonly WIFI: 'WIFI';
    readonly FIXED: 'FIXED';
    readonly UNKNOWN: 'UNKNOWN';
};

export type AccessTechName = (typeof AccessTech)[keyof typeof AccessTech];

/** True only for a cellular 2G/3G/4G/5G bearer. */
export declare function isCellular(accessTech: string): boolean;

/** Best-effort bearer classification from navigator.connection (observation only). */
export declare function detectAccessTech(nav?: Navigator | undefined): AccessTechName;

/** Thrown by send() when requireCellular is set and no cellular bearer is visible. */
export declare class CellularUnavailableError extends Error {
    readonly code: 'CELLULAR_UNAVAILABLE';
    readonly observed: AccessTechName;
}

export declare function trimTrailingSlash(baseUrl: string): string;

export declare function sessionTupleBody(snapshot: {
    srcIp?: string | null;
    srcPort?: number | null;
    ts: number;
    msisdn?: string | null;
    imsi?: string | null;
    accessTech?: string | null;
}): Record<string, unknown>;

export declare class SessionTupleClient {
    constructor(options: SessionTupleClientOptions);
    /** Bearer observed right now (never cached). */
    accessTech(): AccessTechName;
    send(options?: SessionTupleSendOptions): Promise<SessionTupleSendResult>;
    headers(): Record<string, string>;
}

