package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.BuildJobEntity
import com.example.data.local.CompilerSettings
import com.example.data.local.FileBackupEntity
import com.example.data.local.ProjectAnalyzerEngine
import com.example.data.local.ProjectEntity
import com.example.data.local.SafeZipArchiveManager
import com.example.data.local.SettingsDataStore
import com.example.data.local.WibesDatabase
import com.example.data.remote.AnalyzeRequestDto
import com.example.data.remote.AuthRequestDto
import com.example.data.remote.DynamicBackendClient
import com.example.data.remote.HealthResponseDto
import com.example.data.remote.StartBuildRequestDto
import com.example.domain.BuildConfiguration
import com.example.domain.BuildStage
import com.example.domain.OutputFormat
import com.example.domain.ProjectAnalysisResult
import com.example.domain.SupportedLanguage
import com.example.worker.BuildMonitorWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.File
import java.util.UUID

class CompilerRepository(
    private val context: Context,
    private val database: WibesDatabase,
    private val settingsStore: SettingsDataStore,
    private val archiveManager: SafeZipArchiveManager,
    private val analyzerEngine: ProjectAnalyzerEngine,
    private val backendClient: DynamicBackendClient
) {
    private val projectDao = database.projectDao()
    private val buildJobDao = database.buildJobDao()
    private val backupDao = database.fileBackupDao()

    val settingsFlow: Flow<CompilerSettings> = settingsStore.settingsFlow
    val allProjectsFlow: Flow<List<ProjectEntity>> = projectDao.observeAllProjects()
    val allBuildsFlow: Flow<List<BuildJobEntity>> = buildJobDao.observeAllBuilds()

    fun observeBackupsForProject(projectId: String): Flow<List<FileBackupEntity>> =
        backupDao.observeBackupsForProject(projectId)

    suspend fun resumePendingBuildMonitors() = withContext(Dispatchers.IO) {
        val activeBuilds = buildJobDao.getActiveBuilds()
        activeBuilds.forEach { job ->
            BuildMonitorWorker.enqueue(context, job.buildId)
        }
    }

    suspend fun checkBackendHealth(): Result<HealthResponseDto> = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.first()
        runCatching {
            val api = backendClient.createService(settings.serverBaseUrl, settings.authToken)
            api.checkHealth()
        }.recoverCatching { err ->
            throw IllegalStateException(formatConnectionError(settings.serverBaseUrl, err), err)
        }
    }

    suspend fun registerBackendUser(username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val settings = settingsStore.settingsFlow.first()
                val api = backendClient.createService(settings.serverBaseUrl, "")
                val resp = api.register(AuthRequestDto(username.trim(), password))
                settingsStore.updateAuthSession(resp.accessToken, resp.username)
                resp.username
            }
        }

    suspend fun loginBackendUser(username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val settings = settingsStore.settingsFlow.first()
                val api = backendClient.createService(settings.serverBaseUrl, "")
                val resp = api.login(AuthRequestDto(username.trim(), password))
                settingsStore.updateAuthSession(resp.accessToken, resp.username)
                resp.username
            }
        }

    suspend fun logoutBackendUser() {
        settingsStore.clearAuthSession()
    }

    suspend fun updateServerBaseUrl(url: String) = settingsStore.updateServerBaseUrl(url)
    suspend fun updateGeminiApiKey(apiKey: String) = settingsStore.updateGeminiApiKey(apiKey)
    suspend fun updateMaxUploadSizeMb(mb: Int) = settingsStore.updateMaxUploadSizeMb(mb)
    suspend fun updateMaxConcurrentBuilds(limit: Int) = settingsStore.updateMaxConcurrentBuilds(limit)
    suspend fun updateThemeMode(mode: com.example.ui.theme.ThemeMode) = settingsStore.updateThemeMode(mode)

    suspend fun ingestZipProject(uri: Uri): Result<ProjectAnalysisResult> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val workspace = archiveManager.ingestZipUri(uri, settings.maxUploadSizeMb)
        analyzeAndPersistWorkspace(workspace)
    }

    suspend fun ingestSourceFiles(uris: List<Uri>): Result<ProjectAnalysisResult> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val workspace = archiveManager.ingestSourceFiles(uris, settings.maxUploadSizeMb)
        analyzeAndPersistWorkspace(workspace)
    }

    suspend fun ingestFolderTree(treeUri: Uri): Result<ProjectAnalysisResult> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val workspace = archiveManager.ingestFolderTree(treeUri, settings.maxUploadSizeMb)
        analyzeAndPersistWorkspace(workspace)
    }

    suspend fun loadProjectAnalysis(projectId: String): ProjectAnalysisResult? = withContext(Dispatchers.IO) {
        val entity = projectDao.getProjectById(projectId) ?: return@withContext null
        val workspaceDir = File(entity.workspacePath)
        val files = archiveManager.collectWorkspaceEntries(workspaceDir)
        analyzerEngine.analyzeWorkspace(
            projectId = entity.projectId,
            projectName = entity.name,
            sourceType = entity.sourceType,
            workspaceDir = workspaceDir,
            files = files,
            totalSizeBytes = entity.totalSizeBytes,
            sha256Checksum = entity.sha256Checksum,
            zipSecurityPassed = entity.zipSecurityPassed,
            securityNotes = listOf("Loaded from verified local workspace (${entity.totalFiles} files).")
        )
    }

    private suspend fun analyzeAndPersistWorkspace(
        workspace: com.example.data.local.IngestedProjectWorkspace
    ): ProjectAnalysisResult {
        val analysis = analyzerEngine.analyzeWorkspace(
            projectId = workspace.projectId,
            projectName = workspace.projectName,
            sourceType = workspace.sourceType,
            workspaceDir = workspace.workspaceDir,
            files = workspace.files,
            totalSizeBytes = workspace.totalSizeBytes,
            sha256Checksum = workspace.sha256Checksum,
            zipSecurityPassed = workspace.zipSecurityPassed,
            securityNotes = workspace.securityNotes
        )
        val now = System.currentTimeMillis()
        projectDao.upsertProject(
            ProjectEntity(
                projectId = analysis.projectId,
                remoteProjectId = null,
                name = analysis.projectName,
                sourceType = analysis.sourceType,
                workspacePath = workspace.workspaceDir.absolutePath,
                archivePath = workspace.archiveFile.absolutePath,
                detectedLanguageId = analysis.detectedLanguage.id,
                framework = analysis.framework,
                entryPoint = analysis.entryPoint,
                dependencyFilesJson = JSONArray(analysis.dependencyFiles).toString(),
                missingConfigsJson = JSONArray(analysis.missingConfigs).toString(),
                totalFiles = analysis.totalFiles,
                totalSizeBytes = analysis.totalSizeBytes,
                sha256Checksum = analysis.sha256Checksum,
                zipSecurityPassed = analysis.zipSecurityPassed,
                createdAtMillis = now,
                updatedAtMillis = now
            )
        )
        return analysis
    }

    suspend fun readSourceContext(projectId: String): String {
        val entity = projectDao.getProjectById(projectId) ?: return ""
        return archiveManager.readSampleSourceContext(entity.workspacePath)
    }

    suspend fun applySafeFileModification(
        projectId: String,
        relativePath: String,
        newContent: String,
        reason: String
    ): Result<ProjectAnalysisResult> = runCatching {
        val entity = projectDao.getProjectById(projectId)
            ?: throw IllegalStateException("Project not found.")
        val backupFile = archiveManager.createFileBackup(projectId, entity.workspacePath, relativePath)
        backupDao.insertBackup(
            FileBackupEntity(
                projectId = projectId,
                relativePath = relativePath,
                backupFilePath = backupFile.absolutePath,
                reason = reason,
                createdAtMillis = System.currentTimeMillis()
            )
        )
        val updatedSha = archiveManager.writeWorkspaceFileAndRepackage(
            projectId = projectId,
            workspacePath = entity.workspacePath,
            archivePath = entity.archivePath,
            relativePath = relativePath,
            newContent = newContent
        )
        val workspaceDir = File(entity.workspacePath)
        val files = archiveManager.collectWorkspaceEntries(workspaceDir)
        val reanalyzed = analyzerEngine.analyzeWorkspace(
            projectId = entity.projectId,
            projectName = entity.name,
            sourceType = entity.sourceType,
            workspaceDir = workspaceDir,
            files = files,
            totalSizeBytes = entity.archivePath?.let { File(it).length() } ?: entity.totalSizeBytes,
            sha256Checksum = updatedSha,
            zipSecurityPassed = true,
            securityNotes = listOf("Workspace updated with backup snapshot preserved ($relativePath).")
        )
        projectDao.upsertProject(
            entity.copy(
                detectedLanguageId = reanalyzed.detectedLanguage.id,
                framework = reanalyzed.framework,
                entryPoint = reanalyzed.entryPoint,
                dependencyFilesJson = JSONArray(reanalyzed.dependencyFiles).toString(),
                missingConfigsJson = JSONArray(reanalyzed.missingConfigs).toString(),
                totalFiles = reanalyzed.totalFiles,
                sha256Checksum = updatedSha,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        reanalyzed
    }

    suspend fun restoreBackup(backupId: Long): Result<ProjectAnalysisResult> = runCatching {
        val backup = backupDao.getBackupById(backupId)
            ?: throw IllegalStateException("Backup entry not found.")
        val entity = projectDao.getProjectById(backup.projectId)
            ?: throw IllegalStateException("Project not found.")
        val updatedSha = archiveManager.restoreBackupFile(
            projectId = entity.projectId,
            workspacePath = entity.workspacePath,
            archivePath = entity.archivePath,
            relativePath = backup.relativePath,
            backupFilePath = backup.backupFilePath
        )
        val workspaceDir = File(entity.workspacePath)
        val files = archiveManager.collectWorkspaceEntries(workspaceDir)
        val reanalyzed = analyzerEngine.analyzeWorkspace(
            projectId = entity.projectId,
            projectName = entity.name,
            sourceType = entity.sourceType,
            workspaceDir = workspaceDir,
            files = files,
            totalSizeBytes = entity.archivePath?.let { File(it).length() } ?: entity.totalSizeBytes,
            sha256Checksum = updatedSha,
            zipSecurityPassed = true,
            securityNotes = listOf("Restored original '${backup.relativePath}' from immutable backup.")
        )
        projectDao.upsertProject(
            entity.copy(
                detectedLanguageId = reanalyzed.detectedLanguage.id,
                framework = reanalyzed.framework,
                entryPoint = reanalyzed.entryPoint,
                dependencyFilesJson = JSONArray(reanalyzed.dependencyFiles).toString(),
                missingConfigsJson = JSONArray(reanalyzed.missingConfigs).toString(),
                totalFiles = reanalyzed.totalFiles,
                sha256Checksum = updatedSha,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        reanalyzed
    }

    suspend fun startRemoteCompilation(
        projectId: String,
        config: BuildConfiguration
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = projectDao.getProjectById(projectId)
                ?: throw IllegalStateException("Project workspace not found.")
            val language = SupportedLanguage.fromId(entity.detectedLanguageId)
            language.explainUnsupported(config.outputFormat)?.let { reason ->
                throw IllegalArgumentException(reason)
            }

            val settings = settingsStore.settingsFlow.first()
            val activeCount = buildJobDao.getActiveBuilds().size
            if (activeCount >= settings.maxConcurrentBuilds) {
                throw IllegalStateException(
                    "Concurrent build limit reached ($activeCount/${settings.maxConcurrentBuilds}). Wait for an active build or raise the limit in Settings."
                )
            }

            val archiveFile = entity.archivePath?.let { File(it) }
                ?.takeIf { it.exists() }
                ?: File(context.filesDir, "projects/$projectId/source.zip").also {
                    archiveManager.packageWorkspaceToZip(File(entity.workspacePath), it)
                }

            val api = backendClient.createService(settings.serverBaseUrl, settings.authToken)
            val now = System.currentTimeMillis()

            // 1. Upload archive to FastAPI backend
            val reqFile = archiveFile.asRequestBody("application/zip".toMediaType())
            val filePart = MultipartBody.Part.createFormData("file", "${entity.name}.zip", reqFile)
            val namePart = entity.name.toRequestBody("text/plain".toMediaType())

            val uploadResp = api.uploadProject(filePart, namePart)
            val remoteProjectId = uploadResp.projectId

            projectDao.upsertProject(entity.copy(remoteProjectId = remoteProjectId, updatedAtMillis = now))

            // 2. Trigger backend static analysis
            api.analyzeProject(AnalyzeRequestDto(remoteProjectId))

            // 3. Start isolated Celery container build
            val startResp = api.startBuild(
                StartBuildRequestDto(
                    projectId = remoteProjectId,
                    appName = config.appName.ifBlank { entity.name },
                    packageName = config.packageName,
                    versionName = config.versionName,
                    versionCode = config.versionCode,
                    minSdk = config.minSdk,
                    targetSdk = config.targetSdk,
                    outputFormat = config.outputFormat.id,
                    buildMode = config.buildMode.id
                )
            )

            val buildJob = BuildJobEntity(
                buildId = startResp.buildId,
                projectId = projectId,
                remoteProjectId = remoteProjectId,
                appName = config.appName.ifBlank { entity.name },
                packageName = config.packageName,
                versionName = config.versionName,
                versionCode = config.versionCode,
                minSdk = config.minSdk,
                targetSdk = config.targetSdk,
                outputFormatId = config.outputFormat.id,
                buildModeId = config.buildMode.id,
                stageName = BuildStage.fromWire(startResp.stage).name,
                progressPercent = 5,
                dependencyStatus = "Pending container allocation",
                logsText = "[INFO] Build ${startResp.buildId} accepted by FastAPI backend.\n[INFO] ${startResp.message}",
                errorMessage = null,
                outputFilePath = null,
                outputFileSizeBytes = 0L,
                outputSha256 = null,
                durationMillis = 0L,
                startedAtMillis = now,
                completedAtMillis = null
            )
            buildJobDao.upsertBuild(buildJob)

            // 4. Schedule WorkManager to survive app restart / backgrounding
            BuildMonitorWorker.enqueue(context, startResp.buildId)
            startResp.buildId
        }.onFailure { error ->
            // Record honest failed build attempt if local failure happened before server accepted it
            val failedId = "local_err_${UUID.randomUUID().toString().take(8)}"
            val entity = projectDao.getProjectById(projectId)
            if (entity != null && error !is IllegalStateException && error !is IllegalArgumentException) {
                val now = System.currentTimeMillis()
                buildJobDao.upsertBuild(
                    BuildJobEntity(
                        buildId = failedId,
                        projectId = projectId,
                        remoteProjectId = entity.remoteProjectId,
                        appName = config.appName.ifBlank { entity.name },
                        packageName = config.packageName,
                        versionName = config.versionName,
                        versionCode = config.versionCode,
                        minSdk = config.minSdk,
                        targetSdk = config.targetSdk,
                        outputFormatId = config.outputFormat.id,
                        buildModeId = config.buildMode.id,
                        stageName = BuildStage.FAILED.name,
                        progressPercent = 0,
                        dependencyStatus = "Backend unreachable or authentication required",
                        logsText = "[ERROR] Failed to submit build to remote FastAPI backend.\n[ERROR] " + formatConnectionError(settingsStore.settingsFlow.first().serverBaseUrl, error) + "\n[HINT] Verify your FastAPI Build Backend URL and Sign-In token in the Server & History tab.",
                        errorMessage = formatConnectionError(settingsStore.settingsFlow.first().serverBaseUrl, error),
                        outputFilePath = null,
                        outputFileSizeBytes = 0L,
                        outputSha256 = null,
                        durationMillis = 0L,
                        startedAtMillis = now,
                        completedAtMillis = now
                    )
                )
            }
        }
    }

    suspend fun cancelBuild(buildId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            BuildMonitorWorker.cancel(context, buildId)
            val existing = buildJobDao.getBuildById(buildId)
            if (existing != null && !existing.buildId.startsWith("local_err_")) {
                val settings = settingsStore.settingsFlow.first()
                val api = backendClient.createService(settings.serverBaseUrl, settings.authToken)
                runCatching { api.cancelBuild(buildId) }
            }
            if (existing != null) {
                buildJobDao.upsertBuild(
                    existing.copy(
                        stageName = BuildStage.CANCELLED.name,
                        logsText = "${existing.logsText}\n[WARN] Build cancelled by user.",
                        errorMessage = "Cancelled by user",
                        completedAtMillis = System.currentTimeMillis()
                    )
                )
            }
            Unit
        }
    }

    suspend fun deleteProjectAndArtifacts(projectId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = projectDao.getProjectById(projectId)
            if (entity?.remoteProjectId != null) {
                val settings = settingsStore.settingsFlow.first()
                val api = backendClient.createService(settings.serverBaseUrl, settings.authToken)
                runCatching { api.deleteProject(entity.remoteProjectId) }
            }
            val builds = buildJobDao.observeBuildsForProject(projectId).first()
            builds.forEach { build ->
                BuildMonitorWorker.cancel(context, build.buildId)
                File(context.filesDir, "artifacts/${build.buildId}").deleteRecursively()
            }
            buildJobDao.deleteBuildsForProject(projectId)
            backupDao.deleteBackupsForProject(projectId)
            archiveManager.deleteProjectFiles(projectId)
            projectDao.deleteProjectById(projectId)
            Unit
        }
    }

    suspend fun deleteBuildRecord(buildId: String) = withContext(Dispatchers.IO) {
        BuildMonitorWorker.cancel(context, buildId)
        File(context.filesDir, "artifacts/$buildId").deleteRecursively()
        buildJobDao.deleteBuildById(buildId)
    }

    fun formatBytes(bytes: Long): String = archiveManager.formatBytes(bytes)

    fun formatConnectionError(serverUrl: String, error: Throwable): String {
        val raw = error.localizedMessage ?: error.javaClass.simpleName
        val isNetworkError = error is java.net.ConnectException ||
            error is java.net.UnknownHostException ||
            error is java.net.SocketTimeoutException ||
            raw.contains("Failed to connect", ignoreCase = true) ||
            raw.contains("ECONNREFUSED", ignoreCase = true)
        if (!isNetworkError) return raw
        return if (serverUrl.contains("10.0.2.2")) {
            "Cannot reach $serverUrl. Note: 10.0.2.2 only works on the Android Emulator. On a physical phone, open the Server tab and set your computer's LAN IP (e.g. http://192.168.1.x:8000), ADB reverse (http://127.0.0.1:8000), or a hosted HTTPS backend URL."
        } else {
            "Cannot reach build server at $serverUrl ($raw). Ensure the /backend FastAPI + Docker stack is running and reachable."
        }
    }
}
