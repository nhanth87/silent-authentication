/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Cellular bearer gate for the SAS. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation
#if canImport(Network)
import Network
#endif
#if canImport(CoreTelephony)
import CoreTelephony
#endif

/// Decides whether this device may attempt silent auth right now, and hands the
/// caller a `URLSessionConfiguration` that will not quietly stall on cellular.
///
/// **The iOS limitation, stated exactly.** Apple exposes `requiredInterfaceType`
/// and `prohibitedInterfaceTypes` on **`NWParameters`**, which constrain
/// `NWConnection` (and listeners/browsers) — *not* on `URLSessionConfiguration`.
/// The only interface-related properties a `URLSession` has are
/// `allowsCellularAccess`, `allowsExpensiveNetworkAccess`,
/// `allowsConstrainedNetworkAccess` and `allowsUltraConstrainedNetworkAccess`,
/// and those *permit* cellular rather than *prefer* it. So
/// `URLSessionConfiguration.requiredInterfaceType = .cellular` — the line that
/// circulates in CAMARA write-ups — does not exist and will not compile.
///
/// What is real, and what this type does:
/// 1. **Observe** the path with `NWPathMonitor` and fail closed when the request
///    would leave over Wi-Fi, because a Wi-Fi source address cannot be attested
///    to an MSISDN by the PGW.
/// 2. **Permit** cellular unconditionally, so Low Data Mode / constrained
///    networks do not defer the call past the 3 s SAS budget.
/// 3. Leave pinning honest: to truly bind a socket, build it on `NWConnection`
///    with `NWParameters.requiredInterfaceType = .cellular` (plus
///    `prohibitedInterfaceTypes = [.wifi]`) and pass its bytes yourself —
///    URLSession cannot do it.
public final class CellularBearer {

    /// How strictly a cellular bearer is demanded. Mirrors the Android SDK.
    public enum Requirement: String, Sendable {
        /// Lab / TS.43 over Wi-Fi — anything goes.
        case any
        /// Any cellular bearer: 2G, 3G, 4G or 5G.
        case cellular
        /// LTE or NR only.
        case cellular4GPlus
    }

    /// The demanded bearer is unavailable. This is a fallback signal: the app
    /// must take the OTP / passkey / push path, never retry over Wi-Fi.
    public struct Unavailable: Error, CustomStringConvertible, Equatable {
        public let requirement: Requirement
        public let observed: AccessTech

        public init(_ requirement: Requirement, _ observed: AccessTech) {
            self.requirement = requirement
            self.observed = observed
        }

        public var description: String {
            "silent auth needs \(requirement.rawValue) bearer, device is on \(observed.rawValue)"
                + " - turn mobile data on / drop Wi-Fi; fall back to OTP, never proceed"
        }
    }

    private let assumed: AccessTech?

    /// - Parameter assumed: forces the observed bearer (unit tests, or an app
    ///   that pinned its own `NWConnection`). Production callers use the default
    ///   and let `NWPathMonitor` answer.
    public init(assumed: AccessTech? = nil) {
        self.assumed = assumed
    }

    /// Current access technology: `assumed` when set, else the live path.
    public func accessTech() -> AccessTech {
        if let assumed = assumed { return assumed }
        return CellularBearer.probe()
    }

    /// Throws ``Unavailable`` unless the observed bearer satisfies the
    /// requirement. `any` never throws, so lab flows keep working.
    public func check(_ requirement: Requirement) throws {
        let observed = accessTech()
        switch requirement {
        case .any:
            return
        case .cellular:
            if !observed.isCellular { throw Unavailable(requirement, observed) }
        case .cellular4GPlus:
            if observed != .lte && observed != .nr { throw Unavailable(requirement, observed) }
        }
    }

    /// Blocks until the path satisfies `requirement` or `timeout` elapses. Use
    /// it right after asking the user to switch Wi-Fi off — the radio handover
    /// is not instantaneous. Returns nil when the bearer never appeared.
    public func wait(for requirement: Requirement, timeout: TimeInterval) -> AccessTech? {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if (try? check(requirement)) != nil { return accessTech() }
            Thread.sleep(forTimeInterval: 0.1)
        } while Date() < deadline
        return nil
    }

    /// A configuration that will use cellular when it is available and will not
    /// wait for the OS to reconsider an "expensive" route.
    public static func cellularSessionConfiguration(timeout: TimeInterval) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.default
        configuration.allowsCellularAccess = true
        // Cellular counts as expensive/constrained: without these the request
        // can be deferred under Low Data Mode instead of failing fast.
        configuration.allowsExpensiveNetworkAccess = true
        configuration.allowsConstrainedNetworkAccess = true
        configuration.waitsForConnectivity = false
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return configuration
    }

    #if canImport(Network)
    /// Reads the live path. `NWPathMonitor` only reports a real path after it is
    /// started and has fired once, so this starts a short-lived monitor, waits
    /// for the first update, and cancels it — no monitor is retained, and an
    /// unanswered probe is `.unknown` (fail closed) rather than a guess.
    static func probe(timeout: TimeInterval = 0.25) -> AccessTech {
        let monitor = NWPathMonitor()
        let seen = DispatchSemaphore(value: 0)
        let box = PathBox()
        monitor.pathUpdateHandler = { path in
            box.set(path)
            seen.signal()
        }
        monitor.start(queue: DispatchQueue(label: "et.restlink.uesdk.path-probe"))
        _ = seen.wait(timeout: .now() + timeout)
        // Snapshot before cancel(): after cancel the monitor no longer holds a
        // meaningful path. currentPath covers the rare case where the first
        // update did not land inside the probe window.
        let observedPath = box.get() ?? monitor.currentPath
        monitor.cancel()
        guard let path = observedPath, path.status == .satisfied else { return .unknown }
        if path.usesInterfaceType(.cellular) { return radioTech() }
        if path.usesInterfaceType(.wifi) { return .wifi }
        if path.usesInterfaceType(.wiredEthernet) { return .fixed }
        // VPN / hotspot / P2P say nothing about the underlying radio.
        return .unknown
    }

    /// Thread-safe one-shot holder for the first path update.
    private final class PathBox: @unchecked Sendable {
        private let lock = NSLock()
        private var path: NWPath?

        func set(_ newValue: NWPath) {
            lock.lock()
            defer { lock.unlock() }
            if path == nil { path = newValue }
        }

        func get() -> NWPath? {
            lock.lock()
            defer { lock.unlock() }
            return path
        }
    }
    #else
    static func probe(timeout: TimeInterval = 0.25) -> AccessTech { .unknown }
    #endif

    /// Radio generation where readable. A cellular path whose generation cannot
    /// be read stays conservative 2G/3G rather than claiming LTE: the SAS routes
    /// GS_2G3G to MAP PSI, which answers for 2G-5G subscribers, while a wrong
    /// LTE claim would route to S6a and miss.
    static func radioTech() -> AccessTech {
        #if canImport(CoreTelephony) && os(iOS)
        let info = CTTelephonyNetworkInfo()
        if let services = info.serviceCurrentRadioAccessTechnology, !services.isEmpty {
            let mapped = services.values.map { AccessTech.radio($0) }
            for preferred in [AccessTech.nr, .lte, .gs2g3g] {
                if mapped.contains(preferred) { return preferred }
            }
        }
        #endif
        return .gs2g3g
    }
}
