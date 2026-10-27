/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * App Clip invocation: the session id the phone is asked to claim.
 * R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import Foundation

/// Reads the hand-off session an App Clip was launched for.
///
/// **Why an App Clip.** A web page cannot pin traffic to the cellular radio;
/// native code can (``CellularHTTPClient``). An App Clip runs that native code
/// without the user installing the bank app: one tap on the App Clip card.
///
/// **Two ways in, one URL shape.** On a desktop the bank renders the link as a
/// QR code and the iPhone camera scans it. On the iPhone itself a QR code cannot
/// be scanned, so the bank shows the same link as the Safari App Clip card
/// (`<meta name="apple-itunes-app" content="app-id=…, app-clip-bundle-id=…,
/// app-clip-display=card">` on the page at that URL) or sends it in Messages.
/// Either way iOS hands the App Clip an `NSUserActivity` of type
/// `NSUserActivityTypeBrowsingWeb` whose `webpageURL` is that link. Safari's
/// `app-argument` is not passed to App Clips, so the session id must be in the
/// URL itself.
///
/// Accepted: `https://<allowed host>/…/<uuid>` or `…?session=<uuid>` (or
/// `?s=<uuid>`). The host must be one the bank lists, because this URL decides
/// which session the phone proves its number for. A look-alike domain is the
/// cheapest QR / link hijack. Everything else is refused, never parsed hopefully.
///
/// **Relaunch.** From the App Library or Spotlight iOS reuses the *previous*
/// invocation URL, whose session is by then claimed or expired; the claim then
/// comes back not approved and the App Clip shows the fallback. From a
/// notification or the App Switcher there is no URL at all (`nil` →
/// ``Refused``): start again from the bank's page.
public struct AppClipInvocation: Equatable {

    /// The hand-off session to claim.
    public let sessionId: String
    /// The invocation URL as received (for the App Clip's own UI and state).
    public let url: URL

    /// The invocation cannot be used. Fall back; never guess a session.
    public struct Refused: Error, Equatable, CustomStringConvertible {
        public let rule: String
        public var description: String { "App Clip invocation refused: \(rule)" }
    }

    /// - Parameters:
    ///   - url: `NSUserActivity.webpageURL` of the invocation
    ///   - allowedHosts: the bank's App Clip link hosts, e.g. `["clip.bank.example"]`
    public static func parse(_ url: URL?, allowedHosts: Set<String>) throws -> AppClipInvocation {
        guard let url = url else {
            throw Refused(rule: "no invocation URL (relaunched from a notification or the App Switcher)")
        }
        let hosts = Set(allowedHosts.map { $0.lowercased() })
        guard !hosts.isEmpty else { throw Refused(rule: "no allowed host configured") }
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https" else {
            throw Refused(rule: "invocation URL must use https")
        }
        guard let host = components.host?.lowercased(), hosts.contains(host) else {
            throw Refused(rule: "invocation host is not one of the bank's App Clip hosts")
        }
        guard components.user == nil, components.password == nil,
              components.port == nil || components.port == 443 else {
            throw Refused(rule: "invocation URL carries credentials or a non-standard port")
        }

        var fromQuery: String?
        for item in components.queryItems ?? [] where item.name == "session" || item.name == "s" {
            guard fromQuery == nil else { throw Refused(rule: "session given more than once") }
            fromQuery = item.value
        }
        let last = url.pathComponents.last.flatMap { $0 == "/" ? nil : $0 }
        let fromPath = last.flatMap { QrSessionClient.isSessionId($0) ? $0 : nil }

        let candidate: String?
        switch (fromQuery, fromPath) {
        case let (query?, path?):
            guard query.lowercased() == path.lowercased() else {
                throw Refused(rule: "path and query name different sessions")
            }
            candidate = query
        case let (query?, nil):
            candidate = query
        case let (nil, path?):
            candidate = path
        case (nil, nil):
            candidate = nil
        }
        guard let session = candidate, QrSessionClient.isSessionId(session) else {
            throw Refused(rule: "no session id in the invocation URL")
        }
        return AppClipInvocation(sessionId: session, url: url)
    }

    #if canImport(Darwin)
    /// Same, from the activity iOS passes to the App Clip
    /// (`onContinueUserActivity(NSUserActivityTypeBrowsingWeb)` in SwiftUI,
    /// `scene(_:willConnectTo:options:)` / `scene(_:continue:)` in UIKit).
    public static func parse(_ activity: NSUserActivity?,
                             allowedHosts: Set<String>) throws -> AppClipInvocation {
        guard let activity = activity, activity.activityType == NSUserActivityTypeBrowsingWeb else {
            throw Refused(rule: "not a web-browsing invocation")
        }
        return try parse(activity.webpageURL, allowedHosts: allowedHosts)
    }
    #endif
}
