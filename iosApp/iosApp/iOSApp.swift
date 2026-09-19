import SwiftUI

@main
struct iOSApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ChatView(model: model)
                .onAppear { model.start() }
        }
    }
}
