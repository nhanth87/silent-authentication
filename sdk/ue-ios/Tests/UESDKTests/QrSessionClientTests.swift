/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import XCTest
@testable import UESDK

final class QrSessionClientTests: XCTestCase {

    private static let id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

    // MARK: - payload

    func testParsesTheCanonicalScheme() throws {
        XCTAssertEqual(try QrSessionClient.parsePayload("silentauth://qr-session/\(Self.id)"),
                       Self.id)
        XCTAssertEqual(try QrSessionClient.parsePayload("SILENTAUTH://QR-SESSION/\(Self.id)"),
                       Self.id)
    }

    func testParsesABareSessionId() throws {
        XCTAssertEqual(try QrSessionClient.parsePayload(Self.id), Self.id)
        XCTAssertEqual(try QrSessionClient.parsePayload("  \(Self.id)  "), Self.id)
    }

    func testParsesAPayloadCarriedInAUrl() throws {
        XCTAssertEqual(
            try QrSessionClient.parsePayload("https://bank.example/q?p=silentauth://qr-session/\(Self.id)"),
            Self.id)
    }

    func testRefusesAnythingThatIsNotAUuid() {
        XCTAssertThrowsError(try QrSessionClient.parsePayload(nil))
        XCTAssertThrowsError(try QrSessionClient.parsePayload(""))
        XCTAssertThrowsError(try QrSessionClient.parsePayload("   "))
        XCTAssertThrowsError(try QrSessionClient.parsePayload("silentauth://qr-session/"))
        XCTAssertThrowsError(try QrSessionClient.parsePayload("not-a-session"))
        XCTAssertThrowsError(try QrSessionClient.parsePayload(
            "silentauth://qr-session/../../etc/passwd"))
        XCTAssertThrowsError(try QrSessionClient.parsePayload("\(Self.id)/extra"))
    }

    func testRefusesAUuidShapedStringWithTheWrongSeparators() {
        XCTAssertFalse(QrSessionClient.isSessionId("3f2504e04f8911d39a0c0305e82c33010"))
        XCTAssertFalse(QrSessionClient.isSessionId("3f2504e0_4f89-11d3-9a0c-0305e82c3301"))
    }

    // MARK: - claim body

    func testClaimBodyOmitsAbsentFields() {
        XCTAssertEqual(QrSessionClient.claimBody(msisdn: nil, accessTech: .lte,
                                                 operatorToken: nil),
                       #"{"accessTech":"LTE"}"#)
        XCTAssertEqual(QrSessionClient.claimBody(msisdn: nil, accessTech: .unknown,
                                                 operatorToken: nil),
                       "{}")
        XCTAssertEqual(QrSessionClient.claimBody(msisdn: "+251911111111", accessTech: .nr,
                                                 operatorToken: nil),
                       #"{"msisdn":"+251911111111","accessTech":"NR"}"#)
    }

    func testClaimBodyCarriesTheOperatorTokenForTheWifiVariant() {
        XCTAssertEqual(QrSessionClient.claimBody(msisdn: nil, accessTech: .wifi,
                                                 operatorToken: "tk-123"),
                       #"{"accessTech":"WIFI","operatorToken":"tk-123"}"#)
    }

    func testClaimBodyEscapesTheToken() {
        let body = QrSessionClient.claimBody(msisdn: nil, accessTech: .wifi,
                                             operatorToken: "a\"b")
        XCTAssertTrue(body.contains("\\\"b"))
    }

    // MARK: - verdict

    func testApprovedRequiresAnExplicitTrueAndResolved() {
        XCTAssertTrue(QrSessionClient.ClaimResult(sessionId: Self.id, state: .resolved,
                                                 verified: true, fallbackReason: nil).approved)
        XCTAssertFalse(QrSessionClient.ClaimResult(sessionId: Self.id, state: .resolved,
                                                  verified: false,
                                                  fallbackReason: "SIM_SWAP_SUSPECT").approved,
                       "an explicit false is not an approval")
        XCTAssertFalse(QrSessionClient.ClaimResult(sessionId: Self.id, state: .pending,
                                                  verified: true, fallbackReason: nil).approved,
                       "a true that is not RESOLVED is not an approval")
        XCTAssertFalse(QrSessionClient.ClaimResult(sessionId: Self.id, state: .pending,
                                                  verified: nil, fallbackReason: nil).approved)
        XCTAssertFalse(QrSessionClient.ClaimResult(sessionId: Self.id, state: .unknown,
                                                  verified: true, fallbackReason: nil).approved,
                       "an unparseable state is not an approval")
    }

    // MARK: - flat JSON

    func testFlatParsesTheFieldsTheSdkAsksFor() {
        let flat = Flat(#"{"sessionId":"\#(Self.id)","state":"RESOLVED","devicePhoneNumberVerified":true,"fallbackReason":null}"#)
        XCTAssertEqual(flat.string("sessionId"), Self.id)
        XCTAssertEqual(flat.state("state"), .resolved)
        XCTAssertEqual(flat.bool("devicePhoneNumberVerified"), true)
        XCTAssertNil(flat.string("fallbackReason"))
    }

    func testFlatSeparatesAbsentFromFalse() {
        let absent = Flat(#"{"state":"PENDING"}"#)
        XCTAssertNil(absent.bool("devicePhoneNumberVerified"),
                     "an absent verdict must not be read as false, or as true")
        let explicitFalse = Flat(#"{"devicePhoneNumberVerified":false}"#)
        XCTAssertEqual(explicitFalse.bool("devicePhoneNumberVerified"), false)
    }

    func testFlatRefusesMalformedBodies() {
        XCTAssertNil(Flat("").string("state"))
        XCTAssertNil(Flat("not json at all").string("state"))
        XCTAssertNil(Flat(#"{"state":"#).string("state"))
        XCTAssertNil(Flat(#"{"nested":{"state":"RESOLVED"}}"#).string("state"),
                     "a nested object is out of scope; the reader must not reach into it")
        XCTAssertNil(Flat("[1,2,3]").string("state"))
    }

    func testFlatReadsNumbersAndDefaultsOnGarbage() {
        XCTAssertEqual(Flat(#"{"expiresInSeconds":120}"#).number("expiresInSeconds"), 120)
        XCTAssertEqual(Flat(#"{"expiresInSeconds":"soon"}"#).number("expiresInSeconds"), -1)
        XCTAssertEqual(Flat("{}").number("expiresInSeconds"), -1)
    }

    // MARK: - fail-closed on the device

    func testAWifiPhoneCannotClaimWithoutAnOperatorToken() {
        let bearer = CellularBearer(assumed: .wifi)
        XCTAssertThrowsError(try bearer.check(.cellular)) { error in
            XCTAssertTrue(error is CellularBearer.Unavailable)
        }
    }
}
