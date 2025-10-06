import Foundation
import UIKit
import MobileCoreServices

@objc(NativeSaveAs) class NativeSaveAs : CDVPlugin {

    var callbackId: String?
    var tempUrl: URL?

    @objc(saveBase64:)
    func saveBase64(command: CDVInvokedUrlCommand) {
        self.callbackId = command.callbackId

        guard let base64 = command.argument(at: 0) as? String,
              let fileName = command.argument(at: 1) as? String,
              let mimeType = command.argument(at: 2) as? String else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid arguments")
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        // Decode base64 and write temp file with progress UI
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }

            guard let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) else {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Base64 decode failed")
                self.commandDelegate.send(result, callbackId: self.callbackId)
                return
            }

            let tmpDir = URL(fileURLWithPath: NSTemporaryDirectory())
            let tmpFile = tmpDir.appendingPathComponent("nativesaveas_\(UUID().uuidString)_\(fileName)")

            let total = data.count
            let chunkSize = 128 * 1024 // 128KB per chunk

            // Create progress view on main thread
            DispatchQueue.main.async {
                let alert = UIAlertController(title: "Saving file", message: "\n\n", preferredStyle: .alert)
                let margin: CGFloat = 8.0
                let rect = CGRect(x: margin, y: 52.0, width: 250.0 - margin * 2.0, height: 20)
                let progressView = UIProgressView(frame: rect)
                progressView.progress = 0.0
                progressView.progressTintColor = .systemBlue
                alert.view.addSubview(progressView)

                if let topVC = self.topViewController() {
                    topVC.present(alert, animated: true, completion: nil)
                }

                // Write data in background while updating progress
                DispatchQueue.global(qos: .userInitiated).async {
                    do {
                        FileManager.default.createFile(atPath: tmpFile.path, contents: nil, attributes: nil)
                        guard let fileHandle = FileHandle(forWritingAtPath: tmpFile.path) else {
                            throw NSError(domain: "NativeSaveAs", code: -1, userInfo: [NSLocalizedDescriptionKey: "Could not create file handle"])
                        }

                        var offset = 0
                        while offset < total {
                            let end = min(offset + chunkSize, total)
                            let chunk = data.subdata(in: offset..<end)
                            fileHandle.seekToEndOfFile()
                            fileHandle.write(chunk)
                            offset = end

                            let progress = Float(offset) / Float(total)
                            DispatchQueue.main.async {
                                progressView.progress = progress
                            }
                        }

                        fileHandle.closeFile()

                        // Dismiss progress alert and present document picker
                        DispatchQueue.main.async {
                            alert.dismiss(animated: true) {
                                self.tempUrl = tmpFile
                                let picker = UIDocumentPickerViewController(url: tmpFile, in: .exportToService)
                                picker.modalPresentationStyle = .formSheet
                                picker.delegate = self

                                if let topVC = self.topViewController() {
                                    topVC.present(picker, animated: true, completion: nil)
                                }
                            }
                        }
                    } catch {
                        DispatchQueue.main.async {
                            alert.dismiss(animated: true) {
                                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Failed to write file: \(error.localizedDescription)")
                                self.commandDelegate.send(result, callbackId: self.callbackId)
                            }
                        }
                    }
                }
            }
        }
    }

    // MARK: - Helper to get top view controller
    private func topViewController(base: UIViewController? = UIApplication.shared.connectedScenes
                                    .compactMap { ($0 as? UIWindowScene)?.windows.first { $0.isKeyWindow } }
                                    .first?.rootViewController) -> UIViewController? {
        if let nav = base as? UINavigationController {
            return topViewController(base: nav.visibleViewController)
        }
        if let tab = base as? UITabBarController {
            if let selected = tab.selectedViewController {
                return topViewController(base: selected)
            }
        }
        if let presented = base?.presentedViewController {
            return topViewController(base: presented)
        }
        return base
    }
}

// MARK: - UIDocumentPickerDelegate
extension NativeSaveAs: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if let dest = urls.first {
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: dest.absoluteString)
            self.commandDelegate.send(result, callbackId: self.callbackId)
        } else {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "No URL returned")
            self.commandDelegate.send(result, callbackId: self.callbackId)
        }

        // Clean up temp file
        if let tmp = self.tempUrl {
            try? FileManager.default.removeItem(at: tmp)
            self.tempUrl = nil
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "User cancelled")
        self.commandDelegate.send(result, callbackId: self.callbackId)

        if let tmp = self.tempUrl {
            try? FileManager.default.removeItem(at: tmp)
            self.tempUrl = nil
        }
    }
}
