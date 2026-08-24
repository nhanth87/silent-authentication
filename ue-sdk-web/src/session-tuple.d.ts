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
}

export interface SessionTupleSendOptions {
    /** Optional app-supplied MSISDN; omitted from the body when absent. */
    msisdn?: string | null;
}

export interface SessionTupleSendResult {
    status: number;
}

export declare function trimTrailingSlash(baseUrl: string): string;

export declare class SessionTupleClient {
    constructor(options: SessionTupleClientOptions);
    send(options?: SessionTupleSendOptions): Promise<SessionTupleSendResult>;
}
