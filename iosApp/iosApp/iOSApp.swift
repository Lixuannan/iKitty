import SwiftUI

@main
struct iOSApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ChatView(model: model)
                .tint(AppTheme.primary)
                // 这套暖奶油配色是固定的浅色主题，没有深色版本。不锁死的话，系统切到深色后
                // 标题、输入框占位这类跟随外观的文字会变成浅色，压在 `#FFF8F2` 上几乎看不见。
                .preferredColorScheme(.light)
                .onAppear { model.start() }
        }
    }
}
