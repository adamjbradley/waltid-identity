import Foundation

/// Represents the current state of the verification flow
enum VerificationState: Equatable {
    /// Initial state, no verification in progress
    case idle

    /// Verification session is being created
    case loading

    /// Waiting for user to scan QR code / use deep link
    case waitingForWallet(sessionId: String, qrCodeData: String, deepLink: String)

    /// Verification completed successfully
    case verified(claims: [String: String])

    /// Verification failed
    case failed(message: String)

    /// Session expired
    case expired

    /// Whether verification is in progress
    var isInProgress: Bool {
        switch self {
        case .loading, .waitingForWallet:
            return true
        default:
            return false
        }
    }

    /// Whether content should be unlocked
    var isUnlocked: Bool {
        if case .verified = self {
            return true
        }
        return false
    }
}
