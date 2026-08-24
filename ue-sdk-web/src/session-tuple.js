/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Browser-side session-tuple poster for SAS /session-tuple. ES2020, zero deps.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

const DEFAULT_TIMEOUT_MS = 3000;

/**
 * Trims one trailing slash (keeps bare origins intact).
 */
export function trimTrailingSlash(baseUrl) {
  return baseUrl && baseUrl.length > 1 && baseUrl.endsWith('/')
    ? baseUrl.slice(0, -1)
    : baseUrl;
}

/**
 * Builds the /session-tuple body, omitting null fields.
 */
export function sessionTupleBody(snapshot) {
  const body = {};
  if (snapshot.srcIp != null) {
    body.srcIp = snapshot.srcIp;
  }
  if (snapshot.srcPort != null) {
    body.srcPort = snapshot.srcPort;
  }
  body.ts = snapshot.ts;
  if (snapshot.msisdn != null) {
    body.msisdn = snapshot.msisdn;
  }
  if (snapshot.imsi != null) {
    body.imsi = snapshot.imsi;
  }
  return body;
}

/**
 * Posts the device session tuple to the SAS POST /session-tuple endpoint.
 * Browsers cannot observe the real CGNAT IP/port, so snapshots carry ts plus
 * an optional app-supplied msisdn. Contains no authentication logic; approval
 * always happens server-side (fail-closed FSM in the SAS).
 */
export class SessionTupleClient {
  /**
   * @param {object} options
   * @param {string} options.baseUrl SAS base URL.
   * @param {string} [options.apiKey] sent as X-Api-Key when non-blank.
   * @param {(error: unknown) => void} [options.onError] transport-failure hook.
   * @param {number} [options.timeoutMs] overall timeout, default 3000 ms.
   */
  constructor({ baseUrl, apiKey = null, onError = null, timeoutMs = DEFAULT_TIMEOUT_MS }) {
    if (!baseUrl) {
      throw new TypeError('baseUrl is required');
    }
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.apiKey = apiKey;
    this.onError = onError;
    this.timeoutMs = timeoutMs;
  }

  /**
   * Sends the tuple; resolves {status} or throws on network failure/timeout.
   * @param {{msisdn?: string|null}} [options]
   * @returns {Promise<{status: number}>}
   */
  async send({ msisdn = null } = {}) {
    const snapshot = { srcIp: null, srcPort: null, ts: Date.now(), msisdn, imsi: null };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await fetch(this.baseUrl + '/session-tuple', {
        method: 'POST',
        headers: this.headers(),
        body: JSON.stringify(sessionTupleBody(snapshot)),
        signal: controller.signal,
      });
      await response.arrayBuffer().catch(() => {}); // best-effort drain
      return { status: response.status };
    } catch (error) {
      if (typeof this.onError === 'function') {
        try {
          this.onError(error);
        } catch (listenerFailure) {
          // listener must not mask the transport error
        }
      }
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * Request headers: Content-Type always; X-Api-Key only when configured.
   */
  headers() {
    const headers = { 'Content-Type': 'application/json' };
    if (this.apiKey && this.apiKey.trim() !== '') {
      headers['X-Api-Key'] = this.apiKey;
    }
    return headers;
  }
}
