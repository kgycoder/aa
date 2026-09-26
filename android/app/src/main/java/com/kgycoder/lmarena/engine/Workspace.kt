package com.kgycoder.lmarena.engine

import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** lm_arena_cli/workspace.py 포팅. */
object Workspace {
    private val EXCLUDED_DIR_NAMES = setOf(
        ".git", "node_modules", "__pycache__", ".venv", "venv", "env",
        ".idea", ".vscode", "dist", "build", ".next", ".nuxt",
        ".pytest_cache", ".mypy_cache", ".ruff_cache", "target",
        ".tox", ".cache", ".parcel-cache", "coverage",
    )
    private val EXCLUDED_DIR_SUFFIXES = listOf(".egg-info")
    private val SKIP_FILENAME_EXACT = setOf(".DS_Store")
    private val SENSITIVE_PATTERNS = listOf(
        ".env", ".pem", ".key", ".p12", ".pfx", "id_rsa", "id_ed25519",
        "credentials.json", ".npmrc", ".netrc",
    )

    const val DEFAULT_MAX_FILES_WITH_CONTENT = 200
    const val DEFAULT_MAX_FILE_CHARS = 40_000
    const val DEFAULT_MAX_TOTAL_CONTENT_CHARS = 500_000

    data class Snapshot(
        val root: File,
        val treeText: String,
        val contentBlock: String,
        val includedFileCount: Int,
        val truncated: Boolean,
    )

    private fun isExcludedDir(name: String): Boolean {
        if (name in EXCLUDED_DIR_NAMES) return true
        if (name.startsWith(".")) return true
        return EXCLUDED_DIR_SUFFIXES.any { name.endsWith(it) }
    }

    private fun isSensitive(name: String): Boolean {
        val lower = name.lowercase()
        return SENSITIVE_PATTERNS.any { lower.contains(it) }
    }

    /** 바이트를 안전하게(문자 경계 보존) 디코딩하고, 필요하면 문자 단위로만 자른다. */
    private fun tryReadText(file: File, maxChars: Int): Pair<String?, Boolean> {
        val raw = try { file.readBytes() } catch (e: Exception) { return null to false }
        val head = raw.copyOfRange(0, minOf(raw.size, 8000))
        if (head.contains(0.toByte())) return null to false
        val decoder = StandardCharsets.UTF_8.newDecoder()
        decoder.onMalformedInput(CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
        } catch (e: Exception) {
            return null to false
        }
        val truncated = text.length > maxChars
        return (if (truncated) text.substring(0, maxChars) else text) to truncated
    }

    private fun walkSorted(root: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        val children = (root.listFiles() ?: emptyArray())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        for (child in children) {
            if (child.isDirectory) {
                if (isExcludedDir(child.name)) continue
                out.add("dir" to child)
                out.addAll(walkSorted(child))
            } else {
                if (child.name in SKIP_FILENAME_EXACT) continue
                out.add("file" to child)
            }
        }
        return out
    }

    private fun buildTreeText(root: File, entries: List<Pair<String, File>>): String {
        val lines = mutableListOf("${root.name}/")
        for ((kind, path) in entries) {
            val rel = path.relativeTo(root).path.replace(File.separatorChar, '/')
            val depth = rel.count { it == '/' }
            val indent = "  ".repeat(depth)
            val label = if (kind == "dir") "${path.name}/" else path.name
            lines.add("$indent- $label")
        }
        return lines.joinToString("\n")
    }

    private fun xmlAttr(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")

    fun buildSnapshot(
        root: File,
        maxFilesWithContent: Int = DEFAULT_MAX_FILES_WITH_CONTENT,
        maxFileChars: Int = DEFAULT_MAX_FILE_CHARS,
        maxTotalContentChars: Int = DEFAULT_MAX_TOTAL_CONTENT_CHARS,
    ): Snapshot {
        root.mkdirs()
        val entries = walkSorted(root)
        val treeText = buildTreeText(root, entries)

        val chunks = mutableListOf<String>()
        var included = 0
        var totalChars = 0
        var truncated = false

        for ((kind, path) in entries) {
            if (kind != "file") continue
            if (included >= maxFilesWithContent || totalChars >= maxTotalContentChars) {
                truncated = true
                continue
            }
            val rel = path.relativeTo(root).path.replace(File.separatorChar, '/')

            if (isSensitive(path.name)) {
                chunks.add("<file path=\"${xmlAttr(rel)}\" skip=\"sensitive\"/>")
                included++
                continue
            }
            val (text, fileTruncatedRaw) = tryReadText(path, maxFileChars)
            var fileTruncated = fileTruncatedRaw
            if (text == null) {
                chunks.add("<file path=\"${xmlAttr(rel)}\" skip=\"binary\"/>")
                included++
                continue
            }
            var finalText = text
            val remainingBudget = maxTotalContentChars - totalChars
            if (finalText.length > remainingBudget) {
                finalText = finalText.substring(0, remainingBudget)
                fileTruncated = true
                truncated = true
            }
            val attrs = StringBuilder("path=\"${xmlAttr(rel)}\"")
            if (fileTruncated) attrs.append(" truncated=\"true\"")
            chunks.add("<file $attrs>\n$finalText\n</file>")
            included++
            totalChars += finalText.length
        }

        return Snapshot(
            root = root,
            treeText = treeText,
            contentBlock = chunks.joinToString("\n"),
            includedFileCount = included,
            truncated = truncated,
        )
    }

    fun formatContextBlock(snapshot: Snapshot): String {
        val parts = mutableListOf("<tree root=\"${xmlAttr(snapshot.root.path)}\">", snapshot.treeText, "</tree>")
        if (snapshot.contentBlock.isNotEmpty()) parts.add(snapshot.contentBlock)
        if (snapshot.truncated) {
            parts.add(
                "<note>Some file contents were omitted due to size limits; " +
                    "the <tree> above still lists every folder and file.</note>"
            )
        }
        return parts.joinToString("\n")
    }
}
