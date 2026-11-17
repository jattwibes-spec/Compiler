package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.CompilerAiRepository
import com.example.data.local.ProjectAnalyzerEngine
import com.example.data.local.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.example.domain.OutputFormat
import com.example.domain.ProjectFileEntry
import com.example.domain.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches Wibes Compiler AI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Wibes Compiler AI", appName)
    }

    @Test
    fun `analyzer detects python buildozer and enforces output matrix`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "wibes_test_py").apply {
            deleteRecursively()
            mkdirs()
        }
        File(tempDir, "main.py").writeText("print('hello')")
        File(tempDir, "requirements.txt").writeText("kivy==2.3.0\n")

        val engine = ProjectAnalyzerEngine()
        val result = engine.analyzeWorkspace(
            projectId = "p1",
            projectName = "PyKivy",
            sourceType = "ZIP",
            workspaceDir = tempDir,
            files = listOf(
                ProjectFileEntry("main.py", 14),
                ProjectFileEntry("requirements.txt", 13)
            ),
            totalSizeBytes = 27,
            sha256Checksum = "abc123",
            zipSecurityPassed = true,
            securityNotes = emptyList()
        )

        assertEquals(SupportedLanguage.PYTHON, result.detectedLanguage)
        assertEquals("Kivy / Buildozer", result.framework)
        assertEquals("main.py", result.entryPoint)
        assertTrue(OutputFormat.APK in result.compatibleOutputs)
        assertTrue(OutputFormat.EXE in result.compatibleOutputs)
        assertTrue(OutputFormat.WEB in result.compatibleOutputs)
        assertNotNull(result.unsupportedExplanations[OutputFormat.AAB])
        tempDir.deleteRecursively()
    }

    @Test
    fun `analyzer detects flutter and typescript accurately`() {
        val flutterDir = File(System.getProperty("java.io.tmpdir"), "wibes_test_fl").apply {
            deleteRecursively()
            mkdirs()
        }
        File(flutterDir, "pubspec.yaml").writeText("name: demo\ndependencies:\n  flutter:\n    sdk: flutter\n")
        File(flutterDir, "lib").mkdirs()
        File(flutterDir, "lib/main.dart").writeText("void main() {}")

        val engine = ProjectAnalyzerEngine()
        val flResult = engine.analyzeWorkspace(
            projectId = "p2",
            projectName = "FlutterApp",
            sourceType = "FOLDER",
            workspaceDir = flutterDir,
            files = listOf(
                ProjectFileEntry("pubspec.yaml", 60),
                ProjectFileEntry("lib/main.dart", 14)
            ),
            totalSizeBytes = 74,
            sha256Checksum = "def456",
            zipSecurityPassed = true,
            securityNotes = emptyList()
        )

        assertEquals(SupportedLanguage.FLUTTER, flResult.detectedLanguage)
        assertEquals("Flutter", flResult.framework)
        assertEquals("lib/main.dart", flResult.entryPoint)
        assertTrue(OutputFormat.AAB in flResult.compatibleOutputs)
        flutterDir.deleteRecursively()
    }
    @Test
    fun `offline AI diagnostics works without Firebase initialization`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(System.getProperty("java.tmpdir"), "wibes_test_ai").apply {
            deleteRecursively()
            mkdirs()
        }
        File(tempDir, "main.py").writeText("print(123)")
        val engine = ProjectAnalyzerEngine()
        val analysis = engine.analyzeWorkspace(
            projectId = "ai1",
            projectName = "AiDemo",
            sourceType = "ZIP",
            workspaceDir = tempDir,
            files = listOf(ProjectFileEntry("main.py", 10)),
            totalSizeBytes = 10,
            sha256Checksum = "xyz",
            zipSecurityPassed = true,
            securityNotes = emptyList()
        )
        val aiRepo = CompilerAiRepository(context, SettingsDataStore(context))
        val diag = aiRepo.analyzeProjectAndSuggestFixes(
            analysis = analysis,
            sourceContext = "print(123)",
            compilerErrorLogs = "Failed to connect to /10.0.2.2:8080"
        )
        assertTrue(diag.summary.contains("Offline Local Diagnostics"))
        assertTrue(diag.detectedErrors.any { it.contains("10.0.2.2") })
        val reply = aiRepo.streamDeveloperAssistant("Why did 10.0.2.2 fail?", analysis, "print(123)", null).first()
        assertTrue(reply.contains("10.0.2.2"))
        tempDir.deleteRecursively()
    }
}
