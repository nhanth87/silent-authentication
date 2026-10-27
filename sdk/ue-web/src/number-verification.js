/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * CAMARA Number Verification v2, browser side. ES2020, zero deps.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import {
  AccessTech, CellularUnavailableError, detectAccessTech, isCellular,
} from './session-tuple.js';

/**
 * Browser side of CAMARA Number Verification v2.
 *
 * What a web page can and cannot do, stated exactly:
 *
 * - It **cannot pin traffic to the radio**. The OS routes the request; on a phone
 *   with Wi-Fi up it leaves over Wi-Fi. `navigator.connection` (Chromium only)
 *   is an observation, never a control.
 * - It **can** run the OIDC authorization request with `prompt=none` as a
 *   top-level navigation. On a phone whose bearer is cellular, the operator sees
 *   the cellular source address and answers with a code — no page, no OTP.
 * - It **cannot** read a SIM token. `navigator.getSimToken()` does not exist in
 *   any browser. The OS route that does exist is the W3C Digital Credentials API
 *   (`navigator.credentials.get({ digital })`), where the OS wallet / operator
 *   answers a request the bank backend built; it is gated on a user gesture and
 *   shows an OS sheet, so it is SIM-based but not silent.
 * - On a desktop (no SIM) the answer is the QR hand-off in `./qr-session.js`.
 *
 * The page never redeems the code: the bank backend does, with
 * `private_key_jwt` and the PKCE verifier only it holds, and then calls
 * `POST /number-verification/v2/verify`. That answer is the only verdict.
 *
 * Nothing here logs. Error messages carry rule names and OAuth error codes only,
 * never the URL, the code, a token or a phone number.
 */

const OPERATOR_TOKEN_PREFIX = 'operatortoken:';

/** How the request authenticates the subscriber. */
export const AuthMethod = Object.freeze({
  NETWORK: 'NETWORK',
  OPERATOR_TOKEN: 'OPERATOR_TOKEN',
});

/**
 * The authorization server refused, or answered in a way the page cannot use.
 * Always a fallback signal (OTP / passkey); never a partial success. The
 * server's `description` is kept off `message` so logging the error does not
 * log free text the bank does not control.
 */
export class AuthorizationError extends Error {
  constructor(error, description = null, status = 0) {
    super(`authorization refused: ${error || 'unknown_error'}`
      + (status ? ` (HTTP ${status})` : '') + ' - fall back to OTP, never proceed');
    this.name = 'AuthorizationError';
    this.code = 'AUTHORIZATION_REFUSED';
    this.error = error || 'unknown_error';
    this.description = description;
    this.status = status;
  }

  /** True for the errors a later attempt may cure. */
  get retryLater() {
    return this.error === 'temporarily_unavailable' || this.error === 'server_error';
  }
}

/** The browser offers no OS route to a SIM credential. */
export class SimCredentialUnsupportedError extends Error {
  constructor(reason) {
    super(`SIM-based credential unavailable in this browser: ${reason}`
      + ' - use the QR hand-off or fall back to OTP');
    this.name = 'SimCredentialUnsupportedError';
    this.code = 'SIM_CREDENTIAL_UNSUPPORTED';
  }
}

function fail(rule) {
  throw new TypeError(rule);
}

/** Query parameters; a repeated one is refused, never resolved. */
export function queryParams(search) {
  const out = new Map();
  const raw = (search || '').replace(/^\?/, '');
  if (raw === '') return out;
  for (const pair of raw.split('&')) {
    if (pair === '') continue;
    const eq = pair.indexOf('=');
    const decode = (s) => decodeURIComponent(s.replace(/\+/g, ' '));
    const key = decode(eq < 0 ? pair : pair.slice(0, eq));
    const value = eq < 0 ? '' : decode(pair.slice(eq + 1));
    if (out.has(key)) fail(`parameter repeated: ${key}`);
    out.set(key, value);
  }
  return out;
}

const nonBlank = (s) => typeof s === 'string' && s.trim() !== '';

/**
 * Checks the authorization request the bank backend built. The page never
 * builds it: the backend owns client_id, state, nonce and the PKCE verifier.
 *
 * Rules: https (http only with the lab switch); response_type=code; PKCE S256
 * with a challenge; prompt=none (Number Verification shows no page); state,
 * scope, client_id and an absolute redirect_uri; no parameter repeated.
 *
 * @param {string} authorizeUrl
 * @param {{allowInsecureHttp?: boolean}} [options]
 * @returns {{url: URL, state: string, redirectUri: URL, hasLoginHint: boolean}}
 * @throws {TypeError} naming the first rule broken (never echoing the URL)
 */
export function parseAuthorizeUrl(authorizeUrl, { allowInsecureHttp = false } = {}) {
  if (!nonBlank(authorizeUrl)) fail('authorization URL is empty');
  let url;
  try {
    url = new URL(authorizeUrl.trim());
  } catch {
    fail('authorization URL must be absolute');
  }
  requireTransport(url, allowInsecureHttp, 'authorization URL');
  const p = queryParams(url.search);
  if (p.get('response_type') !== 'code') fail('response_type must be code');
  if (!nonBlank(p.get('client_id'))) fail('client_id is missing');
  if (!nonBlank(p.get('scope'))) fail('scope is missing');
  if (!nonBlank(p.get('state'))) fail('state is missing');
  if (p.get('code_challenge_method') !== 'S256' || !nonBlank(p.get('code_challenge'))) {
    fail('PKCE code_challenge with code_challenge_method=S256 is required');
  }
  if (p.get('prompt') !== 'none') {
    fail('prompt=none is required: Number Verification must not show any page');
  }
  if (!nonBlank(p.get('redirect_uri'))) fail('redirect_uri is missing');
  let redirectUri;
  try {
    redirectUri = new URL(p.get('redirect_uri'));
  } catch {
    fail('redirect_uri must be absolute');
  }
  return {
    url, state: p.get('state'), redirectUri, hasLoginHint: p.has('login_hint'),
  };
}

function requireTransport(url, allowInsecureHttp, what) {
  if (url.protocol === 'https:') return;
  if (url.protocol === 'http:' && allowInsecureHttp) return;
  fail(`${what} must use https`);
}

/**
 * The same request with login_hint=operatortoken:<token> — the SIM-based path.
 * Refused when the backend already chose a hint.
 */
export function withOperatorToken(parsed, token) {
  if (!nonBlank(token)) fail('operator token is empty');
  if (parsed.hasLoginHint) fail('the request already carries a login_hint');
  const url = new URL(parsed.url.href);
  const hint = `login_hint=${encodeURIComponent(OPERATOR_TOKEN_PREFIX + token.trim())}`;
  url.search = url.search ? `${url.search}&${hint}` : `?${hint}`;
  return url.href;
}

/**
 * Starts the silent authorization as a top-level navigation.
 *
 * Decision, before anything leaves the page:
 *   operatorToken given        → SIM-based: navigate with the token as login hint
 *   browser reports Wi-Fi/fixed → throw CellularUnavailableError (no request sent)
 *   cellular, or unknown       → network-based: navigate as is; the operator
 *                                 fails closed (error=access_denied) when the
 *                                 address is not its own
 * `bearerPolicy: 'require-cellular'` also refuses an unknown bearer.
 *
 * @param {string} authorizeUrl from the bank backend
 * @param {object} [options]
 * @param {string|null} [options.operatorToken] GSMA TS.43 token, single use
 * @param {'refuse-non-cellular'|'require-cellular'} [options.bearerPolicy]
 * @param {boolean} [options.allowInsecureHttp] lab only
 * @param {Navigator} [options.navigator] probe override (tests)
 * @param {{assign(url: string): void}} [options.location] navigation target (tests)
 * @returns {{method: string, accessTech: string}}
 */
export function startAuthorization(authorizeUrl, {
  operatorToken = null,
  bearerPolicy = 'refuse-non-cellular',
  allowInsecureHttp = false,
  navigator: nav = globalThis.navigator,
  location: loc = globalThis.location,
} = {}) {
  if (bearerPolicy !== 'refuse-non-cellular' && bearerPolicy !== 'require-cellular') {
    fail('bearerPolicy must be refuse-non-cellular or require-cellular');
  }
  const parsed = parseAuthorizeUrl(authorizeUrl, { allowInsecureHttp });
  if (!loc || typeof loc.assign !== 'function') fail('no location to navigate');
  const accessTech = detectAccessTech(nav);
  if (nonBlank(operatorToken)) {
    loc.assign(withOperatorToken(parsed, operatorToken));
    return { method: AuthMethod.OPERATOR_TOKEN, accessTech };
  }
  const refused = bearerPolicy === 'require-cellular'
    ? !isCellular(accessTech)
    : accessTech === AccessTech.WIFI || accessTech === AccessTech.FIXED;
  if (refused) {
    throw new CellularUnavailableError(accessTech);
  }
  loc.assign(parsed.url.href);
  return { method: AuthMethod.NETWORK, accessTech };
}

function constantTimeEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

/**
 * Reads the redirect the authorization server sent the browser back with, on
 * the bank's callback page.
 *
 * @param {string} callbackUrl usually `location.href` on the redirect_uri page
 * @param {string} expectedState the state the backend put in the request
 * @returns {{code: string, state: string}} hand `code` to the backend at once
 * @throws {AuthorizationError} for an OAuth error, a missing code, or a foreign state
 */
export function parseAuthorizationCallback(callbackUrl, expectedState) {
  let params;
  try {
    params = queryParams(new URL(callbackUrl).search);
  } catch {
    throw new AuthorizationError('invalid_response', 'the callback URL is malformed');
  }
  if (params.has('error')) {
    throw new AuthorizationError(params.get('error'), params.get('error_description') || null);
  }
  const code = params.get('code');
  if (!nonBlank(code)) {
    throw new AuthorizationError('invalid_response', 'the callback carries no code');
  }
  if (!constantTimeEquals(expectedState, params.get('state'))) {
    throw new AuthorizationError('invalid_state', 'the callback state does not match this request');
  }
  return { code, state: params.get('state') };
}

/**
 * Asks the OS for a SIM-based phone-number credential through the W3C Digital
 * Credentials API, and returns the opaque answer for the bank backend to verify.
 *
 * The backend builds `requests` (protocol + data, e.g. an OpenID4VP request for
 * a phone-number credential from the operator) and verifies the response; the
 * page only carries both. Must run inside a user gesture in a secure context —
 * the browser shows an OS sheet, so this path is SIM-based but not silent.
 *
 * @param {object} options
 * @param {Array<{protocol: string, data: unknown}>} options.requests from the backend
 * @param {AbortSignal} [options.signal]
 * @param {CredentialsContainer} [options.credentials] override (tests)
 * @param {object} [options.globals] feature-detection scope (tests)
 * @returns {Promise<{protocol: string, data: unknown}>}
 * @throws {SimCredentialUnsupportedError} when the browser has no such route
 */
export async function requestSimCredential({
  requests,
  signal = undefined,
  credentials = globalThis.navigator && globalThis.navigator.credentials,
  globals = globalThis,
} = {}) {
  if (!Array.isArray(requests) || requests.length === 0
    || !requests.every((r) => r && nonBlank(r.protocol) && r.data !== undefined)) {
    fail('requests must be a non-empty array of {protocol, data} from the backend');
  }
  if (globals.isSecureContext === false) {
    throw new SimCredentialUnsupportedError('not a secure context');
  }
  if (typeof globals.DigitalCredential !== 'function'
    || !credentials || typeof credentials.get !== 'function') {
    throw new SimCredentialUnsupportedError('Digital Credentials API not available');
  }
  const credential = await credentials.get({ digital: { requests }, signal });
  if (!credential || !nonBlank(credential.protocol) || credential.data === undefined) {
    throw new SimCredentialUnsupportedError('the OS returned no digital credential');
  }
  return { protocol: credential.protocol, data: credential.data };
}
