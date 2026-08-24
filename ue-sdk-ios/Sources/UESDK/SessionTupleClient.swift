/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Device-side session-tuple poster. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation

/// Posts a `TupleSnapshot` to the SAS `POST /session-tuple` endpoint over
/// `URLSession`.
///
/// Body shape mirrors the server DTO (`srcIp`, `srcPort`, `ts`, `msisdn`,
/// `imsi`); null fields are omitted. Contains no authentication logic;
/// approval always happens server-side (fail-closed FSM in the SAS).
public final class SessionTupleClient {

    private let baseURL: URL
    private let apiKey: String?
    private let session: URLSession

    /// - Parameters:
    ///   - baseUrl: SAS base URL; a single trailing slash is trimmed.
    ///   - apiKey: sent as `X-Api-Key` only when non-nil and non-empty.
    ///   - timeout: request/resource timeout in seconds (SAS contract: 3).
    ///   - configuration: injectable for tests (URLProtocol stubbing).
    public init(baseUrl: String,
                apiKey: String? = nil,
                timeout: TimeInterval = 3.0,
                configuration: URLSessionConfiguration = .default) {
        var trimmed = baseUrl
        if trimmed.count > 1 && trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }
        guard let url = URL(string: trimmed) else {
            preconditionFailure("Invalid SAS base URL: \(baseUrl)")
        }
        self.baseURL = url
        self.apiKey = apiKey
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        self.session = URLSession(configuration: configuration)
    }

    deinit {
        session.finishTasksAndInvalidate()
    }

    /// Sends the snapshot; returns the HTTP status code.
    @discardableResult
    public func send(msisdn: String? = nil) async throws -> Int {
        var request = URLRequest(url: baseURL.appendingPathComponent("session-tuple"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let apiKey = apiKey, !apiKey.isEmpty {
            request.setValue(apiKey, forHTTPHeaderField: "X-Api-Key")
        }
        request.httpBody = try JSONEncoder().encode(TupleSnapshot(msisdn: msisdn))

        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        return http.statusCode
    }
}
