from pathlib import Path
from typing import Dict, List, Optional, Tuple

COMPATIBILITY_MATRIX: Dict[str, List[str]] = {
    "python": ["APK", "EXE", "WEB"],
    "java": ["APK", "JAR"],
    "kotlin": ["APK", "AAB"],
    "flutter": ["APK", "AAB", "WEB"],
    "javascript": ["APK", "WEB"],
    "typescript": ["APK", "WEB"],
    "c": ["NATIVE_BINARY"],
    "cpp": ["NATIVE_BINARY"],
    "csharp": ["EXE", "DLL"],
    "php": ["WEB"],
    "html_css": ["WEB"],
}

UNSUPPORTED_REASONS: Dict[Tuple[str, str], str] = {
    ("python", "AAB"): "Python Buildozer generates APK directly; AAB requires Kotlin/Gradle or Flutter.",
    ("java", "AAB"): "Standard Java projects output APK or JAR; use Kotlin Android Gradle for AAB.",
    ("csharp", "APK"): ".NET worker compiles EXE and DLL assemblies; Android APK requires MAUI workloads.",
    ("c", "APK"): "C/C++ NDK worker emits Native Binaries; wrap via JNI inside a Kotlin/Java host for APK.",
    ("cpp", "APK"): "C/C++ NDK worker emits Native Binaries; wrap via JNI inside a Kotlin/Java host for APK.",
    ("php", "APK"): "PHP is a server-side web runtime and outputs Web application bundles only.",
    ("html_css", "APK"): "HTML/CSS projects output Web bundles; use Capacitor JS/TS to target APK.",
}


def analyze_workspace_dir(workspace: Path) -> dict:
    files = [p.relative_to(workspace).as_posix() for p in workspace.rglob("*") if p.is_file()]
    lower = [f.lower() for f in files]

    language = "python"
    if any(f.endswith("pubspec.yaml") or f.endswith(".dart") for f in lower):
        language = "flutter"
    elif any(f.endswith(".csproj") or f.endswith(".sln") or f.endswith(".cs") for f in lower):
        language = "csharp"
    elif any(f.endswith(".kt") or f.endswith(".kts") for f in lower) and any(
        f.endswith("build.gradle.kts") or f.endswith("build.gradle") for f in lower
    ):
        language = "kotlin"
    elif any(f.endswith(".java") or f.endswith("pom.xml") for f in lower):
        language = "java"
    elif any(f.endswith("tsconfig.json") or f.endswith(".ts") or f.endswith(".tsx") for f in lower):
        language = "typescript"
    elif any(f.endswith("package.json") or f.endswith(".js") or f.endswith(".jsx") for f in lower):
        language = "javascript"
    elif any(f.endswith(".py") or f.endswith("requirements.txt") or f.endswith("buildozer.spec") for f in lower):
        language = "python"
    elif any(f.endswith(".cpp") or f.endswith(".cc") or f.endswith(".hpp") for f in lower):
        language = "cpp"
    elif any(f.endswith(".c") for f in lower):
        language = "c"
    elif any(f.endswith(".php") or f.endswith("composer.json") for f in lower):
        language = "php"
    elif any(f.endswith(".html") or f.endswith(".css") for f in lower):
        language = "html_css"

    framework = _detect_framework(language, workspace, files)
    entry_point = _find_entry_point(language, files)
    dep_files = [
        f for f in files
        if Path(f).name.lower() in {
            "requirements.txt", "pyproject.toml", "buildozer.spec", "package.json",
            "tsconfig.json", "build.gradle", "build.gradle.kts", "pom.xml",
            "pubspec.yaml", "cmakelists.txt", "makefile", "composer.json"
        } or f.lower().endswith(".csproj")
    ]
    missing_configs = _missing_configs(language, lower, entry_point)
    compatible = COMPATIBILITY_MATRIX.get(language, ["WEB"])
    all_formats = ["APK", "AAB", "EXE", "JAR", "WEB", "NATIVE_BINARY", "DLL"]
    unsupported_reasons = {
        fmt: UNSUPPORTED_REASONS.get((language, fmt), f"{fmt} is not supported for {language}.")
        for fmt in all_formats if fmt not in compatible
    }

    return {
        "language": language,
        "framework": framework,
        "entry_point": entry_point,
        "dependency_files": dep_files,
        "missing_configs": missing_configs,
        "compatible_outputs": compatible,
        "unsupported_reasons": unsupported_reasons,
    }


def _detect_framework(language: str, workspace: Path, files: List[str]) -> str:
    def read_text(name: str) -> str:
        for f in files:
            if Path(f).name.lower() == name:
                p = workspace / f
                if p.stat().st_size < 128_000:
                    return p.read_text(errors="ignore").lower()
        return ""

    if language == "python":
        reqs = read_text("requirements.txt") + read_text("pyproject.toml")
        if "kivy" in reqs or any(f.lower().endswith("buildozer.spec") for f in files):
            return "Kivy / Buildozer"
        if "fastapi" in reqs:
            return "FastAPI"
        if "flask" in reqs:
            return "Flask"
        return "Standard Python"
    if language in ("javascript", "typescript"):
        pkg = read_text("package.json")
        if "@capacitor/core" in pkg:
            return "Capacitor"
        if "react" in pkg:
            return "React"
        return "Node.js"
    if language == "flutter":
        return "Flutter"
    if language == "kotlin":
        return "Android Kotlin / Gradle"
    if language == "java":
        return "Java / Gradle / Maven"
    if language in ("c", "cpp"):
        return "CMake / Clang"
    if language == "csharp":
        return ".NET SDK"
    if language == "php":
        return "PHP Web"
    return "Static HTML/CSS"


def _find_entry_point(language: str, files: List[str]) -> Optional[str]:
    candidates = {
        "python": ["main.py", "app.py", "run.py"],
        "javascript": ["index.js", "src/index.js", "app.js", "index.html"],
        "typescript": ["src/index.ts", "src/main.ts", "index.ts", "index.html"],
        "java": ["MainActivity.java", "Main.java"],
        "kotlin": ["MainActivity.kt", "Main.kt"],
        "flutter": ["lib/main.dart", "main.dart"],
        "c": ["main.c"],
        "cpp": ["main.cpp", "main.cc"],
        "csharp": ["Program.cs"],
        "php": ["public/index.php", "index.php"],
        "html_css": ["index.html"],
    }.get(language, [])
    for c in candidates:
        for f in files:
            if f.lower().endswith(c.lower()):
                return f
    return files[0] if files else None


def _missing_configs(language: str, lower: List[str], entry_point: Optional[str]) -> List[str]:
    missing: List[str] = []
    if not entry_point:
        missing.append("Entry point source file")
    if language == "python" and not any(f.endswith("requirements.txt") for f in lower):
        missing.append("requirements.txt")
    if language in ("javascript", "typescript") and not any(f.endswith("package.json") for f in lower):
        missing.append("package.json")
    if language == "flutter" and not any(f.endswith("pubspec.yaml") for f in lower):
        missing.append("pubspec.yaml")
    if language in ("java", "kotlin") and not any(
        f.endswith(("build.gradle", "build.gradle.kts", "pom.xml")) for f in lower
    ):
        missing.append("build.gradle.kts")
    return missing
