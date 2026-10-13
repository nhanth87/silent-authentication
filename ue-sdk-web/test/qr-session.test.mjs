/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { QrSessionClient, parsePayload, isSessionId, parseFlat } from '../src/qr-session.js';
import { QrState } from '../src/qr-session-state.js';

const ID = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';

test('parses the canonical scheme and a bare id', () => {
  assert.equal(parsePayload(`silentauth://qr-session/${ID}`), ID);
  assert.equal(parsePayload(`SILENTAUTH://QR-SESSION/${ID}`), ID);
  assert.equal(parsePayload(ID), ID);
  assert.equal(parsePayload(`  ${ID}  `), ID);
});

test('parses a payload carried in a url', () => {
  assert.equal(parsePayload(`https://bank.example/q?p=silentauth://qr-session/${ID}`), ID);
});

test('refuses anything that is not a uuid', () => {
  assert.throws(() => parsePayload(null), TypeError);
  assert.throws(() => parsePayload(''), TypeError);
  assert.throws(() => parsePayload('   '), TypeError);
  assert.throws(() => parsePayload('silentauth://qr-session/'), TypeError);
  assert.throws(() => parsePayload('not-a-session'), TypeError);
  assert.throws(() => parsePayload('silentauth://qr-session/../../etc/passwd'), TypeError);
  assert.throws(() => parsePayload(`${ID}/extra`), TypeError);
});

test('refuses a uuid-shaped string with the wrong separators', () => {
  assert.equal(isSessionId('3f2504e04f8911d39a0c0305e82c33010'), false);
  assert.equal(isSessionId('3f2504e0_4f89-11d3-9a0c-0305e82c3301'), false);
  assert.equal(isSessionId(ID), true);
});

test('the flat reader separates absent from false', () => {
  const absent = parseFlat('{"state":"PENDING"}');
  assert.equal(absent.devicePhoneNumberVerified, undefined);
  const explicitFalse = parseFlat('{"devicePhoneNumberVerified":false}');
  assert.equal(explicitFalse.devicePhoneNumberVerified, 'false');
});

test('the flat reader refuses malformed bodies instead of guessing', () => {
  assert.deepEqual(parseFlat(''), {});
  assert.deepEqual(parseFlat('not json at all'), {});
  assert.deepEqual(parseFlat('{"state":'), {});
  assert.deepEqual(parseFlat('{"nested":{"state":"RESOLVED"}}'), {});
  assert.deepEqual(parseFlat('[1,2,3]'), {});
});

test('the flat reader keeps a json null as absent', () => {
  const fields = parseFlat('{"state":"RESOLVED","fallbackReason":null}');
  assert.equal(fields.state, 'RESOLVED');
  assert.equal(fields.fallbackReason, null);
});

// A tiny fetch stub: the SDK must read a verdict, not decide one.
function stubFetch(responses) {
  const calls = [];
  let i = 0;
  global.fetch = async (url, init) => {
    calls.push({ url, init });
    const next = responses[Math.min(i, responses.length - 1)];
    i += 1;
    return {
      status: next.status ?? 200,
      text: async () => next.body,
    };
  };
  return calls;
}

test('create returns what the bank renders', async () => {
  const calls = stubFetch([{ body: JSON.stringify({
    sessionId: ID, qrPayload: `silentauth://qr-session/${ID}`, expiresInSeconds: 120,
  }) }]);
  const client = new QrSessionClient({ baseUrl: 'http://127.0.0.1:8085', authorization: 'Bearer lab' });

  const created = await client.create();

  assert.equal(created.sessionId, ID);
  assert.equal(created.qrPayload, `silentauth://qr-session/${ID}`);
  assert.equal(created.expiresInSeconds, 120);
  assert.ok(calls[0].url.endsWith('/qr-session/v1'));
  assert.equal(calls[0].init.method, 'POST');
});

test('create refuses a server answer with no usable session id', async () => {
  stubFetch([{ body: '{"sessionId":"not-a-uuid"}' }]);
  const client = new QrSessionClient({ baseUrl: 'http://127.0.0.1:8085', authorization: 'Bearer lab' });
  await assert.rejects(() => client.create(), /usable session id/);
});

test('status reports pending as not approved', async () => {
  stubFetch([{ body: '{"sessionId":"' + ID + '","state":"PENDING"}' }]);
  const client = new QrSessionClient({ baseUrl: 'http://127.0.0.1:8085', authorization: 'Bearer lab' });

  const status = await client.status(ID);

  assert.equal(status.state, QrState.PENDING);
  assert.equal(status.devicePhoneNumberVerified, null);
  assert.equal(status.approved, false, 'silence is not consent');
});

test('status approves only on an explicit true', async () => {
  stubFetch([{ body: '{"sessionId":"' + ID + '","state":"RESOLVED",'
    + '"devicePhoneNumberVerified":true,"decision":"APPROVED"}' }]);
  const client = new QrSessionClient({ baseUrl: 'http://127.0.0.1:8085', authorization: 'Bearer lab' });

  assert.equal((await client.status(ID)).approved, true);
});

test('status does not approve an explicit false', async () => {
  stubFetch([{ body: '{"state":"RESOLVED","devicePhoneNumberVerified":false,'
    + '"fallbackReason":"SIM_SWAP_SUSPECT"}' }]);
  const client = new QrSessionClient({ baseUrl: 'http://127.0.0.1:8085', authorization: 'Bearer lab' });

  const status = await client.status(ID);
  assert.equal(status.approved, false);
  assert.equal(status.fallbackReason, 'SIM_SWAP_SUSPECT');
});

test('an unreadable status is UNKNOWN, never approved', async () => {
  stubFetch([{ body: 'gateway timeout html' }]);
  const client = new QrSessionClient({ baseUrl: 'http://127.0.0.1:8085', authorization: 'Bearer lab' });

  const status = await client.status(ID);
  assert.equal(status.state, QrState.UNKNOWN);
  assert.equal(status.approved, false);
});

test('poll stops at the first resolved answer', async () => {
  const calls = stubFetch([
    { body: '{"state":"PENDING"}' },
    { body: '{"state":"PENDING"}' },
    { body: '{"state":"RESOLVED","devicePhoneNumberVerified":true}' },
    { body: '{"state":"RESOLVED","devicePhoneNumberVerified":true}' },
  ]);
  const client = new QrSessionClient({ baseUrl: 'http://127.0.0.1:8085', authorization: 'Bearer lab' });

  const seen = [];
  const status = await client.poll(ID, { intervalMs: 1, deadlineMs: 5000, onTick: (s) => seen.push(s.state) });

  assert.equal(status.approved, true);
  assert.deepEqual(seen, ['PENDING', 'PENDING', 'RESOLVED']);
  assert.equal(calls.length, 3, 'polling must stop once the session is resolved');
});

test('a client cannot be built without a bearer', () => {
  assert.throws(() => new QrSessionClient({ baseUrl: 'http://x' }), TypeError);
  assert.throws(() => new QrSessionClient({ authorization: 'Bearer lab' }), TypeError);
});
