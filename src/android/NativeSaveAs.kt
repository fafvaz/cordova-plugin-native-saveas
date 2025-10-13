package com.outsystems.nativesaveas

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
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
        private const val PICK_FOLDER_REQUEST = 2002
        private const val CHANNEL_ID = "native_saveas_channel"
        private const val NOTIFICATION_ID = 1001
        private const val BUFFER_SIZE = 32 * 1024 // 32KB buffer
    }

    private var callback: CallbackContext? = null
    private var tempFilePath: String? = null
    private var mimeType: String? = null
    private var originalFileName: String? = null
    private var safeFileName: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun execute(action: String?, args: JSONArray?, callbackContext: CallbackContext?): Boolean {
        if (action != "saveBase64") return false

        callback = callbackContext
        val base64 = args?.optString(0) ?: ""
        val name = args?.optString(1) ?: "file.bin"
        mimeType = args?.optString(2) ?: detectMimeType(name)
        originalFileName = name

        if (base64.isEmpty()) {
            callbackContext?.error("Base64 string is empty")
            return false
        }

        safeFileName = ensureFilenameHasExtension(name, mimeType)

        scope.launch {
            try {
                val tmpPath = withContext(Dispatchers.IO) {
                    val cacheDir = cordova.activity.cacheDir
                    val tmpName = "nativesaveas_${UUID.randomUUID()}_$safeFileName"
                    val tmp = File(cacheDir, tmpName)
                    try {
                        val data = Base64.decode(base64, Base64.DEFAULT)
                        FileOutputStream(tmp).use { fos ->
                            fos.write(data)
                            fos.flush()
                        }
                        tmp.absolutePath
                    } catch (e: Exception) {
                        tmp.delete()
                        throw e
                    }
                }

                tempFilePath = tmpPath

                withContext(Dispatchers.Main) {
                    // Start folder picker flow (preferred) to avoid duplicates
                    launchFolderPicker()
                }

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

    private fun ensureFilenameHasExtension(fileName: String, mimeType: String?): String {
        val trimmed = fileName.trim()
        if (trimmed.contains(".")) return trimmed
        val ext = when (mimeType?.lowercase()) {
            "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "text/csv" -> ".csv"
            "application/json" -> ".json"
            "application/xml" -> ".xml"
            "text/html" -> ".html"
            "application/zip" -> ".zip"
            "application/msword" -> ".doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
            "application/vnd.ms-excel" -> ".xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
            else -> ""
        }
        return if (ext.isNotEmpty()) trimmed + ext else trimmed
    }

    /** Start a folder picker so we can create inside that folder and check for duplicates. */
    private fun launchFolderPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                // Optional: allow creating new folders in picker UI
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra("android.content.extra.SHOW_ADVANCED", true)
                }
            }
            cordova.setActivityResultCallback(this@NativeSaveAs)
            cordova.activity.startActivityForResult(intent, PICK_FOLDER_REQUEST)
        } catch (e: Exception) {
            // If folder picker fails, fall back to ACTION_CREATE_DOCUMENT
            launchSaveDialogFallback(safeFileName ?: originalFileName ?: "file.bin")
        }
    }

    /** Fallback to original create-document flow (some devices/providers might only support this). */
    private fun launchSaveDialogFallback(fileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = this@NativeSaveAs.mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra("android.content.extra.SHOW_ADVANCED", true)
            }
        }
        try {
            cordova.setActivityResultCallback(this@NativeSaveAs)
            cordova.activity.startActivityForResult(intent, CREATE_FILE_REQUEST)
        } catch (e: Exception) {
            callback?.error("Unable to launch save dialog: ${e.message}")
            cleanupTempFile()
            callback = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode != CREATE_FILE_REQUEST && requestCode != PICK_FOLDER_REQUEST) return

        if (intent != null && resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                PICK_FOLDER_REQUEST -> {
                    intent.data?.let { treeUri ->
                        handleFolderPicked(treeUri, intent)
                    } ?: run {
                        callback?.error("No folder URI received")
                        cleanupTempFile()
                        callback = null
                    }
                }
                CREATE_FILE_REQUEST -> {
                    intent.data?.let { uri ->
                        // fallback flow
                        copyFileWithProgress(uri)
                    } ?: run {
                        callback?.error("No destination URI received")
                        cleanupTempFile()
                        callback = null
                    }
                }
            }
        } else {
            callback?.error("User cancelled")
            cleanupTempFile()
            callback = null
        }
    }

    /** Handle folder selection: check for existing same-named file, delete if present, create new doc, write. */
    private fun handleFolderPicked(treeUri: Uri, intent: Intent) {
        scope.launch {
            val activity = cordova.activity
            try {
                // Persist permissions to the tree so we can write
                try {
                    val takeFlags = (intent.flags
                        and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                    activity.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                } catch (_: Exception) {
                    // ignore if cannot persist
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    withContext(Dispatchers.IO) {
                        val resolver = activity.contentResolver
                        // Parent document uri for the tree
                        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
                        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)

                        var finalDocUri: Uri? = null

                        try {
                            // Try to find existing document with same display name
                            val desired = safeFileName ?: originalFileName ?: "file.bin"
                            val existing = try {
                                DocumentsContract.findDocument(resolver, parentDocUri, desired)
                            } catch (ex: Exception) {
                                null
                            }

                            if (existing != null) {
                                // Delete existing to force overwrite (may fail if provider disallows delete)
                                try {
                                    DocumentsContract.deleteDocument(resolver, existing)
                                } catch (_: Exception) {
                                    // If delete fails, fallback to createDocument which may create (1)
                                }
                            }

                            // Create new document with exact name
                            finalDocUri = try {
                                DocumentsContract.createDocument(resolver, parentDocUri, mimeType
                                    ?: "application/octet-stream", desired)
                            } catch (ex: Exception) {
                                null
                            }

                            // If createDocument failed, fallback to ACTION_CREATE_DOCUMENT (provider limitations)
                            if (finalDocUri == null) {
                                withContext(Dispatchers.Main) {
                                    launchSaveDialogFallback(desired)
                                }
                                return@withContext
                            }

                            // Write file into finalDocUri
                            copyFileWithProgress(finalDocUri)
                        } catch (ex: Exception) {
                            withContext(Dispatchers.Main) {
                                callback?.error("Error during folder-save: ${ex.message}")
                                cleanupTempFile()
                                callback = null
                            }
                        }
                    }
                } else {
                    // If API < 21, fallback to create-document
                    withContext(Dispatchers.Main) {
                        launchSaveDialogFallback(safeFileName ?: originalFileName ?: "file.bin")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback?.error("Error handling folder pick: ${e.message}")
                    cleanupTempFile()
                    callback = null
                }
            }
        }
    }

    /** Copy temp file to destinationUri with the same progress/notification code. */
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
                createNotificationChannel(activity)
                notificationManager = NotificationManagerCompat.from(activity)
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
                            notificationsEnabled = false
                        }
                    }
                }

                withContext(Dispatchers.IO) {
                    val totalBytes = tmpFile.length()
                    var written = 0L
                    var lastUpdatePercent = -1

                    activity.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        FileInputStream(tmpFile).use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (input.read(buffer).also { read = it } > 0) {
                                out.write(buffer, 0, read)
                                written += read

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
                                            } catch (_: Exception) {
                                            }
                                        }
                                    }
                                }
                            }
                            out.flush()
                        }
                    } ?: throw Exception("Could not open output stream for URI: $destinationUri")

                    // Remove temp file after copy
                    tmpFile.delete()
                }

                withContext(Dispatchers.Main) {
                    if (notificationsEnabled) {
                        try {
                            notificationManager?.cancel(NOTIFICATION_ID)
                        } catch (_: Exception) {
                        }
                    }
                    callback?.success(destinationUri.toString())
                    callback = null
                    tempFilePath = null
                }
            } catch (e: Exception) {
                tmpFile.delete()
                withContext(Dispatchers.Main) {
                    if (notificationsEnabled) {
                        try {
                            notificationManager?.cancel(NOTIFICATION_ID)
                        } catch (_: Exception) {
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
            } catch (_: Exception) {
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
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun detectMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".csv") -> "text/csv"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".xml") -> "application/xml"
            lower.endsWith(".html") -> "text/html"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".doc") -> "application/msword"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".xls") -> "application/vnd.ms-excel"
            lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        cleanupTempFile()
        super.onDestroy()
    }

    override fun onReset() {
        scope.coroutineContext.cancelChildren()
        callback = null
        super.onReset()
    }
}
