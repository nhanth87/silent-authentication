/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Tests: wire contract of SessionTupleClient via URLProtocol stub
 * (method, path, headers, body shape, status propagation).
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation
import XCTest
@testable import UESDK

/// Records requests and replays canned responses without any real network.
final class StubURLProtocol: URLProtocol {

    static var handler: ((URLRequest) -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = StubURLProtocol.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        let (response, data) = handler(request)
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    static func stubbedConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return configuration
    }
}

extension URLRequest {
    /// URLProtocol exposes the request body as a stream; read it back for assertions.
    var bodyData: Data? {
        if let httpBody = httpBody { return httpBody }
        guard let stream = httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        let bufferSize = 4096
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        var data = Data()
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }
}

private func stubbedResponse(_ status: Int, _ body: Data = Data()) -> (HTTPURLResponse, Data) {
    let response = HTTPURLResponse(
        url: URL(string: "http://sas.example.et/session-tuple")!,
        statusCode: status,
        httpVersion: "HTTP/1.1",
        headerFields: nil)!
    return (response, body)
}

final class RequestRecorder {
    var method: String?
    var path: String?
    var contentType: String?
    var apiKey: String?
    var bodyData: Data?
}

final class SessionTupleClientTests: XCTestCase {

    private var recorder = RequestRecorder()

    override func setUp() {
        super.setUp()
        recorder = RequestRecorder()
        StubURLProtocol.handler = nil
    }

    override func tearDown() {
        StubURLProtocol.handler = nil
        super.tearDown()
    }

    private func makeClient(baseUrl: String, apiKey: String? = nil) -> SessionTupleClient {
        SessionTupleClient(
            baseUrl: baseUrl,
            apiKey: apiKey,
            configuration: StubURLProtocol.stubbedConfiguration())
    }

    private func record(_ request: URLRequest) {
        recorder.method = request.httpMethod
        recorder.path = request.url?.path
        recorder.contentType = request.value(forHTTPHeaderField: "Content-Type")
        recorder.apiKey = request.value(forHTTPHeaderField: "X-Api-Key")
        recorder.bodyData = request.bodyData
    }

    func testPostsJsonWithApiKeyAndStatusPropagation() async throws {
        StubURLProtocol.handler = { [weak self] request in
            self?.record(request)
            return stubbedResponse(200)
        }

        let status = try await makeClient(baseUrl: "http://sas.example.et/", apiKey: "secret-key")
            .send(msisdn: "+251911111111")

        XCTAssertEqual(status, 200)
        XCTAssertEqual(recorder.method, "POST")
        XCTAssertEqual(recorder.path, "/session-tuple")
        XCTAssertEqual(recorder.contentType, "application/json")
        XCTAssertEqual(recorder.apiKey, "secret-key")

        let json = try XCTUnwrap(
            recorder.bodyData.flatMap { try JSONSerialization.jsonObject(with: $0) as? [String: Any] },
            "body must be a JSON object")
        XCTAssertEqual(json["msisdn"] as? String, "+251911111111")
        XCTAssertNotNil(json["ts"], "devices cannot observe CGNAT ip/port; ts always present")
        XCTAssertEqual(json.count, 2, "null fields omitted: only ts + msisdn on the wire")
    }

    func testOmitsNullFieldsAndApiKeyHeaderWhenAbsent() async throws {
        StubURLProtocol.handler = { [weak self] request in
            self?.record(request)
            return stubbedResponse(200)
        }

        let status = try await makeClient(baseUrl: "http://sas.example.et").send()

        XCTAssertEqual(status, 200)
        XCTAssertNil(recorder.apiKey)
        let json = try XCTUnwrap(
            recorder.bodyData.flatMap { try JSONSerialization.jsonObject(with: $0) as? [String: Any] })
        XCTAssertEqual(Array(json.keys), ["ts"], "device tuple carries only ts when no msisdn supplied")
    }

    func testPropagatesNon2xxStatusFromServer() async throws {
        StubURLProtocol.handler = { _ in
            stubbedResponse(401, Data("{\"code\":\"UNAUTHENTICATED\"}".utf8))
        }

        let status = try await makeClient(baseUrl: "http://sas.example.et", apiKey: "wrong-key").send()

        XCTAssertEqual(status, 401)
    }
}
