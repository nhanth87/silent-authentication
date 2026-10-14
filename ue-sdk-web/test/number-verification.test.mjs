/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Tests: CAMARA NV v2 browser side — request rules, the bearer decision,
 * callback parsing and the Digital Credentials route.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  AuthMethod, AuthorizationError, SimCredentialUnsupportedError,
  parseAuthorizeUrl, parseAuthorizationCallback, requestSimCredential,
  startAuthorization, withOperatorToken,
} from '../src/number-verification.js';
import { CellularUnavailableError } from '../src/session-tuple.js';

const OK = 'https://sas.example.et/authorize?response_type=code&client_id=bank'
  + '&redirect_uri=https%3A%2F%2Fbank.example%2Fcb&scope=openid%20number-verification%3Averify'
  + '&state=s1&nonce=n1&code_challenge=Q2hhbGxlbmdl&code_challenge_method=S256&prompt=none';

const without = (p) => OK.replace(new RegExp(`&${p}=[^&]*`), '');
const nav = (type, effectiveType) => ({ connection: { type, effectiveType } });
const recorder = () => {
  const visited = [];
  return { visited, assign: (u) => visited.push(u) };
};
const rule = (url, opts) => {
  try {
    parseAuthorizeUrl(url, opts);
  } catch (e) {
    assert.ok(e instanceof TypeError);
    return e.message;
  }
  throw new Error('expected a refusal');
};

test('a valid request parses', () => {
  const p = parseAuthorizeUrl(OK);
  assert.equal(p.state, 's1');
  assert.equal(p.redirectUri.href, 'https://bank.example/cb');
  assert.equal(p.hasLoginHint, false);
});

test('silent + PKCE rules are enforced', () => {
  assert.match(rule(without('prompt')), /prompt=none/);
  assert.match(rule(OK.replace('prompt=none', 'prompt=login')), /prompt=none/);
  assert.match(rule(without('code_challenge')), /PKCE/);
  assert.match(rule(OK.replace('S256', 'plain')), /PKCE/);
  assert.match(rule(without('state')), /state/);
  assert.match(rule(without('client_id')), /client_id/);
  assert.match(rule(without('scope')), /scope/);
  assert.match(rule(without('redirect_uri')), /redirect_uri/);
  assert.match(rule(OK.replace('response_type=code', 'response_type=token')), /response_type/);
});

test('transport and pollution rules', () => {
  assert.match(rule(OK.replace('https://', 'http://')), /https/);
  parseAuthorizeUrl(OK.replace('https://', 'http://'), { allowInsecureHttp: true });
  assert.match(rule('/authorize'), /absolute/);
  assert.match(rule(''), /empty/);
  assert.match(rule(`${OK}&redirect_uri=https%3A%2F%2Fevil.example%2Fcb`), /repeated/);
  const msg = rule(`${without('prompt')}&login_hint=operatortoken%3Asecret`);
  assert.ok(!msg.includes('secret') && !msg.includes('sas.example.et'), 'never echo the URL');
});

test('operator token is appended encoded, never over a backend hint', () => {
  const url = withOperatorToken(parseAuthorizeUrl(OK), 'a+b/c=');
  assert.equal(new URL(url).searchParams.get('login_hint'), 'operatortoken:a+b/c=');
  assert.ok(url.endsWith('&login_hint=operatortoken%3Aa%2Bb%2Fc%3D'));
  const hinted = parseAuthorizeUrl(`${OK}&login_hint=tel%3A%2B251911111111`);
  assert.throws(() => withOperatorToken(hinted, 't'), /login_hint/);
  assert.throws(() => withOperatorToken(parseAuthorizeUrl(OK), ' '), /empty/);
});

test('cellular bearer navigates the request as is', () => {
  const loc = recorder();
  const r = startAuthorization(OK, { navigator: nav('cellular', '4g'), location: loc });
  assert.deepEqual(r, { method: AuthMethod.NETWORK, accessTech: 'LTE' });
  assert.deepEqual(loc.visited, [OK]);
});

test('Wi-Fi without a token is refused before any navigation', () => {
  const loc = recorder();
  assert.throws(() => startAuthorization(OK, { navigator: nav('wifi'), location: loc }),
    (e) => e instanceof CellularUnavailableError && e.observed === 'WIFI');
  assert.throws(() => startAuthorization(OK, { navigator: nav('ethernet'), location: loc }),
    CellularUnavailableError);
  assert.equal(loc.visited.length, 0);
});

test('unknown bearer: allowed by default (server fails closed), refused when strict', () => {
  const loc = recorder();
  assert.equal(startAuthorization(OK, { navigator: {}, location: loc }).method, AuthMethod.NETWORK);
  assert.throws(() => startAuthorization(OK, {
    navigator: {}, location: loc, bearerPolicy: 'require-cellular',
  }), CellularUnavailableError);
  assert.equal(loc.visited.length, 1);
  assert.throws(() => startAuthorization(OK, { location: loc, bearerPolicy: 'maybe' }), /bearerPolicy/);
});

test('a token selects the SIM-based path on any bearer', () => {
  const loc = recorder();
  const r = startAuthorization(OK, { navigator: nav('wifi'), location: loc, operatorToken: 'tk-1' });
  assert.equal(r.method, AuthMethod.OPERATOR_TOKEN);
  assert.equal(new URL(loc.visited[0]).searchParams.get('login_hint'), 'operatortoken:tk-1');
});

test('a malformed request never navigates', () => {
  const loc = recorder();
  assert.throws(() => startAuthorization(without('prompt'), { navigator: nav('cellular', '4g'), location: loc }));
  assert.equal(loc.visited.length, 0);
});

test('callback: code with the right state', () => {
  assert.deepEqual(parseAuthorizationCallback('https://bank.example/cb?code=c-1&state=s1', 's1'),
    { code: 'c-1', state: 's1' });
});

test('callback: every refusal is an AuthorizationError', () => {
  const err = (url, state = 's1') => {
    try {
      parseAuthorizationCallback(url, state);
    } catch (e) {
      assert.ok(e instanceof AuthorizationError);
      return e;
    }
    throw new Error('expected a refusal');
  };
  const denied = err('https://bank.example/cb?error=access_denied&error_description=no%20binding&state=s1');
  assert.equal(denied.error, 'access_denied');
  assert.equal(denied.description, 'no binding');
  assert.ok(!denied.message.includes('no binding'), 'free text stays off message');
  assert.equal(err('https://bank.example/cb?code=c&state=other').error, 'invalid_state');
  assert.equal(err('https://bank.example/cb?code=c&state=s').error, 'invalid_state');
  assert.equal(err('https://bank.example/cb?state=s1').error, 'invalid_response');
  assert.equal(err('not a url').error, 'invalid_response');
  assert.equal(err('https://bank.example/cb?code=a&code=b&state=s1').error, 'invalid_response');
  assert.equal(new AuthorizationError('server_error').retryLater, true);
  assert.equal(new AuthorizationError('access_denied').retryLater, false);
});

test('Digital Credentials: passes the backend request through and returns the answer', async () => {
  let asked;
  const credentials = {
    get: async (o) => {
      asked = o;
      return { protocol: 'openid4vp-v1-unsigned', data: { vp_token: 'x' } };
    },
  };
  const requests = [{ protocol: 'openid4vp-v1-unsigned', data: { nonce: 'n' } }];
  const r = await requestSimCredential({
    requests, credentials, globals: { DigitalCredential: function DigitalCredential() {}, isSecureContext: true },
  });
  assert.deepEqual(asked.digital.requests, requests);
  assert.deepEqual(r, { protocol: 'openid4vp-v1-unsigned', data: { vp_token: 'x' } });
});

test('Digital Credentials: absent API, insecure context or empty answer is unsupported', async () => {
  const requests = [{ protocol: 'p', data: {} }];
  const credentials = { get: async () => null };
  await assert.rejects(requestSimCredential({ requests, credentials, globals: {} }),
    SimCredentialUnsupportedError);
  await assert.rejects(requestSimCredential({
    requests, credentials, globals: { DigitalCredential() {}, isSecureContext: false },
  }), SimCredentialUnsupportedError);
  await assert.rejects(requestSimCredential({
    requests, credentials, globals: { DigitalCredential: function D() {} },
  }), SimCredentialUnsupportedError);
  await assert.rejects(requestSimCredential({ requests: [], credentials, globals: {} }), TypeError);
});

test('there is no navigator.getSimToken to lean on', () => {
  assert.equal(typeof globalThis.navigator?.getSimToken, 'undefined');
});
