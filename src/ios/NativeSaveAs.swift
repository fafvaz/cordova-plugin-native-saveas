import Foundation
import UIKit
import MobileCoreServices

@objc(NativeSaveAs)
class NativeSaveAs: CDVPlugin {

    private var callbackId: String?
    private var tempUrl: URL?
    private static let chunkSize = 256 * 1024 // 256KB chunks for optimal performance

    @objc(saveBase64:)
    func saveBase64(command: CDVInvokedUrlCommand) {
        self.callbackId = command.callbackId
        
        guard let base64 = command.argument(at: 0) as? String,
              let fileName = command.argument(at: 1) as? String,
              let mimeType = command.argument(at: 2) as? String,
              !base64.isEmpty else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: "Invalid or empty arguments: base64, filename, or mimeType missing"
            )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        // Clean base64 string (remove data URI prefix if present)
        let cleanBase64 = base64.components(separatedBy: ",").last ?? base64

        // Decode and write file with progress UI
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            
            guard let data = Data(base64Encoded: cleanBase64, options: .ignoreUnknownCharacters) else {
                DispatchQueue.main.async {
                    let result = CDVPluginResult(
                        status: CDVCommandStatus_ERROR,
                        messageAs: "Base64 decode failed: invalid base64 string"
                    )
                    self.commandDelegate.send(result, callbackId: self.callbackId)
                }
                return
            }

            let tmpDir = URL(fileURLWithPath: NSTemporaryDirectory())
            let tmpFile = tmpDir.appendingPathComponent("nativesaveas_\(UUID().uuidString)_\(fileName)")

            // Show progress UI and write file
            DispatchQueue.main.async {
                self.showProgressAndWriteFile(data: data, to: tmpFile, fileName: fileName, mimeType: mimeType)
            }
        }
    }
    
    private func showProgressAndWriteFile(data: Data, to tmpFile: URL, fileName: String, mimeType: String) {
        let alert = UIAlertController(
            title: "Saving File",
            message: "\n\n", // Space for progress view
            preferredStyle: .alert
        )
        
        let margin: CGFloat = 8.0
        let rect = CGRect(x: margin, y: 52.0, width: 250.0 - margin * 2.0, height: 20)
        let progressView = UIProgressView(frame: rect)
        progressView.progress = 0.0
        progressView.progressTintColor = .systemBlue
        alert.view.addSubview(progressView)
        
        // Present progress alert
        if let topVC = self.viewController {
            topVC.present(alert, animated: true) {
                self.writeFileWithProgress(data: data, to: tmpFile, mimeType: mimeType, progressView: progressView, alert: alert)
            }
        } else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: "Unable to access view controller"
            )
            self.commandDelegate.send(result, callbackId: self.callbackId)
            self.cleanupTempFile()
        }
    }
    
    private func writeFileWithProgress(data: Data, to tmpFile: URL, mimeType: String, progressView: UIProgressView, alert: UIAlertController) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            
            do {
                // Create file
                FileManager.default.createFile(atPath: tmpFile.path, contents: nil, attributes: nil)
                
                guard let fileHandle = FileHandle(forWritingAtPath: tmpFile.path) else {
                    throw NSError(
                        domain: "NativeSaveAs",
                        code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Could not create file handle"]
                    )
                }
                
                defer { fileHandle.closeFile() }
                
                let total = data.count
                var offset = 0
                var lastUpdatePercent = 0.0
                
                // Write in chunks with progress updates
                while offset < total {
                    let end = min(offset + NativeSaveAs.chunkSize, total)
                    let chunk = data.subdata(in: offset..<end)
                    
                    fileHandle.seekToEndOfFile()
                    fileHandle.write(chunk)
                    
                    offset = end
                    let progress = Float(offset) / Float(total)
                    let currentPercent = Double(progress * 100.0)
                    
                    if currentPercent - lastUpdatePercent >= 1.0 || offset == total {
                        lastUpdatePercent = currentPercent
                        DispatchQueue.main.async {
                            progressView.progress = progress
                        }
                    }
                }
                
                // File written successfully - show document picker
                DispatchQueue.main.async {
                    alert.dismiss(animated: true) {
                        self.tempUrl = tmpFile
                        self.presentDocumentPicker(for: tmpFile, mimeType: mimeType)
                    }
                }
                
            } catch {
                DispatchQueue.main.async {
                    alert.dismiss(animated: true) {
                        let result = CDVPluginResult(
                            status: CDVCommandStatus_ERROR,
                            messageAs: "Failed to write file: \(error.localizedDescription)"
                        )
                        self.commandDelegate.send(result, callbackId: self.callbackId)
                        self.cleanupTempFile()
                    }
                }
            }
        }
    }
    
    private func presentDocumentPicker(for url: URL, mimeType: String) {
        let uti = mimeTypeToUTI(mimeType) ?? (kUTTypeData as String)
        let picker = UIDocumentPickerViewController(url: url, in: .exportToService)
        picker.modalPresentationStyle = .formSheet
        picker.delegate = self
        picker.shouldShowFileExtensions = true
        
        if let topVC = self.viewController {
            topVC.present(picker, animated: true, completion: nil)
        } else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: "Unable to present document picker"
            )
            self.commandDelegate.send(result, callbackId: self.callbackId)
            self.cleanupTempFile()
        }
    }
    
    private func mimeTypeToUTI(_ mimeType: String) -> String? {
        let mimeToUTI: [String: String] = [
            "application/pdf": kUTTypePDF as String,
            "image/png": kUTTypePNG as String,
            "image/jpeg": kUTTypeJPEG as String,
            "image/jpg": kUTTypeJPEG as String,
            "text/plain": kUTTypePlainText as String,
            "application/octet-stream": kUTTypeData as String
        ]
        return mimeToUTI[mimeType.lowercased()] ?? kUTTypeData as String
    }
    
    private func cleanupTempFile() {
        if let tmp = self.tempUrl {
            try? FileManager.default.removeItem(at: tmp)
            self.tempUrl = nil
        }
    }
}

// MARK: - UIDocumentPickerDelegate
extension NativeSaveAs: UIDocumentPickerDelegate {
    
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let dest = urls.first else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: "No destination URL selected"
            )
            self.commandDelegate.send(result, callbackId: self.callbackId)
            self.cleanupTempFile()
            return
        }
        
        // Ensure file is copied to the selected location
        do {
            if FileManager.default.fileExists(atPath: tempUrl?.path ?? "") {
                try FileManager.default.copyItem(at: tempUrl!, to: dest)
            }
            let result = CDVPluginResult(
                status: CDVCommandStatus_OK,
                messageAs: dest.absoluteString
            )
            self.commandDelegate.send(result, callbackId: self.callbackId)
        } catch {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: "Failed to save file: \(error.localizedDescription)"
            )
            self.commandDelegate.send(result, callbackId: self.callbackId)
        }
        
        self.cleanupTempFile()
        self.callbackId = nil
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        let result = CDVPluginResult(
            status: CDVCommandStatus_ERROR,
            messageAs: "User cancelled the operation"
        )
        self.commandDelegate.send(result, callbackId: self.callbackId)
        self.cleanupTempFile()
        self.callbackId = nil
    }
}