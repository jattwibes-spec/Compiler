package com.example.data.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

object ArtifactAutoSaver {

    fun saveToConfiguredFolder(
        context: Context,
        sourceFile: File,
        folderTreeUriString: String
    ): Result<String> = runCatching {
        require(sourceFile.exists() && sourceFile.length() > 0L) {
            "Compiled artifact is missing or empty."
        }
        val mimeType = when {
            sourceFile.name.endsWith(".apk", ignoreCase = true) ->
                "application/vnd.android.package-archive"
            sourceFile.name.endsWith(".zip", ignoreCase = true) ->
                "application/zip"
            else -> "application/octet-stream"
        }

        if (folderTreeUriString.isNotBlank()) {
            val treeUri = Uri.parse(folderTreeUriString)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("Selected download folder is no longer accessible.")
            if (!treeDoc.canWrite()) {
                throw IllegalStateException("Write permission was revoked for the selected folder. Pick it again in Settings.")
            }
            // Remove existing file with the exact same name to avoid "(1)" duplicates
            treeDoc.findFile(sourceFile.name)?.delete()
            val targetDoc = treeDoc.createFile(mimeType, sourceFile.name)
                ?: throw IllegalStateException("Could not create ${sourceFile.name} inside selected folder.")
            context.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                sourceFile.inputStream().use { inp -> inp.copyTo(out) }
            } ?: throw IllegalStateException("Failed to open output stream for ${sourceFile.name}.")

            val folderDisplay = treeDoc.name ?: "selected folder"
            return@runCatching "Auto-saved to $folderDisplay/${sourceFile.name}"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/WibesCompilerAI")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Failed to create MediaStore download entry.")
            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    sourceFile.inputStream().use { inp -> inp.copyTo(out) }
                } ?: throw IllegalStateException("Unable to write to Downloads.")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw e
            }
            return@runCatching "Auto-saved to Downloads/WibesCompilerAI/${sourceFile.name}"
        }

        @Suppress("DEPRECATION")
        val legacyDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "WibesCompilerAI"
        ).apply { mkdirs() }
        val dest = File(legacyDir, sourceFile.name)
        sourceFile.copyTo(dest, overwrite = true)
        "Auto-saved to ${dest.absolutePath}"
    }
}
