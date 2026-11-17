package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.ProjectAnalysisResult
import com.example.ui.components.KeyValueRow
import com.example.ui.components.SectionCard
import com.example.ui.theme.StatusSuccess

@Composable
fun UploadScreen(
    activeAnalysis: ProjectAnalysisResult?,
    isIngesting: Boolean,
    maxUploadSizeMb: Int,
    formatBytes: (Long) -> String,
    onZipSelected: (android.net.Uri) -> Unit,
    onFilesSelected: (List<android.net.Uri>) -> Unit,
    onFolderSelected: (android.net.Uri) -> Unit,
    onProceedToAnalysis: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(onZipSelected)
    }

    val multiFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onFilesSelected(uris)
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let(onFolderSelected)
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
                    Text(
                        text = "Import Source Project",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Uses Android Storage Access Framework (zero broad storage permissions). Max upload size: $maxUploadSizeMb MB.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        },
                        enabled = !isIngesting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("upload_zip_button")
                    ) {
                        Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Upload ZIP Archive (.zip)")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { multiFileLauncher.launch(arrayOf("*/*")) },
                            enabled = !isIngesting,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("upload_files_button")
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Source Files")
                        }

                        OutlinedButton(
                            onClick = { folderLauncher.launch(null) },
                            enabled = !isIngesting,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("upload_folder_button")
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Project Folder")
                        }
                    }

                    if (isIngesting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Verifying ZIP paths, computing SHA-256 checksum, and extracting safely...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (activeAnalysis != null) {
                item {
                    SectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Uploaded Project Details",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Security scan",
                                    tint = StatusSuccess,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (activeAnalysis.zipSecurityPassed) "Safe Verified" else "Blocked",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StatusSuccess
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        KeyValueRow("Project Name", activeAnalysis.projectName)
                        KeyValueRow("Source Type", activeAnalysis.sourceType)
                        KeyValueRow("Total Files", "${activeAnalysis.totalFiles} files")
                        KeyValueRow("Size", formatBytes(activeAnalysis.totalSizeBytes))
                        KeyValueRow("SHA-256", activeAnalysis.sha256Checksum.take(24) + "...", monoValue = true)

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onProceedToAnalysis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("proceed_to_analysis_button")
                        ) {
                            Text("Inspect Project Analysis")
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }

                item {
                    SectionCard {
                        Text(
                            text = "Extracted File Tree (${activeAnalysis.files.size} entries)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val previewFiles = activeAnalysis.files.take(40)
                        previewFiles.forEachIndexed { idx, entry ->
                            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = entry.relativePath,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!entry.isDirectory) {
                                    Text(
                                        text = formatBytes(entry.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (activeAnalysis.files.size > 40) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "+ ${activeAnalysis.files.size - 40} more entries",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
