package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BuildJobEntity
import com.example.domain.BuildStage
import com.example.ui.components.KeyValueRow
import com.example.ui.components.SectionCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusTerminalBg
import com.example.ui.theme.StatusTerminalText
import java.io.File

@Composable
fun CompilationScreen(
    buildJob: BuildJobEntity?,
    onCancelBuild: () -> Unit,
    onAnalyzeErrorWithAi: (String) -> Unit,
    onViewResults: () -> Unit,
    onGoToConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (buildJob == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("No Build Selected", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Configure your project and start a compilation job to view real-time Docker build logs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(onClick = onGoToConfig) { Text("Go to Build Config") }
            }
        }
        return
    }

    val stage = BuildStage.fromWire(buildJob.stageName)
    val clipboard = LocalClipboardManager.current
    var logFilter by remember { mutableStateOf("ALL") }

    val filteredLogs = remember(buildJob.logsText, logFilter) {
        val lines = buildJob.logsText.lines()
        when (logFilter) {
            "ERROR" -> lines.filter { "ERROR" in it.uppercase() || "FAIL" in it.uppercase() || "TRACEBACK" in it.uppercase() }
            "WARN" -> lines.filter { "WARN" in it.uppercase() || "ERROR" in it.uppercase() }
            else -> lines
        }.joinToString("\n").ifBlank { "(No matching log lines)" }
    }

    val verifiedArtifactExists = remember(buildJob.outputFilePath) {
        val path = buildJob.outputFilePath
        !path.isNullOrBlank() && File(path).let { it.exists() && it.length() > 0L }
    }

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
            // Stage & Progress Card
            item {
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${buildJob.appName} (${buildJob.outputFormatId})",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Build ID: ${buildJob.buildId}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusBadge(stage = stage)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (!stage.isTerminal) {
                        LinearProgressIndicator(
                            progress = { (buildJob.progressPercent.coerceIn(0, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    KeyValueRow("Current Stage", stage.label)
                    KeyValueRow("Progress", "${buildJob.progressPercent}%")
                    KeyValueRow("Dependency Install", buildJob.dependencyStatus)
                    KeyValueRow("Build Mode", buildJob.buildModeId.uppercase())

                    if (!buildJob.errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = buildJob.errorMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!stage.isTerminal) {
                            OutlinedButton(
                                onClick = onCancelBuild,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("cancel_build_button")
                            ) {
                                Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cancel Build")
                            }
                        } else if (stage == BuildStage.FAILED) {
                            Button(
                                onClick = { onAnalyzeErrorWithAi(buildJob.logsText) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("analyze_error_ai_button")
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("AI Error Repair")
                            }
                        } else if (stage == BuildStage.COMPLETED && verifiedArtifactExists) {
                            Button(
                                onClick = onViewResults,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("view_results_button")
                            ) {
                                Text("Open Verified Artifact")
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                            }
                        }
                    }
                }
            }

            // Live Compilation Logs Terminal
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
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Compilation Logs", style = MaterialTheme.typography.titleMedium)
                        }

                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(buildJob.logsText)) },
                            modifier = Modifier.testTag("copy_logs_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ALL", "WARN", "ERROR").forEach { filter ->
                            FilterChip(
                                selected = logFilter == filter,
                                onClick = { logFilter = filter },
                                label = { Text(filter) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 420.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(StatusTerminalBg)
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = filteredLogs,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = StatusTerminalText
                        )
                    }
                }
            }
        }
    }
}
