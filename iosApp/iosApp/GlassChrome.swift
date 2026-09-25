import SwiftUI

/// 镀铬层（导航栏、工具栏、输入栏、浮层控件）的玻璃适配层。
///
/// Apple 从 iOS 26 起用 Liquid Glass 重做了系统控件的外观，并明确要求
/// 「减少控件与导航元素上的自定义背景」，否则自定义底色会盖住玻璃材质和滚动边缘效果。
/// 所以这里不把玻璃效果散落在各个视图里，而是集中成一个显式适配层：
///
/// - iOS 26+：交给系统材质与 `glassEffect` / `glass` 按钮样式。
/// - iOS 17–25：回退到系统自带的材质（`.ultraThinMaterial`）与系统配色，
///   让老系统上的观感尽量接近，而不是各自写一套。
///
/// 内容层（聊天气泡、记忆卡片、表单行）不走这里——玻璃是给导航层用的，
/// 铺到内容上会喧宾夺主。
enum GlassChrome {
    /// 是否该用真正的 Liquid Glass。用来给个别必须知情的调用点做判断。
    static var isAvailable: Bool {
        if #available(iOS 26.0, *) { return true }
        return false
    }
}

extension View {
    /// 胶囊形玻璃容器（聊天输入栏这类悬浮控件）。
    ///
    /// 不加 `.interactive()`：这里承载的是文本框，没有按压反馈可言；
    /// 交互反馈留给旁边的发送按钮。
    @ViewBuilder
    func glassCapsule() -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(.regular, in: Capsule())
        } else {
            background(.ultraThinMaterial, in: Capsule())
        }
    }

    /// 玻璃圆形按钮，并染上品牌色（发送键这类主动作）。
    @ViewBuilder
    func glassAccentButtonStyle() -> some View {
        if #available(iOS 26.0, *) {
            // `.glass(_:)` 要 iOS 26.1，这里用 26.0 就有的 `.glass` + `.tint` 组合达到同样效果。
            buttonStyle(.glass)
                .tint(AppTheme.primary)
        } else {
            buttonStyle(.plain)
                .frame(width: 34, height: 34)
                .background(AppTheme.primary, in: Circle())
                .foregroundStyle(AppTheme.onPrimary)
        }
    }
}
