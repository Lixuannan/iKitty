import Foundation
import UIKit
import Shared

/// 把相册或相机拿到的一张图归一化成"可以直接发给模型、也可以直接落盘"的 JPEG。
///
/// 归一化规则由共享代码定（`IMAGE_MAX_DIMENSION` / `IMAGE_JPEG_QUALITY`）：
/// - 最长边不超过上限，已经足够小的图不放大；
/// - 按方向摆正（`UIImage.imageOrientation` 在解码后仍然有效，底下的 `cgImage` 是未摆正的）；
/// - JPEG 没有透明通道，所以先铺一层白底，否则透明区会压成黑色；
/// - 统一成 JPEG，于是发送时只需要一种 MIME。
///
/// 用 `UIGraphicsImageRenderer` 而不是手工 `CGContext`：手建的 bitmap context 原点在左下，
/// 而 UIKit 的绘制入口假设原点在左上，**不额外翻转一次画出来就是上下颠倒的**。
/// 渲染器会把坐标系、缩放与不透明背景都处理对，省掉这类只在运行期才暴露的坑。
/// `scale = 1` 是为了让输出像素尺寸就等于目标尺寸，而不是再乘一遍屏幕倍率。
enum ImageNormalizer {

    static func normalizedJpegData(from image: UIImage) -> Data? {
        let maxDimension = CGFloat(ImageSupportKt.IMAGE_MAX_DIMENSION)

        let pixelWidth = image.size.width * image.scale
        let pixelHeight = image.size.height * image.scale
        guard pixelWidth > 0, pixelHeight > 0 else { return nil }

        let longest = max(pixelWidth, pixelHeight)
        let ratio = longest > maxDimension ? maxDimension / longest : 1
        let targetWidth = max(1, (pixelWidth * ratio).rounded())
        let targetHeight = max(1, (pixelHeight * ratio).rounded())
        let target = CGSize(width: targetWidth, height: targetHeight)

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        // 已经铺了白底，不需要透明通道；不透明还能省掉一次 alpha 合成。
        format.opaque = true

        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let flattened = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: target))
            // UIImage 已经按 imageOrientation 摆正过，这里画出来方向就是对的。
            image.draw(in: CGRect(origin: .zero, size: target))
        }

        let quality = CGFloat(ImageSupportKt.IMAGE_JPEG_QUALITY) / 100.0
        return flattened.jpegData(compressionQuality: quality)
    }
}
