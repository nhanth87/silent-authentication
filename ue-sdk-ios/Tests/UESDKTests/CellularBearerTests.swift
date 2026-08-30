/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Tests: bearer classification + fail-closed cellular gate.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation
import XCTest
@testable import UESDK

final class CellularBearerTests: XCTestCase {

    func testRadioStringsMapToContractBearers() {
        XCTAssertEqual(AccessTech.radio("LTE"), .lte)
        XCTAssertEqual(AccessTech.radio("NR"), .nr)
        XCTAssertEqual(AccessTech.radio("NRNSA"), .nr)
        XCTAssertEqual(AccessTech.radio("HSPA"), .gs2g3g)
        XCTAssertEqual(AccessTech.radio("GPRS"), .gs2g3g)
        XCTAssertEqual(AccessTech.radio("EDGE"), .gs2g3g)
        // Wi-Fi calling must never be mistaken for a cellular data bearer.
        XCTAssertEqual(AccessTech.radio("WiFi"), .wifi)
        XCTAssertEqual(AccessTech.radio("IWLAN"), .wifi)
        XCTAssertEqual(AccessTech.radio(nil), .unknown)
        XCTAssertEqual(AccessTech.radio(""), .unknown)
        XCTAssertEqual(AccessTech.radio("somethingelse"), .unknown)
    }

    func testOnlyRadioBearersAreCellular() {
        XCTAssertTrue(AccessTech.gs2g3g.isCellular)
        XCTAssertTrue(AccessTech.lte.isCellular)
        XCTAssertTrue(AccessTech.nr.isCellular)
        XCTAssertFalse(AccessTech.wifi.isCellular)
        XCTAssertFalse(AccessTech.fixed.isCellular)
        XCTAssertFalse(AccessTech.unknown.isCellular)
    }

    func testGateAcceptsCellularBearer() throws {
        let bearer = CellularBearer(assumed: .lte)
        XCTAssertNoThrow(try bearer.check(.cellular))
        XCTAssertNoThrow(try bearer.check(.cellular4GPlus))
        XCTAssertEqual(bearer.accessTech(), .lte)
    }

    func testGateFailsClosedOnWifi() {
        let bearer = CellularBearer(assumed: .wifi)
        XCTAssertThrowsError(try bearer.check(.cellular)) { error in
            let unavailable = error as! CellularBearer.Unavailable
            XCTAssertEqual(unavailable.observed, .wifi)
            XCTAssertEqual(unavailable.requirement, .cellular)
            XCTAssertTrue(unavailable.description.contains("fall back"))
        }
    }

    func testTwoGDroppedByFourGPlusRequirement() {
        let bearer = CellularBearer(assumed: .gs2g3g)
        // 2G/3G is cellular, but it has no S6a/5G context for the bank to use.
        XCTAssertNoThrow(try bearer.check(.cellular))
        XCTAssertThrowsError(try bearer.check(.cellular4GPlus))
    }

    func testUnknownBearerNeverSatisfiesCellular() {
        XCTAssertThrowsError(try CellularBearer(assumed: .unknown).check(.cellular))
    }

    func testAnyRequirementKeepsLabFlowsWorking() {
        XCTAssertNoThrow(try CellularBearer(assumed: .wifi).check(.any))
    }

    func testCellularSessionConfigurationAllowsExpensiveRoutes() {
        let configuration = CellularBearer.cellularSessionConfiguration(timeout: 3)
        XCTAssertTrue(configuration.allowsCellularAccess)
        // Cellular counts as "expensive": without this Low Data Mode defers the
        // request and the 3 s SAS budget turns into a timeout.
        XCTAssertTrue(configuration.allowsExpensiveNetworkAccess)
        XCTAssertTrue(configuration.allowsConstrainedNetworkAccess)
        XCTAssertFalse(configuration.waitsForConnectivity)
        XCTAssertEqual(configuration.timeoutIntervalForRequest, 3, accuracy: 0.001)
    }
}
