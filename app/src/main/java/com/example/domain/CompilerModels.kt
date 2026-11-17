package com.example.domain

enum class OutputFormat(
    val id: String,
    val displayName: String,
    val fileExtension: String,
    val description: String
) {
    APK("APK", "Android APK", ".apk", "Installable Android application package"),
    AAB("AAB", "Android App Bundle", ".aab", "Google Play publishing bundle"),
    EXE("EXE", "Windows Executable", ".exe", "Standalone desktop executable"),
    JAR("JAR", "Java Archive", ".jar", "Executable JVM archive"),
    WEB("WEB", "Web Application Bundle", ".zip", "Packaged web deployment bundle"),
    NATIVE_BINARY("NATIVE_BINARY", "Native Binary", ".out", "Compiled ELF / NDK native binary"),
    DLL("DLL", "Dynamic Link Library", ".dll", ".NET compiled assembly library")
}

enum class SupportedLanguage(
    val id: String,
    val displayName: String,
    val workerToolchain: String,
    val compatibleOutputs: List<OutputFormat>,
    val primaryExtensions: List<String>
) {
    PYTHON(
        id = "python",
        displayName = "Python",
        workerToolchain = "Buildozer / PyInstaller / BeeWare",
        compatibleOutputs = listOf(OutputFormat.APK, OutputFormat.EXE, OutputFormat.WEB),
        primaryExtensions = listOf("py")
    ),
    JAVASCRIPT(
        id = "javascript",
        displayName = "JavaScript",
        workerToolchain = "Node.js / npm / Capacitor",
        compatibleOutputs = listOf(OutputFormat.APK, OutputFormat.WEB),
        primaryExtensions = listOf("js", "jsx", "mjs", "cjs")
    ),
    TYPESCRIPT(
        id = "typescript",
        displayName = "TypeScript",
        workerToolchain = "tsc / Node.js / Capacitor",
        compatibleOutputs = listOf(OutputFormat.APK, OutputFormat.WEB),
        primaryExtensions = listOf("ts", "tsx")
    ),
    JAVA(
        id = "java",
        displayName = "Java",
        workerToolchain = "OpenJDK / Gradle / Android SDK",
        compatibleOutputs = listOf(OutputFormat.APK, OutputFormat.JAR),
        primaryExtensions = listOf("java")
    ),
    KOTLIN(
        id = "kotlin",
        displayName = "Kotlin",
        workerToolchain = "Kotlin / Gradle / Android SDK",
        compatibleOutputs = listOf(OutputFormat.APK, OutputFormat.AAB),
        primaryExtensions = listOf("kt", "kts")
    ),
    FLUTTER(
        id = "flutter",
        displayName = "Dart / Flutter",
        workerToolchain = "Flutter SDK / Dart SDK / Android SDK",
        compatibleOutputs = listOf(OutputFormat.APK, OutputFormat.AAB, OutputFormat.WEB),
        primaryExtensions = listOf("dart")
    ),
    C(
        id = "c",
        displayName = "C",
        workerToolchain = "Clang / CMake / Android NDK",
        compatibleOutputs = listOf(OutputFormat.NATIVE_BINARY),
        primaryExtensions = listOf("c", "h")
    ),
    CPP(
        id = "cpp",
        displayName = "C++",
        workerToolchain = "Clang++ / CMake / Android NDK",
        compatibleOutputs = listOf(OutputFormat.NATIVE_BINARY),
        primaryExtensions = listOf("cpp", "cc", "cxx", "hpp")
    ),
    CSHARP(
        id = "csharp",
        displayName = "C#",
        workerToolchain = ".NET SDK",
        compatibleOutputs = listOf(OutputFormat.EXE, OutputFormat.DLL),
        primaryExtensions = listOf("cs")
    ),
    PHP(
        id = "php",
        displayName = "PHP",
        workerToolchain = "PHP / Composer / Web Packager",
        compatibleOutputs = listOf(OutputFormat.WEB),
        primaryExtensions = listOf("php")
    ),
    HTML_CSS(
        id = "html_css",
        displayName = "HTML / CSS",
        workerToolchain = "Static Web Packager",
        compatibleOutputs = listOf(OutputFormat.WEB),
        primaryExtensions = listOf("html", "htm", "css")
    );

    fun explainUnsupported(format: OutputFormat): String? {
        if (format in compatibleOutputs) return null
        return when (this) {
            PYTHON -> when (format) {
                OutputFormat.AAB -> "Python Buildozer produces APK artifacts directly; Play Store AAB packaging requires Gradle/Kotlin or Flutter."
                OutputFormat.JAR -> "Python cannot be compiled to JVM bytecode JAR without an experimental runtime."
                OutputFormat.DLL -> "Python does not emit .NET DLL assemblies."
                OutputFormat.NATIVE_BINARY -> "Use EXE (PyInstaller) for standalone binary packaging."
                else -> "${format.displayName} is not supported for Python."
            }
            JAVA -> when (format) {
                OutputFormat.AAB -> "Standard Java/Maven projects target JAR or APK; use Kotlin/Android Gradle for AAB."
                else -> "${format.displayName} is not supported for Java projects in this toolchain."
            }
            KOTLIN -> "${format.displayName} is not supported for Kotlin Android projects; select APK or AAB."
            FLUTTER -> "${format.displayName} is not supported for Flutter; select APK, AAB, or Web."
            JAVASCRIPT, TYPESCRIPT -> when (format) {
                OutputFormat.AAB -> "Capacitor wraps JS/TS web builds into debug/release APKs; AAB signing is reserved for native Kotlin/Flutter pipelines."
                else -> "${format.displayName} is not supported for ${displayName}; select APK or Web."
            }
            C, CPP -> "C/C++ projects compile via Clang/NDK/CMake into Native Binaries only; wrapping into APK requires a Java/Kotlin JNI host."
            CSHARP -> "C#/.NET SDK targets EXE and DLL assemblies; Android APK/AAB requires MAUI/Xamarin workloads not bundled in the standard .NET worker."
            PHP -> "PHP is a server-side web runtime and packages exclusively as a Web application."
            HTML_CSS -> "HTML/CSS projects package directly as Web application bundles."
        }
    }

    companion object {
        fun fromId(id: String): SupportedLanguage =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PYTHON
    }
}

enum class BuildMode(val id: String, val label: String) {
    DEBUG("debug", "Debug"),
    RELEASE("release", "Release")
}

enum class BuildStage(val label: String, val isTerminal: Boolean = false) {
    IDLE("Idle", false),
    QUEUED("Queued", false),
    PROVISIONING_SANDBOX("Provisioning Isolated Container", false),
    INSTALLING_DEPENDENCIES("Installing Dependencies", false),
    COMPILING("Compiling Source", false),
    PACKAGING("Packaging & Signing", false),
    VERIFYING_ARTIFACT("Verifying Output File", false),
    COMPLETED("Completed", true),
    FAILED("Failed", true),
    CANCELLED("Cancelled", true);

    companion object {
        fun fromWire(value: String): BuildStage =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: QUEUED
    }
}

data class ProjectFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val isDirectory: Boolean = false
)

data class ProjectAnalysisResult(
    val projectId: String,
    val projectName: String,
    val sourceType: String,
    val detectedLanguage: SupportedLanguage,
    val framework: String,
    val entryPoint: String?,
    val dependencyFiles: List<String>,
    val missingConfigs: List<String>,
    val compatibleOutputs: List<OutputFormat>,
    val unsupportedExplanations: Map<OutputFormat, String>,
    val totalFiles: Int,
    val totalSizeBytes: Long,
    val sha256Checksum: String,
    val zipSecurityPassed: Boolean,
    val securityNotes: List<String>,
    val files: List<ProjectFileEntry>
)

data class BuildConfiguration(
    val appName: String = "MyCompiledApp",
    val packageName: String = "com.example.compiledapp",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val customIconUri: String? = null,
    val minSdk: Int = 26,
    val targetSdk: Int = 35,
    val outputFormat: OutputFormat = OutputFormat.APK,
    val buildMode: BuildMode = BuildMode.DEBUG
)

data class BuildLogEntry(
    val timestamp: String,
    val level: String,
    val message: String
)

data class AiDiagnosisResult(
    val summary: String,
    val structureOverview: String,
    val detectedErrors: List<String>,
    val missingDependencies: List<String>,
    val suggestedFrameworks: List<String>,
    val recommendedConfigFile: String?,
    val recommendedConfigContent: String?,
    val targetPatchFile: String?,
    val patchedFileContent: String?
)
