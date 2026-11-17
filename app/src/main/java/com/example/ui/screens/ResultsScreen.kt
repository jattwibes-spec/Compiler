package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.data.local.BuildJobEntity
import com.example.ui.components.KeyValueRow
import com.example.ui.components.SectionCard
import com.example.ui.theme.StatusSuccess
import java.io.File

@Composable
fun ResultsScreen(
    buildJob: BuildJobEntity?,
    formatBytes: (Long) -> String,
    onBuildAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var exportMessage by remember { mutableStateOf<String?>(null) }

    val artifactFile = remember(buildJob?.outputFilePath) {
        buildJob?.outputFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri ->
        if (destUri != null && artifactFile != null) {
            runCatching {
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    artifactFile.inputStream().use { inp -> inp.copyTo(out) }
                }
                exportMessage = "Saved ${artifactFile.name} to selected storage location."
            }.onFailure { err ->
                exportMessage = "Export failed: ${err.localizedMessage}"
            }
        }
    }

    if (buildJob == null || artifactFile == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "No Verified Compiled Artifact",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Output files appear here only after the remote build engine finishes and the downloaded artifact is verified on disk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(onClick = onBuildAgain) {
                    Text("Configure a Build")
                }
            }
        }
        return
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
            item {
                SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified artifact",
                            tint = StatusSuccess,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = "Build Artifact Verified",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = artifactFile.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    KeyValueRow("Application Name", buildJob.appName)
                    KeyValueRow("Package Name", buildJob.packageName, monoValue = true)
                    KeyValueRow("Output Format", buildJob.outputFormatId)
                    KeyValueRow("File Size", formatBytes(artifactFile.length()))
                    KeyValueRow("Build Duration", "${(buildJob.durationMillis / 1000.0).let { "%.1f".format(it) }}s")
                    buildJob.outputSha256?.let {
                        KeyValueRow("SHA-256", it.take(24) + "...", monoValue = true)
                    }

                    if (exportMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = exportMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusSuccess
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { saveDocumentLauncher.launch(artifactFile.name) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_artifact_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download / Save ${buildJob.outputFormatId}")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val contentUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    artifactFile
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, contentUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share ${artifactFile.name}"))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("share_artifact_button")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share Output")
                        }

                        OutlinedButton(
                            onClick = onBuildAgain,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("build_again_button")
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Build Again")
                        }
                    }
                }
            }
        }
    }
}
