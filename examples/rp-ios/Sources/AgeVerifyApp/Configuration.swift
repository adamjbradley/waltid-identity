import Foundation
import WaltIDVerifySDK

/// Application configuration
///
/// In a production app, these values would come from a secure backend or
/// environment configuration. Never embed API keys in production apps.
enum Configuration {
    /// Verify API base URL
    /// Default: http://localhost:7010 for local development
    static let verifyAPIURL: URL = {
        if let urlString = ProcessInfo.processInfo.environment["VERIFY_API_URL"],
           let url = URL(string: urlString) {
            return url
        }
        return URL(string: "http://localhost:7010")!
    }()

    /// API key for Verify API authentication
    /// In production, this should be fetched from your backend, never hardcoded
    static let apiKey: String = {
        if let key = ProcessInfo.processInfo.environment["VERIFY_API_KEY"] {
            return key
        }
        // Demo key for development - replace in production
        return "vfy_demo_key"
    }()

    /// Verification template to use
    static let verificationTemplate = "age_check"

    /// Create a VerifyConfig instance
    static var verifyConfig: VerifyConfig {
        VerifyConfig(apiKey: apiKey, baseURL: verifyAPIURL)
    }
}
