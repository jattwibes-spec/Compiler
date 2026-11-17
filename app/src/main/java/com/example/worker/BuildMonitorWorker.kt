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
import com.example.data.local.DatabaseProvider
import com.example.data.local.SettingsDataStore
import com.example.data.remote.DynamicBackendClient
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
        val settings = SettingsDataStore(applicationContext).settingsFlow.first()
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
                val joinedLogs = logsResponse?.logs?.joinToString("\n") ?: existing.logsText

                var savedFilePath: String? = existing.outputFilePath
                var savedFileSize: Long = existing.outputFileSizeBytes
                var savedSha256: String? = existing.outputSha256
                var finalStage = stage
                var finalError = status.errorMessage

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
                            "Verified output ready (${existing.outputFormatId})"
                        } else {
                            finalError ?: "Open Compilation Logs for diagnostics."
                        }
                    )
                    return Result.success()
                }
            } catch (e: Exception) {
                if (isStopped) return Result.failure()
                // Keep monitoring if transient network hiccup; surface message in Room
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
