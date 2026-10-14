/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Tests: QR on a desktop, a tappable App Clip link on the phone itself.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  HandOffKind, Platform, appClipBannerTag, detectPlatform, handOffLink, presentHandOff,
} from '../src/hand-off.js';

const ID = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';
const BASE = 'https://clip.bank.example/s';
const IPHONE = { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15', maxTouchPoints: 5 };
const IPAD = { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15', maxTouchPoints: 5 };
const MAC = { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15', maxTouchPoints: 0 };
const ANDROID = { userAgent: 'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36' };
const WINDOWS = { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' };

test('platform detection, iPadOS included', () => {
  assert.equal(detectPlatform(IPHONE), Platform.IOS);
  assert.equal(detectPlatform(IPAD), Platform.IOS);
  assert.equal(detectPlatform(MAC), Platform.DESKTOP);
  assert.equal(detectPlatform(ANDROID), Platform.ANDROID);
  assert.equal(detectPlatform(WINDOWS), Platform.DESKTOP);
  assert.equal(detectPlatform(undefined), Platform.DESKTOP);
});

test('a desktop gets a QR code, a phone gets the same link as a button', () => {
  const desk = presentHandOff({ sessionId: ID, linkBase: BASE, navigator: WINDOWS });
  assert.deepEqual(desk, { kind: HandOffKind.QR, platform: 'desktop', url: `${BASE}/${ID}` });
  const phone = presentHandOff({ sessionId: ID, linkBase: BASE, navigator: IPHONE });
  assert.deepEqual(phone, { kind: HandOffKind.LINK, platform: 'ios', url: `${BASE}/${ID}` });
  assert.equal(presentHandOff({ sessionId: ID, linkBase: BASE, navigator: ANDROID }).kind, HandOffKind.LINK);
});

test('the link is https on the bank host with the id in the path', () => {
  assert.equal(handOffLink(ID.toUpperCase(), { linkBase: `${BASE}/` }), `${BASE}/${ID}`);
  assert.equal(handOffLink(ID, { linkBase: 'https://clip.bank.example' }), `https://clip.bank.example/${ID}`);
  assert.throws(() => handOffLink(ID, { linkBase: 'http://clip.bank.example/s' }), /https/);
  assert.throws(() => handOffLink(ID, { linkBase: 'silentauth://qr-session' }), /https/);
  assert.throws(() => handOffLink(ID, { linkBase: `${BASE}?x=1` }), /query/);
  assert.throws(() => handOffLink(ID, { linkBase: 'https://u:p@clip.bank.example/s' }), /credentials/);
  assert.throws(() => handOffLink('not-a-session', { linkBase: BASE }), /session id/);
  assert.throws(() => handOffLink(ID, {}), /https/);
});

test('App Clip banner tag', () => {
  assert.equal(appClipBannerTag({ appStoreId: '123456789', appClipBundleId: 'et.bank.app.Clip' }),
    '<meta name="apple-itunes-app" content="app-id=123456789, app-clip-bundle-id=et.bank.app.Clip, app-clip-display=card">');
  assert.throws(() => appClipBannerTag({ appStoreId: 'abc', appClipBundleId: 'et.bank.app.Clip' }), /numeric/);
  assert.throws(() => appClipBannerTag({ appStoreId: '1', appClipBundleId: 'x"><script>' }), /bundle id/);
});
