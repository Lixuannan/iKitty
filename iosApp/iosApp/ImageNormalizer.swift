import CoreGraphics
import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers
import Shared

/// 把相册或相机拿到的一张图归一化成"可以直接发给模型、也可以直接落盘"的 JPEG。
///
/// 归一化规则由共享代码定（`IMAGE_MAX_DIMENSION` / `IMAGE_JPEG_QUALITY`）：
/// - 最长边不超过上限，已经足够小的图不放大；
/// - 按 EXIF 方向摆正（UIImage 的 `imageOrientation` 在解码后仍然有效，
///   而底下的 `cgImage` 是未摆正的）；
/// - JPEG 没有透明通道，所以先铺一层白底，否则透明区会变成黑色；
/// - 统一成 JPEG，于是发送时只需要一种 MIME。
///
/// 像素操作放在 Swift 而不是 Kotlin/Native cinterop：这里紧挨着"从哪里选图"，
/// 用原生 API 表达最直接，也让共享模块不必碰图形栈。
enum ImageNormalizer {

    static func normalizedJpegData(from image: UIImage) -> Data? {
        let maxDimension = CGFloat(ImageSupportKt.IMAGE_MAX_DIMENSION)

        let pixelWidth = CGFloat(image.size.width * image.scale)
        let pixelHeight = CGFloat(image.size.height * image.scale)
        guard pixelWidth > 0, pixelHeight > 0 else { return nil }

        let longest = max(pixelWidth, pixelHeight)
        let ratio = longest > maxDimension ? maxDimension / longest : 1
        let targetWidth = max(1, Int((pixelWidth * ratio).rounded()))
        let targetHeight = max(1, Int((pixelHeight * ratio).rounded()))

        guard let context = CGContext(
            data: nil,
            width: targetWidth,
            height: targetHeight,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }

        // 白底：JPEG 没有透明通道，不铺底会把透明区压成黑色。
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: targetWidth, height: targetHeight))

        // UIImage 已经按 imageOrientation 摆正过，画到上下文里方向就是对的。
        UIGraphicsPushContext(context)
        image.draw(in: CGRect(x: 0, y: 0, width: targetWidth, height: targetHeight))
        UIGraphicsPopContext()

        guard let flattened = context.makeImage() else { return nil }

        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output as CFMutableData,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else { return nil }

        let quality = Double(ImageSupportKt.IMAGE_JPEG_QUALITY) / 100.0
        CGImageDestinationAddImage(
            destination,
            flattened,
            [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary
        )
        guard CGImageDestinationFinalize(destination) else { return nil }

        return output as Data
    }
}
