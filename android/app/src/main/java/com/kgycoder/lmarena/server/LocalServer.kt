package com.kgycoder.lmarena.server

import android.content.Context
import com.kgycoder.lmarena.engine.AutomationEngine
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter

/**
 * lm_arena_cli/webapp.py 의 Flask 앱과 완전히 같은 경로/계약을 그대로 재현한 로컬 서버.
 * 원본 web_static (index.html/app.js/styles.css)을 손대지 않고 그대로 서빙하므로,
 * 프런트엔드의 모든 기능(Direct/Agent 채팅, 모델 선택, 워크스페이스, JARVIS 음성 모드,
 * Work 모드 콘솔 등)이 원본과 동일하게 동작한다. 뒷단만 Playwright 대신
 * [AutomationEngine](WebView 기반)으로 바뀌었을 뿐이다.
 */
class LocalServer(private val context: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {

    private val bgScope = CoroutineScope(Dispatchers.Default)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                session.method == Method.GET && uri == "/" -> serveIndexWithShim()
                session.method == Method.GET && uri.startsWith("/static/") ->
                    serveAsset("web/" + uri.removePrefix("/static/"), mimeFor(uri))

                session.method == Method.GET && uri == "/api/state" -> json(AutomationEngine.stateJson())
                session.method == Method.GET && uri == "/api/history" -> json(AutomationEngine.historyJson())

                session.method == Method.POST && uri == "/api/target" -> {
                    val data = bodyJson(session)
                    json(AutomationEngine.setTarget(data.optString("target_dir", "")))
                }
                session.method == Method.POST && uri == "/api/auto-write" -> {
                    val data = bodyJson(session)
                    json(AutomationEngine.setAutoWrite(data.optBoolean("value", true)))
                }
                session.method == Method.POST && uri == "/api/share-workspace" -> {
                    val data = bodyJson(session)
                    json(AutomationEngine.setShareWorkspace(data.optBoolean("value", false)))
                }
                session.method == Method.POST && uri == "/api/chat-mode" -> {
                    val data = bodyJson(session)
                    json(runBlocking { AutomationEngine.setChatMode(data.optString("mode", "direct")) })
                }
                session.method == Method.POST && uri == "/api/agent-review" -> {
                    val data = bodyJson(session)
                    json(runBlocking { AutomationEngine.agentReview(data.optString("choice", "")) })
                }
                session.method == Method.POST && uri == "/api/agent-download" ->
                    json(runBlocking { AutomationEngine.agentDownload() })
                session.method == Method.GET && uri == "/api/agent-workspace" ->
                    json(runBlocking { AutomationEngine.agentWorkspaceTree() })
                session.method == Method.POST && uri == "/api/work-mode" -> {
                    val data = bodyJson(session)
                    json(AutomationEngine.setWorkMode(data.optBoolean("value", false)))
                }
                session.method == Method.POST && uri == "/api/filename-enforcement" -> {
                    val data = bodyJson(session)
                    json(AutomationEngine.setFilenameEnforcement(data.optBoolean("value", true)))
                }
                session.method == Method.GET && uri == "/api/models" -> {
                    val force = session.parameters["refresh"]?.firstOrNull() in setOf("1", "true")
                    json(runBlocking { AutomationEngine.listModels(force) })
                }
                session.method == Method.POST && uri == "/api/model" -> {
                    val data = bodyJson(session)
                    json(runBlocking { AutomationEngine.selectModel(data.optString("name", "")) })
                }
                session.method == Method.GET && uri.startsWith("/api/model-icon/") -> {
                    val id = uri.removePrefix("/api/model-icon/")
                    val svg = if (Regex("^[0-9a-f]{12}$").matches(id)) AutomationEngine.getModelIcon(id) else null
                    if (svg == null) {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
                    } else {
                        newFixedLengthResponse(Response.Status.OK, "image/svg+xml", svg).apply {
                            addHeader("Cache-Control", "public, max-age=31536000, immutable")
                            addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                            addHeader("X-Content-Type-Options", "nosniff")
                        }
                    }
                }
                session.method == Method.POST && uri == "/api/new-chat" -> json(runBlocking { AutomationEngine.newChat() })
                session.method == Method.POST && uri == "/api/refresh-login" -> json(runBlocking { AutomationEngine.refreshLogin() })
                session.method == Method.POST && uri == "/api/logout" -> json(runBlocking { AutomationEngine.logout() })
                session.method == Method.POST && uri == "/api/stop" -> {
                    AutomationEngine.requestStop()
                    json(JSONObject().put("ok", true))
                }
                session.method == Method.POST && uri == "/api/send" -> handleSend(session)

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            json(JSONObject().put("ok", false).put("error", e.message ?: e.toString()), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleSend(session: IHTTPSession): Response {
        val data = bodyJson(session)
        val prompt = data.optString("prompt", "").trim()
        if (prompt.isEmpty()) {
            return json(JSONObject().put("error", "빈 프롬프트입니다."), Response.Status.BAD_REQUEST)
        }
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        val writer = PrintWriter(pipeOut, false, Charsets.UTF_8)

        bgScope.launch {
            try {
                AutomationEngine.sendPrompt(prompt) { event ->
                    val line = "data: ${event}\n\n"
                    synchronized(writer) {
                        writer.write(line)
                        writer.flush()
                    }
                }
            } catch (e: Exception) {
                val line = "data: ${JSONObject().put("type", "error").put("message", e.message ?: e.toString())}\n\n"
                synchronized(writer) { writer.write(line); writer.flush() }
            } finally {
                try { writer.close() } catch (_: Exception) {}
                try { pipeOut.close() } catch (_: Exception) {}
            }
        }

        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("X-Accel-Buffering", "no")
        return resp
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────
    private fun bodyJson(session: IHTTPSession): JSONObject {
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val raw = map["postData"] ?: ""
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun json(obj: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())

    private fun json(arr: JSONArray): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", arr.toString())

    // index.html 원본 파일은 전혀 건드리지 않는다. 응답을 내려줄 때만 JARVIS 음성
    // 폴리필 <script> 한 줄을 app.js 앞에 끼워 넣는다 (WebView에는 SpeechRecognition이
    // 없어서 필요 - lma_speech_shim.js 참고).
    private fun serveIndexWithShim(): Response {
        return try {
            val html = context.assets.open("web/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val shimTag = "<script src=\"/static/lma_speech_shim.js\"></script>\n"
            val patched = if (html.contains("<script src=\"/static/app.js\">")) {
                html.replaceFirst("<script src=\"/static/app.js\">", shimTag + "<script src=\"/static/app.js\">")
            } else {
                html.replaceFirst("</body>", "$shimTag</body>")
            }
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", patched)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "index.html missing")
        }
    }

    private fun serveAsset(path: String, mime: String): Response {
        return try {
            val stream = context.assets.open(path)
            newChunkedResponse(Response.Status.OK, mime, stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: $path")
        }
    }

    private fun mimeFor(uri: String): String = when {
        uri.endsWith(".js") -> "application/javascript; charset=utf-8"
        uri.endsWith(".css") -> "text/css; charset=utf-8"
        uri.endsWith(".svg") -> "image/svg+xml"
        uri.endsWith(".png") -> "image/png"
        uri.endsWith(".json") -> "application/json; charset=utf-8"
        else -> "application/octet-stream"
    }
}
