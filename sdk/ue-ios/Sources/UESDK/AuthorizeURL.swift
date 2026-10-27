/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * CAMARA Number Verification v2: the authorization request rules.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation

/// The OIDC authorization request the bank backend built, checked before a byte
/// leaves the device.
///
/// The device never builds it: the backend is the OAuth client and keeps the
/// `client_id`, the PKCE `code_verifier`, `state` and `nonce`, and it
/// authenticates to the token endpoint with `private_key_jwt`. The SDK checks
/// the request is one it can make **silently** and **safely**: https (http only
/// with a lab switch), `response_type=code`, PKCE `S256` with a challenge,
/// `prompt=none`, `state`, `scope`, `client_id`, an absolute `redirect_uri`, and
/// no parameter repeated. Errors name the rule and never echo the URL — the
/// query can carry an operator token.
public struct AuthorizeURL: Equatable {

    public static let operatorTokenHintPrefix = "operatortoken:"

    /// The request URL to send.
    public let url: URL
    /// The `state` the redirect must carry back.
    public let state: String
    /// Where the authorization server sends the result — never loaded by the SDK.
    public let redirectURI: URL
    /// True when the backend already chose a `login_hint`.
    public let hasLoginHint: Bool

    /// A rule the request breaks.
    public struct Invalid: Error, Equatable, CustomStringConvertible {
        public let rule: String
        public var description: String { rule }
    }

    public init(_ raw: String, allowInsecureHTTP: Bool = false) throws {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw Invalid(rule: "authorization URL is empty") }
        guard let url = URL(string: trimmed), url.scheme != nil, url.host != nil else {
            throw Invalid(rule: "authorization URL must be absolute with a host")
        }
        try AuthorizeURL.requireTransport(url, allowInsecureHTTP: allowInsecureHTTP,
                                          what: "authorization URL")
        let params = try AuthorizeURL.query(url)
        func require(_ ok: Bool, _ rule: String) throws {
            if !ok { throw Invalid(rule: rule) }
        }
        try require(params["response_type"] == "code", "response_type must be code")
        try require(AuthorizeURL.nonBlank(params["client_id"]), "client_id is missing")
        try require(AuthorizeURL.nonBlank(params["scope"]), "scope is missing")
        try require(AuthorizeURL.nonBlank(params["state"]), "state is missing")
        try require(params["code_challenge_method"] == "S256"
                    && AuthorizeURL.nonBlank(params["code_challenge"]),
                    "PKCE code_challenge with code_challenge_method=S256 is required")
        try require(params["prompt"] == "none",
                    "prompt=none is required: Number Verification must not show any page")
        guard let redirect = params["redirect_uri"], AuthorizeURL.nonBlank(redirect) else {
            throw Invalid(rule: "redirect_uri is missing")
        }
        guard let redirectURI = URL(string: redirect), redirectURI.scheme != nil else {
            throw Invalid(rule: "redirect_uri must be absolute")
        }
        self.url = url
        self.state = params["state"] ?? ""
        self.redirectURI = redirectURI
        self.hasLoginHint = params["login_hint"] != nil
    }

    /// The same request with `login_hint=operatortoken:<token>` — the TS.43 path.
    /// Refused when the backend already set a hint.
    public func withOperatorToken(_ token: String) throws -> URL {
        let bare = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !bare.isEmpty else { throw Invalid(rule: "operator token is empty") }
        guard !hasLoginHint else { throw Invalid(rule: "the request already carries a login_hint") }
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw Invalid(rule: "authorization URL is not a valid URI")
        }
        let hint = "login_hint=" + AuthorizeURL.encode(AuthorizeURL.operatorTokenHintPrefix + bare)
        if let query = components.percentEncodedQuery, !query.isEmpty {
            components.percentEncodedQuery = query + "&" + hint
        } else {
            components.percentEncodedQuery = hint
        }
        guard let out = components.url else { throw Invalid(rule: "authorization URL is not a valid URI") }
        return out
    }

    /// True when `location` is the registered redirect: same scheme, host, port
    /// and path. The query holds the result, so it is not compared.
    func isRedirect(_ location: URL) -> Bool {
        guard let a = URLComponents(url: redirectURI, resolvingAgainstBaseURL: false),
              let b = URLComponents(url: location, resolvingAgainstBaseURL: false) else { return false }
        let path: (URLComponents) -> String = { $0.percentEncodedPath.isEmpty ? "/" : $0.percentEncodedPath }
        return a.scheme?.lowercased() == b.scheme?.lowercased()
            && a.host?.lowercased() == b.host?.lowercased()
            && a.port == b.port
            && path(a) == path(b)
    }

    /// Turns the redirect into a result, checking `state` in constant time.
    func complete(_ location: URL, method: AuthorizationResult.Method,
                  accessTech: AccessTech) throws -> AuthorizationResult {
        let result: [String: String]
        do {
            result = try AuthorizeURL.query(location)
        } catch {
            throw AuthorizationError("invalid_response", "the redirect query is malformed")
        }
        if let error = result["error"] {
            throw AuthorizationError(error, result["error_description"])
        }
        guard let code = result["code"], AuthorizeURL.nonBlank(code) else {
            throw AuthorizationError("invalid_response", "the redirect carries no code")
        }
        guard AuthorizeURL.constantTimeEquals(state, result["state"]) else {
            throw AuthorizationError("invalid_state", "the redirect state does not match this request")
        }
        return AuthorizationResult(code: code, state: state, method: method, accessTech: accessTech)
    }

    // MARK: - helpers

    static func requireTransport(_ url: URL, allowInsecureHTTP: Bool, what: String) throws {
        switch url.scheme?.lowercased() {
        case "https": return
        case "http" where allowInsecureHTTP: return
        default: throw Invalid(rule: "\(what) must use https")
        }
    }

    /// Decodes the query; a repeated parameter is refused, never resolved.
    static func query(_ url: URL) throws -> [String: String] {
        var out: [String: String] = [:]
        guard let raw = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedQuery,
              !raw.isEmpty else { return out }
        for pair in raw.split(separator: "&", omittingEmptySubsequences: true) {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let decode: (Substring) -> String? = {
                String($0).replacingOccurrences(of: "+", with: " ").removingPercentEncoding
            }
            guard let key = decode(parts[0]) else { throw Invalid(rule: "query is not valid percent-encoding") }
            let value = parts.count > 1 ? decode(parts[1]) : ""
            guard let decoded = value else { throw Invalid(rule: "query is not valid percent-encoding") }
            if out[key] != nil { throw Invalid(rule: "parameter repeated: \(key)") }
            out[key] = decoded
        }
        return out
    }

    static func encode(_ s: String) -> String {
        // RFC 3986 unreserved, ASCII only: CharacterSet.alphanumerics would let
        // non-ASCII letters through unencoded.
        let allowed = CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }

    static func nonBlank(_ s: String?) -> Bool {
        guard let s = s else { return false }
        return !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func constantTimeEquals(_ expected: String?, _ actual: String?) -> Bool {
        guard let e = expected, let a = actual else { return false }
        let x = Array(e.utf8), y = Array(a.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<x.count { diff |= x[i] ^ y[i] }
        return diff == 0
    }
}
