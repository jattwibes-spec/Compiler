package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ai.CompilerAiRepository
import com.example.data.local.BuildEngineMode
import com.example.data.local.BuildJobEntity
import com.example.data.local.CompilerSettings
import com.example.data.local.DatabaseProvider
import com.example.data.local.FileBackupEntity
import com.example.data.local.ProjectAnalyzerEngine
import com.example.data.local.ProjectEntity
import com.example.data.local.SafeZipArchiveManager
import com.example.data.local.SettingsDataStore
import com.example.data.remote.DynamicBackendClient
import com.example.data.remote.GitHubRepoVerification
import com.example.data.remote.HealthResponseDto
import com.example.data.repository.CompilerRepository
import com.example.domain.AiDiagnosisResult
import com.example.domain.BuildConfiguration
import com.example.domain.BuildMode
import com.example.domain.OutputFormat
import com.example.domain.ProjectAnalysisResult
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PipelineStep(val index: Int, val label: String) {
    UPLOAD(0, "Upload"),
    ANALYSIS(1, "Analyze"),
    CONFIG(2, "Configure"),
    COMPILE(3, "Compile"),
    RESULTS(4, "Results")
}

sealed interface AiTaskState {
    data object Idle : AiTaskState
    data object Loading : AiTaskState
    data class Success(val diagnosis: AiDiagnosisResult) : AiTaskState
    data class Error(val message: String) : AiTaskState
}

data class ChatMessage(
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false
)

sealed interface GithubStatusState {
    data object Idle : GithubStatusState
    data object Verifying : GithubStatusState
    data class Verified(val info: GitHubRepoVerification) : GithubStatusState
    data class Error(val message: String) : GithubStatusState
}

sealed interface BackendHealthState {
    data object Unknown : BackendHealthState
    data object Checking : BackendHealthState
    data class Online(val health: HealthResponseDto) : BackendHealthState
    data class Unreachable(val error: String) : BackendHealthState
}

class CompilerViewModel(application: Application) : AndroidViewModel(application) {

    private val database = DatabaseProvider.getDatabase(application)
    private val settingsStore = SettingsDataStore(application)
    private val archiveManager = SafeZipArchiveManager(application)
    private val analyzerEngine = ProjectAnalyzerEngine()
    private val backendClient = DynamicBackendClient()
    private val repository = CompilerRepository(
        context = application,
        database = database,
        settingsStore = settingsStore,
        archiveManager = archiveManager,
        analyzerEngine = analyzerEngine,
        backendClient = backendClient
    )
    private val aiRepository = CompilerAiRepository(application, settingsStore)
    val isFirebaseAiConfigured: Boolean get() = aiRepository.isFirebaseInitialized()

    val settings: StateFlow<CompilerSettings> = repository.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        CompilerSettings()
    )

    val projects: StateFlow<List<ProjectEntity>> = repository.allProjectsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val builds: StateFlow<List<BuildJobEntity>> = repository.allBuildsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val _pipelineStep = MutableStateFlow(PipelineStep.UPLOAD)
    val pipelineStep: StateFlow<PipelineStep> = _pipelineStep.asStateFlow()

    private val _activeAnalysis = MutableStateFlow<ProjectAnalysisResult?>(null)
    val activeAnalysis: StateFlow<ProjectAnalysisResult?> = _activeAnalysis.asStateFlow()

    private val _buildConfig = MutableStateFlow(BuildConfiguration())
    val buildConfig: StateFlow<BuildConfiguration> = _buildConfig.asStateFlow()

    private val _activeBuildId = MutableStateFlow<String?>(null)
    val activeBuildId: StateFlow<String?> = _activeBuildId.asStateFlow()

    val activeBuildJob: StateFlow<BuildJobEntity?> = combine(builds, _activeBuildId) { list, id ->
        if (id != null) list.firstOrNull { it.buildId == id } else list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _activeProjectBackups = MutableStateFlow<List<FileBackupEntity>>(emptyList())
    val activeProjectBackups: StateFlow<List<FileBackupEntity>> = _activeProjectBackups.asStateFlow()

    private val _isIngesting = MutableStateFlow(false)
    val isIngesting: StateFlow<Boolean> = _isIngesting.asStateFlow()

    private val _isStartingBuild = MutableStateFlow(false)
    val isStartingBuild: StateFlow<Boolean> = _isStartingBuild.asStateFlow()

    private val _isPushingRepo = MutableStateFlow(false)
    val isPushingRepo: StateFlow<Boolean> = _isPushingRepo.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _aiTaskState = MutableStateFlow<AiTaskState>(AiTaskState.Idle)
    val aiTaskState: StateFlow<AiTaskState> = _aiTaskState.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _githubStatus = MutableStateFlow<GithubStatusState>(GithubStatusState.Idle)
    val githubStatus: StateFlow<GithubStatusState> = _githubStatus.asStateFlow()

    private val _backendHealth = MutableStateFlow<BackendHealthState>(BackendHealthState.Unknown)
    val backendHealth: StateFlow<BackendHealthState> = _backendHealth.asStateFlow()

    private var backupsJob: Job? = null
    private var chatStreamJob: Job? = null

    init {
        viewModelScope.launch {
            repository.resumePendingBuildMonitors()
        }
        viewModelScope.launch {
            if (repository.settingsFlow.first().buildEngineMode == BuildEngineMode.FASTAPI_BACKEND) {
                checkBackendHealth()
            }
        }
    }

    fun setPipelineStep(step: PipelineStep) {
        _pipelineStep.value = step
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun ingestZip(uri: Uri) {
        viewModelScope.launch {
            _isIngesting.value = true
            _statusBanner.value = null
            repository.ingestZipProject(uri)
                .onSuccess { analysis ->
                    onProjectLoaded(analysis)
                    _pipelineStep.value = PipelineStep.ANALYSIS
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Failed to import ZIP archive."
                }
            _isIngesting.value = false
        }
    }

    fun ingestSourceFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isIngesting.value = true
            _statusBanner.value = null
            repository.ingestSourceFiles(uris)
                .onSuccess { analysis ->
                    onProjectLoaded(analysis)
                    _pipelineStep.value = PipelineStep.ANALYSIS
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Failed to import selected files."
                }
            _isIngesting.value = false
        }
    }

    fun ingestFolder(treeUri: Uri) {
        viewModelScope.launch {
            _isIngesting.value = true
            _statusBanner.value = null
            repository.ingestFolderTree(treeUri)
                .onSuccess { analysis ->
                    onProjectLoaded(analysis)
                    _pipelineStep.value = PipelineStep.ANALYSIS
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Failed to import project folder."
                }
            _isIngesting.value = false
        }
    }

    fun openExistingProject(projectId: String, targetStep: PipelineStep = PipelineStep.ANALYSIS) {
        viewModelScope.launch {
            val analysis = repository.loadProjectAnalysis(projectId)
            if (analysis != null) {
                onProjectLoaded(analysis)
                _pipelineStep.value = targetStep
            } else {
                _statusBanner.value = "Project files could not be located on storage."
            }
        }
    }

    fun openBuildJob(buildJob: BuildJobEntity) {
        _activeBuildId.value = buildJob.buildId
        viewModelScope.launch {
            repository.loadProjectAnalysis(buildJob.projectId)?.let { analysis ->
                onProjectLoaded(analysis)
            }
            _pipelineStep.value = if (buildJob.stageName == "COMPLETED" &&
                !buildJob.outputFilePath.isNullOrBlank()
            ) {
                PipelineStep.RESULTS
            } else {
                PipelineStep.COMPILE
            }
        }
    }

    private fun onProjectLoaded(analysis: ProjectAnalysisResult) {
        _activeAnalysis.value = analysis
        _aiTaskState.value = AiTaskState.Idle
        val sanitizedPkg = analysis.projectName
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .ifBlank { "app" }
            .take(20)
        val defaultOutput = analysis.compatibleOutputs.firstOrNull() ?: OutputFormat.APK
        _buildConfig.value = BuildConfiguration(
            appName = analysis.projectName.take(30),
            packageName = "com.wibes.$sanitizedPkg",
            versionName = "1.0.0",
            versionCode = 1,
            minSdk = 26,
            targetSdk = 35,
            outputFormat = defaultOutput,
            buildMode = BuildMode.DEBUG
        )
        observeBackups(analysis.projectId)
    }

    private fun observeBackups(projectId: String) {
        backupsJob?.cancel()
        backupsJob = viewModelScope.launch {
            repository.observeBackupsForProject(projectId).collect { list ->
                _activeProjectBackups.value = list
            }
        }
    }

    fun updateBuildConfig(transform: (BuildConfiguration) -> BuildConfiguration) {
        _buildConfig.value = transform(_buildConfig.value)
    }

    fun startBuild() {
        val analysis = _activeAnalysis.value ?: run {
            _statusBanner.value = "Upload and analyze a project before compiling."
            return
        }
        viewModelScope.launch {
            _isStartingBuild.value = true
            _statusBanner.value = null
            val config = _buildConfig.value
            repository.startRemoteCompilation(analysis.projectId, config)
                .onSuccess { buildId ->
                    _activeBuildId.value = buildId
                    _pipelineStep.value = PipelineStep.COMPILE
                }
                .onFailure { err ->
                    _statusBanner.value = repository.formatConnectionError(settings.value.serverBaseUrl, err)
                    // If a local failure record was created, jump to Compile screen to display real logs
                    val latest = builds.value.firstOrNull { it.projectId == analysis.projectId }
                    if (latest != null) {
                        _activeBuildId.value = latest.buildId
                        _pipelineStep.value = PipelineStep.COMPILE
                    }
                }
            _isStartingBuild.value = false
        }
    }

    fun cancelActiveBuild() {
        val buildId = activeBuildJob.value?.buildId ?: return
        viewModelScope.launch {
            repository.cancelBuild(buildId)
        }
    }

    fun runAiProjectDiagnosis(compilerLogs: String? = null) {
        val analysis = _activeAnalysis.value ?: run {
            _aiTaskState.value = AiTaskState.Error("Select or upload a project first.")
            return
        }
        viewModelScope.launch {
            _aiTaskState.value = AiTaskState.Loading
            try {
                val contextText = repository.readSourceContext(analysis.projectId)
                val result = aiRepository.analyzeProjectAndSuggestFixes(
                    analysis = analysis,
                    sourceContext = contextText,
                    compilerErrorLogs = compilerLogs
                )
                _aiTaskState.value = AiTaskState.Success(result)
            } catch (e: Exception) {
                _aiTaskState.value = AiTaskState.Error(
                    e.localizedMessage ?: "AI analysis failed."
                )
            }
        }
    }

    fun generateAndApplyMissingConfig(missingConfigLabel: String) {
        val analysis = _activeAnalysis.value ?: return
        viewModelScope.launch {
            _aiTaskState.value = AiTaskState.Loading
            try {
                val contextText = repository.readSourceContext(analysis.projectId)
                val (filePath, content) = aiRepository.generateMissingConfigFile(
                    analysis = analysis,
                    missingConfigName = missingConfigLabel,
                    sourceContext = contextText
                )
                _aiTaskState.value = AiTaskState.Success(
                    AiDiagnosisResult(
                        summary = "Generated configuration proposal for $filePath. Review and apply with automatic backup.",
                        structureOverview = "Target config file: $filePath",
                        detectedErrors = emptyList(),
                        missingDependencies = emptyList(),
                        suggestedFrameworks = listOf(analysis.framework),
                        recommendedConfigFile = filePath,
                        recommendedConfigContent = content,
                        targetPatchFile = null,
                        patchedFileContent = null
                    )
                )
            } catch (e: Exception) {
                _aiTaskState.value = AiTaskState.Error(
                    e.localizedMessage ?: "Failed to generate configuration file."
                )
            }
        }
    }

    fun applyAiModificationWithBackup(relativePath: String, content: String, reason: String) {
        val analysis = _activeAnalysis.value ?: return
        viewModelScope.launch {
            repository.applySafeFileModification(
                projectId = analysis.projectId,
                relativePath = relativePath,
                newContent = content,
                reason = reason
            ).onSuccess { updated ->
                _activeAnalysis.value = updated
                _statusBanner.value = "Saved '$relativePath' safely (original backed up)."
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Failed to write file safely."
            }
        }
    }

    fun restoreBackup(backupId: Long) {
        viewModelScope.launch {
            repository.restoreBackup(backupId)
                .onSuccess { updated ->
                    _activeAnalysis.value = updated
                    _statusBanner.value = "Restored original file from backup."
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Restore failed."
                }
        }
    }

    fun sendChatMessage(message: String) {
        if (message.isBlank()) return
        chatStreamJob?.cancel()
        chatStreamJob = viewModelScope.launch {
            val analysis = _activeAnalysis.value
            val sourceContext = if (analysis != null) repository.readSourceContext(analysis.projectId) else ""
            val latestLogs = activeBuildJob.value?.logsText

            val current = _chatMessages.value.toMutableList()
            current += ChatMessage(isUser = true, text = message)
            current += ChatMessage(isUser = false, text = "", isStreaming = true)
            _chatMessages.value = current

            try {
                aiRepository.streamDeveloperAssistant(
                    userMessage = message,
                    analysis = analysis,
                    sourceContext = sourceContext,
                    latestLogs = latestLogs
                ).collect { accumulated ->
                    val updated = _chatMessages.value.toMutableList()
                    if (updated.isNotEmpty()) {
                        updated[updated.lastIndex] = ChatMessage(
                            isUser = false,
                            text = accumulated,
                            isStreaming = true
                        )
                        _chatMessages.value = updated
                    }
                }
                val done = _chatMessages.value.toMutableList()
                if (done.isNotEmpty()) {
                    val last = done.last()
                    done[done.lastIndex] = last.copy(isStreaming = false)
                    _chatMessages.value = done
                }
            } catch (e: Exception) {
                val errList = _chatMessages.value.toMutableList()
                if (errList.isNotEmpty()) {
                    errList[errList.lastIndex] = ChatMessage(
                        isUser = false,
                        text = "Error: ${e.localizedMessage ?: "AI streaming failed."}",
                        isStreaming = false
                    )
                    _chatMessages.value = errList
                }
            }
        }
    }

    fun updateBuildEngineMode(mode: BuildEngineMode) {
        viewModelScope.launch {
            repository.updateBuildEngineMode(mode)
            if (mode == BuildEngineMode.FASTAPI_BACKEND) {
                checkBackendHealth()
            }
        }
    }

    fun saveAndVerifyGithub(repo: String, token: String) {
        viewModelScope.launch {
            _githubStatus.value = GithubStatusState.Verifying
            repository.verifyGithubConfig(repo, token)
                .onSuccess { info ->
                    _githubStatus.value = GithubStatusState.Verified(info)
                    _statusBanner.value = "GitHub connected: ${info.owner}/${info.repo} (branch ${info.defaultBranch})"
                }
                .onFailure { err ->
                    _githubStatus.value = GithubStatusState.Error(err.localizedMessage ?: "Verification failed.")
                    _statusBanner.value = err.localizedMessage ?: "GitHub verification failed."
                }
        }
    }

    fun updateGithubRepoOptions(autoCreate: Boolean, isPrivate: Boolean) {
        viewModelScope.launch {
            repository.updateGithubRepoOptions(autoCreate, isPrivate)
        }
    }

    fun pushActiveProjectToNewRepo() {
        val analysis = _activeAnalysis.value ?: return
        viewModelScope.launch {
            _isPushingRepo.value = true
            repository.pushProjectToNewRepo(analysis.projectId)
                .onSuccess { res ->
                    _statusBanner.value = "Created ${res.repoHtmlUrl} and pushed ${res.pushedFileCount} items!"
                }
                .onFailure { err ->
                    _statusBanner.value = err.localizedMessage ?: "Failed to push project to GitHub."
                }
            _isPushingRepo.value = false
        }
    }

    fun saveGithubConfigQuick(repo: String, token: String) {
        viewModelScope.launch {
            repository.updateGithubConfig(repo, token)
        }
    }

    fun updateDownloadFolder(uriString: String, label: String) {
        viewModelScope.launch {
            repository.updateDownloadFolder(uriString, label)
            _statusBanner.value = "Auto-download folder set to: $label"
        }
    }

    fun clearDownloadFolder() {
        viewModelScope.launch {
            repository.clearDownloadFolder()
            _statusBanner.value = "Reset auto-download folder to Downloads/WibesCompilerAI."
        }
    }

    fun checkBackendHealth() {
        viewModelScope.launch {
            _backendHealth.value = BackendHealthState.Checking
            repository.checkBackendHealth()
                .onSuccess { _backendHealth.value = BackendHealthState.Online(it) }
                .onFailure {
                    _backendHealth.value = BackendHealthState.Unreachable(
                        it.localizedMessage ?: "Cannot reach FastAPI build backend."
                    )
                }
        }
    }

    fun registerBackendUser(username: String, password: String) {
        viewModelScope.launch {
            repository.registerBackendUser(username, password)
                .onSuccess {
                    _statusBanner.value = "Registered and signed in as $it."
                    checkBackendHealth()
                }
                .onFailure {
                    _statusBanner.value = "Registration failed: ${it.localizedMessage}"
                }
        }
    }

    fun loginBackendUser(username: String, password: String) {
        viewModelScope.launch {
            repository.loginBackendUser(username, password)
                .onSuccess {
                    _statusBanner.value = "Signed in to build backend as $it."
                    checkBackendHealth()
                }
                .onFailure {
                    _statusBanner.value = "Sign-in failed: ${it.localizedMessage}"
                }
        }
    }

    fun logoutBackendUser() {
        viewModelScope.launch {
            repository.logoutBackendUser()
            _statusBanner.value = "Signed out from build backend."
        }
    }

    fun updateGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            repository.updateGeminiApiKey(apiKey)
            _statusBanner.value = if (apiKey.isBlank()) {
                "Gemini API key cleared (using Offline Local Diagnostics unless Firebase is configured)."
            } else {
                "Gemini API key saved (Cloud Gemini 2.5 Flash enabled)."
            }
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            repository.updateServerBaseUrl(url)
            checkBackendHealth()
        }
    }

    fun updateMaxUploadSizeMb(mb: Int) {
        viewModelScope.launch { repository.updateMaxUploadSizeMb(mb) }
    }

    fun updateMaxConcurrentBuilds(limit: Int) {
        viewModelScope.launch { repository.updateMaxConcurrentBuilds(limit) }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.updateThemeMode(mode) }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProjectAndArtifacts(projectId)
            if (_activeAnalysis.value?.projectId == projectId) {
                _activeAnalysis.value = null
                _pipelineStep.value = PipelineStep.UPLOAD
            }
            _statusBanner.value = "Project and compiled artifacts deleted."
        }
    }

    fun deleteBuild(buildId: String) {
        viewModelScope.launch {
            repository.deleteBuildRecord(buildId)
            if (_activeBuildId.value == buildId) {
                _activeBuildId.value = null
            }
        }
    }

    fun formatBytes(bytes: Long): String = repository.formatBytes(bytes)

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CompilerViewModel(application) as T
        }
    }
}
