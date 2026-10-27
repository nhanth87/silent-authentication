/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Type declarations for the hand-off presentation helpers.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

export declare const HandOffKind: Readonly<{ QR: 'qr'; LINK: 'link' }>;
export declare const Platform: Readonly<{ IOS: 'ios'; ANDROID: 'android'; DESKTOP: 'desktop' }>;

export declare function detectPlatform(nav?: Navigator): 'ios' | 'android' | 'desktop';

export declare function handOffLink(sessionId: string, options: { linkBase: string }): string;

export declare function presentHandOff(options: {
  sessionId: string;
  linkBase: string;
  navigator?: Navigator;
}): { kind: 'qr' | 'link'; platform: 'ios' | 'android' | 'desktop'; url: string };

export declare function appClipBannerTag(options: {
  appStoreId: string;
  appClipBundleId: string;
}): string;
