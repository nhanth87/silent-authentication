/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import type { AccessTech } from './session-tuple.js';

/**
 * Lifecycle of a desktop → phone QR hand-off, as the server reports it.
 *
 * `UNKNOWN` exists because a bank must have a value to render when the answer
 * could not be read: an unreadable response is not `PENDING`, and certainly
 * never `RESOLVED`.
 */
export type QrState = 'PENDING' | 'RESOLVED' | 'EXPIRED' | 'UNKNOWN';

/** What the bank renders after opening the hand-off. */
export interface QrCreateResponse {
  /** Opaque id; the phone sends it back on claim. */
  sessionId: string;
  /** The exact string to encode as a QR image. */
  qrPayload: string;
  /** Remaining budget in seconds, or -1 when the server did not say. */
  expiresInSeconds: number;
}

/**
 * The verdict of one hand-off.
 *
 * `devicePhoneNumberVerified` is `null` while pending — deliberately distinct
 * from `false`, so a bank cannot mistake "not answered yet" for "answered no".
 */
export interface QrStatus {
  sessionId: string | null;
  state: QrState;
  devicePhoneNumberVerified: boolean | null;
  fallbackReason: string | null;
  decision: string | null;
  expiresInSeconds: number;
  /** True only for an explicit `true` on a `RESOLVED` session. */
  approved: boolean;
}

export interface QrSessionClientOptions {
  /** SAS base URL, e.g. `https://api.operator.com`. */
  baseUrl: string;
  /** Single-use NV bearer authenticating the bank session. */
  authorization: string;
  /** Sent as `X-Api-Key` when non-blank. */
  apiKey?: string | null;
  /** E.164 to prove; omit for the discovery variant. */
  phoneNumber?: string | null;
  /** Per-request timeout in ms, default 3000. */
  timeoutMs?: number;
  /** Transport-failure hook. */
  onError?: (error: unknown) => void;
}

export interface PollOptions {
  /** Delay between polls in ms, default 1500. */
  intervalMs?: number;
  /** Give up after this long in ms, default 120000. */
  deadlineMs?: number;
  /** Progress hook, called with every status. */
  onTick?: (status: QrStatus) => void;
}

/**
 * Desktop half of the CAMARA NV v2 QR hand-off.
 *
 * A browser cannot claim a session — it has no SIM and cannot bind a socket to
 * a radio. This class therefore only opens the session and reports the phone's
 * verdict.
 */
export class QrSessionClient {
  constructor(options: QrSessionClientOptions);

  /** Opens the hand-off. */
  create(): Promise<QrCreateResponse>;

  /** Reads the verdict once. */
  status(sessionId: string): Promise<QrStatus>;

  /** Polls until resolved, expired, or the deadline passes. */
  poll(sessionId: string, options?: PollOptions): Promise<QrStatus>;
}

/** Extracts the session id from a scanned/rendered QR payload; throws otherwise. */
export function parsePayload(scanned: string | null | undefined): string;

/** True when the value is a well-formed UUID. */
export function isSessionId(value: unknown): boolean;

/** Flat scalar fields of a JSON object; `{}` when unreadable or nested. */
export function parseFlat(payload: string): Record<string, string | null>;

/** Trims one trailing slash from a base URL. */
export function trimTrailingSlash(baseUrl: string): string;

/** Re-exported for callers that classify the bearer before opening a session. */
export type { AccessTech };
