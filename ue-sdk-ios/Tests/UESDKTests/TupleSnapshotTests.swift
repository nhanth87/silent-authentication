/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Tests: TupleSnapshot encode-if-present wire shape (nulls omitted).
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation
import XCTest
@testable import UESDK

final class TupleSnapshotTests: XCTestCase {

    func testEncodesOnlyPresentFieldsInContractOrder() throws {
        let snapshot = TupleSnapshot(ts: 1724200000000, msisdn: "+251911111111")
        let data = try JSONEncoder().encode(snapshot)

        XCTAssertEqual(
            String(data: data, encoding: .utf8),
            "{\"ts\":1724200000000,\"msisdn\":\"+251911111111\"}")
    }

    func testEncodesBareTsWhenNothingSupplied() throws {
        let snapshot = TupleSnapshot(srcIp: nil, srcPort: nil, ts: 1724200000001)
        let data = try JSONEncoder().encode(snapshot)

        XCTAssertEqual(String(data: data, encoding: .utf8), "{\"ts\":1724200000001}")
    }

    func testEscapesStringsPerRFC8259ViaJSONEncoder() throws {
        let snapshot = TupleSnapshot(ts: 1724200000003, msisdn: "+25191\"x\"\n")
        let data = try JSONEncoder().encode(snapshot)

        XCTAssertEqual(
            String(data: data, encoding: .utf8),
            "{\"ts\":1724200000003,\"msisdn\":\"+25191\\\"x\\\"\\n\"}")
    }

    func testDecodesMissingOptionalFieldsAsNil() throws {
        let data = Data("{\"ts\":1724200000004,\"msisdn\":\"+251911111111\"}".utf8)
        let snapshot = try JSONDecoder().decode(TupleSnapshot.self, from: data)

        XCTAssertNil(snapshot.srcIp)
        XCTAssertNil(snapshot.srcPort)
        XCTAssertEqual(snapshot.ts, 1724200000004)
        XCTAssertEqual(snapshot.msisdn, "+251911111111")
        XCTAssertNil(snapshot.imsi)
    }
}
