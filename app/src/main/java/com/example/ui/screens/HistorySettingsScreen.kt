package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.BuildJobEntity
import com.example.data.local.CompilerSettings
import com.example.data.local.ProjectEntity
import com.example.domain.BuildStage
import com.example.domain.SupportedLanguage
import com.example.ui.components.SectionCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.BackendHealthState
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistorySettingsScreen(
    settings: CompilerSettings,
    isFirebaseAiConfigured: Boolean,
    backendHealth: BackendHealthState,
    projects: List<ProjectEntity>,
    builds: List<BuildJobEntity>,
    formatBytes: (Long) -> String,
    onUpdateServerUrl: (String) -> Unit,
    onUpdateGeminiApiKey: (String) -> Unit,
    onCheckHealth: () -> Unit,
    onRegisterUser: (String, String) -> Unit,
    onLoginUser: (String, String) -> Unit,
    onLogoutUser: () -> Unit,
    onUpdateMaxUploadMb: (Int) -> Unit,
    onUpdateMaxConcurrentBuilds: (Int) -> Unit,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onOpenBuild: (BuildJobEntity) -> Unit,
    onDeleteBuild: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var serverUrlInput by remember(settings.serverBaseUrl) { mutableStateOf(settings.serverBaseUrl) }
    var geminiKeyInput by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var usernameInput by remember { mutableStateOf(settings.username) }
    var passwordInput by remember { mutableStateOf("") }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // FastAPI Build Backend Connection
            item {
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("FastAPI Build Backend", style = MaterialTheme.typography.titleMedium)
                        }
                        when (backendHealth) {
                            is BackendHealthState.Online -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = "Online (Docker=${backendHealth.health.dockerAvailable})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StatusSuccess
                                    )
                                }
                            }
                            is BackendHealthState.Checking -> {
                                Text("Checking...", style = MaterialTheme.typography.labelMedium)
                            }
                            is BackendHealthState.Unreachable -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Text("Unreachable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            BackendHealthState.Unknown -> Unit
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        label = { Text("Backend Base URL") },
                        supportingText = { Text("Phone: use PC LAN IP (http://192.168.x.x:8000), ADB USB (http://127.0.0.1:8000), or HTTPS URL. 10.0.2.2 is emulator-only.") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("server_url_input")
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AssistChip(
                            onClick = { serverUrlInput = "http://127.0.0.1:8000" },
                            label = { Text("ADB USB (127.0.0.1:8000)") }
                        )
                        AssistChip(
                            onClick = { serverUrlInput = "http://192.168.1.100:8000" },
                            label = { Text("LAN IP Template") }
                        )
                        AssistChip(
                            onClick = { serverUrlInput = "http://10.0.2.2:8000" },
                            label = { Text("Emulator (10.0.2.2)") }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onUpdateServerUrl(serverUrlInput) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("save_server_url_button")
                        ) {
                            Text("Save & Connect")
                        }
                        OutlinedButton(
                            onClick = onCheckHealth,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("check_health_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Ping")
                        }
                    }

                    if (backendHealth is BackendHealthState.Unreachable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Status: ${backendHealth.error}\nTip: Run `/backend` (`docker compose up --build`), or over USB run `adb reverse tcp:8000 tcp:8000` and connect to `http://127.0.0.1:8000`.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Built-in Gemini AI Configuration
            item {
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Built-in Gemini AI Engine", style = MaterialTheme.typography.titleMedium)
                        }
                        val aiModeLabel = when {
                            settings.geminiApiKey.isNotBlank() -> "Cloud Gemini 2.5 Flash"
                            isFirebaseAiConfigured -> "Firebase AI Logic"
                            else -> "Offline Diagnostics"
                        }
                        Text(
                            text = aiModeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (settings.geminiApiKey.isNotBlank() || isFirebaseAiConfigured) StatusSuccess else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = geminiKeyInput,
                        onValueChange = { geminiKeyInput = it },
                        label = { Text("Google AI Studio Gemini API Key") },
                        supportingText = {
                            Text("Optional: paste an AIza... key for full cloud Gemini 2.5 Flash code repair & chat without Firebase setup. Offline diagnostics work automatically without a key.")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("gemini_api_key_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onUpdateGeminiApiKey(geminiKeyInput) },
                            modifier = Modifier.weight(1f).height(48.dp).testTag("save_gemini_key_button")
                        ) {
                            Text("Save AI Key")
                        }
                        if (settings.geminiApiKey.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    geminiKeyInput = ""
                                    onUpdateGeminiApiKey("")
                                },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            // Authentication Card
            item {
                SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Backend Authentication (JWT)", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (settings.authToken.isNotBlank()) {
                        Text(
                            text = "Authenticated as ${settings.username.ifBlank { "developer" }}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = StatusSuccess,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onLogoutUser,
                            modifier = Modifier.testTag("backend_logout_button")
                        ) {
                            Text("Sign Out")
                        }
                    } else {
                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = { usernameInput = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("auth_username_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("auth_password_input")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onLoginUser(usernameInput, passwordInput) },
                                enabled = usernameInput.isNotBlank() && passwordInput.length >= 6,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("auth_login_button")
                            ) {
                                Text("Sign In")
                            }
                            FilledTonalButton(
                                onClick = { onRegisterUser(usernameInput, passwordInput) },
                                enabled = usernameInput.isNotBlank() && passwordInput.length >= 6,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("auth_register_button")
                            ) {
                                Text("Register")
                            }
                        }
                    }
                }
            }

            // Limits & Appearance
            item {
                SectionCard {
                    Text("Limits & Appearance", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Maximum Upload Size: ${settings.maxUploadSizeMb} MB",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = settings.maxUploadSizeMb.toFloat(),
                        onValueChange = { onUpdateMaxUploadMb(it.roundToInt()) },
                        valueRange = 10f..500f,
                        steps = 48
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Concurrent Build Limit: ${settings.maxConcurrentBuilds}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = settings.maxConcurrentBuilds.toFloat(),
                        onValueChange = { onUpdateMaxConcurrentBuilds(it.roundToInt()) },
                        valueRange = 1f..4f,
                        steps = 2
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Theme Mode", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { idx, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { onUpdateThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = ThemeMode.entries.size),
                                modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}")
                            ) {
                                Text(mode.label)
                            }
                        }
                    }
                }
            }

            // Security Architecture Summary
            item {
                SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = StatusSuccess)
                        Text("Untrusted Code Sandbox Guarantees", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val guarantees = listOf(
                        "ZIP Slip canonical path traversal blocking on client and server",
                        "Isolated per-language Docker build containers (--cpus, --memory, --pids-limit)",
                        "Network-isolated compile stage (--network=none) & strict build timeout",
                        "Automatic workspace cleanup & authenticated artifact downloads",
                        "Immutable source backups before any AI patch is written"
                    )
                    guarantees.forEach { g ->
                        Text("• $g", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Saved Projects
            item {
                SectionCard {
                    Text("Saved Projects (${projects.size})", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (projects.isEmpty()) {
                        Text(
                            text = "No projects stored locally.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        projects.forEachIndexed { idx, project ->
                            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            val lang = SupportedLanguage.fromId(project.detectedLanguageId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenProject(project.projectId) }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(project.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = "${lang.displayName} • ${project.totalFiles} files • ${formatBytes(project.totalSizeBytes)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteProject(project.projectId) },
                                    modifier = Modifier.testTag("delete_project_${project.projectId}")
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete project", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            // Full Build History
            item {
                SectionCard {
                    Text("All Build Jobs (${builds.size})", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (builds.isEmpty()) {
                        Text(
                            text = "No build history yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        builds.forEachIndexed { idx, build ->
                            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenBuild(build) }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${build.appName} (${build.outputFormatId})", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = build.errorMessage ?: "Size: ${formatBytes(build.outputFileSizeBytes)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                StatusBadge(stage = BuildStage.fromWire(build.stageName))
                                IconButton(
                                    onClick = { onDeleteBuild(build.buildId) },
                                    modifier = Modifier.testTag("delete_build_${build.buildId}")
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete build", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
