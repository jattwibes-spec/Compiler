package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.BuildEngineMode
import com.example.data.local.CompilerSettings
import com.example.domain.BuildConfiguration
import com.example.domain.BuildMode
import com.example.domain.OutputFormat
import com.example.domain.ProjectAnalysisResult
import com.example.ui.components.SectionCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildConfigScreen(
    analysis: ProjectAnalysisResult?,
    config: BuildConfiguration,
    settings: CompilerSettings,
    onSaveGithubQuick: (String, String) -> Unit,
    onUpdateDownloadFolder: (String, String) -> Unit,
    isStartingBuild: Boolean,
    onUpdateConfig: ((BuildConfiguration) -> BuildConfiguration) -> Unit,
    onStartBuild: () -> Unit,
    onGoToUpload: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (analysis == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("Upload a project first to configure its build.", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onGoToUpload) { Text("Upload Project") }
            }
        }
        return
    }

    val context = LocalContext.current
    var unsupportedNotice by remember { mutableStateOf<String?>(null) }
    var ghRepoInput by remember(settings.githubRepo) { mutableStateOf(settings.githubRepo) }
    var ghTokenInput by remember(settings.githubToken) { mutableStateOf(settings.githubToken) }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            val label = doc?.name ?: treeUri.lastPathSegment ?: "Selected folder"
            onUpdateDownloadFolder(treeUri.toString(), label)
        }
    }

    val iconPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onUpdateConfig { it.copy(customIconUri = uri.toString()) }
        }
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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Application Identity",
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedButton(
                            onClick = {
                                val pkg = analysis.projectName.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "app" }
                                onUpdateConfig {
                                    it.copy(
                                        appName = analysis.projectName.take(30),
                                        packageName = "com.wibes.$pkg",
                                        versionName = "1.0.0",
                                        versionCode = 1,
                                        minSdk = 26,
                                        targetSdk = 35
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Auto Defaults")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = config.appName,
                        onValueChange = { val v = it; onUpdateConfig { c -> c.copy(appName = v) } },
                        label = { Text("Application Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("config_app_name_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = config.packageName,
                        onValueChange = { val v = it; onUpdateConfig { c -> c.copy(packageName = v) } },
                        label = { Text("Package Name (Reverse DNS)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("config_package_name_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = config.versionName,
                            onValueChange = { val v = it; onUpdateConfig { c -> c.copy(versionName = v) } },
                            label = { Text("Version Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = config.versionCode.toString(),
                            onValueChange = { raw ->
                                val parsed = raw.filter { it.isDigit() }.toIntOrNull() ?: 1
                                onUpdateConfig { c -> c.copy(versionCode = parsed.coerceAtLeast(1)) }
                            },
                            label = { Text("Version Code") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // App Icon Selection using Android Photo Picker
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (config.customIconUri != null) {
                                AsyncImage(
                                    model = config.customIconUri,
                                    contentDescription = "Selected application icon",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.wibes_compiler_icon_1794885768278),
                                    contentDescription = "Default application icon",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            }
                            Column {
                                Text("Application Icon", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = if (config.customIconUri != null) "Custom icon selected" else "Default compiler icon",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                iconPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("config_pick_icon_button")
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Choose Icon")
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (settings.buildEngineMode == BuildEngineMode.GITHUB_ACTIONS) {
                                "GitHub Cloud & Auto-Download"
                            } else {
                                "Build Server & Auto-Download"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (settings.buildEngineMode == BuildEngineMode.GITHUB_ACTIONS) {
                        OutlinedTextField(
                            value = ghRepoInput,
                            onValueChange = {
                                ghRepoInput = it
                                onSaveGithubQuick(it, ghTokenInput)
                            },
                            label = { Text("GitHub Repository (owner/repo or URL)") },
                            placeholder = { Text("https://github.com/user/repo") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ghTokenInput,
                            onValueChange = {
                                ghTokenInput = it
                                onSaveGithubQuick(ghRepoInput, it)
                            },
                            label = { Text("GitHub Access Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    } else {
                        Text(
                            text = "FastAPI Server: ${settings.serverBaseUrl}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Download Folder", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = if (settings.downloadFolderUri.isNotBlank()) {
                                    settings.downloadFolderLabel.ifBlank { "Custom folder selected" }
                                } else {
                                    "Downloads/WibesCompilerAI"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedButton(onClick = { folderPicker.launch(null) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Change")
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text(
                        text = "SDK & Output Target",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = config.minSdk.toString(),
                            onValueChange = { raw ->
                                val parsed = raw.filter { it.isDigit() }.toIntOrNull() ?: 26
                                onUpdateConfig { c -> c.copy(minSdk = parsed.coerceIn(21, 36)) }
                            },
                            label = { Text("Min Android SDK") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = config.targetSdk.toString(),
                            onValueChange = { raw ->
                                val parsed = raw.filter { it.isDigit() }.toIntOrNull() ?: 35
                                onUpdateConfig { c -> c.copy(targetSdk = parsed.coerceIn(26, 36)) }
                            },
                            label = { Text("Target Android SDK") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Output Format (${analysis.detectedLanguage.displayName})",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutputFormat.entries.forEach { format ->
                            val supported = format in analysis.compatibleOutputs
                            FilterChip(
                                selected = config.outputFormat == format,
                                onClick = {
                                    if (supported) {
                                        unsupportedNotice = null
                                        onUpdateConfig { it.copy(outputFormat = format) }
                                    } else {
                                        unsupportedNotice = analysis.unsupportedExplanations[format]
                                            ?: "${format.displayName} is unsupported for ${analysis.detectedLanguage.displayName}."
                                    }
                                },
                                label = {
                                    Text(
                                        text = if (supported) format.id else "${format.id} (N/A)",
                                        color = if (supported) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    )
                                },
                                modifier = Modifier.testTag("output_format_${format.id.lowercase()}")
                            )
                        }
                    }

                    if (unsupportedNotice != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = unsupportedNotice!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Build Mode",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        BuildMode.entries.forEachIndexed { idx, mode ->
                            SegmentedButton(
                                selected = config.buildMode == mode,
                                onClick = { onUpdateConfig { it.copy(buildMode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = BuildMode.entries.size),
                                modifier = Modifier.testTag("build_mode_${mode.id}")
                            ) {
                                Text(mode.label)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onStartBuild,
                        enabled = !isStartingBuild && config.appName.isNotBlank() && config.packageName.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("start_compilation_button")
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isStartingBuild) "Uploading & Starting Build..." else if (settings.buildEngineMode == BuildEngineMode.GITHUB_ACTIONS) "Compile via GitHub Cloud" else "Start Isolated Compilation",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
