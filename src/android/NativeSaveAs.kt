package com.outsystems.nativesaveas

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.PluginResult
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

class NativeSaveAs : CordovaPlugin() {

    companion object {
        private const val CREATE_FILE_REQUEST = 2001
    }

    private var callback: CallbackContext? = null
    private var tempFilePath: String? = null
    private var mimeType: String? = null

    override fun execute(action: String?, args: JSONArray?, callbackContext: CallbackContext?): Boolean {
        if (action == "saveBase64") {
            this.callback = callbackContext
            val base64 = args?.getString(0) ?: ""
            val name = args?.getString(1) ?: "file.bin"
            this.mimeType = args?.getString(2) ?: "application/octet-stream"
            try {
                // write to temp file first (can be large)
                val cacheDir = cordova.activity.cacheDir
                val tmpName = "nativesaveas_${UUID.randomUUID()}_$name"
                val tmp = File(cacheDir, tmpName)
                val data = Base64.decode(base64, Base64.DEFAULT)

                val fosTemp = FileOutputStream(tmp)
                fosTemp.write(data)
                fosTemp.flush()
                fosTemp.close()
                tempFilePath = tmp.absolutePath

                // create document intent (Save As)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = if (!this.mimeType.isNullOrEmpty()) this.mimeType else "application/octet-stream"
                intent.putExtra(Intent.EXTRA_TITLE, name)

                cordova.setActivityResultCallback(this)
                cordova.activity.startActivityForResult(intent, CREATE_FILE_REQUEST)

                // keep callback until result
                val pr = PluginResult(PluginResult.Status.NO_RESULT)
                pr.keepCallback = true
                callbackContext?.sendPluginResult(pr)
                return true
            } catch (e: Exception) {
                callbackContext?.error(e.message)
                return false
            }
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == CREATE_FILE_REQUEST) {
            if (intent != null && resultCode == Activity.RESULT_OK) {
                val uri: Uri? = intent.data
                try {
                    if (uri != null && tempFilePath != null) {
                        // Show native progress dialog while copying
                        val activity = cordova.activity
                        val progress = ProgressDialog(activity)
                        progress.setTitle("Saving file")
                        progress.setMessage("Please wait...")
                        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                        progress.isIndeterminate = false
                        progress.setCancelable(false)
                        val tmpFile = File(tempFilePath)
                        val totalBytes = tmpFile.length().toInt()
                        progress.max = 100
                        activity.runOnUiThread { progress.show() }

                        val out: OutputStream? = activity.contentResolver.openOutputStream(uri)
                        val input = FileInputStream(tmpFile)
                        val buffer = ByteArray(8192)
                        var read: Int
                        var written = 0
                        while (input.read(buffer).also { read = it } > 0) {
                            out?.write(buffer, 0, read)
                            written += read
                            val percent = (written * 100L / totalBytes).toInt()
                            activity.runOnUiThread { progress.progress = percent }
                        }
                        input.close()
                        out?.close()

                        // dismiss progress
                        activity.runOnUiThread { progress.dismiss() }

                        // delete temp
                        tmpFile.delete()

                        // return success URI
                        callback?.success(uri.toString())
                        callback = null
                    } else {
                        callback?.error("No uri or temp file")
                        callback = null
                    }
                } catch (e: Exception) {
                    callback?.error(e.message)
                    callback = null
                }
            } else {
                callback?.error("User cancelled")
                callback = null
            }
        }
    }
}
