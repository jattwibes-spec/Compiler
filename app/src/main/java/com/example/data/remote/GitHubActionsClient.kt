package com.example.data.remote

import android.content.Context
import android.util.Base64
import android.util.Base64OutputStream
import com.example.domain.BuildConfiguration
import com.example.domain.BuildStage
import com.example.domain.SupportedLanguage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class GitHubRepoVerification(
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val isPrivate: Boolean
)

data class GitHubRunSnapshot(
    val runId: Long,
    val stage: BuildStage,
    val progressPercent: Int,
    val dependencyStatus: String,
    val logsText: String,
    val errorMessage: String?,
    val htmlUrl: String?
)

class GitHubActionsClient(private val context: Context) {

    companion object {
        private const val API_BASE = "https://api.github.com"
        private const val WORKFLOW_PATH = ".github/workflows/wibes-cloud-compiler.yml"
        private const val WORKFLOW_FILENAME = "wibes-cloud-compiler.yml"
        private const val WORKFLOW_MARKER = "# wibes-workflow-v1"
        private const val BUILD_BRANCH = "wibes-builds"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun normalizeRepoSlug(raw: String): Pair<String, String> {
        var cleaned = raw.trim()
            .removeSuffix("/")
            .removeSuffix(".git")
        if (cleaned.startsWith("git@github.com:")) {
            cleaned = cleaned.removePrefix("git@github.com:")
        } else if (cleaned.contains("github.com/")) {
            cleaned = cleaned.substringAfter("github.com/")
        }
        val parts = cleaned.split("/").filter { it.isNotBlank() }
        require(parts.size >= 2) {
            "Invalid GitHub repository. Enter 'owner/repo' or 'https://github.com/owner/repo'."
        }
        return parts[0] to parts[1]
    }

    fun verifyRepoAccess(repoInput: String, tokenInput: String): GitHubRepoVerification {
        val token = tokenInput.trim()
        require(token.isNotBlank()) { "GitHub personal access token is required." }
        val (owner, repo) = normalizeRepoSlug(repoInput)
        val json = getJson("$API_BASE/repos/$owner/$repo", token)
        val permissions = json.optJSONObject("permissions")
        if (permissions != null && !permissions.optBoolean("push", false)) {
            throw IllegalStateException("Token does not have push/write permission on $owner/$repo.")
        }
        return GitHubRepoVerification(
            owner = owner,
            repo = repo,
            defaultBranch = json.optString("default_branch", "main").ifBlank { "main" },
            isPrivate = json.optBoolean("private", false)
        )
    }

    fun submitBuild(
        repoInput: String,
        tokenInput: String,
        jobId: String,
        archiveFile: File,
        language: SupportedLanguage,
        config: BuildConfiguration
    ): Pair<String, Long?> {
        val token = tokenInput.trim()
        val info = verifyRepoAccess(repoInput, token)
        val owner = info.owner
        val repo = info.repo
        val defaultBranch = info.defaultBranch

        // 1. Ensure cloud compiler workflow exists on default branch
        ensureWorkflowFile(owner, repo, defaultBranch, token)

        // 2. Ensure dedicated wibes-builds branch exists
        ensureBranchExists(owner, repo, defaultBranch, BUILD_BRANCH, token)

        // 3. Upload source archive to wibes-builds/<jobId>/source.zip via streaming Base64
        val sourcePath = "wibes-builds/$jobId/source.zip"
        uploadFileStreaming(
            owner = owner,
            repo = repo,
            branch = BUILD_BRANCH,
            repoPath = sourcePath,
            file = archiveFile,
            commitMessage = "build: upload workspace $jobId",
            token = token
        )

        // 4. Dispatch workflow_dispatch on defaultBranch
        val inputs = JSONObject().apply {
            put("job_id", jobId)
            put("source_branch", BUILD_BRANCH)
            put("source_path", sourcePath)
            put("language", language.id)
            put("output_format", config.outputFormat.id)
            put("build_mode", config.buildMode.id.uppercase())
            put("app_name", config.appName.ifBlank { "WibesApp" })
            put("package_name", config.packageName.ifBlank { "com.wibes.app" })
            put("version_name", config.versionName.ifBlank { "1.0.0" })
            put("version_code", config.versionCode.toString())
            put("min_sdk", config.minSdk.toString())
            put("target_sdk", config.targetSdk.toString())
        }
        val dispatchPayload = JSONObject().apply {
            put("ref", defaultBranch)
            put("inputs", inputs)
        }
        postJson(
            "$API_BASE/repos/$owner/$repo/actions/workflows/$WORKFLOW_FILENAME/dispatches",
            token,
            dispatchPayload
        )

        val runId = runCatching {
            Thread.sleep(1800L)
            findRunIdForJob(owner, repo, token, jobId)
        }.getOrNull()

        return "$owner/$repo" to runId
    }

    fun findRunIdForJob(owner: String, repo: String, token: String, jobId: String): Long? {
        val json = getJson(
            "$API_BASE/repos/$owner/$repo/actions/workflows/$WORKFLOW_FILENAME/runs?event=workflow_dispatch&per_page=10",
            token
        )
        val runs = json.optJSONArray("workflow_runs") ?: return null
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val name = run.optString("name", "")
            val title = run.optString("display_title", "")
            if (name.contains(jobId) || title.contains(jobId)) {
                return run.optLong("id").takeIf { it > 0L }
            }
        }
        return null
    }

    fun pollRunSnapshot(owner: String, repo: String, token: String, runId: Long): GitHubRunSnapshot {
        val run = getJson("$API_BASE/repos/$owner/$repo/actions/runs/$runId", token)
        val status = run.optString("status", "queued")
        val conclusion = run.optString("conclusion", "")
        val htmlUrl = run.optString("html_url", "").takeIf { it.isNotBlank() }

        val jobsJson = runCatching {
            getJson("$API_BASE/repos/$owner/$repo/actions/runs/$runId/jobs", token)
        }.getOrNull()
        val jobs = jobsJson?.optJSONArray("jobs")
        val firstJob = if (jobs != null && jobs.length() > 0) jobs.optJSONObject(0) else null
        val jobId = firstJob?.optLong("id") ?: 0L
        val steps = firstJob?.optJSONArray("steps")

        val stepLogs = StringBuilder()
        stepLogs.appendLine("[CLOUD] GitHub Actions Run #$runId ($owner/$repo)")
        if (htmlUrl != null) stepLogs.appendLine("[CLOUD] URL: $htmlUrl")

        var completedSteps = 0
        val totalSteps = steps?.length() ?: 0
        var activeStepName = if (status == "queued") "Waiting for GitHub runner" else "Preparing build environment"

        if (steps != null) {
            for (i in 0 until steps.length()) {
                val s = steps.optJSONObject(i) ?: continue
                val sName = s.optString("name", "Step ${i + 1}")
                val sStatus = s.optString("status", "pending")
                val sConc = s.optString("conclusion", "")
                when (sStatus) {
                    "completed" -> {
                        completedSteps++
                        val tag = if (sConc == "success" || sConc == "skipped") "OK" else "FAIL"
                        stepLogs.appendLine("[$tag] $sName ($sConc)")
                    }
                    "in_progress" -> {
                        activeStepName = sName
                        stepLogs.appendLine("[RUN] $sName...")
                    }
                    else -> {
                        stepLogs.appendLine("[WAIT] $sName")
                    }
                }
            }
        }

        val (stage, progress, errorMsg) = when (status) {
            "queued", "waiting", "requested" -> Triple(BuildStage.QUEUED, 10, null)
            "in_progress" -> {
                val pct = if (totalSteps > 0) {
                    (15 + ((completedSteps.toFloat() / totalSteps) * 75f)).toInt().coerceIn(15, 92)
                } else 25
                val mappedStage = when {
                    activeStepName.contains("detect", ignoreCase = true) ||
                        activeStepName.contains("Checkout", ignoreCase = true) -> BuildStage.ANALYZING
                    activeStepName.contains("Upload", ignoreCase = true) ||
                        activeStepName.contains("Verify", ignoreCase = true) -> BuildStage.PACKAGING
                    else -> BuildStage.COMPILING
                }
                Triple(mappedStage, pct, null)
            }
            "completed" -> when (conclusion) {
                "success" -> Triple(BuildStage.COMPLETED, 95, null)
                "cancelled" -> Triple(BuildStage.CANCELLED, 0, "GitHub Actions build was cancelled.")
                else -> Triple(
                    BuildStage.FAILED,
                    0,
                    "GitHub Actions build failed ($conclusion). Open Logs or $htmlUrl"
                )
            }
            else -> Triple(BuildStage.COMPILING, 20, null)
        }

        var fullLogs = stepLogs.toString()
        if (stage == BuildStage.FAILED && jobId > 0L) {
            val tailLog = runCatching { fetchJobLogTail(owner, repo, token, jobId) }.getOrNull()
            if (!tailLog.isNullOrBlank()) {
                fullLogs = "$fullLogs\n--- Runner Output (tail) ---\n$tailLog"
            }
        }

        return GitHubRunSnapshot(
            runId = runId,
            stage = stage,
            progressPercent = progress,
            dependencyStatus = activeStepName,
            logsText = fullLogs,
            errorMessage = errorMsg,
            htmlUrl = htmlUrl
        )
    }

    fun downloadCompiledArtifact(
        owner: String,
        repo: String,
        token: String,
        runId: Long,
        jobId: String,
        destDir: File
    ): File {
        destDir.mkdirs()
        val json = getJson("$API_BASE/repos/$owner/$repo/actions/runs/$runId/artifacts", token)
        val artifacts = json.optJSONArray("artifacts")
            ?: throw IllegalStateException("No build artifacts returned by GitHub Actions.")
        var targetArtifactId: Long = -1L
        val expectedName = "wibes-output-$jobId"
        for (i in 0 until artifacts.length()) {
            val a = artifacts.optJSONObject(i) ?: continue
            if (a.optString("name") == expectedName || targetArtifactId == -1L) {
                targetArtifactId = a.optLong("id", -1L)
                if (a.optString("name") == expectedName) break
            }
        }
        if (targetArtifactId <= 0L) {
            throw IllegalStateException("Compiled artifact '$expectedName' not found in run #$runId.")
        }

        val zipFile = File(destDir, "artifact_bundle.zip")
        val req = Request.Builder()
            .url("$API_BASE/repos/$owner/$repo/actions/artifacts/$targetArtifactId/zip")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful || resp.body == null) {
                throw IllegalStateException("Failed to download artifact ZIP from GitHub (HTTP ${resp.code}).")
            }
            resp.body!!.byteStream().use { inp ->
                FileOutputStream(zipFile).use { out -> inp.copyTo(out) }
            }
        }

        var extractedPrimary: File? = null
        ZipInputStream(zipFile.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val cleanName = File(entry.name).name.ifBlank { "output.bin" }
                val outFile = File(destDir, cleanName)
                FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                if (extractedPrimary == null ||
                    cleanName.endsWith(".apk", ignoreCase = true) ||
                    cleanName.endsWith(".aab", ignoreCase = true)
                ) {
                    extractedPrimary = outFile
                }
            }
        }
        zipFile.delete()
        return extractedPrimary?.takeIf { it.exists() && it.length() > 0L }
            ?: throw IllegalStateException("Artifact archive contained no valid compiled output file.")
    }

    fun cancelRun(owner: String, repo: String, token: String, runId: Long) {
        postJson(
            "$API_BASE/repos/$owner/$repo/actions/runs/$runId/cancel",
            token,
            JSONObject()
        )
    }

    private fun ensureWorkflowFile(owner: String, repo: String, branch: String, token: String) {
        val yamlBytes = context.assets.open(WORKFLOW_FILENAME).use { it.readBytes() }
        val existing = runCatching {
            getJson("$API_BASE/repos/$owner/$repo/contents/$WORKFLOW_PATH?ref=$branch", token)
        }.getOrNull()

        val existingSha = existing?.optString("sha")?.takeIf { it.isNotBlank() }
        val existingContent = existing?.optString("content")
            ?.replace("\n", "")
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrNull() }

        if (existingContent != null && existingContent.contains(WORKFLOW_MARKER)) {
            return
        }

        val payload = JSONObject().apply {
            put("message", "ci: install Wibes Cloud Compiler workflow")
            put("content", Base64.encodeToString(yamlBytes, Base64.NO_WRAP))
            put("branch", branch)
            if (existingSha != null) put("sha", existingSha)
        }
        putJson("$API_BASE/repos/$owner/$repo/contents/$WORKFLOW_PATH", token, payload)
    }

    private fun ensureBranchExists(
        owner: String,
        repo: String,
        baseBranch: String,
        targetBranch: String,
        token: String
    ) {
        val exists = runCatching {
            getJson("$API_BASE/repos/$owner/$repo/git/ref/heads/$targetBranch", token)
        }.isSuccess
        if (exists) return

        val baseRef = getJson("$API_BASE/repos/$owner/$repo/git/ref/heads/$baseBranch", token)
        val baseSha = baseRef.getJSONObject("object").getString("sha")
        val payload = JSONObject().apply {
            put("ref", "refs/heads/$targetBranch")
            put("sha", baseSha)
        }
        postJson("$API_BASE/repos/$owner/$repo/git/refs", token, payload)
    }

    private fun uploadFileStreaming(
        owner: String,
        repo: String,
        branch: String,
        repoPath: String,
        file: File,
        commitMessage: String,
        token: String
    ) {
        val existingSha = runCatching {
            getJson("$API_BASE/repos/$owner/$repo/contents/$repoPath?ref=$branch", token)
                .optString("sha")
                .takeIf { it.isNotBlank() }
        }.getOrNull()

        val tempPayload = File(context.cacheDir, "gh_upload_${System.currentTimeMillis()}.json")
        try {
            FileOutputStream(tempPayload).use { fos ->
                val header = buildString {
                    append("{")
                    append("\"message\":")
                    append(JSONObject.quote(commitMessage))
                    append(",\"branch\":")
                    append(JSONObject.quote(branch))
                    if (existingSha != null) {
                        append(",\"sha\":")
                        append(JSONObject.quote(existingSha))
                    }
                    append(",\"content\":\"")
                }
                fos.write(header.toByteArray(Charsets.UTF_8))

                // Wrap fos so Base64OutputStream.close() flushes padding without closing fos
                val nonClosing = object : FilterOutputStream(fos) {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        out.write(b, off, len)
                    }
                    override fun close() {
                        out.flush()
                    }
                }
                Base64OutputStream(nonClosing, Base64.NO_WRAP).use { b64 ->
                    file.inputStream().use { inp ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            b64.write(buf, 0, n)
                        }
                    }
                }
                fos.write("\"}".toByteArray(Charsets.UTF_8))
                fos.flush()
            }

            val req = Request.Builder()
                .url("$API_BASE/repos/$owner/$repo/contents/$repoPath")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .put(tempPayload.asRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseGitHubError(resp.code, body))
                }
            }
        } finally {
            tempPayload.delete()
        }
    }

    private fun fetchJobLogTail(owner: String, repo: String, token: String, jobId: Long): String {
        val req = Request.Builder()
            .url("$API_BASE/repos/$owner/$repo/actions/jobs/$jobId/logs")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val txt = resp.body?.string().orEmpty()
            return if (txt.length > 4000) txt.takeLast(4000) else txt
        }
    }

    private fun getJson(url: String, token: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseGitHubError(resp.code, body))
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun postJson(url: String, token: String, payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseGitHubError(resp.code, body))
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun putJson(url: String, token: String, payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseGitHubError(resp.code, body))
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun parseGitHubError(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
        return when (code) {
            401 -> "GitHub authentication failed (401): check your Personal Access Token."
            403 -> "GitHub permission denied (403): ensure your token has 'repo' and 'workflow' scopes. ($msg)"
            404 -> "GitHub repository or workflow not found (404): verify owner/repo and token access. ($msg)"
            422 -> "GitHub rejected request (422): $msg"
            else -> "GitHub API error ($code): ${msg.ifBlank { body.take(200) }}"
        }
    }
}
