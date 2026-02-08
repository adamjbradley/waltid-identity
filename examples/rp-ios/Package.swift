// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AgeVerifyApp",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .executable(name: "AgeVerifyApp", targets: ["AgeVerifyApp"])
    ],
    dependencies: [
        .package(path: "../../waltid-verify-sdk-ios")
    ],
    targets: [
        .executableTarget(
            name: "AgeVerifyApp",
            dependencies: [
                .product(name: "WaltIDVerifySDK", package: "waltid-verify-sdk-ios")
            ],
            path: "Sources/AgeVerifyApp"
        )
    ]
)
