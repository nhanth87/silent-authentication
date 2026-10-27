/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * CAMARA Number Verification v2, device side. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation

/// Device side of CAMARA Number Verification v2: carries the bank's OIDC
/// authorization request (`prompt=none`) over the operator's network and returns
/// the authorization code — no page, no SMS, no OTP.
///
/// ```
///  app ──► bank backend: start; backend builds /authorize?…&code_challenge(S256)&prompt=none
///  app ──► NumberVerification.authorize(url)
///          ├─ GET over NWConnection with requiredInterfaceType = .cellular
///          │     operator sees the cellular source address ─► subscriber
///          └─ no cellular path ─► OperatorTokenSource ─► GET &login_hint=operatortoken:…
///                                  over any interface (SIM-based, GSMA TS.43)
///          ◄── 302 redirect_uri?code&state  (caught, never loaded; state checked)
///  app ──► bank backend: code ─► /token (private_key_jwt + verifier) ─► POST /verify
/// ```
///
/// Outcomes:
/// - `AuthorizationResult` — a code for the backend; not a verdict.
/// - `AuthorizeURL.Invalid` — the backend built a request the device refuses.
/// - `CellularBearer.Unavailable` — no cellular path and no operator token.
/// - `AuthorizationError` — the authorization server refused.
/// - `TransportError` / other errors — transport failure after retries.
/// Every throw is a fallback signal: OTP / passkey, never "verified".
///
/// Timeouts: 5 s per hop, one retry on a transport error, 10 redirect hops, 15 s
/// for the whole call. OAuth errors are answers and are never retried.
///
/// Privacy: no logging; error descriptions carry rule names and OAuth codes only.
public final class NumberVerification {

    public struct Configuration {
        /// `.cellular` (default) or `.cellular4GPlus`; `.any` sends the network
        /// request without pinning (lab only).
        public var requirement: CellularBearer.Requirement = .cellular
        /// Source of the SIM-based token for the Wi-Fi path; nil disables it.
        public var operatorTokens: OperatorTokenSource?
        public var perHopTimeout: TimeInterval = 5
        public var maxRedirects = 10
        public var transportRetries = 1
        public var budget: TimeInterval = 15
        /// Accept `http://` hops. Lab only — never in a release build.
        public var allowInsecureHTTPForLab = false

        public init() {}
    }

    private let configuration: Configuration
    private let transport: AuthorizeTransport
    private let radio: () -> AccessTech
    private let observed: () -> AccessTech

    /// - Parameters:
    ///   - transport: defaults to `CellularHTTPClient` (Network.framework)
    ///   - radio: radio generation of the cellular interface (tests inject it)
    ///   - observed: the default-route bearer, reported on refusals (tests inject it)
    public init(configuration: Configuration = Configuration(),
                transport: AuthorizeTransport? = nil,
                radio: (() -> AccessTech)? = nil,
                observed: (() -> AccessTech)? = nil) {
        self.configuration = configuration
        #if canImport(Network)
        self.transport = transport ?? CellularHTTPClient()
        #else
        guard let transport = transport else {
            preconditionFailure("no Network.framework: pass a transport")
        }
        self.transport = transport
        #endif
        self.radio = radio ?? { CellularBearer.radioTech() }
        self.observed = observed ?? { CellularBearer().accessTech() }
    }

    /// Runs the authorization request the backend built and returns the code.
    public func authorize(_ authorizeURL: String) async throws -> AuthorizationResult {
        let deadline = Date().addingTimeInterval(configuration.budget)
        let request = try AuthorizeURL(authorizeURL,
                                       allowInsecureHTTP: configuration.allowInsecureHTTPForLab)
        let need = configuration.requirement
        var unavailable: CellularBearer.Unavailable?

        let tech = radio()
        if need == .cellular4GPlus && tech != .lte && tech != .nr {
            unavailable = CellularBearer.Unavailable(need, tech)
        }
        if unavailable == nil {
            do {
                return try await follow(request, start: request.url, cellularOnly: need != .any,
                                        method: .network, accessTech: tech.isCellular ? tech : .gs2g3g,
                                        deadline: deadline)
            } catch TransportError.cellularUnavailable {
                unavailable = CellularBearer.Unavailable(need, observed())
            } catch let denied as AuthorizationError
                        where denied.error == "access_denied"
                        && configuration.operatorTokens != nil && !request.hasLoginHint {
                // The network could not name the subscriber; the SIM still can.
            }
        }

        guard let source = configuration.operatorTokens, !request.hasLoginHint else {
            throw unavailable ?? CellularBearer.Unavailable(need, observed())
        }
        let token = (try? await source.fetchOperatorToken()) ?? nil
        guard let bare = token, AuthorizeURL.nonBlank(bare) else {
            if let unavailable = unavailable { throw unavailable }
            throw AuthorizationError("access_denied",
                                     "network authentication failed and no operator token is available")
        }
        return try await follow(request, start: try request.withOperatorToken(bare), cellularOnly: false,
                                method: .operatorToken, accessTech: observed(), deadline: deadline)
    }

    // MARK: - redirects

    private func follow(_ request: AuthorizeURL, start: URL, cellularOnly: Bool,
                        method: AuthorizationResult.Method, accessTech: AccessTech,
                        deadline: Date) async throws -> AuthorizationResult {
        var current = start
        for _ in 0...max(0, configuration.maxRedirects) {
            try AuthorizeURL.requireTransport(current, allowInsecureHTTP: configuration.allowInsecureHTTPForLab,
                                              what: "authorization hop")
            let answer = try await get(current, cellularOnly: cellularOnly, deadline: deadline)
            if [301, 302, 303, 307, 308].contains(answer.status) {
                guard let location = answer.location, !location.isEmpty else {
                    throw AuthorizationError("invalid_response", "redirect without a Location",
                                             httpStatus: answer.status)
                }
                guard let next = URL(string: location, relativeTo: current)?.absoluteURL else {
                    throw AuthorizationError("invalid_response", "redirect Location is not a URI",
                                             httpStatus: answer.status)
                }
                if request.isRedirect(next) {
                    return try request.complete(next, method: method, accessTech: accessTech)
                }
                current = next
                continue
            }
            throw NumberVerification.direct(answer)
        }
        throw AuthorizationError("too_many_redirects", "more than \(configuration.maxRedirects) redirects")
    }

    static func direct(_ answer: HTTPHead) -> AuthorizationError {
        if (200..<300).contains(answer.status) {
            return AuthorizationError("interaction_required",
                                      "the authorization server answered with a page instead of a redirect",
                                      httpStatus: answer.status)
        }
        let pattern = "\"error\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,64})\""
        if let regex = try? NSRegularExpression(pattern: pattern),
           let match = regex.firstMatch(in: answer.body, range: NSRange(answer.body.startIndex..., in: answer.body)),
           let range = Range(match.range(at: 1), in: answer.body) {
            return AuthorizationError(String(answer.body[range]), nil, httpStatus: answer.status)
        }
        return AuthorizationError(answer.status >= 500 ? "server_error" : "invalid_request", nil,
                                  httpStatus: answer.status)
    }

    // MARK: - transport

    private func get(_ url: URL, cellularOnly: Bool, deadline: Date) async throws -> HTTPHead {
        var last: Error = TransportError.timedOut
        for attempt in 0...max(0, configuration.transportRetries) {
            let left = deadline.timeIntervalSinceNow
            guard left > 0 else { throw TransportError.timedOut }
            if attempt > 0 {
                let pause = min(0.25 * Double(attempt), left)
                try await Task.sleep(nanoseconds: UInt64(pause * 1_000_000_000))
            }
            do {
                return try await transport.get(url, cellularOnly: cellularOnly,
                                               timeout: min(configuration.perHopTimeout,
                                                            max(0.001, deadline.timeIntervalSinceNow)))
            } catch TransportError.cellularUnavailable {
                // Not a blip: there is no cellular path. Retrying cannot help.
                throw TransportError.cellularUnavailable
            } catch {
                last = error
            }
        }
        throw last
    }
}
