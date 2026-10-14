/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Linux shims so the package builds and tests in CI. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

#if canImport(FoundationNetworking)
import Foundation
import FoundationNetworking

extension URLSession {

    /// swift-corelibs-foundation (Swift 5.10) has no async `data(for:)`; Apple
    /// platforms do, and this file does not exist for them.
    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        try await withCheckedThrowingContinuation { continuation in
            dataTask(with: request) { data, response, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let data = data, let response = response {
                    continuation.resume(returning: (data, response))
                } else {
                    continuation.resume(throwing: URLError(.badServerResponse))
                }
            }.resume()
        }
    }
}
#endif
