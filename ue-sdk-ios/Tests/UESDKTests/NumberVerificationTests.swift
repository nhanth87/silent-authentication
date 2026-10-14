/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Tests: CAMARA NV v2 device side — request rules, redirect capture, state
 * check, the TS.43 fallback and the HTTP head parser.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import XCTest
@testable import UESDK

final class NumberVerificationTests: XCTestCase {

    private static let ok = "https://sas.example.et/authorize?response_type=code&client_id=bank"
        + "&redirect_uri=https%3A%2F%2Fbank.example%2Fcb&scope=openid%20number-verification%3Averify"
        + "&state=s1&nonce=n1&code_challenge=Q2hhbGxlbmdl&code_challenge_method=S256&prompt=none"

    private static func without(_ param: String) -> String {
        ok.replacingOccurrences(of: "&\(param)=[^&]*", with: "", options: .regularExpression)
    }

    /// Records every GET and answers from a script keyed by path.
    private final class ScriptedTransport: AuthorizeTransport {
        var calls: [(url: URL, cellularOnly: Bool)] = []
        var noCellular = false
        var failFirst = 0
        let answer: (URL) -> HTTPHead

        init(_ answer: @escaping (URL) -> HTTPHead) {
            self.answer = answer
        }

        func get(_ url: URL, cellularOnly: Bool, timeout: TimeInterval) async throws -> HTTPHead {
            calls.append((url, cellularOnly))
            if cellularOnly && noCellular { throw TransportError.cellularUnavailable }
            if calls.count <= failFirst { throw URLError(.networkConnectionLost) }
            return answer(url)
        }
    }

    private struct Token: OperatorTokenSource {
        let value: String?
        func fetchOperatorToken() async throws -> String? { value }
    }

    private static func param(_ url: URL, _ name: String) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value
    }

    /// Answers like the SAS: a code on the network path or for the right token.
    private static func sasLike(_ url: URL) -> HTTPHead {
        let state = param(url, "state") ?? ""
        if let hint = param(url, "login_hint") {
            let ok = hint == "operatortoken:tk-1"
            return HTTPHead(status: 302, location: "https://bank.example/cb?"
                            + (ok ? "code=c-tok" : "error=access_denied") + "&state=\(state)", body: "")
        }
        return HTTPHead(status: 302, location: "https://bank.example/cb?code=c-net&state=\(state)", body: "")
    }

    private func sdk(_ transport: ScriptedTransport, tokens: OperatorTokenSource? = nil,
                     radio: AccessTech = .lte) -> NumberVerification {
        var configuration = NumberVerification.Configuration()
        configuration.operatorTokens = tokens
        return NumberVerification(configuration: configuration, transport: transport,
                                  radio: { radio }, observed: { .wifi })
    }

    // MARK: - request rules

    func testValidRequestParses() throws {
        let request = try AuthorizeURL(Self.ok)
        XCTAssertEqual(request.state, "s1")
        XCTAssertEqual(request.redirectURI, URL(string: "https://bank.example/cb"))
        XCTAssertFalse(request.hasLoginHint)
    }

    func testSilentAndPkceRules() {
        for broken in [Self.without("prompt"), Self.ok.replacingOccurrences(of: "prompt=none", with: "prompt=login"),
                       Self.without("code_challenge"), Self.ok.replacingOccurrences(of: "S256", with: "plain"),
                       Self.without("state"), Self.without("client_id"), Self.without("scope"),
                       Self.without("redirect_uri"),
                       Self.ok.replacingOccurrences(of: "https://", with: "http://"),
                       Self.ok + "&redirect_uri=https%3A%2F%2Fevil.example%2Fcb", ""] {
            XCTAssertThrowsError(try AuthorizeURL(broken))
        }
        XCTAssertNoThrow(try AuthorizeURL(Self.ok.replacingOccurrences(of: "https://", with: "http://"),
                                          allowInsecureHTTP: true))
    }

    func testRulesNeverEchoTheQuery() {
        XCTAssertThrowsError(try AuthorizeURL(Self.without("prompt") + "&login_hint=operatortoken%3Asecret")) {
            let rule = ($0 as? AuthorizeURL.Invalid)?.rule ?? ""
            XCTAssertFalse(rule.contains("secret"))
            XCTAssertFalse(rule.contains("sas.example.et"))
        }
    }

    func testOperatorTokenIsAppendedEncoded() throws {
        let url = try AuthorizeURL(Self.ok).withOperatorToken("a+b/c=")
        XCTAssertTrue(url.absoluteString.hasSuffix("&login_hint=operatortoken%3Aa%2Bb%2Fc%3D"))
        let hinted = try AuthorizeURL(Self.ok + "&login_hint=tel%3A%2B251911111111")
        XCTAssertThrowsError(try hinted.withOperatorToken("t"))
    }

    func testRedirectMatchIgnoresQueryOnly() throws {
        let request = try AuthorizeURL(Self.ok)
        XCTAssertTrue(request.isRedirect(URL(string: "https://bank.example/cb?code=1")!))
        XCTAssertFalse(request.isRedirect(URL(string: "https://bank.example/cb2?code=1")!))
        XCTAssertFalse(request.isRedirect(URL(string: "https://bank.example.evil/cb?code=1")!))
        XCTAssertFalse(request.isRedirect(URL(string: "http://bank.example/cb?code=1")!))
    }

    // MARK: - flow

    func testNetworkPathPinsToCellularAndReturnsTheCode() async throws {
        let transport = ScriptedTransport(Self.sasLike)
        let result = try await sdk(transport).authorize(Self.ok)
        XCTAssertEqual(result.code, "c-net")
        XCTAssertEqual(result.method, .network)
        XCTAssertEqual(result.accessTech, .lte)
        XCTAssertEqual(transport.calls.count, 1)
        XCTAssertTrue(transport.calls[0].cellularOnly, "the network path must be cellular-only")
        XCTAssertFalse(result.description.contains("c-net"), "description redacts the code")
    }

    func testNoCellularUsesTheOperatorTokenOverAnyInterface() async throws {
        let transport = ScriptedTransport(Self.sasLike)
        transport.noCellular = true
        let result = try await sdk(transport, tokens: Token(value: "tk-1")).authorize(Self.ok)
        XCTAssertEqual(result.code, "c-tok")
        XCTAssertEqual(result.method, .operatorToken)
        XCTAssertFalse(transport.calls[1].cellularOnly)
        XCTAssertEqual(Self.param(transport.calls[1].url, "login_hint"), "operatortoken:tk-1")
    }

    func testNoCellularAndNoTokenFailsClosed() async {
        let transport = ScriptedTransport(Self.sasLike)
        transport.noCellular = true
        do {
            _ = try await sdk(transport).authorize(Self.ok)
            XCTFail("must fail closed")
        } catch let error as CellularBearer.Unavailable {
            XCTAssertEqual(error.observed, .wifi)
        } catch {
            XCTFail("unexpected \(error)")
        }
        XCTAssertEqual(transport.calls.count, 1, "nothing may go out over Wi-Fi without a token")
    }

    func testTooOldRadioNeverSendsWhen4GPlusIsDemanded() async {
        let transport = ScriptedTransport(Self.sasLike)
        var configuration = NumberVerification.Configuration()
        configuration.requirement = .cellular4GPlus
        let nv = NumberVerification(configuration: configuration, transport: transport,
                                    radio: { .gs2g3g }, observed: { .gs2g3g })
        do {
            _ = try await nv.authorize(Self.ok)
            XCTFail("must fail closed")
        } catch {
            XCTAssertTrue(error is CellularBearer.Unavailable)
        }
        XCTAssertTrue(transport.calls.isEmpty)
    }

    func testNetworkDenialFallsThroughToTheSim() async throws {
        let transport = ScriptedTransport { url in
            let state = Self.param(url, "state") ?? ""
            let code = Self.param(url, "login_hint") == nil ? "error=access_denied" : "code=sim"
            return HTTPHead(status: 302, location: "https://bank.example/cb?\(code)&state=\(state)", body: "")
        }
        let result = try await sdk(transport, tokens: Token(value: "tk-1")).authorize(Self.ok)
        XCTAssertEqual(result.code, "sim")
        XCTAssertEqual(transport.calls.count, 2)
    }

    func testOtherRefusalsAreFinal() async {
        let transport = ScriptedTransport { url in
            HTTPHead(status: 302, location: "https://bank.example/cb?error=invalid_scope&state=s1", body: "")
        }
        do {
            _ = try await sdk(transport, tokens: Token(value: "tk-1")).authorize(Self.ok)
            XCTFail("must refuse")
        } catch let error as AuthorizationError {
            XCTAssertEqual(error.error, "invalid_scope")
        } catch {
            XCTFail("unexpected \(error)")
        }
        XCTAssertEqual(transport.calls.count, 1)
    }

    func testForeignStateIsRefused() async {
        let transport = ScriptedTransport { _ in
            HTTPHead(status: 302, location: "https://bank.example/cb?code=x&state=other", body: "")
        }
        await assertError(sdk(transport), "invalid_state")
    }

    func testDirectJsonErrorAndPageAndLoop() async {
        await assertError(sdk(ScriptedTransport { _ in
            HTTPHead(status: 400, location: nil, body: "{\"error\":\"invalid_request\"}")
        }), "invalid_request")
        await assertError(sdk(ScriptedTransport { _ in
            HTTPHead(status: 200, location: nil, body: "<html>log in</html>")
        }), "interaction_required")
        await assertError(sdk(ScriptedTransport { _ in
            HTTPHead(status: 302, location: "/authorize?again=1", body: "")
        }), "too_many_redirects")
    }

    func testOneTransportBlipIsRetried() async throws {
        let transport = ScriptedTransport(Self.sasLike)
        transport.failFirst = 1
        let result = try await sdk(transport).authorize(Self.ok)
        XCTAssertEqual(result.code, "c-net")
        XCTAssertEqual(transport.calls.count, 2)
    }

    private func assertError(_ nv: NumberVerification, _ expected: String,
                             file: StaticString = #filePath, line: UInt = #line) async {
        do {
            _ = try await nv.authorize(Self.ok)
            XCTFail("expected \(expected)", file: file, line: line)
        } catch let error as AuthorizationError {
            XCTAssertEqual(error.error, expected, file: file, line: line)
        } catch {
            XCTFail("unexpected \(error)", file: file, line: line)
        }
    }

    // MARK: - HTTP wire

    func testRequestLineAndHost() throws {
        let text = String(decoding: try HTTPWire.request(URL(string: "https://sas.example.et:8443/authorize?a=b%20c")!),
                          as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("GET /authorize?a=b%20c HTTP/1.1\r\nHost: sas.example.et:8443\r\n"))
        XCTAssertTrue(text.hasSuffix("Connection: close\r\n\r\n"))
    }

    func testHeadParsing() throws {
        let redirect = Data("HTTP/1.1 302 Found\r\nlocation: https://bank.example/cb?code=1\r\n\r\n".utf8)
        XCTAssertTrue(HTTPWire.isComplete(redirect))
        XCTAssertEqual(try HTTPWire.parse(redirect),
                       HTTPHead(status: 302, location: "https://bank.example/cb?code=1", body: ""))

        let partial = Data("HTTP/1.1 400 Bad Request\r\nContent-Length: 10\r\n\r\n{\"err".utf8)
        XCTAssertFalse(HTTPWire.isComplete(partial))
        XCTAssertFalse(HTTPWire.isComplete(Data("HTTP/1.1 302 Found\r\n".utf8)))
        XCTAssertThrowsError(try HTTPWire.parse(Data("garbage\r\n\r\n".utf8)))
    }
}
