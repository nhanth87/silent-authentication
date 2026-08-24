// swift-tools-version:5.9

/*
 * Silent Auth UE SDK (iOS) — Restlink (Ethiopia).
 * Device-side session-tuple poster for SAS /session-tuple.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

import PackageDescription

let package = Package(
    name: "UESDK",
    products: [
        .library(name: "UESDK", targets: ["UESDK"])
    ],
    targets: [
        .target(
            name: "UESDK"
        ),
        .testTarget(
            name: "UESDKTests",
            dependencies: ["UESDK"]
        )
    ]
)
