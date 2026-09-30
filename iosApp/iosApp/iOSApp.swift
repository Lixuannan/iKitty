import SwiftUI

@main
struct iOSApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ChatView(model: model)
                .tint(AppTheme.primary)
                // 这套暖奶油配色是固定的浅色主题，没有深色版本。不锁死的话，系统切到深色后
                // 标题、输入框占位这类跟随外观的文字会变成浅色，压在 `#FFF8F2` 上几乎看不见。
                .preferredColorScheme(.light)
                .onAppear { model.start() }
        }
        // 回到前台就同步一次：另一台设备刚写的消息在这一刻才可能出现。
        // 不做后台调度（BGTaskScheduler 要额外的 Info.plist 配置与系统授权），
        // 前台化已经覆盖了主要场景。
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.onForeground() }
        }
    }
}
