package com.outsystems.nativesaveas

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.PluginResult
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*

class NativeSaveAs : CordovaPlugin() {

    companion object {
        private const val CREATE_FILE_REQUEST = 2001
        private const val CHANNEL_ID = "native_saveas_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var callback: CallbackContext? = null
    private var tempFilePath: String? = null
    private var mimeType: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun execute(action: String?, args: JSONArray?, callbackContext: CallbackContext?): Boolean {
        if (action == "saveBase64") {
            this.callback = callbackContext
            val base64 = args?.getString(0) ?: ""
            val name = args?.getString(1) ?: "file.bin"
            this.mimeType = args?.getString(2) ?: "application/octet-stream"
            
            scope.launch {
                try {
                    // Write to temp file in background
                    withContext(Dispatchers.IO) {
                        val cacheDir = cordova.activity.cacheDir
                        val tmpName = "nativesaveas_${UUID.randomUUID()}_$name"
                        val tmp = File(cacheDir, tmpName)
                        val data = Base64.decode(base64, Base64.DEFAULT)

                        FileOutputStream(tmp).use { fos ->
                            fos.write(data)
                            fos.flush()
                        }
                        tempFilePath = tmp.absolutePath
                    }

                    // Create document intent (Save As) on main thread
                    withContext(Dispatchers.Main) {
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = this@NativeSaveAs.mimeType ?: "application/octet-stream"
                            putExtra(Intent.EXTRA_TITLE, name)
                        }

                        cordova.setActivityResultCallback(this@NativeSaveAs)
                        cordova.activity.startActivityForResult(intent, CREATE_FILE_REQUEST)
                    }

                    // Keep callback until result
                    val pr = PluginResult(PluginResult.Status.NO_RESULT)
                    pr.keepCallback = true
                    callbackContext?.sendPluginResult(pr)
                } catch (e: Exception) {
                    callbackContext?.error("Error preparing file: ${e.message}")
                }
            }
            return true
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == CREATE_FILE_REQUEST) {
            if (intent != null && resultCode == Activity.RESULT_OK) {
                val uri: Uri? = intent.data
                
                scope.launch {
                    try {
                        if (uri != null && tempFilePath != null) {
                            val activity = cordova.activity
                            val tmpFile = File(tempFilePath)
                            
                            // Create notification channel for Android 8.0+
                            createNotificationChannel(activity)
                            
                            // Show notification instead of deprecated ProgressDialog
                            val notificationManager = NotificationManagerCompat.from(activity)
                            val notificationBuilder = NotificationCompat.Builder(activity, CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.stat_sys_download)
                                .setContentTitle("Saving file")
                                .setContentText("Please wait...")
                                .setPriority(NotificationCompat.PRIORITY_LOW)
                                .setOngoing(true)
                                .setProgress(100, 0, false)
                            
                            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())

                            // Copy file with progress in background
                            withContext(Dispatchers.IO) {
                                val totalBytes = tmpFile.length()
                                val out: OutputStream? = activity.contentResolver.openOutputStream(uri)
                                val input = FileInputStream(tmpFile)
                                
                                out?.use { outputStream ->
                                    input.use { inputStream ->
                                        val buffer = ByteArray(16384) // 16KB buffer for better performance
                                        var read: Int
                                        var written = 0L
                                        
                                        while (inputStream.read(buffer).also { read = it } > 0) {
                                            outputStream.write(buffer, 0, read)
                                            written += read
                                            val percent = ((written * 100L) / totalBytes).toInt()
                                            
                                            // Update notification progress
                                            withContext(Dispatchers.Main) {
                                                notificationBuilder.setProgress(100, percent, false)
                                                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                                            }
                                        }
                                    }
                                }
                                
                                // Delete temp file
                                tmpFile.delete()
                            }

                            // Dismiss notification
                            withContext(Dispatchers.Main) {
                                notificationManager.cancel(NOTIFICATION_ID)
                                callback?.success(uri.toString())
                                callback = null
                            }
                        } else {
                            callback?.error("No uri or temp file")
                            callback = null
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            val notificationManager = NotificationManagerCompat.from(cordova.activity)
                            notificationManager.cancel(NOTIFICATION_ID)
                            callback?.error("Error saving file: ${e.message}")
                            callback = null
                        }
                    }
                }
            } else {
                callback?.error("User cancelled")
                callback = null
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "File Operations"
            val descriptionText = "Shows progress of file save operations"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}