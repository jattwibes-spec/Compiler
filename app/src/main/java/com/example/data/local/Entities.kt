package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val projectId: String,
    val remoteProjectId: String?,
    val name: String,
    val sourceType: String, // "ZIP", "FILES", "FOLDER"
    val workspacePath: String,
    val archivePath: String?,
    val detectedLanguageId: String,
    val framework: String,
    val entryPoint: String?,
    val dependencyFilesJson: String,
    val missingConfigsJson: String,
    val totalFiles: Int,
    val totalSizeBytes: Long,
    val sha256Checksum: String,
    val zipSecurityPassed: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(tableName = "build_jobs")
data class BuildJobEntity(
    @PrimaryKey val buildId: String,
    val projectId: String,
    val remoteProjectId: String?,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val targetSdk: Int,
    val outputFormatId: String,
    val buildModeId: String,
    val stageName: String,
    val progressPercent: Int,
    val dependencyStatus: String,
    val logsText: String,
    val errorMessage: String?,
    val outputFilePath: String?,
    val outputFileSizeBytes: Long,
    val outputSha256: String?,
    val durationMillis: Long,
    val startedAtMillis: Long,
    val completedAtMillis: Long?
)

@Entity(tableName = "file_backups")
data class FileBackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val relativePath: String,
    val backupFilePath: String,
    val reason: String,
    val createdAtMillis: Long
)
