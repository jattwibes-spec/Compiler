package com.example.data.local

import com.example.domain.OutputFormat
import com.example.domain.ProjectAnalysisResult
import com.example.domain.ProjectFileEntry
import com.example.domain.SupportedLanguage
import java.io.File

class ProjectAnalyzerEngine {

    fun analyzeWorkspace(
        projectId: String,
        projectName: String,
        sourceType: String,
        workspaceDir: File,
        files: List<ProjectFileEntry>,
        totalSizeBytes: Long,
        sha256Checksum: String,
        zipSecurityPassed: Boolean,
        securityNotes: List<String>
    ): ProjectAnalysisResult {
        val regularFiles = files.filter { !it.isDirectory }
        val paths = regularFiles.map { it.relativePath }
        val lowerPaths = paths.map { it.lowercase() }

        val language = detectLanguage(lowerPaths)
        val framework = detectFramework(language, workspaceDir, paths, lowerPaths)
        val entryPoint = findEntryPoint(language, paths)
        val dependencyFiles = detectDependencyFiles(paths)
        val missingConfigs = detectMissingConfigs(language, framework, lowerPaths, entryPoint)
        val compatibleOutputs = language.compatibleOutputs
        val unsupportedExplanations = OutputFormat.entries
            .filter { it !in compatibleOutputs }
            .mapNotNull { fmt -> language.explainUnsupported(fmt)?.let { fmt to it } }
            .toMap()

        return ProjectAnalysisResult(
            projectId = projectId,
            projectName = projectName,
            sourceType = sourceType,
            detectedLanguage = language,
            framework = framework,
            entryPoint = entryPoint,
            dependencyFiles = dependencyFiles,
            missingConfigs = missingConfigs,
            compatibleOutputs = compatibleOutputs,
            unsupportedExplanations = unsupportedExplanations,
            totalFiles = regularFiles.size,
            totalSizeBytes = totalSizeBytes,
            sha256Checksum = sha256Checksum,
            zipSecurityPassed = zipSecurityPassed,
            securityNotes = securityNotes,
            files = files
        )
    }

    private fun detectLanguage(lowerPaths: List<String>): SupportedLanguage {
        if (lowerPaths.any { it.endsWith("pubspec.yaml") } || lowerPaths.count { it.endsWith(".dart") } > 0) {
            return SupportedLanguage.FLUTTER
        }
        if (lowerPaths.any { it.endsWith(".csproj") || it.endsWith(".sln") } || lowerPaths.count { it.endsWith(".cs") } > 0) {
            return SupportedLanguage.CSHARP
        }
        if (lowerPaths.any { it.endsWith(".kt") || it.endsWith(".kts") } &&
            lowerPaths.any { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") || it.endsWith("androidmanifest.xml") }
        ) {
            return SupportedLanguage.KOTLIN
        }
        if (lowerPaths.count { it.endsWith(".kt") } > lowerPaths.count { it.endsWith(".java") }) {
            return SupportedLanguage.KOTLIN
        }
        if (lowerPaths.any { it.endsWith(".java") } || lowerPaths.any { it.endsWith("pom.xml") }) {
            return SupportedLanguage.JAVA
        }
        if (lowerPaths.any { it.endsWith("tsconfig.json") } || lowerPaths.count { it.endsWith(".ts") || it.endsWith(".tsx") } > 0) {
            return SupportedLanguage.TYPESCRIPT
        }
        if (lowerPaths.any { it.endsWith("package.json") } || lowerPaths.count { it.endsWith(".js") || it.endsWith(".jsx") } > 0) {
            return SupportedLanguage.JAVASCRIPT
        }
        if (lowerPaths.any { it.endsWith(".py") || it.endsWith("requirements.txt") || it.endsWith("pyproject.toml") || it.endsWith("buildozer.spec") }) {
            return SupportedLanguage.PYTHON
        }
        if (lowerPaths.any { it.endsWith(".cpp") || it.endsWith(".cc") || it.endsWith(".cxx") || it.endsWith(".hpp") }) {
            return SupportedLanguage.CPP
        }
        if (lowerPaths.any { it.endsWith(".c") }) {
            return SupportedLanguage.C
        }
        if (lowerPaths.any { it.endsWith(".php") || it.endsWith("composer.json") }) {
            return SupportedLanguage.PHP
        }
        if (lowerPaths.any { it.endsWith(".html") || it.endsWith(".htm") || it.endsWith(".css") }) {
            return SupportedLanguage.HTML_CSS
        }
        return SupportedLanguage.PYTHON
    }

    private fun detectFramework(
        language: SupportedLanguage,
        workspaceDir: File,
        paths: List<String>,
        lowerPaths: List<String>
    ): String {
        fun readIfSmall(fileName: String): String {
            val match = paths.firstOrNull { it.equals(fileName, ignoreCase = true) || it.lowercase().endsWith("/$fileName") }
                ?: return ""
            val file = File(workspaceDir, match)
            return if (file.exists() && file.length() < 128_000) runCatching { file.readText() }.getOrDefault("") else ""
        }

        return when (language) {
            SupportedLanguage.PYTHON -> {
                val reqs = (readIfSmall("requirements.txt") + "\n" + readIfSmall("pyproject.toml")).lowercase()
                when {
                    lowerPaths.any { it.endsWith("buildozer.spec") } || "kivy" in reqs -> "Kivy / Buildozer"
                    "briefcase" in reqs || "toga" in reqs -> "BeeWare / Toga"
                    "fastapi" in reqs -> "FastAPI"
                    "flask" in reqs -> "Flask"
                    "django" in reqs -> "Django"
                    else -> "Standard Python"
                }
            }
            SupportedLanguage.JAVASCRIPT, SupportedLanguage.TYPESCRIPT -> {
                val pkg = readIfSmall("package.json").lowercase()
                when {
                    "@capacitor/core" in pkg -> "Capacitor Hybrid"
                    "react-native" in pkg -> "React Native"
                    "next" in pkg -> "Next.js"
                    "react" in pkg -> "React / Vite"
                    "vue" in pkg -> "Vue.js"
                    "express" in pkg -> "Express.js"
                    else -> if (language == SupportedLanguage.TYPESCRIPT) "TypeScript / Node.js" else "JavaScript / Node.js"
                }
            }
            SupportedLanguage.JAVA -> {
                val gradle = (readIfSmall("build.gradle") + readIfSmall("pom.xml")).lowercase()
                when {
                    "com.android.application" in gradle || lowerPaths.any { it.endsWith("androidmanifest.xml") } -> "Android Java"
                    "spring-boot" in gradle -> "Spring Boot"
                    else -> "Standard JVM / Maven / Gradle"
                }
            }
            SupportedLanguage.KOTLIN -> {
                val gradle = (readIfSmall("build.gradle.kts") + readIfSmall("build.gradle")).lowercase()
                when {
                    "compose" in gradle -> "Android Jetpack Compose"
                    "com.android.application" in gradle -> "Android Kotlin"
                    else -> "Kotlin JVM / Gradle"
                }
            }
            SupportedLanguage.FLUTTER -> {
                val pubspec = readIfSmall("pubspec.yaml").lowercase()
                if ("flutter:" in pubspec) "Flutter" else "Dart SDK"
            }
            SupportedLanguage.C, SupportedLanguage.CPP -> {
                when {
                    lowerPaths.any { it.endsWith("cmakelists.txt") } -> "CMake / Clang"
                    lowerPaths.any { it.endsWith("makefile") } -> "GNU Make / Clang"
                    else -> "Clang / NDK Standalone"
                }
            }
            SupportedLanguage.CSHARP -> {
                val csproj = paths.firstOrNull { it.lowercase().endsWith(".csproj") }?.let { readIfSmall(it).lowercase() } ?: ""
                when {
                    "microsoft.net.sdk.web" in csproj -> "ASP.NET Core"
                    else -> ".NET SDK Console / Library"
                }
            }
            SupportedLanguage.PHP -> {
                val composer = readIfSmall("composer.json").lowercase()
                when {
                    "laravel/framework" in composer -> "Laravel"
                    "symfony" in composer -> "Symfony"
                    else -> "PHP Web Application"
                }
            }
            SupportedLanguage.HTML_CSS -> "Static HTML5 / CSS3 Web"
        }
    }

    private fun findEntryPoint(language: SupportedLanguage, paths: List<String>): String? {
        val candidates = when (language) {
            SupportedLanguage.PYTHON -> listOf("main.py", "app.py", "run.py", "manage.py", "src/main.py")
            SupportedLanguage.JAVASCRIPT -> listOf("index.js", "src/index.js", "src/main.js", "app.js", "server.js", "index.html")
            SupportedLanguage.TYPESCRIPT -> listOf("src/index.ts", "src/main.ts", "src/main.tsx", "index.ts", "src/App.tsx", "index.html")
            SupportedLanguage.JAVA -> listOf("MainActivity.java", "Main.java", "Application.java")
            SupportedLanguage.KOTLIN -> listOf("MainActivity.kt", "Main.kt")
            SupportedLanguage.FLUTTER -> listOf("lib/main.dart", "bin/main.dart", "main.dart")
            SupportedLanguage.C -> listOf("main.c", "src/main.c")
            SupportedLanguage.CPP -> listOf("main.cpp", "src/main.cpp", "main.cc")
            SupportedLanguage.CSHARP -> listOf("Program.cs", "Startup.cs")
            SupportedLanguage.PHP -> listOf("public/index.php", "index.php")
            SupportedLanguage.HTML_CSS -> listOf("index.html", "public/index.html")
        }
        for (candidate in candidates) {
            val match = paths.firstOrNull {
                it.equals(candidate, ignoreCase = true) || it.lowercase().endsWith("/${candidate.lowercase()}")
            }
            if (match != null) return match
        }
        return paths.firstOrNull { path ->
            language.primaryExtensions.any { ext -> path.lowercase().endsWith(".$ext") }
        }
    }

    private fun detectDependencyFiles(paths: List<String>): List<String> {
        val knownManifests = setOf(
            "requirements.txt", "pyproject.toml", "pipfile", "setup.py", "buildozer.spec",
            "package.json", "package-lock.json", "yarn.lock", "tsconfig.json",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "pom.xml",
            "pubspec.yaml", "pubspec.lock", "cmakelists.txt", "makefile", "composer.json"
        )
        return paths.filter { path ->
            val fileName = path.substringAfterLast('/').lowercase()
            fileName in knownManifests || fileName.endsWith(".csproj") || fileName.endsWith(".sln")
        }
    }

    private fun detectMissingConfigs(
        language: SupportedLanguage,
        framework: String,
        lowerPaths: List<String>,
        entryPoint: String?
    ): List<String> {
        val missing = mutableListOf<String>()
        if (entryPoint == null) {
            missing += "No clear entry point source file found"
        }
        when (language) {
            SupportedLanguage.PYTHON -> {
                if (!lowerPaths.any { it.endsWith("requirements.txt") || it.endsWith("pyproject.toml") }) {
                    missing += "requirements.txt (needed for isolated dependency install)"
                }
                if (!lowerPaths.any { it.endsWith("buildozer.spec") }) {
                    missing += "buildozer.spec (required for Python -> Android APK builds)"
                }
            }
            SupportedLanguage.JAVASCRIPT, SupportedLanguage.TYPESCRIPT -> {
                if (!lowerPaths.any { it.endsWith("package.json") }) {
                    missing += "package.json (required for npm build & Capacitor)"
                }
                if (language == SupportedLanguage.TYPESCRIPT && !lowerPaths.any { it.endsWith("tsconfig.json") }) {
                    missing += "tsconfig.json (TypeScript compiler configuration)"
                }
                if ("capacitor" !in framework.lowercase() && !lowerPaths.any { it.endsWith("capacitor.config.json") || it.endsWith("capacitor.config.ts") }) {
                    missing += "capacitor.config.json (needed only for JS/TS -> APK wrapping)"
                }
            }
            SupportedLanguage.JAVA, SupportedLanguage.KOTLIN -> {
                if (!lowerPaths.any { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") || it.endsWith("pom.xml") }) {
                    missing += "build.gradle.kts / pom.xml (required by Gradle/Maven worker)"
                }
            }
            SupportedLanguage.FLUTTER -> {
                if (!lowerPaths.any { it.endsWith("pubspec.yaml") }) {
                    missing += "pubspec.yaml (required by Flutter SDK)"
                }
            }
            SupportedLanguage.C, SupportedLanguage.CPP -> {
                if (!lowerPaths.any { it.endsWith("cmakelists.txt") || it.endsWith("makefile") }) {
                    missing += "CMakeLists.txt (recommended for NDK/Clang multi-file builds)"
                }
            }
            SupportedLanguage.CSHARP -> {
                if (!lowerPaths.any { it.endsWith(".csproj") }) {
                    missing += "*.csproj (required by dotnet build/publish)"
                }
            }
            SupportedLanguage.PHP -> {
                if (!lowerPaths.any { it.endsWith("composer.json") }) {
                    missing += "composer.json (optional if project uses external Composer packages)"
                }
            }
            SupportedLanguage.HTML_CSS -> {
                if (!lowerPaths.any { it.endsWith("index.html") }) {
                    missing += "index.html (web application entry document)"
                }
            }
        }
        return missing
    }
}
