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
        private const val BUFFER_SIZE = 32 * 1024 // 32KB buffer for optimal performance
    }

    private var callback: CallbackContext? = null
    private var tempFilePath: String? = null
    private var mimeType: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun execute(
        action: String?,
        args: JSONArray?,
        callbackContext: CallbackContext?
    ): Boolean {
        if (action != "saveBase64") return false
        
        callback = callbackContext
        val base64 = args?.optString(0) ?: ""
        val name = args?.optString(1) ?: "file.bin"
        mimeType = args?.optString(2) ?: "application/octet-stream"

        // Validate inputs
        if (base64.isEmpty()) {
            callbackContext?.error("Base64 string is empty")
            return false
        }

        scope.launch {
            try {
                // Decode and write to temp file in background
                val tmpPath = withContext(Dispatchers.IO) {
                    val cacheDir = cordova.activity.cacheDir
                    val tmpName = "nativesaveas_${UUID.randomUUID()}_$name"
                    val tmp = File(cacheDir, tmpName)
                    
                    try {
                        val data = Base64.decode(base64, Base64.DEFAULT)
                        
                        // Write with buffering for better performance
                        FileOutputStream(tmp).use { fos ->
                            fos.write(data)
                            fos.flush()
                        }
                        
                        tmp.absolutePath
                    } catch (e: Exception) {
                        tmp.delete() // Clean up on error
                        throw e
                    }
                }
                
                tempFilePath = tmpPath

                // Launch save dialog on main thread
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = this@NativeSaveAs.mimeType ?: "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, name)
                    }
                    
                    cordova.setActivityResultCallback(this@NativeSaveAs)
                    cordova.activity.startActivityForResult(intent, CREATE_FILE_REQUEST)
                }

                // Keep callback alive
                val pr = PluginResult(PluginResult.Status.NO_RESULT)
                pr.keepCallback = true
                callbackContext?.sendPluginResult(pr)
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callbackContext?.error("Error preparing file: ${e.message}")
                }
            }
        }
        
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode != CREATE_FILE_REQUEST) return

        if (intent != null && resultCode == Activity.RESULT_OK) {
            intent.data?.let { uri ->
                copyFileWithProgress(uri)
            } ?: run {
                callback?.error("No destination URI received")
                callback = null
            }
        } else {
            callback?.error("User cancelled")
            callback = null
        }
    }

    private fun copyFileWithProgress(destinationUri: Uri) {
        scope.launch {
            val activity = cordova.activity
            val tmpPath = tempFilePath
            
            if (tmpPath == null) {
                withContext(Dispatchers.Main) {
                    callback?.error("No temp file path")
                    callback = null
                }
                return@launch
            }

            val tmpFile = File(tmpPath)
            if (!tmpFile.exists()) {
                withContext(Dispatchers.Main) {
                    callback?.error("Temp file does not exist")
                    callback = null
                }
                return@launch
            }

            var notificationManager: NotificationManagerCompat? = null
            
            try {
                // Create notification channel
                createNotificationChannel(activity)
                
                // Show notification
                notificationManager = NotificationManagerCompat.from(activity)
                val notificationBuilder = NotificationCompat.Builder(activity, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Saving file")
                    .setContentText("Please wait...")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setProgress(100, 0, false)
                
                withContext(Dispatchers.Main) {
                    notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                }

                // Copy file with progress tracking
                withContext(Dispatchers.IO) {
                    val totalBytes = tmpFile.length()
                    var written = 0L
                    var lastUpdatePercent = 0
                    
                    activity.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        FileInputStream(tmpFile).use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            
                            while (input.read(buffer).also { read = it } > 0) {
                                out.write(buffer, 0, read)
                                written += read
                                
                                // Update progress (only when percent changes to reduce UI updates)
                                val currentPercent = ((written * 100L) / totalBytes).toInt()
                                if (currentPercent > lastUpdatePercent) {
                                    lastUpdatePercent = currentPercent
                                    
                                    withContext(Dispatchers.Main) {
                                        notificationBuilder
                                            .setProgress(100, currentPercent, false)
                                            .setContentText("$currentPercent%")
                                        notificationManager.notify(
                                            NOTIFICATION_ID, 
                                            notificationBuilder.build()
                                        )
                                    }
                                }
                            }
                        }
                    } ?: throw Exception("Could not open output stream")
                    
                    // Clean up temp file
                    tmpFile.delete()
                }

                // Success - dismiss notification and return result
                withContext(Dispatchers.Main) {
                    notificationManager.cancel(NOTIFICATION_ID)
                    callback?.success(destinationUri.toString())
                    callback = null
                    tempFilePath = null
                }
                
            } catch (e: Exception) {
                // Clean up on error
                tmpFile.delete()
                
                withContext(Dispatchers.Main) {
                    notificationManager?.cancel(NOTIFICATION_ID)
                    callback?.error("Error saving file: ${e.message}")
                    callback = null
                    tempFilePath = null
                }
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "File Operations"
            val description = "Shows progress of file save operations"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
                setShowBadge(false)
            }
            
            val notificationManager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as? NotificationManager
            
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        // Cancel all coroutines and clean up
        scope.cancel()
        
        // Clean up any remaining temp files
        tempFilePath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        
        super.onDestroy()
    }

    override fun onReset() {
        // Cancel pending operations on page reload
        scope.coroutineContext.cancelChildren()
        callback = null
        super.onReset()
    }
}