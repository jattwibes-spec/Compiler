package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.R
import com.example.data.local.ArtifactAutoSaver
import com.example.data.local.BuildJobEntity
import com.example.data.local.DatabaseProvider
import com.example.data.local.SettingsDataStore
import com.example.data.remote.DynamicBackendClient
import com.example.data.remote.GitHubActionsClient
import com.example.domain.BuildStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class BuildMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_BUILD_ID = "build_id"
        private const val CHANNEL_ID = "wibes_build_status"

        fun enqueue(context: Context, buildId: String) {
            val request = OneTimeWorkRequestBuilder<BuildMonitorWorker>()
                .setInputData(workDataOf(KEY_BUILD_ID to buildId))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    "build_monitor_$buildId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }

        fun cancel(context: Context, buildId: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork("build_monitor_$buildId")
        }
    }

    override suspend fun doWork(): Result {
        val buildId = inputData.getString(KEY_BUILD_ID) ?: return Result.failure()
        val db = DatabaseProvider.getDatabase(applicationContext)
        val buildDao = db.buildJobDao()
        val settingsStore = SettingsDataStore(applicationContext)

        if (buildId.startsWith("gh_")) {
            return monitorGitHubBuild(buildId, buildDao, settingsStore)
        }

        val settings = settingsStore.settingsFlow.first()
        val api = DynamicBackendClient().createService(settings.serverBaseUrl, settings.authToken)

        while (!isStopped) {
            val existing = buildDao.getBuildById(buildId) ?: return Result.failure()
            if (BuildStage.fromWire(existing.stageName).isTerminal) {
                return Result.success()
            }

            try {
                val status = api.getBuildStatus(buildId)
                val logsResponse = runCatching { api.getBuildLogs(buildId) }.getOrNull()
                val stage = BuildStage.fromWire(status.stage)
                var joinedLogs = logsResponse?.logs?.joinToString("\n") ?: existing.logsText

                var savedFilePath: String? = existing.outputFilePath
                var savedFileSize: Long = existing.outputFileSizeBytes
                var savedSha256: String? = existing.outputSha256
                var finalStage = stage
                var finalError = status.errorMessage
                var autoSaveNote: String? = null

                if (stage == BuildStage.COMPLETED) {
                    if (!status.artifactReady) {
                        finalStage = BuildStage.FAILED
                        finalError = "Build reported completed, but backend reports artifact_ready=false."
                    } else {
                        val artifactDir = File(applicationContext.filesDir, "artifacts/$buildId").apply {
                            mkdirs()
                        }
                        val filename = status.artifactFilename?.ifBlank { "output_${buildId.take(6)}.bin" }
                            ?: "output_${buildId.take(6)}.bin"
                        val destFile = File(artifactDir, filename)
                        val downloadResp = api.downloadArtifact(buildId)
                        if (downloadResp.isSuccessful && downloadResp.body() != null) {
                            downloadResp.body()!!.byteStream().use { input ->
                                FileOutputStream(destFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (destFile.exists() && destFile.length() > 0L) {
                                savedFilePath = destFile.absolutePath
                                savedFileSize = destFile.length()
                                savedSha256 = computeSha256(destFile)
                                val latestSettings = settingsStore.settingsFlow.first()
                                ArtifactAutoSaver.saveToConfiguredFolder(
                                    applicationContext,
                                    destFile,
                                    latestSettings.downloadFolderUri
                                ).onSuccess { msg ->
                                    autoSaveNote = msg
                                    joinedLogs = "$joinedLogs\n[DOWNLOAD] $msg"
                                }.onFailure { err ->
                                    joinedLogs = "$joinedLogs\n[WARN] Auto-save to folder skipped: ${err.localizedMessage}"
                                }
                            } else {
                                finalStage = BuildStage.FAILED
                                finalError = "Downloaded artifact file is empty or missing on device."
                            }
                        } else {
                            finalStage = BuildStage.FAILED
                            finalError = "Failed to download compiled artifact (HTTP ${downloadResp.code()})."
                        }
                    }
                }

                val now = System.currentTimeMillis()
                val updated = existing.copy(
                    stageName = finalStage.name,
                    progressPercent = if (finalStage == BuildStage.COMPLETED) 100 else status.progressPercent,
                    dependencyStatus = status.dependencyStatus,
                    logsText = joinedLogs,
                    errorMessage = finalError,
                    outputFilePath = savedFilePath,
                    outputFileSizeBytes = savedFileSize,
                    outputSha256 = savedSha256,
                    durationMillis = if (finalStage.isTerminal) {
                        if (status.durationSeconds > 0.0) (status.durationSeconds * 1000).toLong()
                        else (now - existing.startedAtMillis).coerceAtLeast(0L)
                    } else {
                        (now - existing.startedAtMillis).coerceAtLeast(0L)
                    },
                    completedAtMillis = if (finalStage.isTerminal) now else null
                )
                buildDao.upsertBuild(updated)

                if (finalStage.isTerminal) {
                    postStatusNotification(
                        title = if (finalStage == BuildStage.COMPLETED) {
                            "${existing.appName} compiled successfully"
                        } else {
                            "${existing.appName} build ${finalStage.label.lowercase()}"
                        },
                        body = if (finalStage == BuildStage.COMPLETED) {
                            autoSaveNote ?: "Verified output ready (${existing.outputFormatId})"
                        } else {
                            finalError ?: "Open Compilation Logs for diagnostics."
                        }
                    )
                    return Result.success()
                }
            } catch (e: Exception) {
                if (isStopped) return Result.failure()
                val current = buildDao.getBuildById(buildId)
                if (current != null && !BuildStage.fromWire(current.stageName).isTerminal) {
                    buildDao.upsertBuild(
                        current.copy(
                            logsText = "${current.logsText}\n[WARN] Waiting for build backend (${e.localizedMessage ?: "connection error"})..."
                        )
                    )
                }
            }
            delay(2000L)
        }
        return Result.success()
    }

    private suspend fun monitorGitHubBuild(
        buildId: String,
        buildDao: com.example.data.local.BuildJobDao,
        settingsStore: SettingsDataStore
    ): Result {
        val gh = GitHubActionsClient(applicationContext)

        while (!isStopped) {
            val existing = buildDao.getBuildById(buildId) ?: return Result.failure()
            if (BuildStage.fromWire(existing.stageName).isTerminal) {
                return Result.success()
            }

            val settings = settingsStore.settingsFlow.first()
            val token = settings.githubToken.trim()
            if (token.isBlank()) {
                failJob(buildDao, existing, "GitHub access token was cleared before build finished.")
                return Result.failure()
            }

            try {
                // remoteProjectId format: "gh|<owner>/<repo>|<runId>"
                val meta = existing.remoteProjectId.orEmpty().split("|")
                val slug = meta.getOrNull(1)?.takeIf { it.contains("/") }
                    ?: gh.normalizeRepoSlug(settings.githubRepo).let { "${it.first}/${it.second}" }
                val owner = slug.substringBefore("/")
                val repo = slug.substringAfter("/")
                var runId = meta.getOrNull(2)?.toLongOrNull()

                if (runId == null || runId <= 0L) {
                    runId = gh.findRunIdForJob(owner, repo, token, buildId)
                    if (runId == null) {
                        buildDao.upsertBuild(
                            existing.copy(
                                stageName = BuildStage.QUEUED.name,
                                progressPercent = 8,
                                dependencyStatus = "Waiting for GitHub Actions to register run...",
                                logsText = "${existing.logsText.lineSequence().take(6).joinToString("\n")}\n[WAIT] Waiting for GitHub Actions runner assignment..."
                            )
                        )
                        delay(3500L)
                        continue
                    } else {
                        buildDao.upsertBuild(
                            existing.copy(remoteProjectId = "gh|$owner/$repo|$runId")
                        )
                    }
                }

                val snap = gh.pollRunSnapshot(owner, repo, token, runId)
                var finalStage = snap.stage
                var finalLogs = snap.logsText
                var finalError = snap.errorMessage
                var savedFilePath: String? = existing.outputFilePath
                var savedFileSize: Long = existing.outputFileSizeBytes
                var savedSha256: String? = existing.outputSha256
                var autoSaveNote: String? = null

                if (snap.stage == BuildStage.COMPLETED) {
                    val artifactDir = File(applicationContext.filesDir, "artifacts/$buildId").apply {
                        mkdirs()
                    }
                    val downloaded = gh.downloadCompiledArtifact(
                        owner = owner,
                        repo = repo,
                        token = token,
                        runId = runId,
                        jobId = buildId,
                        destDir = artifactDir
                    )
                    savedFilePath = downloaded.absolutePath
                    savedFileSize = downloaded.length()
                    savedSha256 = computeSha256(downloaded)

                    ArtifactAutoSaver.saveToConfiguredFolder(
                        applicationContext,
                        downloaded,
                        settings.downloadFolderUri
                    ).onSuccess { msg ->
                        autoSaveNote = msg
                        finalLogs = "$finalLogs\n[DOWNLOAD] $msg"
                    }.onFailure { err ->
                        finalLogs = "$finalLogs\n[WARN] Auto-save to folder skipped: ${err.localizedMessage}"
                    }
                }

                val now = System.currentTimeMillis()
                val updated = existing.copy(
                    remoteProjectId = "gh|$owner/$repo|$runId",
                    stageName = finalStage.name,
                    progressPercent = if (finalStage == BuildStage.COMPLETED) 100 else snap.progressPercent,
                    dependencyStatus = snap.dependencyStatus,
                    logsText = finalLogs,
                    errorMessage = finalError,
                    outputFilePath = savedFilePath,
                    outputFileSizeBytes = savedFileSize,
                    outputSha256 = savedSha256,
                    durationMillis = (now - existing.startedAtMillis).coerceAtLeast(0L),
                    completedAtMillis = if (finalStage.isTerminal) now else null
                )
                buildDao.upsertBuild(updated)

                if (finalStage.isTerminal) {
                    postStatusNotification(
                        title = if (finalStage == BuildStage.COMPLETED) {
                            "${existing.appName} APK ready"
                        } else {
                            "${existing.appName} build ${finalStage.label.lowercase()}"
                        },
                        body = if (finalStage == BuildStage.COMPLETED) {
                            autoSaveNote ?: "Compiled artifact downloaded (${existing.outputFormatId})"
                        } else {
                            finalError ?: "Open Compilation Logs for details."
                        }
                    )
                    return Result.success()
                }
            } catch (e: Exception) {
                if (isStopped) return Result.failure()
                val current = buildDao.getBuildById(buildId)
                if (current != null && !BuildStage.fromWire(current.stageName).isTerminal) {
                    buildDao.upsertBuild(
                        current.copy(
                            logsText = "${current.logsText}\n[WARN] GitHub polling retry (${e.localizedMessage ?: "network error"})..."
                        )
                    )
                }
            }
            delay(5000L)
        }
        return Result.success()
    }

    private suspend fun failJob(
        buildDao: com.example.data.local.BuildJobDao,
        existing: BuildJobEntity,
        reason: String
    ) {
        val now = System.currentTimeMillis()
        buildDao.upsertBuild(
            existing.copy(
                stageName = BuildStage.FAILED.name,
                progressPercent = 0,
                errorMessage = reason,
                logsText = "${existing.logsText}\n[ERROR] $reason",
                completedAtMillis = now,
                durationMillis = (now - existing.startedAtMillis).coerceAtLeast(0L)
            )
        )
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun postStatusNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Build Status",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(title.hashCode(), notification)
    }
}
