import Foundation
import UIKit
import MobileCoreServices

@objc(NativeSaveAs) class NativeSaveAs : CDVPlugin {

    var callbackId: String?
    var tempUrl: URL?

    @objc(saveBase64:withFilename:withMimeType:)
    func saveBase64(command: CDVInvokedUrlCommand) {
        self.callbackId = command.callbackId
        guard let base64 = command.argument(at: 0) as? String,
              let fileName = command.argument(at: 1) as? String,
              let mimeType = command.argument(at: 2) as? String else {
            self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid args"), callbackId: self.callbackId)
            return
        }

        // Decode base64 and write temp file with progress UI (native modal)
        DispatchQueue.global(qos: .userInitiated).async {
            guard let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) else {
                self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Base64 decode failed"), callbackId: self.callbackId)
                return
            }

            let tmpDir = URL(fileURLWithPath: NSTemporaryDirectory())
            let tmpFile = tmpDir.appendingPathComponent("nativesaveas_\(UUID().uuidString)_\(fileName)")

            // write data chunked to show progress
            let total = data.count
            let chunkSize = 64 * 1024
            var written = 0

            // Create progress view on main thread
            DispatchQueue.main.async {
                let alert = UIAlertController(title: "Saving file", message: "\n\n", preferredStyle: .alert)
                let margin: CGFloat = 8.0
                let rect = CGRect(x: margin, y: 52.0, width: 250.0 - margin * 2.0, height: 20)
                let progressView = UIProgressView(frame: rect)
                progressView.progress = 0.0
                alert.view.addSubview(progressView)
                UIApplication.shared.keyWindow?.rootViewController?.present(alert, animated: true, completion: nil)

                // write data in background while updating progress
                DispatchQueue.global(qos: .userInitiated).async {
                    do {
                        let handle = try FileHandle(forWritingTo: tmpFile)
                        try "".write(to: tmpFile, atomically: true, encoding: .utf8) // ensure file exists (will overwrite)
                        handle.closeFile()
                    } catch {
                        // create empty file
                        FileManager.default.createFile(atPath: tmpFile.path, contents: nil, attributes: nil)
                    }

                    var offset = 0
                    let fileHandle = try? FileHandle(forWritingTo: tmpFile)
                    if let fh = fileHandle {
                        while offset < total {
                            let end = min(offset + chunkSize, total)
                            let chunk = data.subdata(in: offset..<end)
                            fh.seekToEndOfFile()
                            fh.write(chunk)
                            offset = end
                            let progress = Float(offset) / Float(total)
                            DispatchQueue.main.async {
                                progressView.progress = progress
                            }
                        }
                        fh.closeFile()
                    } else {
                        // fallback: write all at once
                        try? data.write(to: tmpFile)
                        DispatchQueue.main.async {
                            progressView.progress = 1.0
                        }
                    }

                    // dismiss progress alert and present document picker
                    DispatchQueue.main.async {
                        alert.dismiss(animated: true, completion: {
                            self.tempUrl = tmpFile
                            let picker = UIDocumentPickerViewController(url: tmpFile, in: .exportToService)
                            picker.modalPresentationStyle = .formSheet
                            picker.delegate = self
                            self.viewController.present(picker, animated: true, completion: nil)
                        })
                    }
                }
            }
        }
    }
}

extension NativeSaveAs: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if let dest = urls.first {
            self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: dest.absoluteString), callbackId: self.callbackId)
        } else {
            self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "No URL returned"), callbackId: self.callbackId)
        }
        if let tmp = self.tempUrl {
            try? FileManager.default.removeItem(at: tmp)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "User cancelled"), callbackId: self.callbackId)
        if let tmp = self.tempUrl {
            try? FileManager.default.removeItem(at: tmp)
        }
    }
}
