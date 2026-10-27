/*
 * Silent Auth — Restlink (Ethiopia).
 * E2E: the web SDK's CAMARA NV v2 flow against the packaged sas-camara +
 * sas-ecs, inside the network namespace set up by scripts/sdk-nv2-e2e.sh.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import http from 'node:http';
import { createHash, randomBytes } from 'node:crypto';

import {
  AuthMethod, AuthorizationError, parseAuthorizationCallback, startAuthorization,
} from '../../sdk/ue-web/src/number-verification.js';
import { CellularUnavailableError } from '../../sdk/ue-web/src/session-tuple.js';

const CAM = 'http://127.0.0.1:8085';
const CAM_FROM_CELL = 'http://10.20.30.40:8085';
const ECS = 'http://127.0.0.1:8095';
const MSISDN = '+251911111111';
let failures = 0;
/** SIM-based (operator-token) codes carry amr [hwk, mno] since 2026-10-14: strictly true. */
const verdictOrGap = (label, v) => check(label, v.startsWith('200 ') && v.includes('"devicePhoneNumberVerified":true'), v);

const check = (label, ok, detail) => {
  console.log(`${ok ? '  OK   ' : '  FAIL '}${label} — ${detail}`);
  if (!ok) failures += 1;
};

const b64url = (buf) => buf.toString('base64url');
const pkce = () => {
  const verifier = b64url(randomBytes(32));
  return { verifier, challenge: b64url(createHash('sha256').update(verifier).digest()) };
};

/** Bank backend: builds the request; the page never does. */
const authorizeUrl = (base, p, state) => `${base}/authorize?response_type=code&client_id=bank-lab`
  + `&redirect_uri=${encodeURIComponent('https://bank.example/cb')}`
  + `&scope=${encodeURIComponent('openid dpv:FraudPreventionAndDetection number-verification:verify')}`
  + `&state=${state}&nonce=n-${state}&code_challenge=${p.challenge}&code_challenge_method=S256&prompt=none`;

/** What the browser does with the navigation: one GET, redirect not followed. */
const browserGet = (url, localAddress, localPort) => new Promise((resolve, reject) => {
  const req = http.request(url, { method: 'GET', localAddress, localPort }, (res) => {
    res.resume();
    resolve({ status: res.statusCode, location: res.headers.location });
  });
  req.on('error', reject);
  req.end();
});

const backendVerdict = async (code, p) => {
  const token = await fetch(`${CAM}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code', code, redirect_uri: 'https://bank.example/cb',
      code_verifier: p.verifier, client_id: 'bank-lab',
    }),
  }).then((r) => r.json());
  if (!token.access_token) return `token exchange failed: ${JSON.stringify(token)}`;
  const r = await fetch(`${CAM}/camara/number-verification/v2/verify`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json', Authorization: `Bearer ${token.access_token}`,
      'x-correlator': `web-e2e-${Date.now()}`,
    },
    body: JSON.stringify({ phoneNumber: MSISDN }),
  });
  return `${r.status} ${await r.text()}`;
};

const cellular = { connection: { type: 'cellular', effectiveType: '4g' } };
const wifi = { connection: { type: 'wifi' } };

console.log('== A. cellular page: silent navigation from the cell address 10.20.30.40');
{
  const p = pkce();
  let navigated = null;
  const r = startAuthorization(authorizeUrl(CAM_FROM_CELL, p, 'web-a'), {
    allowInsecureHttp: true, navigator: cellular, location: { assign: (u) => { navigated = u; } },
  });
  check('start', r.method === AuthMethod.NETWORK && navigated !== null, JSON.stringify(r));
  const hop = await browserGet(navigated, '10.20.30.40', 55555);
  const cb = parseAuthorizationCallback(hop.location, 'web-a');
  check('callback', hop.status === 302 && cb.code.length > 0, `HTTP ${hop.status}, code <redacted>`);
  const v = await backendVerdict(cb.code, p);
  check('backend /verify', v.includes('"devicePhoneNumberVerified":true'), v);
}

console.log('== B. Wi-Fi page without a token: refused before navigation');
{
  let navigated = false;
  try {
    startAuthorization(authorizeUrl(CAM, pkce(), 'web-b'), {
      allowInsecureHttp: true, navigator: wifi, location: { assign: () => { navigated = true; } },
    });
    check('refused', false, 'navigated');
  } catch (e) {
    check('refused', e instanceof CellularUnavailableError && !navigated, e.code);
  }
}

console.log('== C. Wi-Fi page with a TS.43 operator token: SIM-based');
{
  const p = pkce();
  const issued = await fetch(`${ECS}/entitlement/issue`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ msisdn: MSISDN, imsi: '636010000000001', eapMethod: 'EAP-AKA' }),
  }).then((r) => r.json());
  let navigated = null;
  const r = startAuthorization(authorizeUrl(CAM, p, 'web-c'), {
    allowInsecureHttp: true, navigator: wifi, operatorToken: issued.token,
    location: { assign: (u) => { navigated = u; } },
  });
  check('start', r.method === AuthMethod.OPERATOR_TOKEN, JSON.stringify(r));
  const hop = await browserGet(navigated, '127.0.0.1');
  const cb = parseAuthorizationCallback(hop.location, 'web-c');
  check('callback', hop.status === 302 && cb.code.length > 0, `HTTP ${hop.status}, code <redacted>`);
  verdictOrGap('backend /verify', await backendVerdict(cb.code, p));
}

console.log('== D. a page on an address no PGW assigned: the operator fails closed');
{
  let navigated = null;
  startAuthorization(authorizeUrl(CAM, pkce(), 'web-d'), {
    allowInsecureHttp: true, navigator: {}, location: { assign: (u) => { navigated = u; } },
  });
  const hop = await browserGet(navigated, '127.0.0.1');
  try {
    parseAuthorizationCallback(hop.location, 'web-d');
    check('denied', false, 'got a code');
  } catch (e) {
    check('denied', e instanceof AuthorizationError && e.error === 'access_denied', e.message);
  }
}

console.log(failures === 0 ? 'ALL OK' : `${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
