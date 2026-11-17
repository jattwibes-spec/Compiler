package com.example.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

interface CompilerApiService {
    @GET("/health")
    suspend fun checkHealth(): HealthResponseDto

    @POST("/api/auth/register")
    suspend fun register(@Body request: AuthRequestDto): AuthTokenResponseDto

    @POST("/api/auth/token")
    suspend fun login(@Body request: AuthRequestDto): AuthTokenResponseDto

    @Multipart
    @POST("/api/projects/upload")
    suspend fun uploadProject(
        @Part file: MultipartBody.Part,
        @Part("project_name") projectName: RequestBody
    ): UploadProjectResponseDto

    @POST("/api/projects/analyze")
    suspend fun analyzeProject(@Body request: AnalyzeRequestDto): AnalyzeProjectResponseDto

    @POST("/api/build/start")
    suspend fun startBuild(@Body request: StartBuildRequestDto): StartBuildResponseDto

    @GET("/api/build/{build_id}/status")
    suspend fun getBuildStatus(@Path("build_id") buildId: String): BuildStatusResponseDto

    @GET("/api/build/{build_id}/logs")
    suspend fun getBuildLogs(@Path("build_id") buildId: String): BuildLogsResponseDto

    @POST("/api/build/{build_id}/cancel")
    suspend fun cancelBuild(@Path("build_id") buildId: String): CancelBuildResponseDto

    @Streaming
    @GET("/api/build/{build_id}/download")
    suspend fun downloadArtifact(@Path("build_id") buildId: String): Response<ResponseBody>

    @GET("/api/projects/history")
    suspend fun getProjectHistory(): List<ProjectHistoryItemDto>

    @DELETE("/api/projects/{project_id}")
    suspend fun deleteProject(@Path("project_id") projectId: String): DeleteProjectResponseDto
}
