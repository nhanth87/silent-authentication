/*
 * Silent Auth UE SDK (Web) — Restlink (Ethiopia).
 * Type declarations for the CAMARA Number Verification v2 browser module.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

export declare const AuthMethod: Readonly<{
  NETWORK: 'NETWORK';
  OPERATOR_TOKEN: 'OPERATOR_TOKEN';
}>;

export type AuthMethodName = 'NETWORK' | 'OPERATOR_TOKEN';

export declare class AuthorizationError extends Error {
  constructor(error: string, description?: string | null, status?: number);
  readonly name: 'AuthorizationError';
  readonly code: 'AUTHORIZATION_REFUSED';
  /** OAuth error code (RFC 6749 §4.1.2.1) or an SDK-side code. */
  readonly error: string;
  /** Server free text; keep it out of logs. */
  readonly description: string | null;
  /** HTTP status of a direct error answer, 0 for a redirected one. */
  readonly status: number;
  readonly retryLater: boolean;
}

export declare class SimCredentialUnsupportedError extends Error {
  readonly name: 'SimCredentialUnsupportedError';
  readonly code: 'SIM_CREDENTIAL_UNSUPPORTED';
}

export interface ParsedAuthorizeUrl {
  url: URL;
  state: string;
  redirectUri: URL;
  hasLoginHint: boolean;
}

export declare function queryParams(search: string): Map<string, string>;

export declare function parseAuthorizeUrl(
  authorizeUrl: string,
  options?: { allowInsecureHttp?: boolean },
): ParsedAuthorizeUrl;

export declare function withOperatorToken(parsed: ParsedAuthorizeUrl, token: string): string;

export interface StartAuthorizationOptions {
  /** GSMA TS.43 operator token (single use); selects the SIM-based path. */
  operatorToken?: string | null;
  /** Default 'refuse-non-cellular'; 'require-cellular' also refuses an unknown bearer. */
  bearerPolicy?: 'refuse-non-cellular' | 'require-cellular';
  /** Lab only. */
  allowInsecureHttp?: boolean;
  navigator?: Navigator;
  location?: { assign(url: string): void };
}

export declare function startAuthorization(
  authorizeUrl: string,
  options?: StartAuthorizationOptions,
): { method: AuthMethodName; accessTech: string };

export declare function parseAuthorizationCallback(
  callbackUrl: string,
  expectedState: string,
): { code: string; state: string };

export interface DigitalCredentialRequest {
  protocol: string;
  data: unknown;
}

export declare function requestSimCredential(options: {
  requests: DigitalCredentialRequest[];
  signal?: AbortSignal;
  credentials?: { get(options: unknown): Promise<unknown> };
  globals?: object;
}): Promise<{ protocol: string; data: unknown }>;
