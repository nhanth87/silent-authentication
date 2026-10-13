/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Browser-side session-tuple poster for SAS /session-tuple. ES2020, zero deps.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

/**
 * Lifecycle of a desktop → phone QR hand-off, as the server reports it.
 *
 * `UNKNOWN` exists because a bank must have a value to render when the answer
 * could not be read: an unreadable response is not `PENDING` and certainly not
 * `RESOLVED`.
 *
 * @readonly
 * @enum {string}
 */
export const QrState = Object.freeze({
  PENDING: 'PENDING',
  RESOLVED: 'RESOLVED',
  EXPIRED: 'EXPIRED',
  UNKNOWN: 'UNKNOWN',
});
