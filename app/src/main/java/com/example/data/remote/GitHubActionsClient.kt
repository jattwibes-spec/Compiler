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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class GitHubAccountVerification(
    val login: String,
    val targetOwner: String,
    val isOrganization: Boolean,
    val scopes: List<String>
)

data class GitHubRepoVerification(
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val accountLogin: String = owner,
    val autoCreateMode: Boolean = false
)

data class GitHubPushResult(
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val repoHtmlUrl: String,
    val pushedFileCount: Int,
    val usedDirectFiles: Boolean
)

data class GitHubBuildSubmission(
    val slug: String,
    val accountLogin: String,
    val repoHtmlUrl: String,
    val runId: Long?,
    val pushedFileCount: Int
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
        private const val API_VERSION = "2022-11-28"
        private const val USER_AGENT = "WibesCompilerAI-Android"
        private const val WORKFLOW_PATH = ".github/workflows/wibes-cloud-compiler.yml"
        private const val WORKFLOW_FILENAME = "wibes-cloud-compiler.yml"
        private const val WORKFLOW_MARKER = "# wibes-workflow-v2"
        private const val BUILD_BRANCH = "wibes-builds"
        private const val MAX_DIRECT_TREE_FILES = 200
        private const val MAX_SINGLE_BLOB_BYTES = 25L * 1024L * 1024L
        private val SKIPPED_DIRS = setOf(
            ".git", "node_modules", ".gradle", "build", "__pycache__", ".dart_tool", ".idea"
        )
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun cleanToken(raw: String): String = raw
        .trim()
        .removePrefix("Bearer ")
        .removePrefix("bearer ")
        .removePrefix("token ")
        .trim()

    fun normalizeUsername(raw: String): String {
        var cleaned = raw.trim().removePrefix("@").trim('/')
        if (cleaned.contains("github.com/")) {
            cleaned = cleaned.substringAfter("github.com/").substringBefore("/")
        }
        return cleaned.trim()
    }

    fun normalizeRepoSlug(raw: String, defaultOwner: String = ""): Pair<String, String> {
        var cleaned = raw.trim()
            .removeSuffix("/")
            .removeSuffix(".git")
        if (cleaned.startsWith("git@github.com:")) {
            cleaned = cleaned.removePrefix("git@github.com:")
        } else if (cleaned.contains("github.com/")) {
            cleaned = cleaned.substringAfter("github.com/")
        }
        val parts = cleaned.split("/").filter { it.isNotBlank() }
        if (parts.size >= 2) {
            return parts[0] to parts[1]
        }
        val cleanDefault = normalizeUsername(defaultOwner)
        if (parts.size == 1 && cleanDefault.isNotBlank()) {
            return cleanDefault to parts[0]
        }
        throw IllegalArgumentException(
            "Invalid GitHub repository. Enter 'owner/repo', a GitHub URL, or set your GitHub Username."
        )
    }

    fun sanitizeRepoName(raw: String): String {
        val cleaned = raw.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.')
        return cleaned.ifBlank { "wibes-project" }.take(80)
    }

    fun verifyTokenAndAccount(
        tokenInput: String,
        usernameInput: String = "",
        allowExternalUser: Boolean = false
    ): GitHubAccountVerification {
        val token = cleanToken(tokenInput)
        require(token.isNotBlank()) { "GitHub Personal Access Token is required." }

        val enteredUser = normalizeUsername(usernameInput)
        val req = newRequestBuilder("$API_BASE/user", token).get().build()
        var authenticatedLogin = ""
        var scopes = emptyList<String>()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val scopeHeader = resp.header("X-OAuth-Scopes")

            if (resp.code == 401) {
                throw IllegalStateException(
                    "GitHub authentication failed (401): Personal Access Token is invalid, revoked, or expired."
                )
            }
            if (!resp.isSuccessful) {
                // Fine-grained PATs without 'Account: Read' permission may return 403 on /user
                if (resp.code == 403 && token.startsWith("github_pat_") && enteredUser.isNotBlank()) {
                    return GitHubAccountVerification(
                        login = enteredUser,
                        targetOwner = enteredUser,
                        isOrganization = false,
                        scopes = emptyList()
                    )
                }
                throw IllegalStateException(parseGitHubError(resp.code, body, "$API_BASE/user"))
            }

            if (scopeHeader != null) {
                scopes = scopeHeader.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if ("repo" !in scopes && "public_repo" !in scopes) {
                    throw IllegalStateException(
                        "GitHub token is missing the 'repo' scope. Enable both 'repo' and 'workflow' scopes on your token."
                    )
                }
                if ("workflow" !in scopes) {
                    throw IllegalStateException(
                        "GitHub token is missing the 'workflow' scope! Classic PATs require both 'repo' and 'workflow' scopes to push and run builds."
                    )
                }
            }

            val userJson = if (body.isBlank()) JSONObject() else JSONObject(body)
            authenticatedLogin = userJson.optString("login").trim()
            if (authenticatedLogin.isBlank()) {
                throw IllegalStateException("Could not determine GitHub account login from access token.")
            }
        }

        if (enteredUser.isBlank() || enteredUser.equals(authenticatedLogin, ignoreCase = true)) {
            return GitHubAccountVerification(
                login = authenticatedLogin,
                targetOwner = authenticatedLogin,
                isOrganization = false,
                scopes = scopes
            )
        }

        // Entered username differs from token login: check if it is an accessible organization
        val orgResult = runCatching { getJson("$API_BASE/orgs/$enteredUser", token) }.getOrNull()
        if (orgResult != null) {
            val orgLogin = orgResult.optString("login", enteredUser).ifBlank { enteredUser }
            return GitHubAccountVerification(
                login = authenticatedLogin,
                targetOwner = orgLogin,
                isOrganization = true,
                scopes = scopes
            )
        }

        if (allowExternalUser) {
            return GitHubAccountVerification(
                login = authenticatedLogin,
                targetOwner = enteredUser,
                isOrganization = false,
                scopes = scopes
            )
        }

        throw IllegalStateException(
            "GitHub username '$enteredUser' does not match your token account '$authenticatedLogin' (and is not an accessible organization). Enter '$authenticatedLogin' or leave blank."
        )
    }

    fun verifyRepoAccess(
        repoInput: String,
        tokenInput: String,
        defaultOwner: String = "",
        accountLogin: String = ""
    ): GitHubRepoVerification {
        val token = cleanToken(tokenInput)
        require(token.isNotBlank()) { "GitHub Personal Access Token is required." }
        val (owner, repo) = normalizeRepoSlug(repoInput, defaultOwner = defaultOwner)
        val url = "$API_BASE/repos/$owner/$repo"
        val json = try {
            getJson(url, token)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("404") == true) {
                throw IllegalStateException(
                    "Repository '$owner/$repo' was not found (404). Verify the owner/repo name, enable 'Auto-create new repo', or make sure your token has access if it is private."
                )
            }
            throw e
        }
        val permissions = json.optJSONObject("permissions")
        if (permissions != null && !permissions.optBoolean("push", false)) {
            throw IllegalStateException("Token does not have push/write permission on $owner/$repo.")
        }
        val actualOwner = json.optJSONObject("owner")?.optString("login")?.ifBlank { owner } ?: owner
        val actualRepo = json.optString("name", repo).ifBlank { repo }
        return GitHubRepoVerification(
            owner = actualOwner,
            repo = actualRepo,
            defaultBranch = json.optString("default_branch", "main").ifBlank { "main" },
            isPrivate = json.optBoolean("private", false),
            accountLogin = accountLogin.ifBlank { actualOwner },
            autoCreateMode = false
        )
    }

    fun verifyConfiguration(
        usernameInput: String,
        repoInput: String,
        tokenInput: String,
        autoCreateRepo: Boolean,
        isPrivateRepo: Boolean
    ): GitHubRepoVerification {
        val account = verifyTokenAndAccount(
            tokenInput = tokenInput,
            usernameInput = usernameInput,
            allowExternalUser = !autoCreateRepo
        )
        if (autoCreateRepo) {
            val previewName = if (repoInput.isNotBlank()) {
                val rawName = repoInput.trim()
                    .removeSuffix("/")
                    .removeSuffix(".git")
                    .substringAfterLast("/")
                sanitizeRepoName(rawName)
            } else {
                "auto-per-project"
            }
            return GitHubRepoVerification(
                owner = account.targetOwner,
                repo = previewName,
                defaultBranch = "main",
                isPrivate = isPrivateRepo,
                accountLogin = account.login,
                autoCreateMode = true
            )
        }
        if (repoInput.isBlank()) {
            throw IllegalArgumentException(
                "Enter a repository (e.g. '${account.targetOwner}/my-app' or 'my-app') or enable 'Auto-create new repo'."
            )
        }
        return verifyRepoAccess(
            repoInput = repoInput,
            tokenInput = tokenInput,
            defaultOwner = account.targetOwner,
            accountLogin = account.login
        )
    }

    fun createNewRepository(
        tokenInput: String,
        usernameInput: String,
        projectName: String,
        customRepoInput: String,
        isPrivate: Boolean
    ): GitHubRepoVerification {
        val token = cleanToken(tokenInput)
        val account = verifyTokenAndAccount(
            tokenInput = token,
            usernameInput = usernameInput,
            allowExternalUser = false
        )
        val owner = account.targetOwner

        val baseSlug = if (customRepoInput.isNotBlank()) {
            val rawName = customRepoInput.trim()
                .removeSuffix("/")
                .removeSuffix(".git")
                .substringAfterLast("/")
            sanitizeRepoName(rawName)
        } else {
            sanitizeRepoName(projectName)
        }

        // Check if repo already exists; if so, append short unique suffix so a fresh new repo is created
        val candidateExists = runCatching {
            getJson("$API_BASE/repos/$owner/$baseSlug", token)
        }.isSuccess
        val finalName = if (candidateExists) {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(4)
            "${baseSlug.take(70)}-$suffix"
        } else {
            baseSlug
        }

        val payload = JSONObject().apply {
            put("name", finalName)
            put("private", isPrivate)
            put("auto_init", true)
            put("description", "Uploaded and compiled with Wibes Compiler AI")
        }
        val createUrl = if (account.isOrganization) {
            "$API_BASE/orgs/$owner/repos"
        } else {
            "$API_BASE/user/repos"
        }
        val created = postJson(createUrl, token, payload)
        val actualOwner = created.optJSONObject("owner")?.optString("login")?.ifBlank { owner } ?: owner
        val actualRepo = created.optString("name", finalName).ifBlank { finalName }
        val defaultBranch = created.optString("default_branch", "main").ifBlank { "main" }

        // Wait until GitHub finishes initializing default branch commit
        var ready = false
        for (attempt in 0 until 10) {
            if (runCatching {
                    getJson("$API_BASE/repos/$actualOwner/$actualRepo/git/ref/heads/$defaultBranch", token)
                }.isSuccess
            ) {
                ready = true
                break
            }
            Thread.sleep(800L)
        }
        if (!ready) {
            throw IllegalStateException(
                "Created $actualOwner/$actualRepo, but initial branch '$defaultBranch' is still initializing. Please retry."
            )
        }

        return GitHubRepoVerification(
            owner = actualOwner,
            repo = actualRepo,
            defaultBranch = defaultBranch,
            isPrivate = isPrivate,
            accountLogin = account.login,
            autoCreateMode = true
        )
    }

    fun pushWorkspaceToNewRepo(
        tokenInput: String,
        usernameInput: String,
        projectName: String,
        customRepoInput: String,
        isPrivate: Boolean,
        workspaceDir: File,
        archiveFile: File
    ): GitHubPushResult {
        val token = cleanToken(tokenInput)
        val created = createNewRepository(
            tokenInput = token,
            usernameInput = usernameInput,
            projectName = projectName,
            customRepoInput = customRepoInput,
            isPrivate = isPrivate
        )
        return pushWorkspaceAndWorkflowToBranch(
            owner = created.owner,
            repo = created.repo,
            branch = created.defaultBranch,
            token = token,
            workspaceDir = workspaceDir,
            archiveFile = archiveFile
        )
    }

    fun submitBuild(
        repoInput: String,
        usernameInput: String,
        tokenInput: String,
        autoCreateRepo: Boolean,
        isPrivateRepo: Boolean,
        projectName: String,
        jobId: String,
        workspaceDir: File,
        archiveFile: File,
        language: SupportedLanguage,
        config: BuildConfiguration
    ): GitHubBuildSubmission {
        val token = cleanToken(tokenInput)
        require(token.isNotBlank()) { "GitHub Personal Access Token is required." }

        val account = verifyTokenAndAccount(
            tokenInput = token,
            usernameInput = usernameInput,
            allowExternalUser = !autoCreateRepo
        )

        val owner: String
        val repo: String
        val dispatchBranch: String
        val sourceBranch: String
        val sourcePath: String
        var pushedCount = 1

        if (autoCreateRepo || repoInput.isBlank()) {
            val pushed = pushWorkspaceToNewRepo(
                tokenInput = token,
                usernameInput = account.targetOwner,
                projectName = projectName,
                customRepoInput = repoInput,
                isPrivate = isPrivateRepo,
                workspaceDir = workspaceDir,
                archiveFile = archiveFile
            )
            owner = pushed.owner
            repo = pushed.repo
            dispatchBranch = pushed.defaultBranch
            sourceBranch = pushed.defaultBranch
            sourcePath = if (pushed.usedDirectFiles) "." else "wibes-archive/source.zip"
            pushedCount = pushed.pushedFileCount
        } else {
            val info = verifyRepoAccess(
                repoInput = repoInput,
                tokenInput = token,
                defaultOwner = account.targetOwner,
                accountLogin = account.login
            )
            owner = info.owner
            repo = info.repo
            dispatchBranch = info.defaultBranch
            ensureWorkflowFile(owner, repo, dispatchBranch, token)
            ensureBranchExists(owner, repo, dispatchBranch, BUILD_BRANCH, token)
            sourceBranch = BUILD_BRANCH
            sourcePath = "wibes-builds/$jobId/source.zip"
            uploadFileStreaming(
                owner = owner,
                repo = repo,
                branch = BUILD_BRANCH,
                repoPath = sourcePath,
                file = archiveFile,
                commitMessage = "build: upload workspace $jobId",
                token = token
            )
        }

        val inputs = JSONObject().apply {
            put("job_id", jobId)
            put("source_branch", sourceBranch)
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

        waitForWorkflowAndDispatch(owner, repo, dispatchBranch, token, inputs)

        var runId: Long? = null
        for (attempt in 0 until 3) {
            Thread.sleep(1500L)
            runId = runCatching { findRunIdForJob(owner, repo, token, jobId) }.getOrNull()
            if (runId != null) break
        }

        return GitHubBuildSubmission(
            slug = "$owner/$repo",
            accountLogin = account.login,
            repoHtmlUrl = "https://github.com/$owner/$repo",
            runId = runId,
            pushedFileCount = pushedCount
        )
    }

    private fun waitForWorkflowAndDispatch(
        owner: String,
        repo: String,
        branch: String,
        token: String,
        inputs: JSONObject
    ) {
        // GitHub Actions takes a few seconds to index a newly committed workflow YAML.
        var workflowIdOrFilename: String = WORKFLOW_FILENAME
        for (attempt in 0 until 10) {
            val workflowsResp = runCatching {
                getJson("$API_BASE/repos/$owner/$repo/actions/workflows", token)
            }.getOrNull()
            val arr = workflowsResp?.optJSONArray("workflows")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val wf = arr.optJSONObject(i) ?: continue
                    val path = wf.optString("path", "")
                    val state = wf.optString("state", "")
                    if (path == WORKFLOW_PATH || path.endsWith("/$WORKFLOW_FILENAME")) {
                        if (state == "disabled_manually" || state == "disabled_inactivity") {
                            throw IllegalStateException(
                                "GitHub Actions workflow is disabled on $owner/$repo ($state). Enable Actions in the repo settings."
                            )
                        }
                        val numericId = wf.optLong("id", 0L)
                        if (numericId > 0L) {
                            workflowIdOrFilename = numericId.toString()
                        }
                        break
                    }
                }
                if (workflowIdOrFilename != WORKFLOW_FILENAME) break
            }
            Thread.sleep(1500L)
        }

        val dispatchPayload = JSONObject().apply {
            put("ref", branch)
            put("inputs", inputs)
        }

        var lastError: Throwable? = null
        for (attempt in 0 until 8) {
            val url = "$API_BASE/repos/$owner/$repo/actions/workflows/$workflowIdOrFilename/dispatches"
            val req = newRequestBuilder(url, token)
                .post(dispatchPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    return
                }
                val retryable = resp.code == 404 ||
                    (resp.code == 422 && body.contains("workflow_dispatch", ignoreCase = true))
                if (!retryable) {
                    throw IllegalStateException(parseGitHubError(resp.code, body, url))
                }
                lastError = IllegalStateException(parseGitHubError(resp.code, body, url))
            }
            Thread.sleep(2000L)
        }

        throw (lastError ?: IllegalStateException(
            "GitHub Actions did not index $WORKFLOW_PATH on $owner/$repo in time. Please retry."
        ))
    }

    private fun pushWorkspaceAndWorkflowToBranch(
        owner: String,
        repo: String,
        branch: String,
        token: String,
        workspaceDir: File,
        archiveFile: File
    ): GitHubPushResult {
        val refJson = getJson("$API_BASE/repos/$owner/$repo/git/ref/heads/$branch", token)
        val baseCommitSha = refJson.getJSONObject("object").getString("sha")
        val commitJson = getJson("$API_BASE/repos/$owner/$repo/git/commits/$baseCommitSha", token)
        val baseTreeSha = commitJson.getJSONObject("tree").getString("sha")

        // Unwrap single top-level directory inside workspaceDir if present
        val effectiveRoot = unwrapSingleRootDir(workspaceDir)
        val collected = collectUploadableFiles(effectiveRoot)
        val useDirectFiles = collected.isNotEmpty() && collected.size <= MAX_DIRECT_TREE_FILES

        val treeItems = JSONArray()
        var pushedCount = 0

        if (useDirectFiles) {
            for ((relPath, file) in collected) {
                if (file.length() > MAX_SINGLE_BLOB_BYTES) continue
                val blobSha = createGitBlobStreaming(owner, repo, token, file)
                treeItems.put(
                    JSONObject().apply {
                        put("path", relPath)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", blobSha)
                    }
                )
                pushedCount++
            }
        }

        // Always push the uploaded source ZIP so the archive itself is preserved in the repo
        if (archiveFile.exists() && archiveFile.length() in 1L..MAX_SINGLE_BLOB_BYTES) {
            val zipBlobSha = createGitBlobStreaming(owner, repo, token, archiveFile)
            treeItems.put(
                JSONObject().apply {
                    put("path", "wibes-archive/source.zip")
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", zipBlobSha)
                }
            )
            pushedCount++
        }

        // Include workflow YAML in the same commit
        val yamlBytes = context.assets.open(WORKFLOW_FILENAME).use { it.readBytes() }
        val wfBlobSha = createGitBlobBytes(owner, repo, token, yamlBytes)
        treeItems.put(
            JSONObject().apply {
                put("path", WORKFLOW_PATH)
                put("mode", "100644")
                put("type", "blob")
                put("sha", wfBlobSha)
            }
        )

        val treePayload = JSONObject().apply {
            put("base_tree", baseTreeSha)
            put("tree", treeItems)
        }
        val newTreeSha = postJson("$API_BASE/repos/$owner/$repo/git/trees", token, treePayload)
            .getString("sha")

        val newCommitPayload = JSONObject().apply {
            put("message", "feat: push uploaded project files and ZIP via Wibes Compiler AI")
            put("tree", newTreeSha)
            put("parents", JSONArray().put(baseCommitSha))
        }
        val newCommitSha = postJson("$API_BASE/repos/$owner/$repo/git/commits", token, newCommitPayload)
            .getString("sha")

        patchJson(
            "$API_BASE/repos/$owner/$repo/git/refs/heads/$branch",
            token,
            JSONObject().apply {
                put("sha", newCommitSha)
                put("force", false)
            }
        )

        return GitHubPushResult(
            owner = owner,
            repo = repo,
            defaultBranch = branch,
            repoHtmlUrl = "https://github.com/$owner/$repo",
            pushedFileCount = pushedCount,
            usedDirectFiles = useDirectFiles
        )
    }

    private fun unwrapSingleRootDir(workspaceDir: File): File {
        var current = workspaceDir
        while (true) {
            val children = current.listFiles()?.filter { it.name != "__MACOSX" } ?: break
            if (children.size == 1 && children[0].isDirectory && children[0].name !in SKIPPED_DIRS) {
                current = children[0]
            } else {
                break
            }
        }
        return current
    }

    private fun collectUploadableFiles(root: File): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        fun walk(dir: File) {
            val entries = dir.listFiles() ?: return
            for (entry in entries) {
                if (entry.name in SKIPPED_DIRS || entry.name == "__MACOSX") continue
                if (entry.isDirectory) {
                    walk(entry)
                } else if (entry.isFile) {
                    val rel = entry.relativeTo(root).path.replace(File.separatorChar, '/')
                    if (rel.isNotBlank() && !rel.startsWith(".github/")) {
                        result += rel to entry
                    }
                }
            }
        }
        walk(root)
        return result
    }

    private fun createGitBlobBytes(owner: String, repo: String, token: String, bytes: ByteArray): String {
        val payload = JSONObject().apply {
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("encoding", "base64")
        }
        return postJson("$API_BASE/repos/$owner/$repo/git/blobs", token, payload).getString("sha")
    }

    private fun createGitBlobStreaming(owner: String, repo: String, token: String, file: File): String {
        val tempPayload = File(context.cacheDir, "gh_blob_${System.currentTimeMillis()}.json")
        try {
            FileOutputStream(tempPayload).use { fos ->
                fos.write("{\"encoding\":\"base64\",\"content\":\"".toByteArray(Charsets.UTF_8))
                val nonClosing = object : FilterOutputStream(fos) {
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                    override fun close() = out.flush()
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
            val url = "$API_BASE/repos/$owner/$repo/git/blobs"
            val req = newRequestBuilder(url, token)
                .post(tempPayload.asRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseGitHubError(resp.code, body, url))
                }
                return JSONObject(body).getString("sha")
            }
        } finally {
            tempPayload.delete()
        }
    }

    fun findRunIdForJob(owner: String, repo: String, tokenInput: String, jobId: String): Long? {
        val token = cleanToken(tokenInput)
        val json = runCatching {
            getJson(
                "$API_BASE/repos/$owner/$repo/actions/runs?event=workflow_dispatch&per_page=15",
                token
            )
        }.getOrNull() ?: return null
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

    fun pollRunSnapshot(owner: String, repo: String, tokenInput: String, runId: Long): GitHubRunSnapshot {
        val token = cleanToken(tokenInput)
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
        stepLogs.appendLine("[CLOUD] Repo: https://github.com/$owner/$repo")
        if (htmlUrl != null) stepLogs.appendLine("[CLOUD] Run URL: $htmlUrl")

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
                        activeStepName.contains("Checkout", ignoreCase = true) -> BuildStage.PROVISIONING_SANDBOX
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
        tokenInput: String,
        runId: Long,
        jobId: String,
        destDir: File
    ): File {
        val token = cleanToken(tokenInput)
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
        val url = "$API_BASE/repos/$owner/$repo/actions/artifacts/$targetArtifactId/zip"
        val req = newRequestBuilder(url, token).build()

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

    fun cancelRun(owner: String, repo: String, tokenInput: String, runId: Long) {
        val token = cleanToken(tokenInput)
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
                val nonClosing = object : FilterOutputStream(fos) {
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                    override fun close() = out.flush()
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

            val url = "$API_BASE/repos/$owner/$repo/contents/$repoPath"
            val req = newRequestBuilder(url, token)
                .put(tempPayload.asRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseGitHubError(resp.code, body, url))
                }
            }
        } finally {
            tempPayload.delete()
        }
    }

    private fun fetchJobLogTail(owner: String, repo: String, token: String, jobId: Long): String {
        val req = newRequestBuilder("$API_BASE/repos/$owner/$repo/actions/jobs/$jobId/logs", token).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val txt = resp.body?.string().orEmpty()
            return if (txt.length > 4000) txt.takeLast(4000) else txt
        }
    }

    private fun newRequestBuilder(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", USER_AGENT)

    private fun getJson(url: String, token: String): JSONObject {
        val req = newRequestBuilder(url, token).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseGitHubError(resp.code, body, url))
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun postJson(url: String, token: String, payload: JSONObject): JSONObject {
        val req = newRequestBuilder(url, token)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseGitHubError(resp.code, body, url))
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun putJson(url: String, token: String, payload: JSONObject): JSONObject {
        val req = newRequestBuilder(url, token)
            .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseGitHubError(resp.code, body, url))
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun patchJson(url: String, token: String, payload: JSONObject): JSONObject {
        val req = newRequestBuilder(url, token)
            .patch(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(parseGitHubError(resp.code, body, url))
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun parseGitHubError(code: Int, body: String, url: String = ""): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val msg = parsed?.optString("message").orEmpty()
        val extraErrors = parsed?.optJSONArray("errors")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                arr.optJSONObject(idx)?.optString("message")?.takeIf { it.isNotBlank() }
            }.joinToString("; ")
        }.orEmpty()
        val detail = listOf(msg, extraErrors).filter { it.isNotBlank() }.joinToString(" — ")

        return when (code) {
            401 -> "GitHub authentication failed (401): check your Personal Access Token. ($detail)"
            403 -> "GitHub permission denied (403): ensure your token has 'repo' and 'workflow' scopes. ($detail)"
            404 -> when {
                url.contains("/actions/workflows/") ->
                    "GitHub Actions workflow not found (404): ensure Actions is enabled on the repository and your token has the 'workflow' scope. ($detail)"
                url.contains("/orgs/") ->
                    "GitHub organization not found (404): verify the entered GitHub Username. ($detail)"
                else ->
                    "GitHub repository not found (404): check the GitHub Username / repository name, enable 'Auto-create new repo', or verify your token's 'repo' scope. ($detail)"
            }
            422 -> "GitHub rejected request (422): $detail"
            else -> "GitHub API error ($code): ${detail.ifBlank { body.take(200) }}"
        }
    }
}
