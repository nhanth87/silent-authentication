/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Access technology + radio mapping. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation

/// Radio access technology the tuple was captured on. Raw values mirror the SAS
/// enum `et.restlink.sas.model.AccessTech` and the `accessTech` field of
/// `POST /session-tuple`.
public enum AccessTech: String, Codable, CaseIterable {

    /// 2G/3G packet domain (GPRS/EDGE/UMTS/HSPA) — MAP verifier, TS 29.002.
    case gs2g3g = "GS_2G3G"
    /// 4G LTE — Diameter S6a verifier, TS 29.272.
    case lte = "LTE"
    /// 5G NR.
    case nr = "NR"
    /// Wi-Fi — never a cellular binding.
    case wifi = "WIFI"
    /// Fixed access.
    case fixed = "FIXED"
    /// Could not be determined.
    case unknown = "UNKNOWN"

    /// True only for a real cellular 2G/3G/4G/5G data bearer.
    public var isCellular: Bool {
        switch self {
        case .gs2g3g, .lte, .nr: return true
        case .wifi, .fixed, .unknown: return false
        }
    }
}

extension AccessTech {

    /// Maps a `CTRadioAccessTechnology` raw value (and the equivalents the SAS
    /// logs) to this enum. Kept as a pure function so it is unit-testable
    /// without a radio.
    ///
    /// Wi-Fi calling (`WiFi` / `IWLAN`) is deliberately **not** cellular: the
    /// IP the operator sees belongs to the Wi-Fi network, so it cannot attest
    /// an IP→MSISDN binding.
    public static func radio(_ raw: String?) -> AccessTech {
        guard let raw = raw, !raw.isEmpty else { return .unknown }
        let key = raw.uppercased()
        if key.contains("IWLAN") || key == "WIFI" || key.contains("WLAN") { return .wifi }
        if key.contains("NRNSA") || key.contains("NR") { return .nr }
        if key.contains("LTE") { return .lte }
        if key.contains("GPRS") || key.contains("EDGE") || key.contains("UMTS")
            || key.contains("UTRAN") || key.contains("HSPA") || key.contains("HSDPA")
            || key.contains("HSUPA") || key.contains("HSPAP") || key.contains("CDMA")
            || key.contains("EVDO") || key.contains("EHRPD") || key.contains("GSM")
            || key == "1XRTT" || key.contains("TDSCDMA") {
            return .gs2g3g
        }
        return .unknown
    }
}
