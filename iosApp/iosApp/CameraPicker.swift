import SwiftUI
import UIKit

/// 相机。
///
/// 用 `UIImagePickerController` 而不是 `AVCaptureSession`：这里只需要"拍一张照片"，
/// 自己拼预览层要多写几百行，还要处理旋转与中断。
///
/// 真机才有相机；调用方要先判断 `isSourceTypeAvailable(.camera)`。
/// 权限文案在 Info.plist 的 `NSCameraUsageDescription`。
struct CameraPicker: UIViewControllerRepresentable {
    @Binding var isPresented: Bool
    let onPicked: (UIImage) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let parent: CameraPicker

        init(_ parent: CameraPicker) {
            self.parent = parent
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage {
                parent.onPicked(image)
            }
            parent.isPresented = false
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.isPresented = false
        }
    }
}
