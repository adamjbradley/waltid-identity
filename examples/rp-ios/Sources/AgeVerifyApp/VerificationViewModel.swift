import Foundation
import WaltIDVerifySDK
import SwiftUI

/// ViewModel for managing the verification flow
@MainActor
class VerificationViewModel: ObservableObject {
    /// Current verification state
    @Published var state: VerificationState = .idle

    /// The Verify API client
    private let client: VerifyClient

    /// Current polling task (cancellable)
    private var pollingTask: Task<Void, Never>?

    init(config: VerifyConfig = Configuration.verifyConfig) {
        self.client = VerifyClient(config: config)
    }

    /// Start a new verification session
    func startVerification() {
        // Cancel any existing polling
        pollingTask?.cancel()

        state = .loading

        Task {
            do {
                // Create verification request
                let request = VerificationRequest(
                    template: Configuration.verificationTemplate,
                    responseMode: "direct_post",
                    metadata: ["source": "ios_app"]
                )

                // Call Verify API
                let response = try await client.verifyIdentity(request)

                // Update state with session details
                state = .waitingForWallet(
                    sessionId: response.sessionId,
                    qrCodeData: response.qrCodeData,
                    deepLink: response.deepLink
                )

                // Start polling for completion
                startPolling(sessionId: response.sessionId)

            } catch let error as VerifyError {
                state = .failed(message: error.localizedDescription)
            } catch {
                state = .failed(message: "An unexpected error occurred: \(error.localizedDescription)")
            }
        }
    }

    /// Cancel the current verification
    func cancelVerification() {
        pollingTask?.cancel()
        pollingTask = nil
        state = .idle
    }

    /// Reset to initial state
    func reset() {
        pollingTask?.cancel()
        pollingTask = nil
        state = .idle
    }

    /// Open wallet with deep link (same-device flow)
    func openWallet() {
        guard case .waitingForWallet(_, _, let deepLink) = state else {
            return
        }

        guard let url = URL(string: deepLink) else {
            return
        }

        #if os(iOS)
        UIApplication.shared.open(url)
        #elseif os(macOS)
        NSWorkspace.shared.open(url)
        #endif
    }

    // MARK: - Private Methods

    private func startPolling(sessionId: String) {
        pollingTask = Task { [weak self] in
            guard let self = self else { return }

            do {
                // Poll for session completion (2 second intervals, 5 minute timeout)
                let status = try await self.client.waitForSession(
                    sessionId,
                    pollingInterval: 2,
                    timeout: 300
                )

                // Update state based on result
                await MainActor.run {
                    switch status.status {
                    case "verified":
                        // Extract disclosed claims from result
                        var claims: [String: String] = [:]
                        if let result = status.result {
                            if let answers = result.answers {
                                claims = answers
                            }
                            if let credentials = result.credentials {
                                for credential in credentials {
                                    for (key, value) in credential.disclosedClaims {
                                        claims[key] = value
                                    }
                                }
                            }
                        }
                        self.state = .verified(claims: claims)

                    case "failed":
                        self.state = .failed(message: "Verification was not successful")

                    case "expired":
                        self.state = .expired

                    default:
                        self.state = .failed(message: "Unknown status: \(status.status)")
                    }
                }

            } catch VerifyError.timeout {
                await MainActor.run {
                    self.state = .expired
                }
            } catch is CancellationError {
                // Task was cancelled, ignore
            } catch {
                await MainActor.run {
                    self.state = .failed(message: error.localizedDescription)
                }
            }
        }
    }
}
