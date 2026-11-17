package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BuildCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BuildCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.PipelineStepperBar
import com.example.ui.screens.AiAssistantScreen
import com.example.ui.screens.AnalysisScreen
import com.example.ui.screens.BuildConfigScreen
import com.example.ui.screens.CompilationScreen
import com.example.ui.screens.HistorySettingsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ResultsScreen
import com.example.ui.screens.UploadScreen
import com.example.ui.theme.WibesCompilerTheme
import com.example.ui.viewmodel.CompilerViewModel
import com.example.ui.viewmodel.PipelineStep
import java.io.File

const val ROUTE_HOME = "home"
const val ROUTE_WORKSPACE = "workspace"
const val ROUTE_AI = "ai_assistant"
const val ROUTE_HISTORY_SETTINGS = "history_settings"

private data class NavTabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val TopTabs = listOf(
    NavTabItem(ROUTE_HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    NavTabItem(ROUTE_WORKSPACE, "Compiler", Icons.Filled.BuildCircle, Icons.Outlined.BuildCircle),
    NavTabItem(ROUTE_AI, "AI Repair", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    NavTabItem(ROUTE_HISTORY_SETTINGS, "Server", Icons.Filled.Dns, Icons.Outlined.Dns)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WibesAppRoot(viewModel: CompilerViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val builds by viewModel.builds.collectAsStateWithLifecycle()
    val pipelineStep by viewModel.pipelineStep.collectAsStateWithLifecycle()
    val activeAnalysis by viewModel.activeAnalysis.collectAsStateWithLifecycle()
    val buildConfig by viewModel.buildConfig.collectAsStateWithLifecycle()
    val activeBuildJob by viewModel.activeBuildJob.collectAsStateWithLifecycle()
    val backups by viewModel.activeProjectBackups.collectAsStateWithLifecycle()
    val isIngesting by viewModel.isIngesting.collectAsStateWithLifecycle()
    val isStartingBuild by viewModel.isStartingBuild.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val aiTaskState by viewModel.aiTaskState.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val backendHealth by viewModel.backendHealth.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_HOME
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val hasVerifiedResult = activeBuildJob?.let { job ->
        job.stageName == "COMPLETED" &&
            !job.outputFilePath.isNullOrBlank() &&
            File(job.outputFilePath).let { it.exists() && it.length() > 0L }
    } == true

    WibesCompilerTheme(themeMode = settings.themeMode) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 600.dp

            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = when (currentRoute) {
                                    ROUTE_HOME -> "Wibes Compiler AI"
                                    ROUTE_WORKSPACE -> "Build Pipeline — ${pipelineStep.label}"
                                    ROUTE_AI -> "AI Diagnostics & Repair"
                                    ROUTE_HISTORY_SETTINGS -> "Server, Auth & History"
                                    else -> "Wibes Compiler AI"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                bottomBar = {
                    if (!useNavigationRail) {
                        NavigationBar {
                            TopTabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag("nav_tab_${tab.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavigationRail) {
                        NavigationRail {
                            TopTabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag("rail_tab_${tab.route}")
                                )
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_HOME,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable(ROUTE_HOME) {
                            HomeScreen(
                                projects = projects,
                                builds = builds,
                                maxConcurrentBuilds = settings.maxConcurrentBuilds,
                                backendHealth = backendHealth,
                                formatBytes = viewModel::formatBytes,
                                onUploadProjectClick = {
                                    viewModel.setPipelineStep(PipelineStep.UPLOAD)
                                    navigateToTab(ROUTE_WORKSPACE)
                                },
                                onOpenAiAssistant = { navigateToTab(ROUTE_AI) },
                                onOpenServerSettings = { navigateToTab(ROUTE_HISTORY_SETTINGS) },
                                onSelectProject = { projectId ->
                                    viewModel.openExistingProject(projectId, PipelineStep.ANALYSIS)
                                    navigateToTab(ROUTE_WORKSPACE)
                                },
                                onSelectBuild = { job ->
                                    viewModel.openBuildJob(job)
                                    navigateToTab(ROUTE_WORKSPACE)
                                }
                            )
                        }

                        composable(ROUTE_WORKSPACE) {
                            BackHandler(enabled = pipelineStep != PipelineStep.UPLOAD) {
                                val prev = PipelineStep.entries
                                    .getOrNull(pipelineStep.index - 1) ?: PipelineStep.UPLOAD
                                viewModel.setPipelineStep(prev)
                            }
                            Column(modifier = Modifier.fillMaxSize()) {
                                PipelineStepperBar(
                                    currentStep = pipelineStep,
                                    hasProject = activeAnalysis != null,
                                    hasBuild = activeBuildJob != null,
                                    hasVerifiedResult = hasVerifiedResult,
                                    onStepSelected = viewModel::setPipelineStep
                                )

                                when (pipelineStep) {
                                    PipelineStep.UPLOAD -> UploadScreen(
                                        activeAnalysis = activeAnalysis,
                                        isIngesting = isIngesting,
                                        maxUploadSizeMb = settings.maxUploadSizeMb,
                                        formatBytes = viewModel::formatBytes,
                                        onZipSelected = viewModel::ingestZip,
                                        onFilesSelected = viewModel::ingestSourceFiles,
                                        onFolderSelected = viewModel::ingestFolder,
                                        onProceedToAnalysis = { viewModel.setPipelineStep(PipelineStep.ANALYSIS) }
                                    )
                                    PipelineStep.ANALYSIS -> AnalysisScreen(
                                        analysis = activeAnalysis,
                                        onGenerateMissingConfig = { missing ->
                                            viewModel.generateAndApplyMissingConfig(missing)
                                            navigateToTab(ROUTE_AI)
                                        },
                                        onRunAiAnalysis = {
                                            viewModel.runAiProjectDiagnosis()
                                            navigateToTab(ROUTE_AI)
                                        },
                                        onProceedToConfig = { viewModel.setPipelineStep(PipelineStep.CONFIG) },
                                        onGoToUpload = { viewModel.setPipelineStep(PipelineStep.UPLOAD) }
                                    )
                                    PipelineStep.CONFIG -> BuildConfigScreen(
                                        analysis = activeAnalysis,
                                        config = buildConfig,
                                        isStartingBuild = isStartingBuild,
                                        onUpdateConfig = viewModel::updateBuildConfig,
                                        onStartBuild = viewModel::startBuild,
                                        onGoToUpload = { viewModel.setPipelineStep(PipelineStep.UPLOAD) }
                                    )
                                    PipelineStep.COMPILE -> CompilationScreen(
                                        buildJob = activeBuildJob,
                                        onCancelBuild = viewModel::cancelActiveBuild,
                                        onAnalyzeErrorWithAi = { logs ->
                                            viewModel.runAiProjectDiagnosis(logs)
                                            navigateToTab(ROUTE_AI)
                                        },
                                        onViewResults = { viewModel.setPipelineStep(PipelineStep.RESULTS) },
                                        onGoToConfig = { viewModel.setPipelineStep(PipelineStep.CONFIG) }
                                    )
                                    PipelineStep.RESULTS -> ResultsScreen(
                                        buildJob = activeBuildJob,
                                        formatBytes = viewModel::formatBytes,
                                        onBuildAgain = { viewModel.setPipelineStep(PipelineStep.CONFIG) }
                                    )
                                }
                            }
                        }

                        composable(ROUTE_AI) {
                            AiAssistantScreen(
                                activeAnalysis = activeAnalysis,
                                activeBuildJob = activeBuildJob,
                                aiTaskState = aiTaskState,
                                backups = backups,
                                chatMessages = chatMessages,
                                onAnalyzeCode = { viewModel.runAiProjectDiagnosis() },
                                onDiagnoseBuildError = { logs -> viewModel.runAiProjectDiagnosis(logs) },
                                onApplyPatchWithBackup = viewModel::applyAiModificationWithBackup,
                                onRestoreBackup = viewModel::restoreBackup,
                                onSendChat = viewModel::sendChatMessage
                            )
                        }

                        composable(ROUTE_HISTORY_SETTINGS) {
                            HistorySettingsScreen(
                                settings = settings,
                                backendHealth = backendHealth,
                                projects = projects,
                                builds = builds,
                                formatBytes = viewModel::formatBytes,
                                onUpdateServerUrl = viewModel::updateServerUrl,
                                onCheckHealth = viewModel::checkBackendHealth,
                                onRegisterUser = viewModel::registerBackendUser,
                                onLoginUser = viewModel::loginBackendUser,
                                onLogoutUser = viewModel::logoutBackendUser,
                                onUpdateMaxUploadMb = viewModel::updateMaxUploadSizeMb,
                                onUpdateMaxConcurrentBuilds = viewModel::updateMaxConcurrentBuilds,
                                onUpdateThemeMode = viewModel::updateThemeMode,
                                onOpenProject = { projectId ->
                                    viewModel.openExistingProject(projectId, PipelineStep.ANALYSIS)
                                    navigateToTab(ROUTE_WORKSPACE)
                                },
                                onDeleteProject = viewModel::deleteProject,
                                onOpenBuild = { job ->
                                    viewModel.openBuildJob(job)
                                    navigateToTab(ROUTE_WORKSPACE)
                                },
                                onDeleteBuild = viewModel::deleteBuild
                            )
                        }
                    }
                }
            }
        }
    }
}
