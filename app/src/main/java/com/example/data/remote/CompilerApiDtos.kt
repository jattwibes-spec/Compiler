package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class HealthResponseDto(
    val status: String,
    @Json(name = "docker_available") val dockerAvailable: Boolean = false,
    @Json(name = "active_workers") val activeWorkers: Int = 0,
    val version: String = "1.0.0"
)

@JsonClass(generateAdapter = true)
data class AuthRequestDto(
    val username: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class AuthTokenResponseDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String,
    val username: String
)

@JsonClass(generateAdapter = true)
data class UploadProjectResponseDto(
    @Json(name = "project_id") val projectId: String,
    val name: String,
    @Json(name = "size_bytes") val sizeBytes: Long,
    @Json(name = "sha256") val sha256: String,
    @Json(name = "file_count") val fileCount: Int,
    @Json(name = "security_passed") val securityPassed: Boolean
)

@JsonClass(generateAdapter = true)
data class AnalyzeRequestDto(
    @Json(name = "project_id") val projectId: String
)

@JsonClass(generateAdapter = true)
data class AnalyzeProjectResponseDto(
    @Json(name = "project_id") val projectId: String,
    val language: String,
    val framework: String,
    @Json(name = "entry_point") val entryPoint: String?,
    @Json(name = "dependency_files") val dependencyFiles: List<String>,
    @Json(name = "missing_configs") val missingConfigs: List<String>,
    @Json(name = "compatible_outputs") val compatibleOutputs: List<String>,
    @Json(name = "unsupported_reasons") val unsupportedReasons: Map<String, String> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class StartBuildRequestDto(
    @Json(name = "project_id") val projectId: String,
    @Json(name = "app_name") val appName: String,
    @Json(name = "package_name") val packageName: String,
    @Json(name = "version_name") val versionName: String,
    @Json(name = "version_code") val versionCode: Int,
    @Json(name = "min_sdk") val minSdk: Int,
    @Json(name = "target_sdk") val targetSdk: Int,
    @Json(name = "output_format") val outputFormat: String,
    @Json(name = "build_mode") val buildMode: String
)

@JsonClass(generateAdapter = true)
data class StartBuildResponseDto(
    @Json(name = "build_id") val buildId: String,
    val stage: String,
    val message: String
)

@JsonClass(generateAdapter = true)
data class BuildStatusResponseDto(
    @Json(name = "build_id") val buildId: String,
    @Json(name = "project_id") val projectId: String,
    val stage: String,
    @Json(name = "progress_percent") val progressPercent: Int,
    @Json(name = "dependency_status") val dependencyStatus: String,
    @Json(name = "error_message") val errorMessage: String?,
    @Json(name = "artifact_ready") val artifactReady: Boolean,
    @Json(name = "artifact_filename") val artifactFilename: String?,
    @Json(name = "artifact_size_bytes") val artifactSizeBytes: Long = 0L,
    @Json(name = "artifact_sha256") val artifactSha256: String? = null,
    @Json(name = "duration_seconds") val durationSeconds: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class BuildLogsResponseDto(
    @Json(name = "build_id") val buildId: String,
    val logs: List<String>,
    val stage: String
)

@JsonClass(generateAdapter = true)
data class CancelBuildResponseDto(
    @Json(name = "build_id") val buildId: String,
    val cancelled: Boolean,
    val message: String
)

@JsonClass(generateAdapter = true)
data class ProjectHistoryItemDto(
    @Json(name = "project_id") val projectId: String,
    val name: String,
    val language: String,
    val framework: String,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "latest_build_id") val latestBuildId: String?,
    @Json(name = "latest_build_status") val latestBuildStatus: String?
)

@JsonClass(generateAdapter = true)
data class DeleteProjectResponseDto(
    @Json(name = "project_id") val projectId: String,
    val deleted: Boolean
)
