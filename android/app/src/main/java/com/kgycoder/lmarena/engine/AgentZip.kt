package com.kgycoder.lmarena.engine

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** lm_arena_cli/agent_mode.py 의 zip 안전 해제 로직 포팅. */
object AgentZip {

    private fun isZip(file: File): Boolean {
        return try {
            ZipFile(file).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun safeEntries(zf: ZipFile): List<ZipEntry> {
        val safe = mutableListOf<ZipEntry>()
        val entries = zf.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            val name = e.name.replace('\\', '/')
            if (name.isEmpty() || name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name)) continue
            val parts = name.split("/").filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) continue
            safe.add(e)
        }
        return safe
    }

    private fun commonRoot(entries: List<ZipEntry>): String? {
        val roots = mutableSetOf<String>()
        for (e in entries) {
            val name = e.name.replace('\\', '/').trim('/')
            if (name.isEmpty()) continue
            val head = name.split("/", limit = 2)
            if (head.size == 1 && !e.isDirectory) return null
            roots.add(head[0])
            if (roots.size > 1) return null
        }
        return roots.singleOrNull()
    }

    /** zip을 destDir 아래에 푼다. 반환값은 실제로 기록된 파일 경로 목록. */
    fun extract(archive: File, destDir: File, stripRoot: Boolean = true): List<File> {
        destDir.mkdirs()
        val written = mutableListOf<File>()

        if (!isZip(archive)) {
            val target = File(destDir, archive.name)
            archive.copyTo(target, overwrite = true)
            return listOf(target)
        }

        ZipFile(archive).use { zf ->
            val members = safeEntries(zf)
            val root = if (stripRoot) commonRoot(members) else null
            val destCanonical = destDir.canonicalFile
            for (entry in members) {
                var name = entry.name.replace('\\', '/').trim('/')
                if (root != null) {
                    if (name == root) continue
                    if (name.startsWith("$root/")) name = name.substring(root.length + 1)
                }
                if (name.isEmpty()) continue
                val target = File(destDir, name).canonicalFile
                if (target.path != destCanonical.path && !target.path.startsWith(destCanonical.path + File.separator)) {
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                written.add(target)
            }
        }
        return written
    }
}
