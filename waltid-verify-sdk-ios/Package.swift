// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "WaltIDVerifySDK",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "WaltIDVerifySDK", targets: ["WaltIDVerifySDK"])
    ],
    targets: [
        .target(name: "WaltIDVerifySDK", path: "Sources")
    ]
)
