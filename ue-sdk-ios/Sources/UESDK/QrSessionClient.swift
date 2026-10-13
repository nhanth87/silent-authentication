/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Desktop → phone QR hand-off. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation
#if canImport(Network)
import Network
#endif

/// Device side of the desktop → phone QR hand-off for CAMARA Number
/// Verification v2.
///
/// **The flow.** A customer signs in to internet banking on a desktop. That
/// machine has no SIM, so the cellular IP-match path cannot help them — the
/// address it sees is a LAN or VPN address no PGW ever assigned. The bank
/// renders a QR code, the phone scans it and calls ``claim(sasBaseUrl:sessionId:bearer:requirement:msisdn:operatorToken:apiKey:)``,
/// and the desktop polls ``status(sasBaseUrl:sessionId:apiKey:)``. From that
/// moment the phone is the device proving the number.
///
/// **Why the phone and not the browser.** Android can bind a socket to the
/// cellular `Network`; a browser cannot bind anything, and neither can the
/// desktop. That asymmetry is the entire reason this hand-off exists.
///
/// **What this SDK does not do.** It moves three things — the session id from
/// the QR image, the bearer it observed, and optionally a GSMA TS.43 temporary
/// token or a number for the discovery variant. It makes no trust decision:
/// ``ClaimResult/approved()`` is the server's verdict, reported as it arrived.
/// A pending or unparseable answer reads as *not* approved, because silence is
/// not consent.
///
/// **The iOS constraint, restated.** `requiredInterfaceType` lives on
/// `NWParameters`, not on `URLSessionConfiguration` — see ``CellularBearer``.
/// This client therefore *observes* the path through `NWPathMonitor` and fails
/// closed when the claim would leave over Wi-Fi, rather than pretending it
/// pinned the socket.
public final class QrSessionClient {

    /// Scheme the bank puts in the QR image.
    public static let payloadScheme = "silentauth://qr-session/"

    /// Lifecycle as the server sees it.
    public enum QrState: String, Sendable {
        case pending = "PENDING"
        case resolved = "RESOLVED"
        case expired = "EXPIRED"
        case unknown = "UNKNOWN"
    }

    /// The scanned QR payload is not a session reference.
    public struct InvalidPayload: Error, CustomStringConvertible, Equatable {
        public let payload: String?
        public var description: String {
            "not a silent-auth QR payload: \(payload ?? "<empty>")"
        }
    }

    /// The verdict of one hand-off.
    public struct ClaimResult: Sendable {
        public let sessionId: String?
        public let state: QrState
        /// `nil` while still pending — deliberately distinct from `false`.
        public let verified: Bool?
        public let fallbackReason: String?

        /// True only for an explicit approval on a resolved session.
        public var approved: Bool { verified == true && state == .resolved }
    }

    /// Status as the bank polls it.
    public struct StatusResult: Sendable {
        public let sessionId: String?
        public let state: QrState
        public let verified: Bool?
        public let fallbackReason: String?
        public let decision: String?
        public let expiresInSeconds: Int

        public var approved: Bool { verified == true && state == .resolved }
    }

    private let timeout: TimeInterval

    public init(timeout: TimeInterval = 5.0) {
        self.timeout = timeout
    }

    // MARK: - payload

    /// Extracts the session id from a scanned QR payload.
    ///
    /// Accepts the canonical `silentauth://qr-session/<id>` form and a bare
    /// UUID, because a bank may render either. This string authorises the
    /// claim, so anything else throws rather than being parsed hopefully.
    public static func parsePayload(_ scanned: String?) throws -> String {
        guard var value = scanned?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else {
            throw InvalidPayload(payload: scanned)
        }
        if value.lowercased().hasPrefix(payloadScheme) {
            value = String(value.dropFirst(payloadScheme.count))
        } else if let slash = value.lastIndex(of: "/"), value.index(after: slash) < value.endIndex {
            value = String(value[value.index(after: slash)...])
        }
        if let query = value.firstIndex(of: "?") {
            value = String(value[..<query])
        }
        guard isSessionId(value) else { throw InvalidPayload(payload: scanned) }
        return value
    }

    /// A session id is a UUID; nothing else may be claimed.
    static func isSessionId(_ value: String) -> Bool {
        let characters = Array(value.utf8)
        guard characters.count == 36 else { return false }
        let dashPositions: Set<Int> = [8, 13, 18, 23]
        for (index, byte) in characters.enumerated() {
            let isDash = byte == UInt8(ascii: "-")
            if dashPositions.contains(index) {
                guard isDash else { return false }
            } else {
                let isHex = (byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9"))
                    || (byte >= UInt8(ascii: "a") && byte <= UInt8(ascii: "f"))
                    || (byte >= UInt8(ascii: "A") && byte <= UInt8(ascii: "F"))
                guard isHex else { return false }
            }
        }
        return true
    }

    // MARK: - claim

    /// Claims the hand-off from this device.
    ///
    /// - Throws: ``CellularBearer/Unavailable`` when the bearer cannot satisfy
    ///   `requirement` and no operator token was supplied — the app must then
    ///   fall back to OTP, not retry over Wi-Fi.
    public func claim(sasBaseUrl: String,
                      sessionId: String,
                      bearer: CellularBearer,
                      requirement: CellularBearer.Requirement = .cellular,
                      msisdn: String? = nil,
                      operatorToken: String? = nil,
                      apiKey: String? = nil) async throws -> ClaimResult {
        let tokenPath = !(operatorToken?.isEmpty ?? true)
        let tech = bearer.accessTech()
        if !tokenPath {
            try bearer.check(requirement)
        }
        let body = Self.claimBody(msisdn: msisdn,
                                  accessTech: tokenPath ? AccessTech.wifi : tech,
                                  operatorToken: operatorToken)
        let url = try Self.url(sasBaseUrl, path: "/qr-session/v1/\(sessionId)/claim")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let declared = tokenPath ? AccessTech.wifi : tech
        if declared != .unknown {
            request.setValue(declared.rawValue, forHTTPHeaderField: "X-Sas-Access-Tech")
        }
        if let apiKey = apiKey, !apiKey.isEmpty {
            request.setValue(apiKey, forHTTPHeaderField: "X-Api-Key")
        }
        request.httpBody = Data(body.utf8)

        let payload = try await Self.send(request, bearer: bearer)
        let flat = Flat(payload)
        return ClaimResult(sessionId: flat.string("sessionId"),
                           state: flat.state("state"),
                           verified: flat.bool("devicePhoneNumberVerified"),
                           fallbackReason: flat.string("fallbackReason"))
    }

    // MARK: - status

    /// Reads the verdict the bank is polling for.
    public func status(sasBaseUrl: String,
                       sessionId: String,
                       bearer: CellularBearer = CellularBearer(assumed: .lte),
                       apiKey: String? = nil) async throws -> StatusResult {
        let url = try Self.url(sasBaseUrl, path: "/qr-session/v1/\(sessionId)")
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let apiKey = apiKey, !apiKey.isEmpty {
            request.setValue(apiKey, forHTTPHeaderField: "X-Api-Key")
        }
        let payload = try await Self.send(request, bearer: bearer)
        let flat = Flat(payload)
        return StatusResult(sessionId: flat.string("sessionId"),
                            state: flat.state("state"),
                            verified: flat.bool("devicePhoneNumberVerified"),
                            fallbackReason: flat.string("fallbackReason"),
                            decision: flat.string("decision"),
                            expiresInSeconds: flat.number("expiresInSeconds"))
    }

    // MARK: - transport

    private static func send(_ request: URLRequest, bearer: CellularBearer) async throws -> String {
        let session = URLSession(configuration: CellularBearer
            .cellularSessionConfiguration(timeout: 5.0))
        defer { session.invalidateAndCancel() }
        // The bearer is consulted here for its gate only; URLSession cannot pin a
        // socket to an interface, which is why claim() fails closed on a Wi-Fi path
        // instead of pretending otherwise.
        _ = bearer
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let text = String(data: data, encoding: .utf8) ?? ""
        // A 4xx body is still parsed: it carries the server's reason, and the
        // caller needs it to decide between OTP and passkey.
        guard status == 200 || (400..<600).contains(status) else {
            throw URLError(.badServerResponse)
        }
        return text
    }

    private static func url(_ base: String, path: String) throws -> URL {
        var trimmed = base
        if trimmed.hasSuffix("/") { trimmed.removeLast() }
        guard let url = URL(string: trimmed + path) else {
            throw URLError(.badURL)
        }
        return url
    }

    // MARK: - bodies

    static func claimBody(msisdn: String?, accessTech: AccessTech,
                          operatorToken: String?) -> String {
        var parts: [String] = []
        if let msisdn = msisdn, !msisdn.trimmingCharacters(in: .whitespaces).isEmpty {
            parts.append("\"msisdn\":\(quote(msisdn))")
        }
        if accessTech != .unknown {
            parts.append("\"accessTech\":\(quote(accessTech.rawValue))")
        }
        if let token = operatorToken, !token.trimmingCharacters(in: .whitespaces).isEmpty {
            parts.append("\"operatorToken\":\(quote(token))")
        }
        return "{" + parts.joined(separator: ",") + "}"
    }

    private static func quote(_ value: String) -> String {
        var out = "\""
        for character in value.unicodeScalars {
            switch character {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if character.value < 0x20 {
                    out += String(format: "\\u%04x", character.value)
                } else {
                    out.unicodeScalars.append(character)
                }
            }
        }
        return out + "\""
    }
}

/// Flat scalar fields of a JSON object.
///
/// Deliberately tiny and unforgiving: anything that is not a single-level
/// object of scalars comes back empty, so an unreadable answer reads as "no
/// verdict" rather than as a half-parsed approval. `JSONSerialization` would
/// also hand back nested dictionaries; refusing them keeps the device SDK from
/// guessing structure it was never given.
struct Flat {
    private let fields: [String: String?]

    init(_ payload: String) {
        guard let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let dictionary = object as? [String: Any] else {
            self.fields = [:]
            return
        }
        var parsed: [String: String?] = [:]
        for (key, value) in dictionary {
            if value is NSNull {
                parsed[key] = nil
            } else if let scalar = value as? String {
                parsed[key] = scalar
            } else if let number = value as? NSNumber {
                // JSONSerialization collapses true/false into NSNumber; keep the
                // distinction so `false` never reads as the string "0".
                if CFGetTypeID(number) == CFBooleanGetTypeID() {
                    parsed[key] = number.boolValue ? "true" : "false"
                } else {
                    parsed[key] = number.stringValue
                }
            } else {
                parsed = [:]     // nested or array: out of scope, refuse wholesale
                break
            }
        }
        self.fields = parsed
    }

    func string(_ field: String) -> String? { fields[field] ?? nil }

    func bool(_ field: String) -> Bool? {
        guard let raw = fields[field] ?? nil else { return nil }
        if raw.lowercased() == "true" { return true }
        if raw.lowercased() == "false" { return false }
        return nil
    }

    func number(_ field: String) -> Int {
        guard let raw = fields[field] ?? nil, let value = Int(raw) else { return -1 }
        return value
    }

    func state(_ field: String) -> QrSessionClient.QrState {
        guard let raw = fields[field] ?? nil,
              let state = QrSessionClient.QrState(rawValue: raw) else { return .unknown }
        return state
    }
}
