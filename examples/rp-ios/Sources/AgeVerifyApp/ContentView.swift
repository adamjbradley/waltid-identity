import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = VerificationViewModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    // Header
                    headerSection

                    // Main content area
                    contentSection
                }
                .padding()
            }
            .navigationTitle("Age Verification Demo")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.large)
            #endif
        }
    }

    // MARK: - Header Section

    private var headerSection: some View {
        VStack(spacing: 16) {
            Image(systemName: "wineglass.fill")
                .font(.system(size: 60))
                .foregroundStyle(.purple)

            Text("Premium Wine Store")
                .font(.title)
                .fontWeight(.bold)

            Text("Quality wines for discerning palates")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.top)
    }

    // MARK: - Content Section

    @ViewBuilder
    private var contentSection: some View {
        switch viewModel.state {
        case .idle:
            ageGatedContentView

        case .loading:
            loadingView

        case .waitingForWallet(_, let qrCodeData, _):
            qrCodeView(qrCodeData: qrCodeData)

        case .verified(let claims):
            verifiedContentView(claims: claims)

        case .failed(let message):
            failedView(message: message)

        case .expired:
            expiredView
        }
    }

    // MARK: - Age Gated Content View

    private var ageGatedContentView: some View {
        VStack(spacing: 24) {
            // Blurred content preview
            ZStack {
                // Simulated product grid (blurred)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
                    ForEach(0..<4) { _ in
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.gray.opacity(0.3))
                            .frame(height: 150)
                    }
                }
                .blur(radius: 10)

                // Overlay message
                VStack(spacing: 16) {
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(.orange)

                    Text("Age-Restricted Content")
                        .font(.headline)

                    Text("You must verify you are 21 or older to view our wine selection.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(32)
                .background(.regularMaterial)
                .cornerRadius(16)
            }

            // Verify button
            Button(action: { viewModel.startVerification() }) {
                Label("Verify My Age", systemImage: "checkmark.shield.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding()
            }
            .buttonStyle(.borderedProminent)
            .tint(.purple)
        }
    }

    // MARK: - Loading View

    private var loadingView: some View {
        VStack(spacing: 16) {
            ProgressView()
                .scaleEffect(1.5)

            Text("Starting verification...")
                .font(.headline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(48)
    }

    // MARK: - QR Code View

    private func qrCodeView(qrCodeData: String) -> some View {
        VStack(spacing: 24) {
            Text("Scan with your wallet app")
                .font(.headline)

            // QR Code
            if let qrImage = QRCodeGenerator.generate(from: qrCodeData) {
                qrImage
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 250, height: 250)
                    .padding()
                    .background(Color.white)
                    .cornerRadius(16)
                    .shadow(radius: 4)
            } else {
                // Fallback if QR generation fails
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color.gray.opacity(0.2))
                    .frame(width: 250, height: 250)
                    .overlay {
                        Text("QR Code")
                            .foregroundStyle(.secondary)
                    }
            }

            // Same device option
            VStack(spacing: 12) {
                Text("On mobile?")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Button(action: { viewModel.openWallet() }) {
                    Label("Open Wallet App", systemImage: "arrow.up.forward.app")
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.bordered)
            }

            // Cancel button
            Button("Cancel", role: .cancel) {
                viewModel.cancelVerification()
            }
            .foregroundStyle(.secondary)
        }
        .padding()
    }

    // MARK: - Verified Content View

    private func verifiedContentView(claims: [String: String]) -> some View {
        VStack(spacing: 24) {
            // Success indicator
            VStack(spacing: 16) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.green)

                Text("Age Verified!")
                    .font(.title2)
                    .fontWeight(.bold)

                if !claims.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(claims.keys.sorted()), id: \.self) { key in
                            HStack {
                                Text(formatClaimKey(key))
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Text(claims[key] ?? "")
                                    .fontWeight(.medium)
                            }
                        }
                    }
                    .padding()
                    .background(Color.green.opacity(0.1))
                    .cornerRadius(12)
                }
            }
            .padding()
            .frame(maxWidth: .infinity)
            .background(.regularMaterial)
            .cornerRadius(16)

            // Unlocked content
            VStack(alignment: .leading, spacing: 16) {
                Text("Our Selection")
                    .font(.headline)

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
                    wineCard(name: "Chateau Margaux", year: "2018", price: "$450")
                    wineCard(name: "Opus One", year: "2019", price: "$380")
                    wineCard(name: "Screaming Eagle", year: "2017", price: "$3,200")
                    wineCard(name: "Petrus", year: "2016", price: "$4,500")
                }
            }

            // Reset button
            Button("Start Over") {
                viewModel.reset()
            }
            .foregroundStyle(.secondary)
        }
    }

    private func wineCard(name: String, year: String, price: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color.purple.opacity(0.2))
                .frame(height: 100)
                .overlay {
                    Image(systemName: "wineglass")
                        .font(.title)
                        .foregroundStyle(.purple)
                }

            Text(name)
                .font(.subheadline)
                .fontWeight(.medium)
                .lineLimit(1)

            HStack {
                Text(year)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(price)
                    .font(.caption)
                    .fontWeight(.bold)
                    .foregroundStyle(.purple)
            }
        }
        .padding(12)
        .background(cardBackgroundColor)
        .cornerRadius(12)
        .shadow(color: .black.opacity(0.1), radius: 4)
    }

    // MARK: - Failed View

    private func failedView(message: String) -> some View {
        VStack(spacing: 24) {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.red)

            Text("Verification Failed")
                .font(.title2)
                .fontWeight(.bold)

            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button(action: { viewModel.startVerification() }) {
                Label("Try Again", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity)
                    .padding()
            }
            .buttonStyle(.borderedProminent)
            .tint(.purple)

            Button("Cancel") {
                viewModel.reset()
            }
            .foregroundStyle(.secondary)
        }
        .padding()
    }

    // MARK: - Expired View

    private var expiredView: some View {
        VStack(spacing: 24) {
            Image(systemName: "clock.badge.exclamationmark.fill")
                .font(.system(size: 64))
                .foregroundStyle(.orange)

            Text("Session Expired")
                .font(.title2)
                .fontWeight(.bold)

            Text("The verification session has timed out. Please try again.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button(action: { viewModel.startVerification() }) {
                Label("Start New Verification", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity)
                    .padding()
            }
            .buttonStyle(.borderedProminent)
            .tint(.purple)

            Button("Cancel") {
                viewModel.reset()
            }
            .foregroundStyle(.secondary)
        }
        .padding()
    }

    // MARK: - Helpers

    /// Cross-platform background color for cards
    private var cardBackgroundColor: Color {
        #if os(iOS)
        return Color(UIColor.systemBackground)
        #else
        return Color(NSColor.windowBackgroundColor)
        #endif
    }

    private func formatClaimKey(_ key: String) -> String {
        // Convert camelCase or snake_case to Title Case
        let result = key
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "([a-z])([A-Z])", with: "$1 $2", options: .regularExpression)
        return result.prefix(1).uppercased() + result.dropFirst()
    }
}

#Preview {
    ContentView()
}
