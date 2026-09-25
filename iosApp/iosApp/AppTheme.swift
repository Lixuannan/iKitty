import SwiftUI

/// iOS 的品牌色，取自 Android 的 `CatLightColors`（`MainActivity.kt`）里的对应角色。
///
/// 只保留界面真正用到的角色：Liquid Glass 接管了导航栏、工具栏、表单和弹层的外观之后，
/// 那些位置不该再刷自定义底色，所以像 `surface` / `outlineVariant` 这类角色在 iOS 侧不再需要。
/// 玻璃是镀铬层的语言，暖色是内容层的识别度——两者分工，不互相覆盖。
enum AppTheme {
    /// 强调色：标题图标、发送键、选中态、用户气泡。
    static let primary = color(0xE08A5F)
    /// 压在 `primary` 上的文字（用户气泡正文）。
    static let onPrimary = color(0xFFFFFF)
    /// 内容区底色，也是玻璃底下透出来的那层暖色。
    static let background = color(0xFFF8F2)
    /// 内容卡片与猫猫气泡的填充色。
    ///
    /// 不用 `.secondarySystemBackground`：它在浅色外观下偏冷的灰蓝，压在这层暖奶油底上
    /// 会显得发脏。这里固定用暖白，玻璃材质留给镀铬层。
    static let surface = color(0xFFFFFF)
    /// 卡片与气泡上的正文。
    static let onSurface = color(0x463A33)
    /// 次级说明文字。
    static let onSurfaceVariant = color(0x7C6A5E)
    /// 错误文字。
    static let error = color(0xD4665A)

    private static func color(_ rgb: UInt32) -> Color {
        Color(
            .sRGB,
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255,
            opacity: 1
        )
    }
}
