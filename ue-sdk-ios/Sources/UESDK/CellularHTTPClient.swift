/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * CAMARA Number Verification v2: one HTTP GET pinned to the cellular interface.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation
#if canImport(Network)
import Network
#endif

/// The head of an HTTP answer — all the authorization flow needs.
public struct HTTPHead: Equatable {
    public let status: Int
    public let location: String?
    /// At most 8 KiB of the body, for an error document.
    public let body: String

    public init(status: Int, location: String?, body: String) {
        self.status = status
        self.location = location
        self.body = body
    }
}

/// One HTTP exchange on a chosen interface. `NumberVerification` uses `get` for
/// every authorization hop; `QrSessionClient` uses `send` for the cellular claim.
public protocol AuthorizeTransport {
    /// - Parameters:
    ///   - cellularOnly: the request must leave on the cellular interface or not at all
    ///   - timeout: for the whole exchange
    func get(_ url: URL, cellularOnly: Bool, timeout: TimeInterval) async throws -> HTTPHead

    /// Any method with headers and a body. The default only covers a bodiless GET,
    /// so a transport written for `get` alone keeps working.
    func send(_ method: String, _ url: URL, headers: [(String, String)], body: Data?,
              cellularOnly: Bool, timeout: TimeInterval) async throws -> HTTPHead
}

extension AuthorizeTransport {
    public func send(_ method: String, _ url: URL, headers: [(String, String)], body: Data?,
                     cellularOnly: Bool, timeout: TimeInterval) async throws -> HTTPHead {
        guard method == "GET", body == nil else { throw TransportError.unsupportedURL }
        return try await get(url, cellularOnly: cellularOnly, timeout: timeout)
    }
}

/// Transport outcomes that are not plain I/O errors.
public enum TransportError: Error, Equatable {
    /// No cellular path: mobile data off, airplane mode, no signal, or a
    /// Wi-Fi-only iPad. Fall back — never retry over Wi-Fi.
    case cellularUnavailable
    case timedOut
    case malformedResponse
    case unsupportedURL
}

enum HTTPWire {

    static let maxBody = 8 * 1024
    static let maxResponse = 64 * 1024

    /// `GET <path?query> HTTP/1.1` with `Connection: close`. No cookies, no
    /// credentials: the authorization request carries everything in its URL.
    static func request(_ url: URL) throws -> Data {
        try request("GET", url, headers: [("Accept", "application/json")], body: nil)
    }

    /// Any method. `Host`, `Connection: close` and, with a body, `Content-Length`
    /// are always set here; caller headers may not override them (a header with
    /// a CR or LF is refused — no header injection).
    static func request(_ method: String, _ url: URL, headers: [(String, String)],
                        body: Data?) throws -> Data {
        let reserved: Set<String> = ["host", "connection", "content-length", "transfer-encoding"]
        guard ["GET", "POST"].contains(method) else { throw TransportError.unsupportedURL }
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let host = components.host, !host.isEmpty else {
            throw TransportError.unsupportedURL
        }
        var target = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
        if let query = components.percentEncodedQuery, !query.isEmpty {
            target += "?" + query
        }
        let bracketed = host.contains(":") ? "[\(host)]" : host
        let hostHeader = components.port.map { "\(bracketed):\($0)" } ?? bracketed
        var text = "\(method) \(target) HTTP/1.1\r\n" + "Host: \(hostHeader)\r\n"
        for (name, value) in headers {
            guard !reserved.contains(name.lowercased()),
                  !(name + value).contains("\r"), !(name + value).contains("\n"),
                  !name.contains(":") else {
                throw TransportError.unsupportedURL
            }
            text += "\(name): \(value)\r\n"
        }
        if let body = body {
            text += "Content-Length: \(body.count)\r\n"
        }
        text += "Connection: close\r\n\r\n"
        var out = Data(text.utf8)
        if let body = body { out.append(body) }
        return out
    }

    /// Offset just past the blank line ending the head, or nil while incomplete.
    static func headEnd(_ data: Data) -> Int? {
        guard let range = data.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        return range.upperBound
    }

    /// True once enough has arrived to answer: a redirect needs no body, and a
    /// `Content-Length` body is complete when that many bytes are in.
    static func isComplete(_ data: Data) -> Bool {
        guard let end = headEnd(data), let head = try? parse(data) else { return false }
        if (300..<400).contains(head.status) { return true }
        if let length = contentLength(data[..<end]) { return data.count - end >= length }
        if isChunked(data[..<end]) { return data[end...].range(of: Data("0\r\n\r\n".utf8)) != nil }
        return false
    }

    static func parse(_ data: Data) throws -> HTTPHead {
        guard let end = headEnd(data) else { throw TransportError.malformedResponse }
        let headText = String(decoding: data[data.startIndex..<(end - 4)], as: UTF8.self)
        var lines = headText.components(separatedBy: "\r\n")
        guard !lines.isEmpty else { throw TransportError.malformedResponse }
        let statusLine = lines.removeFirst().split(separator: " ", maxSplits: 2)
        guard statusLine.count >= 2, statusLine[0].hasPrefix("HTTP/"),
              let status = Int(statusLine[1]), (100..<600).contains(status) else {
            throw TransportError.malformedResponse
        }
        var location: String?
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            if name == "location" {
                location = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            }
        }
        let raw = Data(data[end...])
        let bodyBytes = (isChunked(data[..<end]) ? dechunk(raw) : raw).prefix(maxBody)
        return HTTPHead(status: status, location: location,
                        body: String(decoding: bodyBytes, as: UTF8.self))
    }

    private static func isChunked(_ head: Data) -> Bool {
        header("transfer-encoding", in: head)?.lowercased().contains("chunked") ?? false
    }

    /// Decodes `Transfer-Encoding: chunked`; stops at the last complete chunk.
    static func dechunk(_ data: Data) -> Data {
        var out = Data()
        var index = data.startIndex
        let crlf = Data("\r\n".utf8)
        while index < data.endIndex,
              let line = data[index...].range(of: crlf) {
            let sizeText = String(decoding: data[index..<line.lowerBound], as: UTF8.self)
                .split(separator: ";").first.map(String.init) ?? ""
            guard let size = Int(sizeText.trimmingCharacters(in: .whitespaces), radix: 16), size > 0 else { break }
            let start = line.upperBound
            guard data.distance(from: start, to: data.endIndex) >= size else { break }
            let stop = data.index(start, offsetBy: size)
            out.append(data[start..<stop])
            index = min(data.endIndex, data.index(stop, offsetBy: 2, limitedBy: data.endIndex) ?? data.endIndex)
        }
        return out
    }

    private static func contentLength(_ head: Data) -> Int? {
        header("content-length", in: head).flatMap { Int($0) }
    }

    private static func header(_ name: String, in head: Data) -> String? {
        let text = String(decoding: head, as: UTF8.self)
        for line in text.components(separatedBy: "\r\n") {
            let parts = line.split(separator: ":", maxSplits: 1)
            if parts.count == 2, parts[0].trimmingCharacters(in: .whitespaces).lowercased() == name {
                return parts[1].trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }
}

#if canImport(Network)
/// Sends one GET over `NWConnection`, pinned to the cellular interface when asked.
///
/// This is the iOS answer to "make the request leave on cellular while Wi-Fi is
/// up". `URLSession` cannot do it — `URLSessionConfiguration` has no
/// `requiredInterfaceType`. `NWParameters` does: `requiredInterfaceType =
/// .cellular` plus `prohibitedInterfaceTypes = [.wifi, .wiredEthernet]` makes the
/// connection use the cellular interface or no interface at all. Binding a BSD
/// socket to a hard-coded local address, as some write-ups suggest, does not
/// work: the cellular address is assigned by the PGW and changes.
///
/// TLS is Network.framework's default — system trust evaluation, SNI from the
/// host — with a TLS 1.2 floor. Nothing is logged.
public final class CellularHTTPClient: AuthorizeTransport {

    public init() {}

    public func get(_ url: URL, cellularOnly: Bool, timeout: TimeInterval) async throws -> HTTPHead {
        try await send("GET", url, headers: [("Accept", "application/json")], body: nil,
                       cellularOnly: cellularOnly, timeout: timeout)
    }

    public func send(_ method: String, _ url: URL, headers: [(String, String)], body: Data?,
                     cellularOnly: Bool, timeout: TimeInterval) async throws -> HTTPHead {
        guard let host = url.host, !host.isEmpty, let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else {
            throw TransportError.unsupportedURL
        }
        let tls = scheme == "https"
        guard let port = NWEndpoint.Port(rawValue: UInt16(clamping: url.port ?? (tls ? 443 : 80))) else {
            throw TransportError.unsupportedURL
        }
        let parameters: NWParameters
        if tls {
            let options = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(options.securityProtocolOptions, .TLSv12)
            parameters = NWParameters(tls: options, tcp: NWProtocolTCP.Options())
        } else {
            parameters = NWParameters.tcp
        }
        if cellularOnly {
            parameters.requiredInterfaceType = .cellular
            parameters.prohibitedInterfaceTypes = [.wifi, .wiredEthernet]
        }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: parameters)
        let exchange = Exchange(connection: connection,
                                request: try HTTPWire.request(method, url, headers: headers, body: body),
                                cellularOnly: cellularOnly, timeout: timeout)
        return try await exchange.run()
    }

    /// One request/response on one connection. Every callback runs on `queue`,
    /// and `finish` resumes the continuation exactly once and cancels the
    /// connection, so no connection or handler outlives the call.
    private final class Exchange: @unchecked Sendable {
        private let connection: NWConnection
        private let request: Data
        private let cellularOnly: Bool
        private let timeout: TimeInterval
        private let queue = DispatchQueue(label: "et.restlink.uesdk.nv-http")
        private var buffer = Data()
        private var continuation: CheckedContinuation<HTTPHead, Error>?

        init(connection: NWConnection, request: Data, cellularOnly: Bool, timeout: TimeInterval) {
            self.connection = connection
            self.request = request
            self.cellularOnly = cellularOnly
            self.timeout = timeout
        }

        func run() async throws -> HTTPHead {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<HTTPHead, Error>) in
                queue.async {
                    self.continuation = continuation
                    // Strong captures on purpose: the exchange must outlive the
                    // suspension. finish() clears the handler, which breaks the cycle.
                    self.connection.stateUpdateHandler = { state in self.on(state) }
                    self.connection.start(queue: self.queue)
                    self.queue.asyncAfter(deadline: .now() + self.timeout) {
                        self.finish(.failure(TransportError.timedOut))
                    }
                }
            }
        }

        private func on(_ state: NWConnection.State) {
            switch state {
            case .ready:
                connection.send(content: request, completion: .contentProcessed { [weak self] error in
                    if let error = error {
                        self?.finish(.failure(error))
                    } else {
                        self?.receive()
                    }
                })
            case .waiting(let error):
                // No usable path right now. For a cellular-only request that is the
                // answer — waiting would only spend the budget. A TLS error is not a
                // path problem and is reported as itself.
                if case .tls = error {
                    finish(.failure(error))
                } else {
                    finish(.failure(cellularOnly ? TransportError.cellularUnavailable : error))
                }
            case .failed(let error):
                finish(.failure(error))
            default:
                break
            }
        }

        private func receive() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: HTTPWire.maxResponse) {
                [weak self] data, _, isComplete, error in
                guard let self = self else { return }
                if let data = data { self.buffer.append(data) }
                if HTTPWire.isComplete(self.buffer) || isComplete || self.buffer.count >= HTTPWire.maxResponse {
                    self.finish(Result { try HTTPWire.parse(self.buffer) })
                } else if let error = error {
                    self.finish(.failure(error))
                } else {
                    self.receive()
                }
            }
        }

        private func finish(_ result: Result<HTTPHead, Error>) {
            guard let continuation = continuation else { return }
            self.continuation = nil
            connection.stateUpdateHandler = nil
            connection.cancel()
            continuation.resume(with: result)
        }
    }
}
#endif
