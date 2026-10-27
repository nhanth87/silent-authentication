/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * CAMARA Number Verification v2: result, refusal and operator-token types.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation

/// The authorization code the operator issued — for the bank backend only.
///
/// Not a verdict: the backend redeems it (`private_key_jwt` + the PKCE verifier
/// only it holds) and calls `POST /number-verification/v2/verify`; that answer
/// is the only thing that authenticates anyone. Single use and short-lived, so
/// send it on at once. `description` redacts it.
public struct AuthorizationResult: Equatable, CustomStringConvertible {

    /// How the subscriber was authenticated.
    public enum Method: String, Equatable {
        /// Network-based: the request left over the cellular interface.
        case network = "NETWORK"
        /// SIM-based: a GSMA TS.43 operator token was the login hint.
        case operatorToken = "OPERATOR_TOKEN"
    }

    public let code: String
    public let state: String
    public let method: Method
    /// The bearer observed. Advisory, never an assurance input.
    public let accessTech: AccessTech

    public var description: String {
        "AuthorizationResult(method: \(method.rawValue), accessTech: \(accessTech.rawValue), code: <redacted>)"
    }
}

/// The authorization server refused, or answered in a way the SDK cannot use.
///
/// Always a fallback signal (OTP / passkey). `error` is the OAuth code
/// (`access_denied`, `login_required`, `invalid_request`, …) or an SDK one
/// (`invalid_state`, `invalid_response`, `too_many_redirects`). The server's
/// free text stays in `serverDescription`, off `description`, so logging the
/// error does not log text the bank does not control.
public struct AuthorizationError: Error, Equatable, CustomStringConvertible {
    public let error: String
    public let serverDescription: String?
    /// Status of a direct (non-redirect) answer; 0 for a redirected error.
    public let httpStatus: Int

    public init(_ error: String, _ serverDescription: String? = nil, httpStatus: Int = 0) {
        self.error = error.isEmpty ? "unknown_error" : error
        self.serverDescription = serverDescription
        self.httpStatus = httpStatus
    }

    /// True for the errors a later attempt may cure.
    public var retryLater: Bool {
        error == "temporarily_unavailable" || error == "server_error"
    }

    public var description: String {
        "authorization refused: \(error)" + (httpStatus > 0 ? " (HTTP \(httpStatus))" : "")
            + " - fall back to OTP, never proceed"
    }
}

/// Supplies a GSMA TS.43 temporary operator token — the SIM-based identity that
/// lets Number Verification work over Wi-Fi.
///
/// iOS gives a third-party app no API to run EAP-AKA against the SIM, so the SDK
/// cannot mint this token itself; it comes from what the operator provides (an
/// operator app, an OS service, or the Restlink entitlement server in the lab).
/// Return the bare token (no `operatortoken:` prefix) or nil. Tokens are single
/// use: return a fresh one per call. Never log it.
public protocol OperatorTokenSource {
    func fetchOperatorToken() async throws -> String?
}
