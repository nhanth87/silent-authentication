/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Device-side session-tuple snapshot. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation

/// Point-in-time device tuple posted to SAS `POST /session-tuple`.
///
/// Devices cannot observe the real CGNAT source IP/port: `srcIp` and
/// `srcPort` are only ever set when the embedding app supplies them, so in
/// practice snapshots carry `ts` plus an optional app-supplied MSISDN.
/// The SDK itself never reads subscriber identifiers.
public struct TupleSnapshot: Codable, Equatable {

    public var srcIp: String?
    public var srcPort: Int?
    /// Capture timestamp, epoch milliseconds.
    public var ts: Int64
    public var msisdn: String?
    public var imsi: String?
    /// Bearer the tuple was captured on. Omitted from the wire when nil or
    /// unknown — the SAS treats an absent declaration as "not attested", never
    /// as cellular.
    public var accessTech: AccessTech?

    public init(srcIp: String? = nil,
                srcPort: Int? = nil,
                ts: Int64,
                msisdn: String? = nil,
                imsi: String? = nil,
                accessTech: AccessTech? = nil) {
        self.srcIp = srcIp
        self.srcPort = srcPort
        self.ts = ts
        self.msisdn = msisdn
        self.imsi = imsi
        self.accessTech = accessTech
    }

    /// Device-visible snapshot: capture timestamp now, no CGNAT visibility.
    public init(msisdn: String? = nil, accessTech: AccessTech? = nil) {
        let nowMillis = Int64(Date().timeIntervalSince1970 * 1000.0)
        self.init(ts: nowMillis, msisdn: msisdn, accessTech: accessTech)
    }

    private enum CodingKeys: String, CodingKey {
        case srcIp
        case srcPort
        case ts
        case msisdn
        case imsi
        case accessTech
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        srcIp = try container.decodeIfPresent(String.self, forKey: .srcIp)
        srcPort = try container.decodeIfPresent(Int.self, forKey: .srcPort)
        ts = try container.decode(Int64.self, forKey: .ts)
        msisdn = try container.decodeIfPresent(String.self, forKey: .msisdn)
        imsi = try container.decodeIfPresent(String.self, forKey: .imsi)
        accessTech = try container.decodeIfPresent(AccessTech.self, forKey: .accessTech)
    }

    /// Encodes only present fields so nulls are omitted from the wire body.
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(srcIp, forKey: .srcIp)
        try container.encodeIfPresent(srcPort, forKey: .srcPort)
        try container.encode(ts, forKey: .ts)
        try container.encodeIfPresent(msisdn, forKey: .msisdn)
        try container.encodeIfPresent(imsi, forKey: .imsi)
        if let accessTech = accessTech, accessTech != .unknown {
            try container.encode(accessTech, forKey: .accessTech)
        }
    }
}

