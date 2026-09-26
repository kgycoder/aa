package com.kgycoder.lmarena.engine

import java.io.File

/**
 * lm_arena_cli/file_writer.py 포팅.
 *
 * Windows판은 사용자가 지정한 임의의 절대경로 폴더에 직접 썼지만, 안드로이드는
 * scoped storage 때문에 임의의 절대경로에 쓸 수 없다. 대신 앱 전용 저장소
 * (context.getExternalFilesDir) 아래의 "workspace/<사용자가 정한 이름>" 폴더를
 * target_dir로 사용한다. 파일명 힌트 인식/중복 처리 로직 자체는 원본과 동일하다.
 */
object FileWriterUtil {

    private val LANGUAGE_EXTENSION_MAP: Map<String, String> = mapOf(
        "python" to "py", "py" to "py", "javascript" to "js", "js" to "js",
        "typescript" to "ts", "ts" to "ts", "jsx" to "jsx", "tsx" to "tsx",
        "json" to "json", "html" to "html", "css" to "css", "scss" to "scss",
        "bash" to "sh", "shell" to "sh", "sh" to "sh", "zsh" to "sh",
        "yaml" to "yaml", "yml" to "yaml", "markdown" to "md", "md" to "md",
        "sql" to "sql", "java" to "java", "c" to "c", "cpp" to "cpp", "c++" to "cpp",
        "csharp" to "cs", "c#" to "cs", "go" to "go", "rust" to "rs", "ruby" to "rb",
        "php" to "php", "kotlin" to "kt", "swift" to "swift", "dockerfile" to "Dockerfile",
        "toml" to "toml", "xml" to "xml", "plaintext" to "txt", "text" to "txt",
    )

    // 1) 한 줄 주석(#, //, --, ;, %, ::, REM) + filename/file 키워드
    private val PAT1 = Regex("""^(?:#|//|--|;|%|::|REM)\s*(?:filename|file)\s*[:=]\s*([\w./-]+)""", RegexOption.IGNORE_CASE)
    // 2) HTML 블록 주석
    private val PAT2 = Regex("""^<!--\s*(?:filename|file)\s*[:=]\s*([\w./-]+)\s*-->""", RegexOption.IGNORE_CASE)
    // 3) CSS/SCSS 블록 주석
    private val PAT3 = Regex("""^/\*\s*(?:filename|file)\s*[:=]\s*([\w./-]+)\s*\*/""", RegexOption.IGNORE_CASE)
    // 4) 키워드 없이 확장자만 있는 폴백
    private val PAT4 = Regex("""^(?:#|//|--|;|%)\s*([\w./-]+\.\w+)\s*$""")
    private val PAT5 = Regex("""^/\*\s*([\w./-]+\.\w+)\s*\*/\s*$""")
    private val PAT6 = Regex("""^<!--\s*([\w./-]+\.\w+)\s*-->\s*$""")
    private val PATTERNS = listOf(PAT1, PAT2, PAT3, PAT4, PAT5, PAT6)

    private const val HINT_SCAN_LINES = 5
    private val COMMENT_PREFIXES = listOf("#", "//", "--", ";", "%", "::", "REM", "<!--", "/*")

    private fun extractFilenameHint(code: String): String? {
        val lines = code.lines()
        for (line in lines.take(HINT_SCAN_LINES)) {
            val stripped = line.trim()
            if (stripped.isEmpty()) continue
            for (pattern in PATTERNS) {
                val m = pattern.find(stripped)
                if (m != null) return m.groupValues[1]
            }
            if (COMMENT_PREFIXES.none { stripped.uppercase().startsWith(it.uppercase()) }) break
        }
        return null
    }

    private fun extensionFor(language: String): String =
        LANGUAGE_EXTENSION_MAP[language.trim().lowercase()] ?: "txt"

    /** 절대경로/상위 폴더 탈출을 제거해 안전한 상대경로로 만든다 (Agent 모드 artifact 파일명 대비). */
    private fun sanitizeHint(hint: String?): String? {
        if (hint.isNullOrBlank()) return null
        var name = hint.trim().replace('\\', '/')
        if (name.isEmpty() || name.endsWith("/")) return null
        var parts = name.split("/").filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        parts = parts.map { it.replace(Regex("""^[A-Za-z]:"""), "") }
        parts = parts.map { it.replace(Regex("""[<>:"|?*\x00-\x1f]"""), "_").trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }

    fun resolveFilename(cb: CodeBlock, index: Int, usedNames: MutableSet<String>): String {
        val hint = sanitizeHint(cb.filenameHint) ?: extractFilenameHint(cb.code)
        var name = if (hint != null) {
            hint
        } else {
            val ext = extensionFor(cb.language)
            if (ext == "Dockerfile") "Dockerfile" else "generated_$index.$ext"
        }
        val original = name
        var counter = 1
        while (usedNames.contains(name)) {
            val dot = original.lastIndexOf('.')
            val slash = original.lastIndexOf('/')
            val stem = if (dot > slash) original.substring(0, dot) else original
            val suffix = if (dot > slash) original.substring(dot) else ""
            name = "${stem}_$counter$suffix"
            counter++
        }
        usedNames.add(name)
        return name
    }

    data class WrittenFile(val relativePath: String, val absolutePath: String, val modified: Boolean)

    /** targetDir(앱 전용 저장소 안의 폴더) 아래에 code_blocks를 파일로 기록한다. */
    fun writeCodeBlocks(targetDir: File, codeBlocks: List<CodeBlock>): List<WrittenFile> {
        targetDir.mkdirs()
        val used = mutableSetOf<String>()
        val out = mutableListOf<WrittenFile>()
        codeBlocks.forEachIndexed { i, cb ->
            val filename = resolveFilename(cb, i + 1, used)
            val file = resolveSafely(targetDir, filename) ?: return@forEachIndexed
            file.parentFile?.mkdirs()
            val existed = file.exists()
            file.writeText(cb.code, Charsets.UTF_8)
            out.add(WrittenFile(filename, file.absolutePath, existed))
        }
        return out
    }

    /** targetDir 밖으로 나가는 경로(../ 등)를 차단한다(zip-slip과 동일한 방어). */
    private fun resolveSafely(root: File, relative: String): File? {
        val candidate = File(root, relative).canonicalFile
        val rootCanonical = root.canonicalFile
        return if (candidate.path == rootCanonical.path || candidate.path.startsWith(rootCanonical.path + File.separator)) {
            candidate
        } else null
    }
}
