package com.example.data.ai

import android.util.Log
import com.example.domain.AiDiagnosisResult
import com.example.domain.ProjectAnalysisResult
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class CompilerAiRepository {

    companion object {
        private const val TAG = "CompilerAiRepository"
        private const val MODEL_NAME = "gemini-3.8-flash"
    }

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
        val prompt = buildString {
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
            
            Return JSON matching the schema where recommendedConfigFile is "$cleanTarget" and recommendedConfigContent holds the exact file contents without markdown fences.
        """.trimIndent()

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

    fun streamDeveloperAssistant(
        userMessage: String,
        analysis: ProjectAnalysisResult?,
        sourceContext: String,
        latestLogs: String?
    ): Flow<String> {
        val fullPrompt = buildString {
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

        val accumulated = StringBuilder()
        return chatModel.generateContentStream(fullPrompt)
            .map { chunk ->
                accumulated.append(chunk.text.orEmpty())
                accumulated.toString()
            }
            .catch { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Streaming AI chat failed", e)
                throw IllegalStateException(e.localizedMessage ?: "AI streaming failed.", e)
            }
    }

    private fun parseDiagnosisJson(jsonString: String): AiDiagnosisResult {
        val json = JSONObject(jsonString)
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
