// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "WoVoiceCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "WoVoiceCore", targets: ["WoVoiceCore"])],
    targets: [
        .systemLibrary(name: "CSQLite"),
        .target(name: "WoVoiceCore", dependencies: ["CSQLite"]),
        .testTarget(name: "WoVoiceCoreTests", dependencies: ["WoVoiceCore"])
    ]
)
