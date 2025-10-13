package com.outsystems.nativesaveas

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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
    private var originalFileName: String? = null
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
        originalFileName = name

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
                    launchSaveDialog(name)
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

    private fun launchSaveDialog(fileName: String) {
        // Separate base name and extension to handle duplicates properly
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex != -1 && dotIndex > 0) fileName.substring(0, dotIndex) else fileName

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = this@NativeSaveAs.mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, baseName)
            
            // CRITICAL: These flags ensure the dialog is always shown
            // and handles file conflicts properly
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            
            // Force the system to show the dialog even if default location is set
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // This ensures proper UI for file conflict handling
                putExtra("android.content.extra.SHOW_ADVANCED", true)
            }
        }
        
        cordova.setActivityResultCallback(this@NativeSaveAs)
        
        try {
            cordova.activity.startActivityForResult(intent, CREATE_FILE_REQUEST)
        } catch (e: Exception) {
            // If ACTION_CREATE_DOCUMENT is not available, fall back to older method
            callback?.error("Unable to launch save dialog: ${e.message}")
            cleanupTempFile()
            callback = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode != CREATE_FILE_REQUEST) return

        var uri: Uri? = intent?.data

        if (uri != null && resultCode == Activity.RESULT_OK) {
            // Query display name to check for duplicate suffix
            val activity = cordova.activity
            var cursor = activity.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    var displayName = it.getString(0)
                    val original = originalFileName ?: return

                    if (displayName != original) {
                        // Check if it's appended with " (n)"
                        val appendedPrefix = original.replace(".", " ") + " ("  // Rough check, but adjust for no-ext case
                        if (displayName.contains(" (") && displayName.endsWith(")")) {
                            try {
                                // Extract suffix number
                                val suffixStart = displayName.lastIndexOf(" (")
                                if (suffixStart != -1) {
                                    val nStr = displayName.substring(suffixStart + 2, displayName.length - 1)
                                    val n = nStr.toInt()

                                    // Compute new name without space: base(n).ext
                                    val dot = original.lastIndexOf('.')
                                    val base = if (dot > 0) original.substring(0, dot) else original
                                    val ext = if (dot > 0) original.substring(dot) else ""
                                    val newName = "$base($n)$ext"

                                    // Attempt rename
                                    val newUri = DocumentsContract.renameDocument(activity.contentResolver, uri, newName)
                                    if (newUri != null) {
                                        uri = newUri
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore rename failure, proceed with original URI
                            }
                        }
                    }
                }
            }

            copyFileWithProgress(uri)
        } else {
            callback?.error("User cancelled")
            cleanupTempFile()
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
            var notificationsEnabled = false
            
            try {
                // Create notification channel
                createNotificationChannel(activity)
                
                // Show notification (check if permission granted)
                notificationManager = NotificationManagerCompat.from(activity)
                
                // Check if notifications are enabled
                notificationsEnabled = notificationManager.areNotificationsEnabled()
                
                val notificationBuilder = NotificationCompat.Builder(activity, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Saving file")
                    .setContentText("0%")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setProgress(100, 0, false)
                    .setOnlyAlertOnce(true)
                
                withContext(Dispatchers.Main) {
                    if (notificationsEnabled) {
                        try {
                            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                        } catch (e: SecurityException) {
                            // Permission denied - continue without notification
                            notificationsEnabled = false
                        }
                    }
                }

                // Copy file with progress tracking
                withContext(Dispatchers.IO) {
                    val totalBytes = tmpFile.length()
                    var written = 0L
                    var lastUpdatePercent = -1
                    
                    // Use content resolver to write to the URI selected by user
                    activity.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        FileInputStream(tmpFile).use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            
                            while (input.read(buffer).also { read = it } > 0) {
                                out.write(buffer, 0, read)
                                written += read
                                
                                // Update progress (only when percent changes to reduce UI updates)
                                val currentPercent = ((written * 100L) / totalBytes).toInt()
                                if (currentPercent != lastUpdatePercent) {
                                    lastUpdatePercent = currentPercent
                                    
                                    withContext(Dispatchers.Main) {
                                        if (notificationsEnabled) {
                                            try {
                                                notificationBuilder
                                                    .setProgress(100, currentPercent, false)
                                                    .setContentText("$currentPercent%")
                                                notificationManager?.notify(
                                                    NOTIFICATION_ID, 
                                                    notificationBuilder.build()
                                                )
                                            } catch (e: Exception) {
                                                // Ignore notification update errors
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // Ensure all data is written
                            out.flush()
                        }
                    } ?: throw Exception("Could not open output stream for URI: $destinationUri")
                    
                    // Clean up temp file
                    tmpFile.delete()
                }

                // Success - dismiss notification and return result
                withContext(Dispatchers.Main) {
                    if (notificationsEnabled) {
                        try {
                            notificationManager?.cancel(NOTIFICATION_ID)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                    callback?.success(destinationUri.toString())
                    callback = null
                    tempFilePath = null
                }
                
            } catch (e: Exception) {
                // Clean up on error
                tmpFile.delete()
                
                withContext(Dispatchers.Main) {
                    if (notificationsEnabled) {
                        try {
                            notificationManager?.cancel(NOTIFICATION_ID)
                        } catch (ex: Exception) {
                            // Ignore
                        }
                    }
                    callback?.error("Error saving file: ${e.message}")
                    callback = null
                    tempFilePath = null
                }
            }
        }
    }

    private fun cleanupTempFile() {
        tempFilePath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        tempFilePath = null
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
        cleanupTempFile()
        
        super.onDestroy()
    }

    override fun onReset() {
        // Cancel pending operations on page reload
        scope.coroutineContext.cancelChildren()
        callback = null
        super.onReset()
    }
}
