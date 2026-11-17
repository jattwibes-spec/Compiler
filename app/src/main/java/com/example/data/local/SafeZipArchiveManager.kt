package com.example.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.example.domain.ProjectFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class IngestedProjectWorkspace(
    val projectId: String,
    val projectName: String,
    val sourceType: String,
    val workspaceDir: File,
    val archiveFile: File,
    val files: List<ProjectFileEntry>,
    val totalSizeBytes: Long,
    val sha256Checksum: String,
    val zipSecurityPassed: Boolean,
    val securityNotes: List<String>
)

class SafeZipArchiveManager(private val context: Context) {

    companion object {
        private const val MAX_ZIP_ENTRIES = 10_000
        private const val BUFFER_SIZE = 8192
    }

    suspend fun ingestZipUri(uri: Uri, maxUploadSizeMb: Int): IngestedProjectWorkspace =
        withContext(Dispatchers.IO) {
            val projectId = UUID.randomUUID().toString()
            val maxBytes = maxUploadSizeMb.toLong() * 1024L * 1024L
            val rawName = resolveDisplayName(uri) ?: "project_${projectId.take(6)}.zip"
            val projectName = rawName.removeSuffix(".zip").ifBlank { "UploadedProject" }

            val projectRoot = File(context.filesDir, "projects/$projectId").apply { mkdirs() }
            val workspaceDir = File(projectRoot, "workspace").apply { mkdirs() }
            val archiveFile = File(projectRoot, "source.zip")

            copyUriToFileWithLimit(uri, archiveFile, maxBytes)

            val securityNotes = mutableListOf<String>()
            var zipSecurityPassed = true
            var totalExtractedBytes = 0L
            var entryCount = 0
            val canonicalWorkspace = workspaceDir.canonicalFile

            ZipInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw SecurityException("Archive exceeds maximum allowed entry count ($MAX_ZIP_ENTRIES).")
                    }
                    val entryName = entry.name
                    if (entryName.startsWith("/") || entryName.startsWith("\\") || entryName.contains("..")) {
                        zipSecurityPassed = false
                        throw SecurityException("Blocked unsafe ZIP entry path traversal attempt: $entryName")
                    }

                    val targetFile = File(workspaceDir, entryName)
                    val canonicalTarget = targetFile.canonicalFile
                    if (!canonicalTarget.path.startsWith(canonicalWorkspace.path + File.separator) &&
                        canonicalTarget.path != canonicalWorkspace.path
                    ) {
                        zipSecurityPassed = false
                        throw SecurityException("ZIP Slip detected: entry escapes workspace ($entryName)")
                    }

                    if (entry.isDirectory) {
                        canonicalTarget.mkdirs()
                    } else {
                        canonicalTarget.parentFile?.mkdirs()
                        val buffer = ByteArray(BUFFER_SIZE)
                        FileOutputStream(canonicalTarget).use { fos ->
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                totalExtractedBytes += read
                                if (totalExtractedBytes > maxBytes * 2) {
                                    throw SecurityException("ZIP decompression bomb guard triggered (exceeded ${maxUploadSizeMb * 2} MB).")
                                }
                                fos.write(buffer, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (entryCount == 0) {
                throw IllegalArgumentException("The selected ZIP archive is empty.")
            }

            securityNotes += "ZIP Slip path traversal check passed ($entryCount entries verified)."
            securityNotes += "Uncompressed size (${formatBytes(totalExtractedBytes)}) within safety threshold."

            val files = collectWorkspaceEntries(workspaceDir)
            val sha256 = computeSha256(archiveFile)

            IngestedProjectWorkspace(
                projectId = projectId,
                projectName = projectName,
                sourceType = "ZIP",
                workspaceDir = workspaceDir,
                archiveFile = archiveFile,
                files = files,
                totalSizeBytes = archiveFile.length(),
                sha256Checksum = sha256,
                zipSecurityPassed = zipSecurityPassed,
                securityNotes = securityNotes
            )
        }

    suspend fun ingestSourceFiles(uris: List<Uri>, maxUploadSizeMb: Int): IngestedProjectWorkspace =
        withContext(Dispatchers.IO) {
            require(uris.isNotEmpty()) { "No files were selected." }
            val projectId = UUID.randomUUID().toString()
            val maxBytes = maxUploadSizeMb.toLong() * 1024L * 1024L

            val projectRoot = File(context.filesDir, "projects/$projectId").apply { mkdirs() }
            val workspaceDir = File(projectRoot, "workspace").apply { mkdirs() }
            val archiveFile = File(projectRoot, "source.zip")

            var cumulativeSize = 0L
            val copiedNames = mutableListOf<String>()

            for (uri in uris) {
                val safeFileName = sanitizeFileName(resolveDisplayName(uri) ?: "source_${copiedNames.size}.txt")
                val destFile = File(workspaceDir, safeFileName)
                copyUriToFileWithLimit(uri, destFile, maxBytes - cumulativeSize)
                cumulativeSize += destFile.length()
                copiedNames += safeFileName
            }

            val projectName = if (copiedNames.size == 1) {
                copiedNames.first().substringBeforeLast('.')
            } else {
                "SourceBundle_${projectId.take(6)}"
            }.ifBlank { "SourceProject" }

            packageWorkspaceToZip(workspaceDir, archiveFile)
            val files = collectWorkspaceEntries(workspaceDir)
            val sha256 = computeSha256(archiveFile)

            IngestedProjectWorkspace(
                projectId = projectId,
                projectName = projectName,
                sourceType = "FILES",
                workspaceDir = workspaceDir,
                archiveFile = archiveFile,
                files = files,
                totalSizeBytes = cumulativeSize,
                sha256Checksum = sha256,
                zipSecurityPassed = true,
                securityNotes = listOf(
                    "Imported ${copiedNames.size} source file(s) via Android Storage Access Framework.",
                    "Filenames sanitized and packaged cleanly for isolated container build."
                )
            )
        }

    suspend fun ingestFolderTree(treeUri: Uri, maxUploadSizeMb: Int): IngestedProjectWorkspace =
        withContext(Dispatchers.IO) {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalArgumentException("Unable to access the selected directory.")
            val projectId = UUID.randomUUID().toString()
            val maxBytes = maxUploadSizeMb.toLong() * 1024L * 1024L

            val projectRoot = File(context.filesDir, "projects/$projectId").apply { mkdirs() }
            val workspaceDir = File(projectRoot, "workspace").apply { mkdirs() }
            val archiveFile = File(projectRoot, "source.zip")

            var cumulativeSize = 0L
            var fileCount = 0

            fun copyDocumentTree(doc: DocumentFile, targetDir: File) {
                for (child in doc.listFiles()) {
                    val name = sanitizeFileName(child.name ?: continue)
                    if (name == ".git" || name == "node_modules" || name == "__pycache__" || name == "build" || name == ".gradle") {
                        continue
                    }
                    if (child.isDirectory) {
                        val subDir = File(targetDir, name).apply { mkdirs() }
                        copyDocumentTree(child, subDir)
                    } else if (child.isFile) {
                        fileCount++
                        if (fileCount > MAX_ZIP_ENTRIES) {
                            throw SecurityException("Folder exceeds maximum file limit ($MAX_ZIP_ENTRIES).")
                        }
                        val destFile = File(targetDir, name)
                        copyUriToFileWithLimit(child.uri, destFile, maxBytes - cumulativeSize)
                        cumulativeSize += destFile.length()
                    }
                }
            }

            copyDocumentTree(rootDoc, workspaceDir)
            if (fileCount == 0) {
                throw IllegalArgumentException("The selected folder contains no readable source files.")
            }

            packageWorkspaceToZip(workspaceDir, archiveFile)
            val files = collectWorkspaceEntries(workspaceDir)
            val sha256 = computeSha256(archiveFile)
            val projectName = sanitizeFileName(rootDoc.name ?: "FolderProject")

            IngestedProjectWorkspace(
                projectId = projectId,
                projectName = projectName,
                sourceType = "FOLDER",
                workspaceDir = workspaceDir,
                archiveFile = archiveFile,
                files = files,
                totalSizeBytes = cumulativeSize,
                sha256Checksum = sha256,
                zipSecurityPassed = true,
                securityNotes = listOf(
                    "Imported $fileCount file(s) from folder '$projectName' via SAF.",
                    "Excluded build/cache artifacts (.git, node_modules, build) and packaged safely."
                )
            )
        }

    suspend fun createFileBackup(projectId: String, workspacePath: String, relativePath: String): File =
        withContext(Dispatchers.IO) {
            val workspaceDir = File(workspacePath)
            val sourceFile = File(workspaceDir, relativePath).canonicalFile
            require(sourceFile.path.startsWith(workspaceDir.canonicalPath)) {
                "Invalid relative path outside project workspace."
            }
            val backupDir = File(context.filesDir, "projects/$projectId/backups/${System.currentTimeMillis()}").apply {
                mkdirs()
            }
            val safeRel = relativePath.replace("/", "_").replace("\\", "_")
            val backupFile = File(backupDir, safeRel)
            if (sourceFile.exists()) {
                sourceFile.copyTo(backupFile, overwrite = true)
            } else {
                backupFile.writeText("")
            }
            backupFile
        }

    suspend fun writeWorkspaceFileAndRepackage(
        projectId: String,
        workspacePath: String,
        archivePath: String?,
        relativePath: String,
        newContent: String
    ): String = withContext(Dispatchers.IO) {
        val workspaceDir = File(workspacePath)
        val canonicalWorkspace = workspaceDir.canonicalFile
        val targetFile = File(workspaceDir, relativePath).canonicalFile
        require(targetFile.path.startsWith(canonicalWorkspace.path + File.separator)) {
            "Unsafe path outside workspace."
        }
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(newContent)

        val archiveFile = if (!archivePath.isNullOrBlank()) {
            File(archivePath)
        } else {
            File(context.filesDir, "projects/$projectId/source.zip")
        }
        packageWorkspaceToZip(workspaceDir, archiveFile)
        computeSha256(archiveFile)
    }

    suspend fun restoreBackupFile(
        projectId: String,
        workspacePath: String,
        archivePath: String?,
        relativePath: String,
        backupFilePath: String
    ): String = withContext(Dispatchers.IO) {
        val backupFile = File(backupFilePath)
        require(backupFile.exists()) { "Backup snapshot file no longer exists." }
        val content = backupFile.readText()
        writeWorkspaceFileAndRepackage(projectId, workspacePath, archivePath, relativePath, content)
    }

    suspend fun readSampleSourceContext(workspacePath: String, maxChars: Int = 12_000): String =
        withContext(Dispatchers.IO) {
            val workspaceDir = File(workspacePath)
            if (!workspaceDir.exists()) return@withContext ""
            val sb = StringBuilder()
            val files = collectWorkspaceEntries(workspaceDir).filter { !it.isDirectory }
            sb.appendLine("FILE TREE (${files.size} files):")
            files.take(60).forEach { sb.appendLine("- ${it.relativePath} (${it.sizeBytes} bytes)") }
            sb.appendLine()

            val prioritized = files.sortedByDescending { entry ->
                val lower = entry.relativePath.lowercase()
                when {
                    lower.endsWith("requirements.txt") || lower.endsWith("package.json") ||
                        lower.endsWith("pubspec.yaml") || lower.endsWith("build.gradle.kts") ||
                        lower.endsWith("build.gradle") || lower.endsWith("cmakelists.txt") ||
                        lower.endsWith(".csproj") || lower.endsWith("composer.json") ||
                        lower.endsWith("buildozer.spec") -> 100
                    lower.contains("main.") || lower.contains("app.") || lower.contains("index.") -> 80
                    else -> 10
                }
            }

            for (entry in prioritized) {
                if (sb.length >= maxChars) break
                val ext = entry.relativePath.substringAfterLast('.', "").lowercase()
                if (ext in listOf("png", "jpg", "jpeg", "webp", "jar", "apk", "exe", "dll", "so", "zip", "ttf", "otf")) {
                    continue
                }
                val file = File(workspaceDir, entry.relativePath)
                if (file.exists() && file.length() <= 64_000) {
                    val snippet = runCatching { file.readText() }.getOrNull() ?: continue
                    sb.appendLine("--- ${entry.relativePath} ---")
                    sb.appendLine(snippet.take(2500))
                    sb.appendLine()
                }
            }
            sb.toString().take(maxChars)
        }

    fun collectWorkspaceEntries(workspaceDir: File): List<ProjectFileEntry> {
        if (!workspaceDir.exists()) return emptyList()
        val entries = mutableListOf<ProjectFileEntry>()
        workspaceDir.walkTopDown().forEach { file ->
            if (file == workspaceDir) return@forEach
            val rel = file.relativeTo(workspaceDir).path.replace(File.separatorChar, '/')
            entries += ProjectFileEntry(
                relativePath = rel,
                sizeBytes = if (file.isDirectory) 0L else file.length(),
                isDirectory = file.isDirectory
            )
        }
        return entries.sortedWith(compareBy<ProjectFileEntry> { !it.isDirectory }.thenBy { it.relativePath })
    }

    fun packageWorkspaceToZip(workspaceDir: File, outputZip: File) {
        outputZip.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zos ->
            workspaceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(workspaceDir).path.replace(File.separatorChar, '/')
                val entry = ZipEntry(rel)
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis -> fis.copyTo(zos, BUFFER_SIZE) }
                zos.closeEntry()
            }
        }
    }

    suspend fun deleteProjectFiles(projectId: String) = withContext(Dispatchers.IO) {
        File(context.filesDir, "projects/$projectId").deleteRecursively()
    }

    private fun copyUriToFileWithLimit(uri: Uri, dest: File, remainingLimitBytes: Long) {
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    total += read
                    if (total > remainingLimitBytes) {
                        dest.delete()
                        throw SecurityException("Upload exceeds configured maximum size limit.")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalStateException("Could not open selected file stream.")
    }

    private fun resolveDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace("..", "_")
            .replace("/", "_")
            .replace("\\", "_")
            .ifBlank { "unnamed_file" }
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.2f MB".format(mb)
    }
}
