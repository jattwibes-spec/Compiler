package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.data.local.SettingsDataStore
import com.example.domain.AiDiagnosisResult
import com.example.domain.ProjectAnalysisResult
import com.example.domain.SupportedLanguage
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class CompilerAiRepository(
    private val context: Context,
    private val settingsStore: SettingsDataStore
) {

    companion object {
        private const val TAG = "CompilerAiRepository"
        private const val MODEL_NAME = "gemini-2.5-flash"
        private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun isFirebaseInitialized(): Boolean = runCatching {
        FirebaseApp.getApps(context).isNotEmpty()
    }.getOrDefault(false)

    private val diagnosisSchema = Schema.obj(
        properties = mapOf(
            "summary" to Schema.string(description = "Concise technical summary of findings"),
            "structureOverview" to Schema.string(description = "Explanation of project structure and entry point"),
            "detectedErrors" to Schema.array(
                items = Schema.string(),
                description = "List of detected code or configuration errors"
            ),
            "missingDependencies" to Schema.array(
                items = Schema.string(),
                description = "Missing packages or SDK dependencies"
            ),
            "suggestedFrameworks" to Schema.array(
                items = Schema.string(),
                description = "Recommended compatible frameworks or build tools"
            ),
            "recommendedConfigFile" to Schema.string(
                description = "Relative path of a missing build config file to generate (e.g. buildozer.spec, requirements.txt, CMakeLists.txt)",
                nullable = true
            ),
            "recommendedConfigContent" to Schema.string(
                description = "Complete ready-to-save content for recommendedConfigFile",
                nullable = true
            ),
            "targetPatchFile" to Schema.string(
                description = "Relative path of the source file needing a fix",
                nullable = true
            ),
            "patchedFileContent" to Schema.string(
                description = "Complete corrected content for targetPatchFile",
                nullable = true
            )
        ),
        optionalProperties = listOf(
            "recommendedConfigFile",
            "recommendedConfigContent",
            "targetPatchFile",
            "patchedFileContent"
        )
    )

    private val structuredModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = diagnosisSchema
            }
        )
    }

    private val chatModel by lazy {
        Firebase.ai.generativeModel(modelName = MODEL_NAME)
    }

    suspend fun analyzeProjectAndSuggestFixes(
        analysis: ProjectAnalysisResult,
        sourceContext: String,
        compilerErrorLogs: String? = null
    ): AiDiagnosisResult {
        val prompt = buildDiagnosisPrompt(analysis, sourceContext, compilerErrorLogs)
        val apiKey = settingsStore.settingsFlow.first().geminiApiKey.trim()

        if (apiKey.isNotBlank()) {
            return try {
                val rawJson = callGeminiRestStructured(apiKey, prompt)
                parseDiagnosisJson(rawJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Direct Gemini API analysis failed", e)
                throw IllegalStateException(
                    e.localizedMessage ?: "Gemini API request failed.",
                    e
                )
            }
        }

        if (isFirebaseInitialized()) {
            return try {
                val response = withTimeout(60_000L) {
                    structuredModel.generateContent(prompt)
                }
                val rawJson = response.text
                    ?: throw IllegalStateException("Gemini returned an empty analysis response.")
                parseDiagnosisJson(rawJson)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "AI analysis timed out", e)
                throw IllegalStateException("AI analysis timed out after 60 seconds. Please retry.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI analysis failed", e)
                throw IllegalStateException(
                    e.localizedMessage ?: "Firebase AI Logic request failed.",
                    e
                )
            }
        }

        return buildOfflineDiagnosis(analysis, sourceContext, compilerErrorLogs)
    }

    suspend fun generateMissingConfigFile(
        analysis: ProjectAnalysisResult,
        missingConfigName: String,
        sourceContext: String
    ): Pair<String, String> {
        val cleanTarget = missingConfigName.substringBefore(" ").trim()
        val prompt = """
            Generate a clean, production-ready configuration file for "$cleanTarget".
            Project Name: ${analysis.projectName}
            Language: ${analysis.detectedLanguage.displayName}
            Framework: ${analysis.framework}
            Entry Point: ${analysis.entryPoint ?: "unknown"}

            Source Context:
            $sourceContext

            Return strict JSON with keys:
            summary, structureOverview, detectedErrors (array), missingDependencies (array), suggestedFrameworks (array), recommendedConfigFile ("$cleanTarget"), recommendedConfigContent (exact file contents without markdown fences), targetPatchFile (null), patchedFileContent (null).
        """.trimIndent()

        val apiKey = settingsStore.settingsFlow.first().geminiApiKey.trim()
        if (apiKey.isNotBlank()) {
            val rawJson = callGeminiRestStructured(apiKey, prompt)
            val result = parseDiagnosisJson(rawJson)
            val path = result.recommendedConfigFile?.takeIf { it.isNotBlank() } ?: cleanTarget
            val content = result.recommendedConfigContent?.takeIf { it.isNotBlank() }
                ?: generateStarterConfigContent(cleanTarget, analysis)
            return path to content
        }

        if (isFirebaseInitialized()) {
            val result = try {
                val response = withTimeout(60_000L) {
                    structuredModel.generateContent(prompt)
                }
                val rawJson = response.text ?: throw IllegalStateException("Empty AI response.")
                parseDiagnosisJson(rawJson)
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("Config generation timed out after 60 seconds.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Config generation failed", e)
                throw IllegalStateException(e.localizedMessage ?: "Config generation failed.", e)
            }

            val path = result.recommendedConfigFile?.takeIf { it.isNotBlank() } ?: cleanTarget
            val content = result.recommendedConfigContent?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("AI did not return configuration content for $cleanTarget.")
            return path to content
        }

        return cleanTarget to generateStarterConfigContent(cleanTarget, analysis)
    }

    fun streamDeveloperAssistant(
        userMessage: String,
        analysis: ProjectAnalysisResult?,
        sourceContext: String,
        latestLogs: String?
    ): Flow<String> = flow {
        val apiKey = settingsStore.settingsFlow.first().geminiApiKey.trim()
        val fullPrompt = buildChatPrompt(userMessage, analysis, sourceContext, latestLogs)

        if (apiKey.isNotBlank()) {
            streamGeminiRestChat(apiKey, fullPrompt).collect { emit(it) }
            return@flow
        }

        if (isFirebaseInitialized()) {
            val accumulated = StringBuilder()
            chatModel.generateContentStream(fullPrompt)
                .map { chunk ->
                    accumulated.append(chunk.text.orEmpty())
                    accumulated.toString()
                }
                .collect { emit(it) }
            return@flow
        }

        emit(buildOfflineChatReply(userMessage, analysis, latestLogs))
    }.catch { e ->
        if (e is CancellationException) throw e
        Log.e(TAG, "AI chat failed", e)
        throw IllegalStateException(e.localizedMessage ?: "AI request failed.", e)
    }.flowOn(Dispatchers.IO)

    private fun buildDiagnosisPrompt(
        analysis: ProjectAnalysisResult,
        sourceContext: String,
        compilerErrorLogs: String?
    ): String = buildString {
        appendLine("You are Wibes Compiler AI, a senior build systems and compiler diagnostics engineer.")
        appendLine("Analyze this uploaded software project and return strict JSON matching the schema.")
        appendLine("Project Name: ${analysis.projectName}")
        appendLine("Detected Language: ${analysis.detectedLanguage.displayName}")
        appendLine("Detected Framework: ${analysis.framework}")
        appendLine("Entry Point: ${analysis.entryPoint ?: "None detected"}")
        appendLine("Dependency Files: ${analysis.dependencyFiles.joinToString(", ").ifBlank { "None" }}")
        appendLine("Missing Configs: ${analysis.missingConfigs.joinToString("; ").ifBlank { "None" }}")
        if (!compilerErrorLogs.isNullOrBlank()) {
            appendLine()
            appendLine("COMPILER / BUILD ERROR LOGS TO DIAGNOSE AND FIX:")
            appendLine(compilerErrorLogs.take(4500))
        }
        appendLine()
        appendLine("SOURCE CONTEXT:")
        appendLine(sourceContext)
        appendLine()
        appendLine("Requirements:")
        appendLine("1. Explain the project structure clearly.")
        appendLine("2. Identify coding errors, syntax issues, or missing dependencies.")
        appendLine("3. If a critical build configuration file is missing (e.g., requirements.txt, buildozer.spec, package.json, pubspec.yaml, CMakeLists.txt), provide recommendedConfigFile and its complete recommendedConfigContent.")
        appendLine("4. If a source file in SOURCE CONTEXT has an error or caused the compiler failure, provide targetPatchFile (exact relative path from FILE TREE) and the full corrected patchedFileContent.")
    }

    private fun buildChatPrompt(
        userMessage: String,
        analysis: ProjectAnalysisResult?,
        sourceContext: String,
        latestLogs: String?
    ): String = buildString {
        appendLine("You are Wibes Compiler AI, an expert developer build assistant.")
        if (analysis != null) {
            appendLine("Active Project: ${analysis.projectName} (${analysis.detectedLanguage.displayName} / ${analysis.framework})")
            appendLine("Entry Point: ${analysis.entryPoint ?: "None"}")
        }
        if (!latestLogs.isNullOrBlank()) {
            appendLine("Latest Build Logs (tail):")
            appendLine(latestLogs.takeLast(2500))
        }
        if (sourceContext.isNotBlank()) {
            appendLine("Project Context:")
            appendLine(sourceContext.take(6000))
        }
        appendLine()
        appendLine("User Request: $userMessage")
    }

    private suspend fun callGeminiRestStructured(apiKey: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put(
                            "responseSchema",
                            JSONObject().apply {
                                put("type", "OBJECT")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        put("summary", JSONObject().put("type", "STRING"))
                                        put("structureOverview", JSONObject().put("type", "STRING"))
                                        put(
                                            "detectedErrors",
                                            JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))
                                        )
                                        put(
                                            "missingDependencies",
                                            JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))
                                        )
                                        put(
                                            "suggestedFrameworks",
                                            JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))
                                        )
                                        put("recommendedConfigFile", JSONObject().put("type", "STRING").put("nullable", true))
                                        put("recommendedConfigContent", JSONObject().put("type", "STRING").put("nullable", true))
                                        put("targetPatchFile", JSONObject().put("type", "STRING").put("nullable", true))
                                        put("patchedFileContent", JSONObject().put("type", "STRING").put("nullable", true))
                                    }
                                )
                                put(
                                    "required",
                                    JSONArray(
                                        listOf(
                                            "summary",
                                            "structureOverview",
                                            "detectedErrors",
                                            "missingDependencies",
                                            "suggestedFrameworks"
                                        )
                                    )
                                )
                            }
                        )
                    }
                )
            }

            val req = Request.Builder()
                .url("$GEMINI_API_BASE/$MODEL_NAME:generateContent?key=$apiKey")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseGeminiHttpError(resp.code, body))
                }
                extractCandidateText(body)
                    ?: throw IllegalStateException("Gemini API returned an empty response.")
            }
        }

    private fun streamGeminiRestChat(apiKey: String, prompt: String): Flow<String> = flow {
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
        }

        val req = Request.Builder()
            .url("$GEMINI_API_BASE/$MODEL_NAME:streamGenerateContent?alt=sse&key=$apiKey")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                throw IllegalStateException(parseGeminiHttpError(resp.code, errBody))
            }
            val stream = resp.body?.byteStream()
                ?: throw IllegalStateException("Empty Gemini streaming response.")
            val reader = BufferedReader(InputStreamReader(stream))
            val accumulated = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("data:")) {
                    val jsonPart = line.removePrefix("data:").trim()
                    if (jsonPart.isNotEmpty() && jsonPart != "[DONE]") {
                        val chunkText = extractCandidateText(jsonPart)
                        if (!chunkText.isNullOrEmpty()) {
                            accumulated.append(chunkText)
                            emit(accumulated.toString())
                        }
                    }
                }
            }
            if (accumulated.isEmpty()) {
                throw IllegalStateException("Gemini API returned an empty stream.")
            }
        }
    }

    private fun extractCandidateText(rawJson: String): String? {
        return runCatching {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val text = part.optString("text", "")
                if (text.isNotEmpty()) sb.append(text)
            }
            sb.toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseGeminiHttpError(code: Int, body: String): String {
        val apiMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return when (code) {
            400, 401, 403 -> "Gemini API authentication error ($code): ${apiMessage ?: "Check your Gemini API key in Settings."}"
            429 -> "Gemini API rate limit reached (429): ${apiMessage ?: "Wait a moment and try again."}"
            else -> "Gemini API error ($code): ${apiMessage ?: body.take(200)}"
        }
    }

    private fun buildOfflineDiagnosis(
        analysis: ProjectAnalysisResult,
        sourceContext: String,
        compilerErrorLogs: String?
    ): AiDiagnosisResult {
        val errors = mutableListOf<String>()
        val missingDeps = mutableListOf<String>()

        if (!compilerErrorLogs.isNullOrBlank()) {
            if (compilerErrorLogs.contains("10.0.2.2") || compilerErrorLogs.contains("Failed to connect", ignoreCase = true)) {
                errors += "Build server connection failed: 10.0.2.2 only works on the Android Emulator. On a physical phone, set your PC's LAN IP (e.g. http://192.168.x.x:8000), ADB reverse (http://127.0.0.1:8000), or a hosted HTTPS backend URL in Server Settings."
            }
            if (compilerErrorLogs.contains("401") || compilerErrorLogs.contains("Unauthorized", ignoreCase = true)) {
                errors += "Backend authentication failed (401). Sign in or register in the Server tab."
            }
            if (compilerErrorLogs.contains("No module named", ignoreCase = true)) {
                errors += "Python import error detected in build logs. Add missing packages to requirements.txt."
            }
        }

        if (analysis.entryPoint == null) {
            errors += "No standard entry point detected for ${analysis.detectedLanguage.displayName}. Verify main file naming (e.g. main.py, index.js, lib/main.dart, MainActivity.kt)."
        }

        analysis.missingConfigs.forEach { cfg ->
            missingDeps += cfg
        }

        if (sourceContext.contains("TODO", ignoreCase = true)) {
            errors += "Source files contain TODO markers that may need review before release."
        }

        val firstMissing = analysis.missingConfigs.firstOrNull()?.substringBefore(" ")?.trim()
        val recommendedContent = firstMissing?.let { generateStarterConfigContent(it, analysis) }

        return AiDiagnosisResult(
            summary = "Offline Local Diagnostics: Analyzed ${analysis.projectName} (${analysis.detectedLanguage.displayName} / ${analysis.framework}, ${analysis.totalFiles} files). Add a Gemini API Key above or in Server Settings to unlock cloud Gemini 2.5 Flash code repair.",
            structureOverview = "Detected toolchain: ${analysis.detectedLanguage.workerToolchain}. Entry point: ${analysis.entryPoint ?: "Not detected"}. Dependency manifests: ${analysis.dependencyFiles.joinToString(", ").ifBlank { "None" }}.",
            detectedErrors = errors,
            missingDependencies = missingDeps,
            suggestedFrameworks = listOf(analysis.framework, analysis.detectedLanguage.workerToolchain),
            recommendedConfigFile = firstMissing,
            recommendedConfigContent = recommendedContent,
            targetPatchFile = null,
            patchedFileContent = null
        )
    }

    private fun buildOfflineChatReply(
        userMessage: String,
        analysis: ProjectAnalysisResult?,
        latestLogs: String?
    ): String = buildString {
        val lower = userMessage.lowercase()
        appendLine("[Offline Local Assistant]")
        if ("10.0.2.2" in lower || "8080" in lower || "8000" in lower || "connect" in lower || (latestLogs?.contains("10.0.2.2") == true)) {
            appendLine("• Connection issue explanation: 10.0.2.2 is the Android Emulator loopback to your PC's localhost. It does NOT work on a physical Android phone.")
            appendLine("• How to connect a real phone: Start the /backend Docker server on your computer (`docker compose up --build`), then in the Server tab either:")
            appendLine("  1) Use your PC's Wi-Fi LAN IP (e.g. http://192.168.1.50:8000), OR")
            appendLine("  2) Plug in via USB with `adb reverse tcp:8000 tcp:8000` and set Server URL to `http://127.0.0.1:8000`, OR")
            appendLine("  3) Deploy /backend to a cloud host with HTTPS.")
            appendLine()
        }
        if (analysis != null) {
            appendLine("Active project: ${analysis.projectName} (${analysis.detectedLanguage.displayName} / ${analysis.framework})")
            appendLine("Entry point: ${analysis.entryPoint ?: "None detected"} • Files: ${analysis.totalFiles}")
            if (analysis.missingConfigs.isNotEmpty()) {
                appendLine("Missing configs detected: ${analysis.missingConfigs.joinToString(", ")}")
            }
            appendLine()
        }
        append("To enable full cloud Gemini 2.5 Flash reasoning and code patching, paste a Gemini API key from Google AI Studio into the Gemini AI Key box above or in the Server tab.")
    }

    private fun generateStarterConfigContent(
        configName: String,
        analysis: ProjectAnalysisResult
    ): String {
        val cleanName = configName.lowercase()
        val appTitle = analysis.projectName.ifBlank { "WibesApp" }
        val pkgName = appTitle.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "wibesapp" }
        return when {
            cleanName.endsWith("requirements.txt") -> buildString {
                appendLine("# Generated by Wibes Compiler AI")
                if (analysis.framework.contains("Kivy", ignoreCase = true)) {
                    appendLine("kivy>=2.3.0")
                } else if (analysis.framework.contains("FastAPI", ignoreCase = true)) {
                    appendLine("fastapi>=0.115.0")
                    appendLine("uvicorn>=0.32.0")
                } else {
                    appendLine("# Add your Python package dependencies below")
                }
            }
            cleanName.endsWith("buildozer.spec") -> """
                [app]
                title = $appTitle
                package.name = $pkgName
                package.domain = org.wibes
                source.dir = .
                source.include_exts = py,png,jpg,kv,atlas,json
                version = 1.0.0
                requirements = python3,kivy
                orientation = portrait
                fullscreen = 0
                android.archs = arm64-v8a,armeabi-v7a
                android.api = 35
                android.minapi = 24

                [buildozer]
                log_level = 2
            """.trimIndent()
            cleanName.endsWith("package.json") -> """
                {
                  "name": "$pkgName",
                  "version": "1.0.0",
                  "private": true,
                  "scripts": {
                    "build": "echo \"Build ready\"",
                    "start": "node ${analysis.entryPoint ?: "index.js"}"
                  },
                  "dependencies": {}
                }
            """.trimIndent()
            cleanName.endsWith("pubspec.yaml") -> """
                name: $pkgName
                description: Generated Flutter configuration
                publish_to: 'none'
                version: 1.0.0+1

                environment:
                  sdk: '>=3.4.0 <4.0.0'

                dependencies:
                  flutter:
                    sdk: flutter

                flutter:
                  uses-material-design: true
            """.trimIndent()
            cleanName.endsWith("cmakelists.txt") -> """
                cmake_minimum_required(VERSION 3.22)
                project($pkgName)
                set(CMAKE_CXX_STANDARD 17)
                add_executable($pkgName ${analysis.entryPoint ?: "main.cpp"})
            """.trimIndent()
            else -> """
                # Starter configuration for $configName
                # Project: $appTitle (${analysis.detectedLanguage.displayName})
                # Entry point: ${analysis.entryPoint ?: "unknown"}
            """.trimIndent()
        }
    }

    private fun parseDiagnosisJson(jsonString: String): AiDiagnosisResult {
        val cleaned = jsonString
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = JSONObject(cleaned)
        fun stringList(key: String): List<String> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
        }
        fun optNullableString(key: String): String? {
            if (!json.has(key) || json.isNull(key)) return null
            return json.optString(key).takeIf { it.isNotBlank() && it != "null" }
        }
        return AiDiagnosisResult(
            summary = json.optString("summary", "Analysis complete."),
            structureOverview = json.optString("structureOverview", ""),
            detectedErrors = stringList("detectedErrors"),
            missingDependencies = stringList("missingDependencies"),
            suggestedFrameworks = stringList("suggestedFrameworks"),
            recommendedConfigFile = optNullableString("recommendedConfigFile"),
            recommendedConfigContent = optNullableString("recommendedConfigContent"),
            targetPatchFile = optNullableString("targetPatchFile"),
            patchedFileContent = optNullableString("patchedFileContent")
        )
    }
}
