import SwiftUI
import CoreImage.CIFilterBuiltins

/// Utility for generating QR code images
struct QRCodeGenerator {
    /// Generate a QR code image from a string
    /// - Parameters:
    ///   - string: The data to encode in the QR code
    ///   - size: The size of the output image
    /// - Returns: A platform-specific image
    static func generate(from string: String, size: CGSize = CGSize(width: 250, height: 250)) -> Image? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()

        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else {
            return nil
        }

        // Scale the image to the desired size
        let scaleX = size.width / outputImage.extent.size.width
        let scaleY = size.height / outputImage.extent.size.height
        let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))

        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else {
            return nil
        }

        #if os(iOS)
        let uiImage = UIImage(cgImage: cgImage)
        return Image(uiImage: uiImage)
        #elseif os(macOS)
        let nsImage = NSImage(cgImage: cgImage, size: size)
        return Image(nsImage: nsImage)
        #endif
    }
}
