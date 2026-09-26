package com.kgycoder.lmarena.engine

import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * arena_lib.js의 다운로드 가로채기(grabUrl)가 base64 청크로 보내주는 파일을
 * 조립한다. agent_mode.py의 download_workspace()가 Playwright의
 * page.expect_download()로 하던 일의 안드로이드 버전.
 */
object DownloadRegistry {
    data class Session(
        val file: File,
        val stream: FileOutputStream,
        var name: String,
        val deferred: CompletableDeferred<File>,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    lateinit var cacheDir: File

    fun begin(id: String, suggestedName: String): CompletableDeferred<File> {
        val dir = File(cacheDir, "downloads").apply { mkdirs() }
        val file = File(dir, "$id.part")
        val session = Session(file, FileOutputStream(file), suggestedName, CompletableDeferred())
        sessions[id] = session
        return session.deferred
    }

    @Synchronized
    fun appendChunk(id: String, name: String, bytes: ByteArray, last: Boolean) {
        val session = sessions[id] ?: run {
            // 첫 청크에 세션이 없으면(이벤트 순서 문제 대비) 즉석에서 만든다.
            val d = begin(id, name)
            sessions[id]!!
        }
        try {
            if (bytes.isNotEmpty()) session.stream.write(bytes)
            if (name.isNotBlank()) session.name = name
            if (last) {
                session.stream.flush()
                session.stream.close()
                val finalFile = File(session.file.parentFile, safeName(session.name))
                if (finalFile.exists()) finalFile.delete()
                session.file.renameTo(finalFile)
                sessions.remove(id)
                session.deferred.complete(finalFile)
            }
        } catch (e: Exception) {
            fail(id, e.message ?: "download write failed")
        }
    }

    fun fail(id: String, message: String) {
        val session = sessions.remove(id) ?: return
        try { session.stream.close() } catch (_: Exception) {}
        try { session.file.delete() } catch (_: Exception) {}
        session.deferred.completeExceptionally(RuntimeException(message))
    }

    private fun safeName(name: String): String {
        val base = name.ifBlank { "workspace.zip" }.substringAfterLast('/').substringAfterLast('\\')
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "workspace.zip" }
    }
}
