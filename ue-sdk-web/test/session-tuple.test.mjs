/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Tests: wire contract of SessionTupleClient (method, path, headers, body
 * shape, status propagation, timeout rejection).
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {
  SessionTupleClient,
  sessionTupleBody,
  trimTrailingSlash,
} from '../src/session-tuple.js';

async function listen(handler) {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return server;
}

function baseUrlOf(server) {
  return `http://127.0.0.1:${server.address().port}`;
}

async function stopServer(server) {
  if (typeof server.closeAllConnections === 'function') {
    server.closeAllConnections();
  }
  await new Promise((resolve) => server.close(resolve));
}

function recordRequest(recorder, req) {
  recorder.method = req.method;
  recorder.url = req.url;
  recorder.apiKey = req.headers['x-api-key'];
  recorder.contentType = req.headers['content-type'];
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => {
      data += chunk;
    });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

test('posts exact body shape and headers', async () => {
  const recorder = {};
  let rawBody = '';
  const server = await listen((req, res) => {
    recordRequest(recorder, req);
    readBody(req).then((body) => {
      rawBody = body;
      res.statusCode = 200;
      res.end();
    });
  });
  try {
    const originalNow = Date.now;
    Date.now = () => 1724200000000;
    let result;
    try {
      const client = new SessionTupleClient({
        baseUrl: baseUrlOf(server) + '/',
        apiKey: 'secret-key',
      });
      result = await client.send({ msisdn: '+251911111111' });
    } finally {
      Date.now = originalNow;
    }

    assert.deepEqual(result, { status: 200 });
    assert.equal(recorder.method, 'POST');
    assert.equal(recorder.url, '/session-tuple');
    assert.equal(recorder.contentType, 'application/json');
    assert.equal(recorder.apiKey, 'secret-key');
    assert.equal(
      rawBody,
      '{"ts":1724200000000,"msisdn":"+251911111111"}',
      'devices cannot observe CGNAT ip/port; only ts + optional msisdn'
    );
  } finally {
    await stopServer(server);
  }
});

test('omits null fields and X-Api-Key header when absent', async () => {
  const recorder = {};
  let rawBody = '';
  const server = await listen((req, res) => {
    recordRequest(recorder, req);
    readBody(req).then((body) => {
      rawBody = body;
      res.statusCode = 200;
      res.end();
    });
  });
  try {
    const originalNow = Date.now;
    Date.now = () => 1724200000001;
    let result;
    try {
      const client = new SessionTupleClient({ baseUrl: baseUrlOf(server) });
      result = await client.send();
    } finally {
      Date.now = originalNow;
    }

    assert.deepEqual(result, { status: 200 });
    assert.equal(rawBody, '{"ts":1724200000001}');
    assert.ok(!rawBody.includes('null'), 'no null tokens on the wire');
    assert.equal(recorder.apiKey, undefined);
  } finally {
    await stopServer(server);
  }
});

test('propagates non-2xx status from server', async () => {
  const server = await listen((req, res) => {
    readBody(req).then(() => {
      res.statusCode = 401;
      res.setHeader('Content-Type', 'application/json');
      res.end('{"code":"UNAUTHENTICATED"}');
    });
  });
  try {
    const client = new SessionTupleClient({
      baseUrl: baseUrlOf(server),
      apiKey: 'wrong-key',
    });
    const result = await client.send();
    assert.deepEqual(result, { status: 401 });
  } finally {
    await stopServer(server);
  }
});

test('rejects on timeout and reports via onError', async () => {
  let onErrorSeen = 0;
  const server = await listen(() => {
    // hang: never respond
  });
  try {
    const client = new SessionTupleClient({
      baseUrl: baseUrlOf(server),
      timeoutMs: 100,
      onError: () => {
        onErrorSeen += 1;
      },
    });
    await assert.rejects(client.send(), (error) => error.name === 'AbortError');
    assert.equal(onErrorSeen, 1);
  } finally {
    await stopServer(server);
  }
});

test('throws on connection refused', async () => {
  // Port 1 on loopback: nothing listens there.
  const client = new SessionTupleClient({ baseUrl: 'http://127.0.0.1:1' });
  await assert.rejects(client.send());
});

test('builds session tuple body omitting nulls', () => {
  assert.deepEqual(
    sessionTupleBody({ srcIp: null, srcPort: null, ts: 1724200000002, msisdn: '+251911111111', imsi: null }),
    { ts: 1724200000002, msisdn: '+251911111111' }
  );
  assert.deepEqual(
    sessionTupleBody({ srcIp: '10.20.30.40', srcPort: null, ts: 1724200000003, msisdn: null, imsi: null }),
    { srcIp: '10.20.30.40', ts: 1724200000003 }
  );
});

test('trims trailing slash on base URL', () => {
  assert.equal(trimTrailingSlash('http://h/'), 'http://h');
  assert.equal(trimTrailingSlash('http://h'), 'http://h');
});
