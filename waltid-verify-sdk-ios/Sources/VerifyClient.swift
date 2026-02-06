import Foundation

// MARK: - Configuration

/// Configuration for the VerifyClient
public struct VerifyConfig {
    /// API key for authentication
    public let apiKey: String
    /// Base URL of the Verify API
    public let baseURL: URL

    /// Initialize a new VerifyConfig
    /// - Parameters:
    ///   - apiKey: API key for authentication
    ///   - baseURL: Base URL of the Verify API (defaults to https://verify.example.com)
    public init(apiKey: String, baseURL: URL = URL(string: "https://verify.example.com")!) {
        self.apiKey = apiKey
        self.baseURL = baseURL
    }
}

// MARK: - Request Types

/// Request to initiate identity verification
public struct VerificationRequest: Encodable {
    /// Template name to use for verification
    public let template: String
    /// Response mode (e.g., "direct_post", "redirect")
    public let responseMode: String?
    /// Redirect URI for callback
    public let redirectUri: String?
    /// Additional metadata for the session
    public let metadata: [String: String]?

    /// Initialize a new VerificationRequest
    /// - Parameters:
    ///   - template: Template name to use for verification
    ///   - responseMode: Response mode (optional)
    ///   - redirectUri: Redirect URI for callback (optional)
    ///   - metadata: Additional metadata for the session (optional)
    public init(template: String, responseMode: String? = nil, redirectUri: String? = nil, metadata: [String: String]? = nil) {
        self.template = template
        self.responseMode = responseMode
        self.redirectUri = redirectUri
        self.metadata = metadata
    }

    enum CodingKeys: String, CodingKey {
        case template
        case responseMode = "response_mode"
        case redirectUri = "redirect_uri"
        case metadata
    }
}

// MARK: - Response Types

/// Response from initiating a verification session
public struct VerificationResponse: Decodable {
    /// Unique session identifier
    public let sessionId: String
    /// URL for the QR code image
    public let qrCodeUrl: String
    /// Raw data encoded in the QR code
    public let qrCodeData: String
    /// Deep link for same-device flow
    public let deepLink: String
    /// Unix timestamp when the session expires
    public let expiresAt: Int64

    enum CodingKeys: String, CodingKey {
        case sessionId = "session_id"
        case qrCodeUrl = "qr_code_url"
        case qrCodeData = "qr_code_data"
        case deepLink = "deep_link"
        case expiresAt = "expires_at"
    }
}

/// Status of a verification session
public struct SessionStatus: Decodable {
    /// Unique session identifier
    public let sessionId: String
    /// Current status (e.g., "pending", "verified", "failed", "expired")
    public let status: String
    /// Name of the template used
    public let templateName: String
    /// Verification result (present when status is "verified")
    public let result: SessionResult?
    /// Unix timestamp when verification completed
    public let verifiedAt: Int64?
    /// Additional metadata
    public let metadata: [String: String]?
    /// Unix timestamp when the session expires
    public let expiresAt: Int64

    enum CodingKeys: String, CodingKey {
        case sessionId = "session_id"
        case status
        case templateName = "template_name"
        case result
        case verifiedAt = "verified_at"
        case metadata
        case expiresAt = "expires_at"
    }
}

/// Result of a successful verification
public struct SessionResult: Decodable {
    /// Answers to questions in the template
    public let answers: [String: String]?
    /// Verified credentials
    public let credentials: [Credential]?
}

/// A verified credential
public struct Credential: Decodable {
    /// Credential format (e.g., "dc+sd-jwt", "mso_mdoc")
    public let format: String
    /// Verifiable Credential Type (for SD-JWT)
    public let vct: String?
    /// Document type (for mdoc)
    public let doctype: String?
    /// Claims disclosed from the credential
    public let disclosedClaims: [String: String]

    enum CodingKeys: String, CodingKey {
        case format, vct, doctype
        case disclosedClaims = "disclosed_claims"
    }
}

// MARK: - Errors

/// Errors that can occur when using the VerifyClient
public enum VerifyError: Error, LocalizedError {
    /// The API request failed
    case requestFailed(statusCode: Int, message: String?)
    /// The request timed out
    case timeout
    /// Network error occurred
    case networkError(Error)
    /// Failed to encode request
    case encodingError(Error)
    /// Failed to decode response
    case decodingError(Error)
    /// Invalid URL
    case invalidURL

    public var errorDescription: String? {
        switch self {
        case .requestFailed(let statusCode, let message):
            return "Request failed with status \(statusCode)\(message.map { ": \($0)" } ?? "")"
        case .timeout:
            return "Request timed out"
        case .networkError(let error):
            return "Network error: \(error.localizedDescription)"
        case .encodingError(let error):
            return "Failed to encode request: \(error.localizedDescription)"
        case .decodingError(let error):
            return "Failed to decode response: \(error.localizedDescription)"
        case .invalidURL:
            return "Invalid URL"
        }
    }
}

// MARK: - Client

/// Client for interacting with the walt.id Verify API
public class VerifyClient {
    private let config: VerifyConfig
    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    /// Initialize a new VerifyClient
    /// - Parameter config: Configuration for the client
    public init(config: VerifyConfig) {
        self.config = config
        self.session = URLSession.shared
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    /// Initialize a new VerifyClient with a custom URLSession
    /// - Parameters:
    ///   - config: Configuration for the client
    ///   - session: Custom URLSession to use for requests
    public init(config: VerifyConfig, session: URLSession) {
        self.config = config
        self.session = session
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
    }

    // MARK: - Public Methods

    /// Initiate an identity verification session
    /// - Parameter request: The verification request
    /// - Returns: The verification response containing session details and QR code
    /// - Throws: VerifyError if the request fails
    public func verifyIdentity(_ request: VerificationRequest) async throws -> VerificationResponse {
        let url = config.baseURL.appendingPathComponent("v1/verify/identity")

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")

        do {
            urlRequest.httpBody = try encoder.encode(request)
        } catch {
            throw VerifyError.encodingError(error)
        }

        return try await performRequest(urlRequest, expectedStatus: 201)
    }

    /// Get the status of a verification session
    /// - Parameter sessionId: The session ID to check
    /// - Returns: The current session status
    /// - Throws: VerifyError if the request fails
    public func getSession(_ sessionId: String) async throws -> SessionStatus {
        let url = config.baseURL.appendingPathComponent("v1/sessions/\(sessionId)")

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "GET"
        urlRequest.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")

        return try await performRequest(urlRequest, expectedStatus: 200)
    }

    /// Poll for session completion with timeout
    /// - Parameters:
    ///   - sessionId: The session ID to poll
    ///   - pollingInterval: Interval between polls in seconds (default: 2)
    ///   - timeout: Maximum time to wait in seconds (default: 300)
    /// - Returns: The final session status
    /// - Throws: VerifyError if the request fails or times out
    public func waitForSession(_ sessionId: String, pollingInterval: TimeInterval = 2, timeout: TimeInterval = 300) async throws -> SessionStatus {
        let startTime = Date()

        while Date().timeIntervalSince(startTime) < timeout {
            let status = try await getSession(sessionId)

            if status.status != "pending" {
                return status
            }

            try await Task.sleep(nanoseconds: UInt64(pollingInterval * 1_000_000_000))
        }

        throw VerifyError.timeout
    }

    // MARK: - Private Methods

    private func performRequest<T: Decodable>(_ request: URLRequest, expectedStatus: Int) async throws -> T {
        let data: Data
        let response: URLResponse

        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw VerifyError.networkError(error)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw VerifyError.requestFailed(statusCode: 0, message: "Invalid response type")
        }

        guard httpResponse.statusCode == expectedStatus else {
            let message = String(data: data, encoding: .utf8)
            throw VerifyError.requestFailed(statusCode: httpResponse.statusCode, message: message)
        }

        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw VerifyError.decodingError(error)
        }
    }
}
