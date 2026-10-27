/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Browser side of the desktop → phone QR hand-off. ES2020, zero deps.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import { QrState } from './qr-session-state.js';

/**
 * Desktop half of the CAMARA NV v2 QR hand-off: the bank opens the session and
 * polls for the verdict, while the **phone** does the claiming.
 *
 * Why this split is the whole point. A desktop has no SIM, so the cellular
 * IP-match path cannot help the person sitting at it — the address the browser
 * sends is a LAN or VPN address no PGW ever assigned. The phone does have the
 * SIM, and on Android it can bind a socket to the cellular `Network`. So the
 * bank shows a QR code, the phone scans it, and this class waits.
 *
 * A browser therefore **cannot** claim a session. `claim` is deliberately not
 * exposed here: a page that pretended to prove a number would be exactly the
 * phishing hole this product exists to close. The web SDK's job is to open the
 * session and report the server's verdict — nothing more.
 *
 * Fail-closed: `approved` is true only for an explicit `true` on a `RESOLVED`
 * session. A pending, expired or unreadable answer is not consent, and a bank
 * must treat it that way.
 */

const DEFAULT_TIMEOUT_MS = 3000;

/** Trims one trailing slash (keeps a bare origin intact). */
export function trimTrailingSlash(baseUrl) {
  return baseUrl && baseUrl.length > 1 && baseUrl.endsWith('/')
    ? baseUrl.slice(0, -1)
    : baseUrl;
}

/**
 * Extracts the session id from a scanned/rendered QR payload.
 *
 * Accepts the canonical `silentauth://qr-session/<id>` and a bare UUID, because
 * a bank may render either. This string authorises the claim, so anything else
 * throws rather than being parsed hopefully.
 *
 * @param {string|null|undefined} scanned
 * @returns {string}
 * @throws {TypeError} when the payload is not a session reference
 */
export function parsePayload(scanned) {
  if (typeof scanned !== 'string') {
    throw new TypeError('empty QR payload');
  }
  let value = scanned.trim();
  if (value === '') {
    throw new TypeError('empty QR payload');
  }
  const scheme = 'silentauth://qr-session/';
  if (value.toLowerCase().startsWith(scheme)) {
    value = value.slice(scheme.length);
  } else {
    const slash = value.lastIndexOf('/');
    if (slash >= 0 && slash < value.length - 1) {
      value = value.slice(slash + 1);
    }
  }
  const query = value.indexOf('?');
  if (query >= 0) {
    value = value.slice(0, query);
  }
  if (!isSessionId(value)) {
    throw new TypeError(`not a silent-auth QR payload: ${scanned}`);
  }
  return value;
}

/** A session id is a UUID; nothing else may be claimed. */
export function isSessionId(value) {
  if (typeof value !== 'string' || value.length !== 36) {
    return false;
  }
  const dashes = new Set([8, 13, 18, 23]);
  for (let i = 0; i < 36; i += 1) {
    const c = value[i];
    if (dashes.has(i)) {
      if (c !== '-') return false;
    } else if (!/[0-9a-fA-F]/.test(c)) {
      return false;
    }
  }
  return true;
}

/**
 * Flat scalar fields of a JSON object.
 *
 * Deliberately tiny and unforgiving: anything that is not a single-level object
 * of scalars comes back empty, so an unreadable answer reads as "no verdict"
 * rather than as a half-parsed approval.
 */
export function parseFlat(payload) {
  const fields = {};
  if (typeof payload !== 'string' || payload.trim() === '') {
    return fields;
  }
  let parsed;
  try {
    parsed = JSON.parse(payload);
  } catch (notJson) {
    return fields;
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return fields;
  }
  for (const [key, value] of Object.entries(parsed)) {
    if (value === null) {
      fields[key] = null;
    } else if (typeof value === 'string' || typeof value === 'boolean') {
      fields[key] = String(value);
    } else if (typeof value === 'number') {
      fields[key] = String(value);
    } else {
      return {};                    // nested or array: out of scope, refuse
    }
  }
  return fields;
}

function stateOf(raw) {
  if (typeof raw !== 'string') {
    return QrState.UNKNOWN;
  }
  const upper = raw.trim().toUpperCase();
  return Object.values(QrState).includes(upper) ? upper : QrState.UNKNOWN;
}

function boolOf(fields, key) {
  const raw = fields[key];
  if (raw === undefined || raw === null) {
    return null;                     // absent is not false, and not true
  }
  if (raw === 'true') return true;
  if (raw === 'false') return false;
  return null;
}

/**
 * Desktop half of the hand-off: opens the session and polls for the verdict.
 *
 * @param {object} options
 * @param {string} options.baseUrl        SAS base URL.
 * @param {string} options.authorization  single-use NV bearer (the bank session).
 * @param {string} [options.apiKey]       sent as X-Api-Key when non-blank.
 * @param {string} [options.phoneNumber]  E.164 to prove; omit for the discovery
 *        variant, where the phone states which number it wants proven.
 * @param {number} [options.timeoutMs]    per-request timeout, default 3000 ms.
 * @param {(error: unknown) => void} [options.onError]
 */
export class QrSessionClient {
  constructor({
    baseUrl,
    authorization,
    apiKey = null,
    phoneNumber = null,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    onError = null,
  }) {
    if (!baseUrl) {
      throw new TypeError('baseUrl is required');
    }
    if (!authorization) {
      throw new TypeError('authorization is required to open a QR session');
    }
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.authorization = authorization;
    this.apiKey = apiKey;
    this.phoneNumber = phoneNumber;
    this.timeoutMs = timeoutMs;
    this.onError = onError;
  }

  /**
   * Opens the hand-off and returns what the bank renders.
   *
   * @returns {Promise<{sessionId: string, qrPayload: string, expiresInSeconds: number}>}
   */
  async create() {
    const body = {};
    if (this.phoneNumber) {
      body.phoneNumber = this.phoneNumber;
    }
    const fields = await this.exchange('POST', '/qr-session/v1', body);
    const sessionId = fields.sessionId;
    if (typeof sessionId !== 'string' || !isSessionId(sessionId)) {
      throw new Error('server did not return a usable session id');
    }
    return {
      sessionId,
      qrPayload: typeof fields.qrPayload === 'string'
        ? fields.qrPayload
        : `silentauth://qr-session/${sessionId}`,
      expiresInSeconds: Number(fields.expiresInSeconds ?? -1),
    };
  }

  /**
   * Reads the verdict the phone produced.
   *
   * @returns {Promise<{sessionId: string|null, state: string,
   *   devicePhoneNumberVerified: boolean|null, fallbackReason: string|null,
   *   decision: string|null, expiresInSeconds: number, approved: boolean}>}
   */
  async status(sessionId) {
    if (!isSessionId(sessionId)) {
      throw new TypeError('not a silent-auth session id');
    }
    const fields = await this.exchange('GET', `/qr-session/v1/${sessionId}`, null);
    const state = stateOf(fields.state);
    const verified = boolOf(fields, 'devicePhoneNumberVerified');
    return {
      sessionId: fields.sessionId ?? null,
      state,
      devicePhoneNumberVerified: verified,
      fallbackReason: fields.fallbackReason ?? null,
      decision: fields.decision ?? null,
      expiresInSeconds: Number(fields.expiresInSeconds ?? -1),
      approved: verified === true && state === QrState.RESOLVED,
    };
  }

  /**
   * Polls until the session resolves, expires, or the deadline passes.
   *
   * Resolves on the first terminal answer. A timeout resolves with whatever was
   * last seen rather than throwing, because a bank still has to render *not
   * authenticated* — an exception here must not become a retry loop that looks
   * like consent.
   *
   * @param {string} sessionId
   * @param {object} [options]
   * @param {number} [options.intervalMs] default 1500 ms.
   * @param {number} [options.deadlineMs] default 120000 ms.
   * @param {(status: object) => void} [options.onTick] progress hook.
   */
  async poll(sessionId, { intervalMs = 1500, deadlineMs = 120000, onTick = null } = {}) {
    const deadline = Date.now() + deadlineMs;
    let last = null;
    for (;;) {
      // eslint-disable-next-line no-await-in-loop
      const current = await this.status(sessionId);
      last = current;
      if (typeof onTick === 'function') {
        onTick(current);
      }
      if (current.state === QrState.RESOLVED
        || current.state === QrState.EXPIRED
        || Date.now() >= deadline) {
        return current;
      }
      // eslint-disable-next-line no-await-in-loop
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
    }
  }

  headers() {
    const headers = {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      Authorization: this.authorization,
    };
    if (this.apiKey && this.apiKey.trim() !== '') {
      headers['X-Api-Key'] = this.apiKey;
    }
    return headers;
  }

  async exchange(method, path, body) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await fetch(this.baseUrl + path, {
        method,
        headers: this.headers(),
        body: body === null ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
      const text = await response.text().catch(() => '');
      return parseFlat(text);
    } catch (error) {
      if (typeof this.onError === 'function') {
        try {
          this.onError(error);
        } catch (listenerFailure) {
          // a listener must not mask the transport error
        }
      }
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }
}
