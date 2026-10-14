/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Choosing how the hand-off session reaches the phone: QR or a tappable link.
 * ES2020, zero deps. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import { isSessionId } from './qr-session.js';

/**
 * A hand-off session (`POST /qr-session/v1`) is just an id with a 120 s life and
 * a single claim. How it reaches the phone depends on where the user is:
 *
 *   desktop  → QR code of the link; the iPhone camera opens the App Clip card
 *              (or the Android bank app), which claims over cellular
 *   iPhone   → the SAME link as a button / Safari App Clip card. A phone cannot
 *              scan its own screen. Tapping opens the App Clip, which claims over
 *              cellular even with Wi-Fi on; this page keeps polling the status.
 *   Android  → the same link as a button; with the bank app installed an Android
 *              App Link opens it (Android has no App Clip equivalent any more)
 *
 * iOS never launches an App Clip silently: one tap on its card is always needed.
 *
 * The link must be `https://<the bank's App Clip host>/…/<session id>`:
 * - the App Clip receives exactly this URL (`NSUserActivity.webpageURL`), and
 *   Safari's `app-argument` is not passed to App Clips, so the id lives in the path;
 * - the App Clip only accepts its bank's hosts (`AppClipInvocation`);
 * - a custom scheme (`silentauth://…`) would not open an App Clip from the camera.
 *
 * The page served at that URL must carry the Smart App Banner tag from
 * `appClipBannerTag()` in its HTML. Safari reads it at load, so inserting it
 * from script afterwards is not reliable.
 */

export const HandOffKind = Object.freeze({ QR: 'qr', LINK: 'link' });
export const Platform = Object.freeze({ IOS: 'ios', ANDROID: 'android', DESKTOP: 'desktop' });

/**
 * Best-effort platform of the page. iPadOS presents a desktop Safari user agent,
 * so a "Macintosh" with touch points is an iPad.
 *
 * @param {Navigator} [nav]
 * @returns {string} one of Platform
 */
export function detectPlatform(nav = globalThis.navigator) {
  const ua = nav && typeof nav.userAgent === 'string' ? nav.userAgent : '';
  if (/\b(iPhone|iPad|iPod)\b/.test(ua)) return Platform.IOS;
  if (/\bMacintosh\b/.test(ua) && nav && Number(nav.maxTouchPoints) > 1) return Platform.IOS;
  if (/\bAndroid\b/.test(ua)) return Platform.ANDROID;
  return Platform.DESKTOP;
}

/**
 * The link the phone opens: `<linkBase>/<sessionId>`.
 *
 * @param {string} sessionId from `POST /qr-session/v1`
 * @param {{linkBase: string}} options e.g. `https://clip.bank.example/s`
 * @returns {string}
 * @throws {TypeError} for a non-https base, a base with query/fragment, or a bad id
 */
export function handOffLink(sessionId, { linkBase } = {}) {
  if (!isSessionId(sessionId)) throw new TypeError('not a hand-off session id');
  let base;
  try {
    base = new URL(linkBase);
  } catch {
    throw new TypeError('linkBase must be an absolute https URL');
  }
  if (base.protocol !== 'https:') throw new TypeError('linkBase must be an absolute https URL');
  if (base.search || base.hash || base.username || base.password) {
    throw new TypeError('linkBase must not carry a query, fragment or credentials');
  }
  const path = base.pathname.endsWith('/') ? base.pathname : `${base.pathname}/`;
  return `${base.origin}${path}${sessionId.toLowerCase()}`;
}

/**
 * What to show the user for this session on this device.
 *
 * @param {object} options
 * @param {string} options.sessionId
 * @param {string} options.linkBase
 * @param {Navigator} [options.navigator]
 * @returns {{kind: string, platform: string, url: string}}
 *   kind `qr`: render `url` as a QR code; kind `link`: render a button to `url`
 */
export function presentHandOff({ sessionId, linkBase, navigator: nav = globalThis.navigator } = {}) {
  const url = handOffLink(sessionId, { linkBase });
  const platform = detectPlatform(nav);
  return { kind: platform === Platform.DESKTOP ? HandOffKind.QR : HandOffKind.LINK, platform, url };
}

/**
 * The Smart App Banner tag that makes Safari (iOS 15+) show the App Clip card,
 * for the bank to render server-side in the page served at the hand-off link.
 * `app-id` lets devices that cannot run App Clips open the full app instead.
 *
 * @param {{appStoreId: string, appClipBundleId: string}} options
 * @returns {string} `<meta name="apple-itunes-app" content="…">`
 */
export function appClipBannerTag({ appStoreId, appClipBundleId } = {}) {
  if (typeof appStoreId !== 'string' || !/^[0-9]{1,15}$/.test(appStoreId)) {
    throw new TypeError('appStoreId must be the numeric App Store id');
  }
  if (typeof appClipBundleId !== 'string' || !/^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/.test(appClipBundleId)) {
    throw new TypeError('appClipBundleId must be a reverse-DNS bundle id');
  }
  return `<meta name="apple-itunes-app" content="app-id=${appStoreId}, `
    + `app-clip-bundle-id=${appClipBundleId}, app-clip-display=card">`;
}
