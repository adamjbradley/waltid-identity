import XCTest
@testable import WaltIDVerifySDK

/// Tests for the walt.id Verify SDK
///
/// These tests validate the SDK functionality with mock responses
/// and sandbox credentials for integration testing.
final class VerifyClientTests: XCTestCase {

    // MARK: - Sandbox Credentials

    static let sandboxTestApiKey = "vfy_test_sandbox_demo_key_12345678"
    static let sandboxLiveApiKey = "vfy_live_sandbox_demo_key_12345678"
    static let sandboxApiUrl = URL(string: "http://localhost:7010")!

    // MARK: - Configuration Tests

    func testConfigInitialization() {
        let config = VerifyConfig(
            apiKey: Self.sandboxTestApiKey,
            baseURL: Self.sandboxApiUrl
        )

        XCTAssertEqual(config.apiKey, Self.sandboxTestApiKey)
        XCTAssertEqual(config.baseURL, Self.sandboxApiUrl)
    }

    func testConfigWithDefaultURL() {
        let config = VerifyConfig(apiKey: Self.sandboxTestApiKey)

        XCTAssertEqual(config.apiKey, Self.sandboxTestApiKey)
        XCTAssertEqual(config.baseURL.absoluteString, "https://verify.example.com")
    }

    // MARK: - Client Initialization Tests

    func testClientInitialization() {
        let config = VerifyConfig(
            apiKey: Self.sandboxTestApiKey,
            baseURL: Self.sandboxApiUrl
        )
        let client = VerifyClient(config: config)

        XCTAssertNotNil(client)
    }

    func testClientWithCustomSession() {
        let config = VerifyConfig(
            apiKey: Self.sandboxTestApiKey,
            baseURL: Self.sandboxApiUrl
        )
        let session = URLSession(configuration: .ephemeral)
        let client = VerifyClient(config: config, session: session)

        XCTAssertNotNil(client)
    }

    // MARK: - Request Type Tests

    func testVerificationRequestEncoding() throws {
        let request = VerificationRequest(
            template: "kyc-basic",
            responseMode: "answers",
            redirectUri: "https://example.com/callback",
            metadata: ["userId": "12345", "orderId": "order-abc"]
        )

        let encoder = JSONEncoder()
        let data = try encoder.encode(request)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]

        XCTAssertEqual(json["template"] as? String, "kyc-basic")
        XCTAssertEqual(json["response_mode"] as? String, "answers")
        XCTAssertEqual(json["redirect_uri"] as? String, "https://example.com/callback")

        let metadata = json["metadata"] as? [String: String]
        XCTAssertEqual(metadata?["userId"], "12345")
        XCTAssertEqual(metadata?["orderId"], "order-abc")
    }

    func testVerificationRequestWithMinimalParameters() throws {
        let request = VerificationRequest(template: "kyc-basic")

        let encoder = JSONEncoder()
        let data = try encoder.encode(request)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]

        XCTAssertEqual(json["template"] as? String, "kyc-basic")
        XCTAssertNil(json["response_mode"])
        XCTAssertNil(json["redirect_uri"])
        XCTAssertNil(json["metadata"])
    }

    // MARK: - Response Type Tests

    func testVerificationResponseDecoding() throws {
        let json = """
        {
            "session_id": "vs_test123",
            "qr_code_url": "https://api.example.com/qr/test123.png",
            "qr_code_data": "openid4vp://authorize?response_type=vp_token",
            "deep_link": "wallet://verify?request_uri=https://api.example.com/request",
            "expires_at": 1735689600
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        let response = try decoder.decode(VerificationResponse.self, from: json)

        XCTAssertEqual(response.sessionId, "vs_test123")
        XCTAssertEqual(response.qrCodeUrl, "https://api.example.com/qr/test123.png")
        XCTAssertTrue(response.qrCodeData.hasPrefix("openid4vp://"))
        XCTAssertTrue(response.deepLink.hasPrefix("wallet://"))
        XCTAssertEqual(response.expiresAt, 1735689600)
    }

    func testSessionStatusDecoding() throws {
        let json = """
        {
            "session_id": "vs_test123",
            "status": "pending",
            "template_name": "kyc-basic",
            "expires_at": 1735689600
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        let status = try decoder.decode(SessionStatus.self, from: json)

        XCTAssertEqual(status.sessionId, "vs_test123")
        XCTAssertEqual(status.status, "pending")
        XCTAssertEqual(status.templateName, "kyc-basic")
        XCTAssertNil(status.result)
        XCTAssertNil(status.verifiedAt)
        XCTAssertNil(status.metadata)
        XCTAssertEqual(status.expiresAt, 1735689600)
    }

    func testSessionStatusWithResultDecoding() throws {
        let json = """
        {
            "session_id": "vs_verified",
            "status": "verified",
            "template_name": "kyc-basic",
            "result": {
                "answers": {
                    "full_name": "John Doe",
                    "date_of_birth": "1990-01-15"
                }
            },
            "verified_at": 1735689500,
            "metadata": {"userId": "12345"},
            "expires_at": 1735689600
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        let status = try decoder.decode(SessionStatus.self, from: json)

        XCTAssertEqual(status.sessionId, "vs_verified")
        XCTAssertEqual(status.status, "verified")
        XCTAssertEqual(status.result?.answers?["full_name"], "John Doe")
        XCTAssertEqual(status.result?.answers?["date_of_birth"], "1990-01-15")
        XCTAssertEqual(status.verifiedAt, 1735689500)
        XCTAssertEqual(status.metadata?["userId"], "12345")
    }

    func testSessionResultWithCredentialsDecoding() throws {
        let json = """
        {
            "answers": null,
            "credentials": [
                {
                    "format": "dc+sd-jwt",
                    "vct": "urn:eudi:pid:1",
                    "disclosed_claims": {
                        "given_name": "John",
                        "family_name": "Doe"
                    }
                },
                {
                    "format": "mso_mdoc",
                    "doctype": "org.iso.18013.5.1.mDL",
                    "disclosed_claims": {
                        "document_number": "DL123456"
                    }
                }
            ]
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        let result = try decoder.decode(SessionResult.self, from: json)

        XCTAssertNil(result.answers)
        XCTAssertEqual(result.credentials?.count, 2)

        let sdJwtCredential = result.credentials?[0]
        XCTAssertEqual(sdJwtCredential?.format, "dc+sd-jwt")
        XCTAssertEqual(sdJwtCredential?.vct, "urn:eudi:pid:1")
        XCTAssertEqual(sdJwtCredential?.disclosedClaims["given_name"], "John")

        let mdocCredential = result.credentials?[1]
        XCTAssertEqual(mdocCredential?.format, "mso_mdoc")
        XCTAssertEqual(mdocCredential?.doctype, "org.iso.18013.5.1.mDL")
        XCTAssertEqual(mdocCredential?.disclosedClaims["document_number"], "DL123456")
    }

    // MARK: - Error Tests

    func testVerifyErrorRequestFailed() {
        let error = VerifyError.requestFailed(statusCode: 400, message: "Invalid template")

        XCTAssertEqual(error.errorDescription, "Request failed with status 400: Invalid template")
    }

    func testVerifyErrorTimeout() {
        let error = VerifyError.timeout

        XCTAssertEqual(error.errorDescription, "Request timed out")
    }

    func testVerifyErrorNetworkError() {
        let underlyingError = URLError(.notConnectedToInternet)
        let error = VerifyError.networkError(underlyingError)

        XCTAssertTrue(error.errorDescription?.contains("Network error") ?? false)
    }

    func testVerifyErrorInvalidURL() {
        let error = VerifyError.invalidURL

        XCTAssertEqual(error.errorDescription, "Invalid URL")
    }

    func testVerifyErrorEncodingError() {
        struct NonEncodable {}
        let underlyingError = EncodingError.invalidValue(
            NonEncodable(),
            EncodingError.Context(codingPath: [], debugDescription: "Cannot encode")
        )
        let error = VerifyError.encodingError(underlyingError)

        XCTAssertTrue(error.errorDescription?.contains("Failed to encode") ?? false)
    }

    func testVerifyErrorDecodingError() {
        let underlyingError = DecodingError.dataCorrupted(
            DecodingError.Context(codingPath: [], debugDescription: "Invalid JSON")
        )
        let error = VerifyError.decodingError(underlyingError)

        XCTAssertTrue(error.errorDescription?.contains("Failed to decode") ?? false)
    }

    // MARK: - URL Construction Tests

    func testVerifyIdentityURLConstruction() {
        let config = VerifyConfig(
            apiKey: Self.sandboxTestApiKey,
            baseURL: Self.sandboxApiUrl
        )

        let expectedURL = config.baseURL.appendingPathComponent("v1/verify/identity")
        XCTAssertEqual(expectedURL.absoluteString, "http://localhost:7010/v1/verify/identity")
    }

    func testGetSessionURLConstruction() {
        let config = VerifyConfig(
            apiKey: Self.sandboxTestApiKey,
            baseURL: Self.sandboxApiUrl
        )

        let sessionId = "vs_test123"
        let expectedURL = config.baseURL.appendingPathComponent("v1/sessions/\(sessionId)")
        XCTAssertEqual(expectedURL.absoluteString, "http://localhost:7010/v1/sessions/vs_test123")
    }
}

// MARK: - Mock URLProtocol for Testing

/// A mock URL protocol for testing network requests without making real HTTP calls
class MockURLProtocol: URLProtocol {

    static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        return true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }

    override func startLoading() {
        guard let handler = MockURLProtocol.requestHandler else {
            XCTFail("No request handler set")
            return
        }

        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

// MARK: - Mock Client Tests

final class VerifyClientMockTests: XCTestCase {

    var client: VerifyClient!
    var session: URLSession!

    override func setUp() {
        super.setUp()

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: configuration)

        let config = VerifyConfig(
            apiKey: VerifyClientTests.sandboxTestApiKey,
            baseURL: VerifyClientTests.sandboxApiUrl
        )
        client = VerifyClient(config: config, session: session)
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        super.tearDown()
    }

    func testVerifyIdentitySuccess() async throws {
        let expectedResponse = """
        {
            "session_id": "vs_mock123",
            "qr_code_url": "https://api.example.com/qr/mock123.png",
            "qr_code_data": "openid4vp://authorize?response_type=vp_token&nonce=abc",
            "deep_link": "wallet://verify?request_uri=https://api.example.com/request",
            "expires_at": 1735689600
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertTrue(request.url?.absoluteString.contains("/v1/verify/identity") ?? false)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertTrue(request.value(forHTTPHeaderField: "Authorization")?.hasPrefix("Bearer ") ?? false)

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 201,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!

            return (response, expectedResponse)
        }

        let request = VerificationRequest(template: "kyc-basic", responseMode: "answers")
        let result = try await client.verifyIdentity(request)

        XCTAssertEqual(result.sessionId, "vs_mock123")
        XCTAssertTrue(result.qrCodeData.hasPrefix("openid4vp://"))
    }

    func testVerifyIdentityWithMetadata() async throws {
        var capturedBody: [String: Any]?

        let expectedResponse = """
        {
            "session_id": "vs_metadata",
            "qr_code_url": "https://api.example.com/qr/metadata.png",
            "qr_code_data": "openid4vp://...",
            "deep_link": "wallet://...",
            "expires_at": 1735689600
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            // Try httpBody first, then fall back to httpBodyStream
            if let body = request.httpBody {
                capturedBody = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
            } else if let stream = request.httpBodyStream {
                stream.open()
                var data = Data()
                let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 4096)
                defer {
                    buffer.deallocate()
                    stream.close()
                }
                while stream.hasBytesAvailable {
                    let bytesRead = stream.read(buffer, maxLength: 4096)
                    if bytesRead > 0 {
                        data.append(buffer, count: bytesRead)
                    }
                }
                capturedBody = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            }

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 201,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, expectedResponse)
        }

        let request = VerificationRequest(
            template: "kyc-basic",
            metadata: ["userId": "12345", "orderId": "order-abc"]
        )
        _ = try await client.verifyIdentity(request)

        let metadata = capturedBody?["metadata"] as? [String: String]
        XCTAssertEqual(metadata?["userId"], "12345")
        XCTAssertEqual(metadata?["orderId"], "order-abc")
    }

    func testVerifyIdentityFailure() async {
        MockURLProtocol.requestHandler = { request in
            let errorResponse = """
            {"error": "Invalid template name", "code": "INVALID_TEMPLATE"}
            """.data(using: .utf8)!

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 400,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, errorResponse)
        }

        let request = VerificationRequest(template: "nonexistent")

        do {
            _ = try await client.verifyIdentity(request)
            XCTFail("Expected error to be thrown")
        } catch let error as VerifyError {
            if case .requestFailed(let statusCode, _) = error {
                XCTAssertEqual(statusCode, 400)
            } else {
                XCTFail("Wrong error type: \(error)")
            }
        } catch {
            XCTFail("Wrong error type: \(error)")
        }
    }

    func testGetSessionSuccess() async throws {
        let expectedResponse = """
        {
            "session_id": "vs_session123",
            "status": "pending",
            "template_name": "kyc-basic",
            "expires_at": 1735689600
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertTrue(request.url?.absoluteString.contains("/v1/sessions/vs_session123") ?? false)

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, expectedResponse)
        }

        let status = try await client.getSession("vs_session123")

        XCTAssertEqual(status.sessionId, "vs_session123")
        XCTAssertEqual(status.status, "pending")
        XCTAssertEqual(status.templateName, "kyc-basic")
    }

    func testGetSessionVerified() async throws {
        let expectedResponse = """
        {
            "session_id": "vs_verified",
            "status": "verified",
            "template_name": "kyc-basic",
            "result": {
                "answers": {
                    "full_name": "John Doe",
                    "date_of_birth": "1990-01-15"
                }
            },
            "verified_at": 1735689500,
            "expires_at": 1735689600
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, expectedResponse)
        }

        let status = try await client.getSession("vs_verified")

        XCTAssertEqual(status.status, "verified")
        XCTAssertEqual(status.result?.answers?["full_name"], "John Doe")
        XCTAssertNotNil(status.verifiedAt)
    }

    func testGetSessionNotFound() async {
        MockURLProtocol.requestHandler = { request in
            let errorResponse = """
            {"error": "Session not found", "code": "SESSION_NOT_FOUND"}
            """.data(using: .utf8)!

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 404,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, errorResponse)
        }

        do {
            _ = try await client.getSession("vs_nonexistent")
            XCTFail("Expected error to be thrown")
        } catch let error as VerifyError {
            if case .requestFailed(let statusCode, _) = error {
                XCTAssertEqual(statusCode, 404)
            } else {
                XCTFail("Wrong error type: \(error)")
            }
        } catch {
            XCTFail("Wrong error type: \(error)")
        }
    }

    func testWaitForSessionImmediateVerification() async throws {
        let expectedResponse = """
        {
            "session_id": "vs_immediate",
            "status": "verified",
            "template_name": "kyc-basic",
            "result": {"answers": {"name": "Test User"}},
            "expires_at": 1735689600
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, expectedResponse)
        }

        let status = try await client.waitForSession(
            "vs_immediate",
            pollingInterval: 0.1,
            timeout: 1.0
        )

        XCTAssertEqual(status.status, "verified")
    }

    func testWaitForSessionPolling() async throws {
        var pollCount = 0

        MockURLProtocol.requestHandler = { request in
            pollCount += 1
            let status = pollCount >= 3 ? "verified" : "pending"

            let responseJSON: String
            if status == "verified" {
                responseJSON = """
                {
                    "session_id": "vs_polling",
                    "status": "verified",
                    "template_name": "kyc-basic",
                    "result": {"answers": {"name": "Polled User"}},
                    "expires_at": 1735689600
                }
                """
            } else {
                responseJSON = """
                {
                    "session_id": "vs_polling",
                    "status": "pending",
                    "template_name": "kyc-basic",
                    "expires_at": 1735689600
                }
                """
            }

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, responseJSON.data(using: .utf8)!)
        }

        let status = try await client.waitForSession(
            "vs_polling",
            pollingInterval: 0.1,
            timeout: 5.0
        )

        XCTAssertEqual(status.status, "verified")
        XCTAssertEqual(pollCount, 3)
    }

    func testWaitForSessionTimeout() async {
        MockURLProtocol.requestHandler = { request in
            let responseJSON = """
            {
                "session_id": "vs_timeout",
                "status": "pending",
                "template_name": "kyc-basic",
                "expires_at": 1735689600
            }
            """.data(using: .utf8)!

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, responseJSON)
        }

        do {
            _ = try await client.waitForSession(
                "vs_timeout",
                pollingInterval: 0.1,
                timeout: 0.3
            )
            XCTFail("Expected timeout error")
        } catch let error as VerifyError {
            if case .timeout = error {
                // Expected
            } else {
                XCTFail("Wrong error type: \(error)")
            }
        } catch {
            XCTFail("Wrong error type: \(error)")
        }
    }

    func testWaitForSessionFailed() async throws {
        MockURLProtocol.requestHandler = { request in
            let responseJSON = """
            {
                "session_id": "vs_failed",
                "status": "failed",
                "template_name": "kyc-basic",
                "expires_at": 1735689600
            }
            """.data(using: .utf8)!

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, responseJSON)
        }

        let status = try await client.waitForSession(
            "vs_failed",
            pollingInterval: 0.1,
            timeout: 1.0
        )

        XCTAssertEqual(status.status, "failed")
    }

    func testWaitForSessionExpired() async throws {
        MockURLProtocol.requestHandler = { request in
            let responseJSON = """
            {
                "session_id": "vs_expired",
                "status": "expired",
                "template_name": "kyc-basic",
                "expires_at": 1735689600
            }
            """.data(using: .utf8)!

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: nil
            )!

            return (response, responseJSON)
        }

        let status = try await client.waitForSession(
            "vs_expired",
            pollingInterval: 0.1,
            timeout: 1.0
        )

        XCTAssertEqual(status.status, "expired")
    }
}

// MARK: - Integration Tests

/// Integration tests that require a running Verify API
/// Set RUN_INTEGRATION_TESTS=true environment variable to enable
final class VerifyClientIntegrationTests: XCTestCase {

    var client: VerifyClient!
    var integrationEnabled: Bool {
        ProcessInfo.processInfo.environment["RUN_INTEGRATION_TESTS"] == "true"
    }

    override func setUp() {
        super.setUp()

        guard integrationEnabled else { return }

        let apiUrl = ProcessInfo.processInfo.environment["VERIFY_API_URL"]
            ?? "http://localhost:7010"
        let apiKey = ProcessInfo.processInfo.environment["VERIFY_API_KEY"]
            ?? VerifyClientTests.sandboxTestApiKey

        let config = VerifyConfig(
            apiKey: apiKey,
            baseURL: URL(string: apiUrl)!
        )
        client = VerifyClient(config: config)
    }

    func testCreateVerificationSession() async throws {
        try XCTSkipUnless(integrationEnabled, "Integration tests disabled")

        let request = VerificationRequest(
            template: "kyc-basic",
            responseMode: "answers",
            metadata: ["testRun": "integration-test"]
        )

        let verification = try await client.verifyIdentity(request)

        XCTAssertTrue(verification.sessionId.hasPrefix("vs_"))
        XCTAssertFalse(verification.qrCodeUrl.isEmpty)
        XCTAssertTrue(verification.qrCodeData.hasPrefix("openid4vp://"))
        XCTAssertFalse(verification.deepLink.isEmpty)
        XCTAssertGreaterThan(verification.expiresAt, 0)
    }

    func testGetSessionStatus() async throws {
        try XCTSkipUnless(integrationEnabled, "Integration tests disabled")

        // First create a session
        let request = VerificationRequest(template: "kyc-basic")
        let verification = try await client.verifyIdentity(request)

        // Then check its status
        let status = try await client.getSession(verification.sessionId)

        XCTAssertEqual(status.sessionId, verification.sessionId)
        XCTAssertEqual(status.status, "pending")
        XCTAssertEqual(status.templateName, "kyc-basic")
    }

    func testSandboxCredentials() async throws {
        try XCTSkipUnless(integrationEnabled, "Integration tests disabled")

        // Test that sandbox credentials work
        let config = VerifyConfig(
            apiKey: VerifyClientTests.sandboxTestApiKey,
            baseURL: URL(string: ProcessInfo.processInfo.environment["VERIFY_API_URL"] ?? "http://localhost:7010")!
        )
        let testClient = VerifyClient(config: config)

        let request = VerificationRequest(template: "kyc-basic")
        let verification = try await testClient.verifyIdentity(request)

        XCTAssertNotNil(verification.sessionId)
    }
}
