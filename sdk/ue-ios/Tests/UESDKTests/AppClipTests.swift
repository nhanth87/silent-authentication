/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Tests: App Clip invocation parsing, the cellular-pinned QR claim, and the
 * POST / chunked wire format it rides on. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import XCTest
@testable import UESDK

final class AppClipTests: XCTestCase {

    private static let id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private static let hosts: Set<String> = ["clip.bank.example"]

    private func parse(_ raw: String) throws -> AppClipInvocation {
        try AppClipInvocation.parse(URL(string: raw), allowedHosts: Self.hosts)
    }

    private func refused(_ raw: String?, file: StaticString = #filePath, line: UInt = #line) -> String {
        do {
            _ = try AppClipInvocation.parse(raw.flatMap(URL.init(string:)), allowedHosts: Self.hosts)
            XCTFail("expected a refusal for \(raw ?? "nil")", file: file, line: line)
            return ""
        } catch let error as AppClipInvocation.Refused {
            return error.rule
        } catch {
            XCTFail("unexpected \(error)", file: file, line: line)
            return ""
        }
    }

    // MARK: - invocation

    func testSessionInThePathOrTheQuery() throws {
        XCTAssertEqual(try parse("https://clip.bank.example/s/\(Self.id)").sessionId, Self.id)
        XCTAssertEqual(try parse("https://CLIP.BANK.EXAMPLE/nv/\(Self.id)?utm=x").sessionId, Self.id)
        XCTAssertEqual(try parse("https://clip.bank.example/nv?session=\(Self.id)").sessionId, Self.id)
        XCTAssertEqual(try parse("https://clip.bank.example/nv?s=\(Self.id)").sessionId, Self.id)
        XCTAssertEqual(try parse("https://clip.bank.example/s/\(Self.id)?s=\(Self.id)").sessionId, Self.id)
    }

    func testHostAndTransportAreEnforced() {
        XCTAssertTrue(refused("https://clip.bank.example.evil/s/\(Self.id)").contains("host"))
        XCTAssertTrue(refused("https://evil.example/s/\(Self.id)").contains("host"))
        XCTAssertTrue(refused("http://clip.bank.example/s/\(Self.id)").contains("https"))
        XCTAssertTrue(refused("https://user:pw@clip.bank.example/s/\(Self.id)").contains("credentials"))
        XCTAssertTrue(refused("https://clip.bank.example:8443/s/\(Self.id)").contains("port"))
        XCTAssertThrowsError(try AppClipInvocation.parse(URL(string: "https://clip.bank.example/s/\(Self.id)"),
                                                         allowedHosts: []))
    }

    func testAmbiguousOrMissingSessionIsRefused() {
        let other = "11111111-2222-3333-4444-555555555555"
        XCTAssertTrue(refused("https://clip.bank.example/s/\(Self.id)?s=\(other)").contains("different"))
        XCTAssertTrue(refused("https://clip.bank.example/s?s=\(Self.id)&session=\(other)").contains("more than once"))
        XCTAssertTrue(refused("https://clip.bank.example/").contains("no session"))
        XCTAssertTrue(refused("https://clip.bank.example/s/not-a-uuid").contains("no session"))
        XCTAssertTrue(refused(nil).contains("no invocation URL"))
    }

    // MARK: - pinned claim

    private final class RecordingTransport: AuthorizeTransport {
        var sent: [(method: String, url: URL, headers: [(String, String)], body: Data?, cellularOnly: Bool)] = []
        var noCellular = false
        var answer = HTTPHead(status: 200, location: nil,
                              body: "{\"sessionId\":\"\(AppClipTests.id)\",\"state\":\"RESOLVED\",\"devicePhoneNumberVerified\":true}")

        func get(_ url: URL, cellularOnly: Bool, timeout: TimeInterval) async throws -> HTTPHead {
            XCTFail("the claim must POST")
            return answer
        }

        func send(_ method: String, _ url: URL, headers: [(String, String)], body: Data?,
                  cellularOnly: Bool, timeout: TimeInterval) async throws -> HTTPHead {
            sent.append((method, url, headers, body, cellularOnly))
            if noCellular { throw TransportError.cellularUnavailable }
            return answer
        }
    }

    func testClaimLeavesOnCellularEvenWhileWifiIsTheDefaultRoute() async throws {
        let transport = RecordingTransport()
        let client = QrSessionClient(transport: transport, radio: { .lte })

        let result = try await client.claim(sasBaseUrl: "https://sas.example.et/", sessionId: Self.id,
                                            bearer: CellularBearer(assumed: .wifi), apiKey: "k1")

        XCTAssertTrue(result.approved)
        XCTAssertEqual(transport.sent.count, 1)
        let request = transport.sent[0]
        XCTAssertEqual(request.method, "POST")
        XCTAssertTrue(request.cellularOnly, "the claim must be pinned to cellular")
        XCTAssertEqual(request.url.absoluteString, "https://sas.example.et/qr-session/v1/\(Self.id)/claim")
        XCTAssertTrue(request.headers.contains { $0 == ("X-Sas-Access-Tech", "LTE") },
                      "declares the cellular radio, not the Wi-Fi default route")
        XCTAssertTrue(request.headers.contains { $0 == ("X-Api-Key", "k1") })
        XCTAssertEqual(request.body.map { String(decoding: $0, as: UTF8.self) }, "{\"accessTech\":\"LTE\"}")
    }

    func testNoCellularInterfaceIsAnUnavailableBearer() async {
        let transport = RecordingTransport()
        transport.noCellular = true
        do {
            _ = try await QrSessionClient(transport: transport, radio: { .lte })
                .claim(sasBaseUrl: "https://sas.example.et", sessionId: Self.id,
                       bearer: CellularBearer(assumed: .wifi))
            XCTFail("must fail closed")
        } catch let error as CellularBearer.Unavailable {
            XCTAssertEqual(error.observed, .wifi)
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testFourGPlusOnAThreeGRadioNeverSends() async {
        let transport = RecordingTransport()
        do {
            _ = try await QrSessionClient(transport: transport, radio: { .gs2g3g })
                .claim(sasBaseUrl: "https://sas.example.et", sessionId: Self.id,
                       bearer: CellularBearer(assumed: .wifi), requirement: .cellular4GPlus)
            XCTFail("must fail closed")
        } catch {
            XCTAssertTrue(error is CellularBearer.Unavailable)
        }
        XCTAssertTrue(transport.sent.isEmpty)
    }

    func testServerRefusalIsReadNotApproved() async throws {
        let transport = RecordingTransport()
        transport.answer = HTTPHead(status: 409, location: nil,
                                    body: "{\"status\":409,\"code\":\"ALREADY_CLAIMED\"}")
        let result = try await QrSessionClient(transport: transport, radio: { .lte })
            .claim(sasBaseUrl: "https://sas.example.et", sessionId: Self.id,
                   bearer: CellularBearer(assumed: .wifi))
        XCTAssertFalse(result.approved, "a stale relaunch URL must not read as approval")
    }

    func testWithoutTransportTheOldGateStillFailsClosedOnWifi() async {
        do {
            _ = try await QrSessionClient(transport: nil)
                .claim(sasBaseUrl: "https://sas.example.et", sessionId: Self.id,
                       bearer: CellularBearer(assumed: .wifi))
            XCTFail("must fail closed")
        } catch {
            XCTAssertTrue(error is CellularBearer.Unavailable)
        }
    }

    // MARK: - wire

    func testPostCarriesLengthAndBodyAndRefusesHeaderInjection() throws {
        let text = String(decoding: try HTTPWire.request("POST", URL(string: "https://sas.example.et/c")!,
                                                         headers: [("Content-Type", "application/json")],
                                                         body: Data("{}".utf8)), as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("POST /c HTTP/1.1\r\nHost: sas.example.et\r\nContent-Type: application/json\r\n"))
        XCTAssertTrue(text.hasSuffix("Content-Length: 2\r\nConnection: close\r\n\r\n{}"))
        XCTAssertThrowsError(try HTTPWire.request("POST", URL(string: "https://a.example/")!,
                                                  headers: [("X-A", "b\r\nHost: evil")], body: nil))
        XCTAssertThrowsError(try HTTPWire.request("POST", URL(string: "https://a.example/")!,
                                                  headers: [("Host", "evil")], body: nil))
        XCTAssertThrowsError(try HTTPWire.request("DELETE", URL(string: "https://a.example/")!,
                                                  headers: [], body: nil))
    }

    func testChunkedBodyIsDecoded() throws {
        let raw = Data(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "7\r\n{\"a\":1,\r\n6\r\n\"b\":2}\r\n0\r\n\r\n").utf8)
        XCTAssertTrue(HTTPWire.isComplete(raw))
        XCTAssertEqual(try HTTPWire.parse(raw).body, "{\"a\":1,\"b\":2}")
        let partial = Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n7\r\n{\"a\":1,\r\n".utf8)
        XCTAssertFalse(HTTPWire.isComplete(partial))
    }
}
