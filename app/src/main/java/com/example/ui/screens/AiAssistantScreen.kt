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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BuildJobEntity
import com.example.data.local.FileBackupEntity
import com.example.domain.ProjectAnalysisResult
import com.example.ui.components.SectionCard
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusTerminalBg
import com.example.ui.theme.StatusTerminalText
import com.example.ui.viewmodel.AiTaskState
import com.example.ui.viewmodel.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AiAssistantScreen(
    activeAnalysis: ProjectAnalysisResult?,
    activeBuildJob: BuildJobEntity?,
    aiTaskState: AiTaskState,
    backups: List<FileBackupEntity>,
    chatMessages: List<ChatMessage>,
    onAnalyzeCode: () -> Unit,
    onDiagnoseBuildError: (String?) -> Unit,
    onApplyPatchWithBackup: (relativePath: String, content: String, reason: String) -> Unit,
    onRestoreBackup: (Long) -> Unit,
    onSendChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var chatInput by remember { mutableStateOf("") }
    val dateFmt = SimpleDateFormat("MMM dd HH:mm:ss", Locale.getDefault())

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
            item {
                SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Gemini Compiler Assistant",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (activeAnalysis != null) {
                            "Active scope: ${activeAnalysis.projectName} (${activeAnalysis.detectedLanguage.displayName} / ${activeAnalysis.framework}). Original files are always backed up before any AI modification."
                        } else {
                            "Upload or select a project to run code structure analysis, dependency inspection, config generation, and safe error repair."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onAnalyzeCode,
                            enabled = activeAnalysis != null && aiTaskState !is AiTaskState.Loading,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("ai_analyze_code_button")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Analyze Code")
                        }

                        FilledTonalButton(
                            onClick = { onDiagnoseBuildError(activeBuildJob?.logsText) },
                            enabled = activeAnalysis != null && aiTaskState !is AiTaskState.Loading,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("ai_diagnose_error_button")
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fix Build Errors")
                        }
                    }

                    if (aiTaskState is AiTaskState.Loading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Running Gemini project diagnostics...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (aiTaskState is AiTaskState.Error) {
                item {
                    SectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            text = "AI Analysis Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = aiTaskState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (aiTaskState is AiTaskState.Success) {
                val diag = aiTaskState.diagnosis
                item {
                    SectionCard {
                        Text("AI Diagnosis & Architecture Overview", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(diag.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (diag.structureOverview.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = diag.structureOverview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (diag.detectedErrors.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Detected Issues:", style = MaterialTheme.typography.labelLarge)
                            diag.detectedErrors.forEach { err ->
                                Text("• $err", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }

                        if (diag.missingDependencies.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Missing Dependencies:", style = MaterialTheme.typography.labelLarge)
                            diag.missingDependencies.forEach { dep ->
                                Text("• $dep", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        if (diag.suggestedFrameworks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Suggested Frameworks: ${diag.suggestedFrameworks.joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Proposed Missing Config File with Safe Save
                if (!diag.recommendedConfigFile.isNullOrBlank() && !diag.recommendedConfigContent.isNullOrBlank()) {
                    item {
                        SectionCard {
                            Text(
                                text = "Proposed Config: ${diag.recommendedConfigFile}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(StatusTerminalBg)
                                    .padding(10.dp)
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = diag.recommendedConfigContent,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    color = StatusTerminalText
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    onApplyPatchWithBackup(
                                        diag.recommendedConfigFile,
                                        diag.recommendedConfigContent,
                                        "AI generated missing config"
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("apply_ai_config_button")
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Save ${diag.recommendedConfigFile} (With Backup)")
                            }
                        }
                    }
                }

                // Proposed Source Code Patch with Safe Save
                if (!diag.targetPatchFile.isNullOrBlank() && !diag.patchedFileContent.isNullOrBlank()) {
                    item {
                        SectionCard {
                            Text(
                                text = "Proposed Code Repair: ${diag.targetPatchFile}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(StatusTerminalBg)
                                    .padding(10.dp)
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = diag.patchedFileContent,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    color = StatusTerminalText
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    onApplyPatchWithBackup(
                                        diag.targetPatchFile,
                                        diag.patchedFileContent,
                                        "AI compiler error repair"
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("apply_ai_patch_button")
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Backup Original & Apply Fix")
                            }
                        }
                    }
                }
            }

            // Immutable Backup Snapshots
            if (backups.isNotEmpty()) {
                item {
                    SectionCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Original File Backups (${backups.size})", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        backups.forEachIndexed { idx, backup ->
                            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(backup.relativePath, style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        text = "${backup.reason} • ${dateFmt.format(Date(backup.createdAtMillis))}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(
                                    onClick = { onRestoreBackup(backup.id) },
                                    modifier = Modifier.testTag("restore_backup_${backup.id}")
                                ) {
                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Restore")
                                }
                            }
                        }
                    }
                }
            }

            // Interactive Streaming Chat
            item {
                SectionCard {
                    Text("Ask Compiler Assistant", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    chatMessages.forEach { msg ->
                        Surface(
                            color = if (msg.isUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (msg.isUser) "You" else "Wibes AI",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (msg.isUser) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = msg.text.ifBlank { if (msg.isStreaming) "Thinking..." else "" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            placeholder = { Text("Ask about errors, dependencies, or build config...") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_chat_input")
                        )
                        IconButton(
                            onClick = {
                                if (chatInput.isNotBlank()) {
                                    onSendChat(chatInput)
                                    chatInput = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("ai_chat_send_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                        }
                    }
                }
            }
        }
    }
}
