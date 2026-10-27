/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Browser-side session-tuple poster for SAS /session-tuple. ES2020, zero deps.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

const DEFAULT_TIMEOUT_MS = 3000;

/**
 * Access technologies the SAS understands. Names mirror
 * `et.restlink.sas.model.AccessTech`.
 */
export const AccessTech = Object.freeze({
  GS_2G3G: 'GS_2G3G',
  LTE: 'LTE',
  NR: 'NR',
  WIFI: 'WIFI',
  FIXED: 'FIXED',
  UNKNOWN: 'UNKNOWN',
});

/** True only for a cellular 2G/3G/4G/5G bearer. */
export function isCellular(accessTech) {
  return accessTech === AccessTech.GS_2G3G
    || accessTech === AccessTech.LTE
    || accessTech === AccessTech.NR;
}

/**
 * Best-effort bearer classification from the Network Information API.
 *
 * A browser cannot pin a request to a radio: the OS routing table decides, and
 * on a phone attached to both Wi-Fi and LTE the page almost always leaves over
 * Wi-Fi. `navigator.connection` (Chromium/Android only; absent in Safari and
 * Firefox) is therefore an *observation*, never a control — which is exactly
 * why the default answer is UNKNOWN and why `requireCellular` fails closed
 * instead of guessing.
 *
 * @param {Navigator|undefined} nav environment to probe (injectable for tests)
 * @returns {string} one of AccessTech
 */
export function detectAccessTech(nav = globalThis.navigator) {
  const conn = nav && nav.connection;
  if (!conn || typeof conn !== 'object') {
    return AccessTech.UNKNOWN;
  }
  const type = typeof conn.type === 'string' ? conn.type.toLowerCase() : '';
  if (type === 'wifi') return AccessTech.WIFI;
  if (type === 'ethernet') return AccessTech.FIXED;
  if (type === 'cellular') {
    // `effectiveType` is a throughput estimate, not a radio reading: "4g" means
    // "fast enough to look like LTE", so it must never be reported as NR. LTE is
    // the strongest claim a browser may make.
    const eff = typeof conn.effectiveType === 'string' ? conn.effectiveType.toLowerCase() : '';
    return eff === '4g' ? AccessTech.LTE : AccessTech.GS_2G3G;
  }
  // 'none', 'bluetooth', 'wimax', 'other', 'unknown', or no API at all.
  return AccessTech.UNKNOWN;
}

/**
 * Thrown when a cellular bearer was demanded but the browser could not confirm
 * one. The app must fall back to OTP/passkey — never retry over Wi-Fi.
 */
export class CellularUnavailableError extends Error {
  constructor(observed) {
    super(`silent auth needs a cellular bearer, browser reports ${observed}`
      + ' - fall back to OTP; a web page cannot pin traffic to the radio');
    this.name = 'CellularUnavailableError';
    this.code = 'CELLULAR_UNAVAILABLE';
    this.observed = observed;
  }
}

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
  if (snapshot.accessTech != null && snapshot.accessTech !== AccessTech.UNKNOWN) {
    body.accessTech = snapshot.accessTech;
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
   * @param {boolean} [options.requireCellular] refuse to post when the browser
   *        cannot confirm a cellular bearer (default false = lab behaviour).
   * @param {Navigator} [options.navigator] probe override for tests.
   */
  constructor({
    baseUrl,
    apiKey = null,
    onError = null,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    requireCellular = false,
    navigator: nav = globalThis.navigator,
  }) {
    if (!baseUrl) {
      throw new TypeError('baseUrl is required');
    }
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.apiKey = apiKey;
    this.onError = onError;
    this.timeoutMs = timeoutMs;
    this.requireCellular = requireCellular;
    this.navigator = nav;
  }

  /** Bearer observed at call time (never cached — radios change mid-session). */
  accessTech() {
    return detectAccessTech(this.navigator);
  }

  /**
   * Sends the tuple; resolves {status} or throws on network failure/timeout.
   * @param {{msisdn?: string|null}} [options]
   * @returns {Promise<{status: number}>}
   * @throws {CellularUnavailableError} when requireCellular is set and the
   *         browser is not on a cellular bearer — before any request is sent.
   */
  async send({ msisdn = null } = {}) {
    const accessTech = this.accessTech();
    if (this.requireCellular && !isCellular(accessTech)) {
      throw new CellularUnavailableError(accessTech);
    }
    const snapshot = {
      srcIp: null, srcPort: null, ts: Date.now(), msisdn, imsi: null, accessTech,
    };
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
   * Request headers: Content-Type always; X-Api-Key only when configured;
   * X-Sas-Access-Tech only when the bearer is actually known.
   */
  headers() {
    const headers = { 'Content-Type': 'application/json' };
    if (this.apiKey && this.apiKey.trim() !== '') {
      headers['X-Api-Key'] = this.apiKey;
    }
    return headers;
  }
}
