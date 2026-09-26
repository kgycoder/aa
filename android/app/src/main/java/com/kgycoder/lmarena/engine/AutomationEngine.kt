package com.kgycoder.lmarena.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.kgycoder.lmarena.bridge.ArenaJsBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class AutomationJsException(message: String) : Exception(message)
class GenerationStoppedException : Exception("stopped by user")

/**
 * lm_arena_cli/webapp.py 의 AutomationWorker(+ browser.py/model_selector.py/agent_mode.py/
 * work_agent.py 호출부)를 안드로이드용으로 옮긴 것. Playwright 대신 실제 WebView 하나
 * (automationWebView)를 붙잡고 arena_lib.js를 통해 DOM을 조작/관찰한다.
 *
 * 원본은 "요청을 큐에 넣고 전용 스레드가 하나씩 처리"하는 구조였다. 여기서는 그 역할을
 * turnMutex 하나가 대신한다: 브라우저를 실제로 건드리는 함수는 전부 이 뮤텍스를 잡고
 * 실행되므로, 여전히 "한 번에 한 가지 브라우저 동작만" 이라는 불변 조건이 유지된다.
 */
object AutomationEngine {

    interface UiHost {
        fun onRevealAutomation(reason: String)
        fun onHideAutomation()
        fun onStateChanged()
    }

    lateinit var appContext: Context
        private set
    lateinit var settings: Settings
        private set
    lateinit var bridge: ArenaJsBridge
        private set

    @Volatile var automationWebView: WebView? = null
    @Volatile var uiHost: UiHost? = null

    private val turnMutex = Mutex()
    private val stopRequested = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ── 상태 (webapp.py의 self._state) ────────────────────────────
    private val stateLock = Any()
    private var stage = "starting"
    private var loggedIn = false
    private var currentModel: JSONObject? = null
    private var lastError: String? = null
    private var agentReviewPending = false
    private var workspaceSharedThisSession = false

    private val history = mutableListOf<JSONObject>()
    private val icons = HashMap<String, String>()
    private var modelCatalog: JSONObject? = null

    fun attach(context: Context, wv: WebView, host: UiHost) {
        appContext = context.applicationContext
        settings = Settings(appContext)
        bridge = ArenaJsBridge()
        DownloadRegistry.cacheDir = appContext.cacheDir
        automationWebView = wv
        uiHost = host
    }

    fun detachUi(host: UiHost) {
        if (uiHost === host) uiHost = null
    }

    // ── 작업 폴더(=workspace root) : Android scoped storage 대응 ───
    // 임의의 절대경로에 직접 쓸 수 없으므로, 앱 전용 외부 저장소 아래
    // workspace/<사용자가 정한 이름> 폴더를 target_dir로 쓴다. 권한 대화상자 없이
    // 항상 쓰기 가능하고, 로그아웃/재설치 전까지 그대로 남는다.
    fun workspaceRoot(): File = File(appContext.getExternalFilesDir(null), "workspace").apply { mkdirs() }

    fun targetDir(): File {
        val name = sanitizeFolderName(settings.targetDir)
        return File(workspaceRoot(), name).apply { mkdirs() }
    }

    private fun sanitizeFolderName(raw: String): String {
        val cleaned = raw.trim().ifBlank { "default" }
            .replace(Regex("""[\\/]+"""), "_")
            .replace(Regex("""[<>:"|?*\x00-\x1f]"""), "_")
        return cleaned.ifBlank { "default" }
    }

    // ══════════════════════════════════════════════════════════════
    // JS 호출 저수준 헬퍼
    // ══════════════════════════════════════════════════════════════
    private suspend fun evalRaw(js: String): String = suspendCancellableCoroutine { cont ->
        val wv = automationWebView
        if (wv == null) {
            if (cont.isActive) cont.resume("null")
            return@suspendCancellableCoroutine
        }
        mainHandler.post {
            try {
                wv.evaluateJavascript(js) { result ->
                    if (cont.isActive) cont.resume(result ?: "null")
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("null")
            }
        }
    }

    private fun unwrapEvalString(raw: String): String {
        if (raw.isBlank() || raw == "null") return "null"
        return try {
            val v = JSONTokener(raw).nextValue()
            if (v is String) v else raw
        } catch (e: Exception) {
            raw
        }
    }

    private fun jsStr(s: String): String = JSONObject.quote(s)

    /** window.__lma.<fn>(argsLiteral) 를 호출하고 결과를 파싱해 돌려준다. */
    suspend fun callLma(fn: String, argsLiteral: String = ""): Any? {
        val js = "(function(){try{return JSON.stringify(window.__lma && window.__lma.$fn($argsLiteral));}" +
            "catch(e){return JSON.stringify({__lma_error:String((e&&e.message)||e)});}})()"
        val raw = evalRaw(js)
        val unwrapped = unwrapEvalString(raw)
        if (unwrapped == "null" || unwrapped.isBlank()) return null
        val value = try { JSONTokener(unwrapped).nextValue() } catch (e: Exception) { return null }
        if (value is JSONObject && value.has("__lma_error")) {
            throw AutomationJsException(value.getString("__lma_error"))
        }
        return value
    }

    private suspend fun ensureLibInjected() {
        val has = evalRaw("(function(){return window.__lma ? 'y' : 'n';})()")
        if (has.contains("y")) return
        val js = try {
            appContext.assets.open("lma/arena_lib.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            return
        }
        evalRaw(js)
    }

    // ══════════════════════════════════════════════════════════════
    // 상태 / 이력 (읽기는 뮤텍스 없이 즉시 응답 - GET /api/state 등)
    // ══════════════════════════════════════════════════════════════
    private fun setState(
        stage2: String? = null,
        loggedIn2: Boolean? = null,
        currentModel2: JSONObject? = null,
        error2: String? = null,
        agentReviewPending2: Boolean? = null,
        clearCurrentModel: Boolean = false,
        clearError: Boolean = false,
    ) {
        synchronized(stateLock) {
            if (stage2 != null) stage = stage2
            if (loggedIn2 != null) loggedIn = loggedIn2
            if (clearCurrentModel) currentModel = null
            if (currentModel2 != null) currentModel = currentModel2
            if (clearError) lastError = null
            if (error2 != null) lastError = error2
            if (agentReviewPending2 != null) agentReviewPending = agentReviewPending2
        }
        uiHost?.onStateChanged()
    }

    fun stateJson(): JSONObject = synchronized(stateLock) {
        JSONObject()
            .put("stage", stage)
            .put("logged_in", loggedIn)
            .put("target_dir", targetDir().absolutePath)
            .put("auto_write_files", settings.autoWriteFiles)
            .put("enforce_filename_comments", settings.enforceFilenameComments)
            .put("share_workspace", settings.shareWorkspace)
            .put("work_mode", settings.workMode)
            .put("chat_mode", settings.chatMode)
            .put("agent_auto_download", settings.agentAutoDownload)
            .put("agent_review_pending", agentReviewPending)
            .put("current_model", currentModel)
            .put("error", lastError)
    }

    fun historyJson(): JSONArray = synchronized(history) {
        JSONArray().apply { history.forEach { put(it) } }
    }

    private fun appendHistory(entry: JSONObject) {
        synchronized(history) { history.add(entry) }
    }

    private fun clearHistory() {
        synchronized(history) { history.clear() }
    }

    fun getModelIcon(key: String): String? = synchronized(icons) { icons[key] }

    private fun registerIcon(svg: String?): String? {
        if (svg.isNullOrBlank()) return null
        val key = MessageDigest.getInstance("SHA-1").digest(svg.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.substring(0, 12)
        synchronized(icons) { icons[key] = svg }
        return key
    }

    fun requestStop() {
        stopRequested.set(true)
    }

    private suspend fun checkStop(emit: suspend (JSONObject) -> Unit) {
        if (!stopRequested.compareAndSet(true, false)) return
        callLma("stopGeneration")
        emit(JSONObject().put("type", "stopped"))
        throw GenerationStoppedException()
    }

    // ══════════════════════════════════════════════════════════════
    // 부트스트랩: 로그인 대기 + 초기 모델 조회 (main.py의 시작부 대응)
    // ══════════════════════════════════════════════════════════════
    suspend fun bootstrap() = turnMutex.withLock {
        try {
            setState(stage2 = "starting", clearError = true)
            ensureLibInjected()
            gotoModeInternal(settings.chatMode)

            val loginState = isLoggedInNow()
            if (loginState) {
                setState(stage2 = "ready", loggedIn2 = true)
            } else {
                setState(stage2 = "waiting_login", loggedIn2 = false)
                uiHost?.onRevealAutomation("로그인이 필요합니다")
                callLma("clickSignIn")
                val ok = waitForLogin(600_000)
                uiHost?.onHideAutomation()
                if (ok) {
                    setState(stage2 = "ready", loggedIn2 = true)
                } else {
                    setState(
                        stage2 = "login_timeout",
                        error2 = "로그인 대기 시간이 초과되었습니다. 화면 상단의 로그인 화면에서 다시 로그인해 주세요.",
                    )
                }
            }
        } catch (e: Exception) {
            setState(stage2 = "error", error2 = e.message ?: e.toString())
        }

        if (loggedIn && settings.chatMode != Settings.CHAT_MODE_AGENT) {
            refreshCurrentModel(5000)
        }
    }

    private suspend fun isLoggedInNow(): Boolean {
        val r = callLma("isLoggedIn") as? JSONObject ?: return false
        return r.optBoolean("ok", false)
    }

    private suspend fun waitForLogin(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLoggedInNow()) return true
            delay(600)
        }
        return false
    }

    private suspend fun gotoModeInternal(mode: String) {
        val url = MODE_URLS[mode] ?: DIRECT_MODE_URL
        val wv = automationWebView ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            mainHandler.post {
                wv.loadUrl(url)
                if (cont.isActive) cont.resume(Unit)
            }
        }
        waitForPageReady(15_000)
        ensureLibInjected()
        callLma("installHooks")
    }

    private suspend fun waitForPageReady(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val info = callLma("pageInfo") as? JSONObject
            if (info != null && info.optString("ready") == "complete" && info.optInt("nodes") > 10) break
            delay(200)
        }
        delay(500)
    }

    // ══════════════════════════════════════════════════════════════
    // 잡: 새 채팅 / 모드 전환 / 설정
    // ══════════════════════════════════════════════════════════════
    suspend fun newChat(): JSONObject = turnMutex.withLock {
        val agent = settings.chatMode == Settings.CHAT_MODE_AGENT
        val ok = (callLma("clickNewChat", agent.toString()) as? Boolean) ?: false
        if (ok) {
            delay(400)
            clearHistory()
            workspaceSharedThisSession = false
            setState(agentReviewPending2 = false)
            if (!agent) refreshCurrentModel(3000)
        }
        JSONObject().put("type", "new_chat_result").put("ok", ok)
    }

    suspend fun setChatMode(modeRaw: String): JSONObject = turnMutex.withLock {
        val mode = Settings.normalizeChatMode(modeRaw)
        try {
            gotoModeInternal(mode)
        } catch (e: Exception) {
            return@withLock JSONObject().put("type", "chat_mode_result").put("ok", false)
                .put("chat_mode", settings.chatMode).put("error", e.message)
        }
        settings.chatMode = mode
        clearHistory()
        workspaceSharedThisSession = false
        setState(agentReviewPending2 = false)
        if (mode == Settings.CHAT_MODE_AGENT) {
            setState(clearCurrentModel = true)
        } else {
            refreshCurrentModel(3000)
        }
        JSONObject().put("type", "chat_mode_result").put("ok", true)
            .put("chat_mode", mode).put("current", currentModel).put("error", JSONObject.NULL)
    }

    fun setTarget(raw: String): JSONObject {
        settings.targetDir = raw
        workspaceSharedThisSession = false
        return JSONObject().put("type", "target_result").put("target_dir", targetDir().absolutePath)
    }

    fun setShareWorkspace(value: Boolean): JSONObject {
        val was = settings.shareWorkspace
        settings.shareWorkspace = value
        if (value && !was) workspaceSharedThisSession = false
        return JSONObject().put("type", "share_workspace_result").put("value", value)
    }

    fun setWorkMode(value: Boolean): JSONObject {
        settings.workMode = value
        return JSONObject().put("type", "work_mode_result").put("value", value)
    }

    fun setAutoWrite(value: Boolean): JSONObject {
        settings.autoWriteFiles = value
        return JSONObject().put("type", "auto_write_result").put("value", value)
    }

    fun setFilenameEnforcement(value: Boolean): JSONObject {
        settings.enforceFilenameComments = value
        return JSONObject().put("type", "filename_enforcement_result").put("value", value)
    }

    suspend fun refreshLogin(): JSONObject = turnMutex.withLock {
        val ok = isLoggedInNow()
        setState(loggedIn2 = ok)
        JSONObject().put("type", "login_result").put("logged_in", ok)
    }

    // ══════════════════════════════════════════════════════════════
    // 모델 선택 (model_selector.py 오케스트레이션 포팅)
    // ══════════════════════════════════════════════════════════════
    private suspend fun currentModelPayload(): JSONObject? {
        val info = callLma("readCurrentModel") as? JSONObject ?: return null
        if (!info.optBoolean("found", false)) return null
        val name = info.optString("name", "").trim()
        if (name.isEmpty()) return null
        return JSONObject()
            .put("name", name)
            .put("icon", registerIcon(info.optString("icon", null)))
            .put("slug", info.opt("slug").let { if (it == JSONObject.NULL) null else it })
    }

    suspend fun refreshCurrentModel(waitMs: Long): JSONObject? {
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            val payload = try { currentModelPayload() } catch (e: Exception) { null }
            if (payload != null) {
                setState(currentModel2 = payload)
                return payload
            }
            if (System.currentTimeMillis() >= deadline) return null
            delay(400)
        }
    }

    suspend fun listModels(force: Boolean): JSONObject = turnMutex.withLock {
        val cached = modelCatalog
        if (cached != null && !force) {
            return@withLock modelsPayload(true).put("cached", true)
        }
        try {
            val models = scrapeModels()
            modelCatalog = JSONObject().put("models", models).put("fetched_at", System.currentTimeMillis() / 1000.0)
            refreshCurrentModel(0)
            modelsPayload(true).put("cached", false)
        } catch (e: Exception) {
            modelsPayload(false).put("error", e.message ?: e.toString())
        }
    }

    private fun modelsPayload(ok: Boolean): JSONObject {
        val catalog = modelCatalog
        return JSONObject()
            .put("type", "models_result")
            .put("ok", ok)
            .put("models", catalog?.optJSONArray("models") ?: JSONArray())
            .put("fetched_at", catalog?.opt("fetched_at"))
            .put("current", currentModel)
    }

    private suspend fun scrapeModels(): JSONArray {
        openModelDialog()
        try {
            val itemSel = waitItemSelector(5000) ?: throw AutomationJsException("모델 목록 항목을 찾을 수 없습니다 (DOM 변경 가능성).")
            val found = LinkedHashMap<String, JSONObject>()
            suspend fun merge(): Int {
                val batch = callLma("collectModels", jsStr(itemSel)) as? JSONArray ?: JSONArray()
                var added = 0
                for (i in 0 until batch.length()) {
                    val row = batch.getJSONObject(i)
                    val key = row.optString("label", "").trim().lowercase()
                    if (key.isNotEmpty() && !found.containsKey(key)) {
                        found[key] = JSONObject()
                            .put("name", row.optString("label"))
                            .put("search", row.optString("search", row.optString("label")))
                            .put("icon", registerIcon(row.optString("icon", null)))
                        added++
                    }
                }
                return added
            }
            suspend fun scroll(mode: String): JSONObject =
                callLma("scrollList", "${jsStr(itemSel)},${jsStr(mode)}") as? JSONObject ?: JSONObject()

            merge()
            if (found.isEmpty()) throw AutomationJsException("모델 목록을 읽지 못했습니다 (DOM 변경 가능성).")
            val firstLabel = found.values.first().optString("name")

            var stall = 0
            run bottomLoop@{
                repeat(60) {
                    if (!scroll("bottom").optBoolean("found", false)) return@bottomLoop
                    delay(220)
                    stall = if (merge() == 0) stall + 1 else 0
                    if (stall >= 2) return@bottomLoop
                }
            }
            val idx = callLma("findItem", "${jsStr(itemSel)},${jsStr(firstLabel)}") as? Int ?: -1
            if (idx < 0) {
                scroll("top")
                delay(180)
                stall = 0
                run stepLoop@{
                    repeat(300) {
                        val added = merge()
                        val sc = scroll("step")
                        if (!sc.optBoolean("found", false)) return@stepLoop
                        delay(110)
                        stall = if (sc.optBoolean("moved", false) || added > 0) 0 else stall + 1
                        if (stall >= 2) return@stepLoop
                    }
                }
            }
            if (found.isEmpty()) throw AutomationJsException("모델 목록을 읽지 못했습니다 (DOM 변경 가능성).")
            return JSONArray().apply { found.values.forEach { put(it) } }
        } finally {
            closeModelDialog()
        }
    }

    private suspend fun openModelDialog() {
        val open = (callLma("openModelDialog") as? JSONObject)?.optBoolean("open", false) ?: false
        if (open) return
        if (!(callLma("modelButtonFound") as? Boolean ?: false)) {
            throw AutomationJsException("모델 선택 버튼을 찾지 못했습니다 (DOM 변경 가능성).")
        }
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            if (callLma("searchVisible") as? Boolean == true) return
            delay(100)
        }
        throw AutomationJsException("모델 선택 창을 열지 못했습니다 (DOM 변경 가능성).")
    }

    private suspend fun closeModelDialog() {
        repeat(2) {
            if (callLma("searchVisible") as? Boolean != true) return
            callLma("pressEscape")
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                if (callLma("searchVisible") as? Boolean != true) return
                delay(100)
            }
        }
    }

    private suspend fun waitItemSelector(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val sel = callLma("itemSelector") as? String
            if (!sel.isNullOrEmpty()) return sel
            delay(100)
        }
        return null
    }

    suspend fun selectModel(name: String): JSONObject = turnMutex.withLock {
        val target = name.trim()
        if (target.isEmpty()) {
            return@withLock JSONObject().put("type", "model_result").put("ok", false)
                .put("current", JSONObject.NULL).put("reset", false).put("error", "모델 이름이 비어 있습니다.")
        }
        var uiError: String? = null
        var uiBroken = false
        var clicked = false
        try {
            val current = currentModelPayload()
            if (current != null && current.optString("name").trim().equals(target, ignoreCase = true)) {
                return@withLock JSONObject().put("type", "model_result").put("ok", true)
                    .put("current", current).put("reset", false).put("error", JSONObject.NULL)
            }
            openModelDialog()
            val itemSel = waitItemSelector(5000)
                ?: throw AutomationJsException("모델 목록 항목을 찾을 수 없습니다 (DOM 변경 가능성).")
            clicked = clickModelItem(itemSel, target)
            if (!clicked) uiError = "목록에서 '$target' 모델을 찾지 못했습니다."
        } catch (e: AutomationJsException) {
            uiError = e.message; uiBroken = true
        } catch (e: Exception) {
            uiError = "모델 변경 중 오류: ${e.message}"; uiBroken = true
        } finally {
            closeModelDialog()
        }

        if (clicked) {
            val cur = waitCurrentModel(target, 6000)
            if (cur != null) {
                setState(currentModel2 = cur)
                return@withLock JSONObject().put("type", "model_result").put("ok", true)
                    .put("current", cur).put("reset", false).put("error", JSONObject.NULL)
            }
            return@withLock JSONObject().put("type", "model_result").put("ok", false)
                .put("current", currentModelPayload()).put("reset", false)
                .put("error", "모델 변경을 확인하지 못했습니다. 잠시 후 다시 확인해주세요.")
        }

        if (!uiBroken) {
            return@withLock JSONObject().put("type", "model_result").put("ok", false)
                .put("current", currentModelPayload()).put("reset", false).put("error", uiError)
        }

        return@withLock try {
            val slug = target.trim().lowercase().replace(Regex("""\s+"""), "-")
            gotoModeInternal(Settings.CHAT_MODE_DIRECT)
            val wv = automationWebView
            if (wv != null) {
                val urlWithModel = "$DIRECT_MODE_URL?model_a=" + java.net.URLEncoder.encode(slug, "UTF-8")
                suspendCancellableCoroutine<Unit> { cont ->
                    mainHandler.post { wv.loadUrl(urlWithModel); if (cont.isActive) cont.resume(Unit) }
                }
                waitForPageReady(8000)
                ensureLibInjected()
            }
            val cur = waitCurrentModel(target, 6000)
            if (cur != null && cur.optString("name").equals(target, ignoreCase = true)) {
                clearHistory(); workspaceSharedThisSession = false
                setState(currentModel2 = cur)
                JSONObject().put("type", "model_result").put("ok", true).put("current", cur)
                    .put("reset", true).put("error", JSONObject.NULL)
            } else {
                JSONObject().put("type", "model_result").put("ok", false)
                    .put("current", currentModelPayload()).put("reset", true)
                    .put("error", uiError ?: "모델을 변경하지 못했습니다.")
            }
        } catch (e: Exception) {
            JSONObject().put("type", "model_result").put("ok", false)
                .put("current", currentModelPayload()).put("reset", false)
                .put("error", "${uiError ?: "모델을 변경하지 못했습니다."} (${e.message})")
        }
    }

    private suspend fun clickModelItem(itemSel: String, target: String): Boolean {
        for (query in listOf(target, "")) {
            callLma("fillSearch", jsStr(query))
            delay(300)
            callLma("scrollList", "${jsStr(itemSel)},${jsStr("top")}")
            var stall = 0
            var found = false
            run stepLoop@{
                repeat(300) {
                    val idx = callLma("findItem", "${jsStr(itemSel)},${jsStr(target)}") as? Int ?: -1
                    if (idx >= 0) {
                        found = (callLma("clickItem", "${jsStr(itemSel)},$idx") as? Boolean) ?: false
                        return@stepLoop
                    }
                    val sc = callLma("scrollList", "${jsStr(itemSel)},${jsStr("step")}") as? JSONObject ?: JSONObject()
                    if (!sc.optBoolean("found", false)) return@stepLoop
                    delay(120)
                    stall = if (sc.optBoolean("moved", false)) 0 else stall + 1
                    if (stall >= 2) return@stepLoop
                }
            }
            if (found) return true
        }
        return false
    }

    private suspend fun waitCurrentModel(target: String, timeoutMs: Long): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val cur = try { currentModelPayload() } catch (e: Exception) { null }
            if (cur != null && cur.optString("name").trim().equals(target, ignoreCase = true)) return cur
            delay(200)
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════
    // Agent 모드: workspace 트리 / 다운로드 / 리뷰
    // ══════════════════════════════════════════════════════════════
    suspend fun agentWorkspaceTree(): JSONObject = turnMutex.withLock {
        val tree = callLma("readTree") as? JSONArray ?: JSONArray()
        JSONObject().put("type", "agent_workspace").put("tree", tree)
    }

    private suspend fun agentDownloadWorkspaceInternal(): JSONObject {
        val marked = callLma("markWorkspaceDownload") as? JSONObject ?: JSONObject()
        if (!marked.optBoolean("found", false)) {
            return JSONObject().put("ok", false).put("files", JSONArray()).put("archive_name", "")
                .put("error", "workspace 다운로드 버튼을 찾지 못했습니다 (프로젝트가 없거나 DOM 변경).")
        }
        val id = "d${System.currentTimeMillis()}"
        val deferred = DownloadRegistry.begin(id, "workspace.zip")
        callLma("setCapture", "true")
        val fetched = callLma("fetchMarkedDownload") as? Boolean ?: false
        if (!fetched) {
            DownloadRegistry.fail(id, "download trigger failed")
        }
        return try {
            val file = kotlinx.coroutines.withTimeout(60_000) { deferred.await() }
            val written = AgentZip.extract(file, targetDir(), settings.agentStripRootFolder)
            file.delete()
            JSONObject().put("ok", true)
                .put("files", JSONArray(written.map { it.relativeTo(workspaceRoot()).path }))
                .put("archive_name", marked.optString("download", "workspace.zip"))
                .put("error", JSONObject.NULL)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("files", JSONArray()).put("archive_name", "")
                .put("error", "workspace를 받지 못했습니다: ${e.message}")
        } finally {
            callLma("setCapture", "false")
        }
    }

    suspend fun agentDownload(): JSONObject = turnMutex.withLock {
        val r = agentDownloadWorkspaceInternal()
        JSONObject().put("type", "agent_download").apply { r.keys().forEach { k -> put(k, r.get(k)) } }
    }

    suspend fun agentReview(choice: String): JSONObject = turnMutex.withLock {
        val (ok, error) = if (choice == "dismiss") {
            val c = callLma("closeReview") as? Boolean ?: false
            c to (if (c) null else "평가 패널을 닫지 못했습니다.")
        } else {
            val r = callLma("submitReview", jsStr(choice)) as? JSONObject ?: JSONObject()
            (r.optBoolean("ok", false)) to r.optString("error", null)
        }
        if (ok) setState(agentReviewPending2 = false)
        JSONObject().put("type", "agent_review_result").put("ok", ok).put("choice", choice)
            .put("error", error)
    }

    suspend fun logout(): JSONObject = turnMutex.withLock {
        automationWebView?.let { wv ->
            suspendCancellableCoroutine<Unit> { cont ->
                mainHandler.post {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    wv.clearCache(true)
                    wv.clearFormData()
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        setState(stage2 = "closed", loggedIn2 = false)
        JSONObject().put("type", "logout_result").put("ok", true)
    }

    // ══════════════════════════════════════════════════════════════
    // 프롬프트 전송(직접/에이전트/워크 세 모드 공통 진입점)
    // ══════════════════════════════════════════════════════════════
    suspend fun sendPrompt(prompt: String, emit: suspend (JSONObject) -> Unit): Unit = turnMutex.withLock {
        appendHistory(JSONObject().put("role", "user").put("content", prompt))
        emit(JSONObject().put("type", "user_echo").put("prompt", prompt))
        stopRequested.set(false)

        val mode = settings.chatMode
        try {
            when {
                mode == Settings.CHAT_MODE_AGENT -> handleAgentPrompt(prompt, emit)
                settings.workMode -> WorkAgent.run(prompt, emit)
                else -> handleDirectPrompt(prompt, emit)
            }
        } catch (e: GenerationStoppedException) {
            // 정상적인 조기 종료: 이미 "stopped" 이벤트를 보냈다.
        } catch (e: Exception) {
            emit(JSONObject().put("type", "error").put("message", e.message ?: e.toString()))
        }
    }

    private suspend fun shareWorkspaceIfNeeded(emit: suspend (JSONObject) -> Unit): String? {
        if (!(settings.shareWorkspace && !workspaceSharedThisSession)) return null
        return try {
            val snapshot = Workspace.buildSnapshot(targetDir())
            workspaceSharedThisSession = true
            emit(JSONObject().put("type", "workspace_shared").put("included_file_count", snapshot.includedFileCount))
            Workspace.formatContextBlock(snapshot)
        } catch (e: Exception) {
            emit(
                JSONObject().put("type", "workspace_share_warning")
                    .put("message", "작업 폴더를 읽는 중 오류가 발생해 이번 턴은 공유 없이 진행합니다: ${e.message}")
            )
            null
        }
    }

    private fun codeBlockToDict(cb: CodeBlock): JSONObject = cb.toJson()

    private suspend fun performSend(outgoingPrompt: String, agent: Boolean) {
        bridge.setPendingPrompt(outgoingPrompt)
        callLma("beginTurn", agent.toString())
        val fillRes = callLma("fillPrompt") as? JSONObject
        if (fillRes == null || !fillRes.optBoolean("ok", false)) {
            throw AutomationJsException("입력창을 찾지 못했거나 텍스트를 채우지 못했습니다 (DOM 변경 가능성).")
        }
        callLma("focusPrompt")
        val entered = callLma("pressEnter") as? Boolean ?: false
        delay(150)
        val signals = callLma("sendSignals") as? JSONObject
        val stillHasText = signals?.optBoolean("empty", true) == false
        if (!entered || stillHasText) {
            callLma("clickSend")
            delay(150)
        }
    }

    /** browser.py의 iter_response_stream + parse_last_response 를 하나로 합친 스트리머. */
    suspend fun streamTurn(
        agent: Boolean,
        timeoutSeconds: Int,
        emit: suspend (JSONObject) -> Unit,
        onTick: (suspend () -> Unit)? = null,
    ): Pair<String, List<CodeBlock>> {
        var revealedForCaptcha = false
        var reasoningStartMs: Long? = null
        var lastReasoningText = ""
        var reasoningFinal: ReasoningState? = null

        var container = false
        var lastPlain = ""
        var lastBlocks: List<CodeBlock> = emptyList()

        while (!container) {
            onTick?.invoke()
            val poll = callLma("poll", "{\"reasoning\":true}") as? JSONObject ?: JSONObject()
            if (poll.optBoolean("captcha", false)) {
                if (!revealedForCaptcha) { revealedForCaptcha = true; uiHost?.onRevealAutomation("보안 확인이 필요합니다") }
            } else if (revealedForCaptcha) {
                revealedForCaptcha = false; uiHost?.onHideAutomation()
            }
            container = poll.optBoolean("container", false)
            if (!container) {
                val r = poll.opt("reasoning")
                if (r is String && r.isNotEmpty()) {
                    if (reasoningStartMs == null) reasoningStartMs = System.currentTimeMillis()
                    lastReasoningText = r
                }
            }
            if (!container) {
                val state = reasoningStartMs?.let {
                    ReasoningState(lastReasoningText, (System.currentTimeMillis() - it) / 1000.0, false)
                }
                emit(
                    JSONObject().put("type", "delta").put("plain_text", "")
                        .put("code_blocks", JSONArray()).put("reasoning", state?.toJson()).put("done", false)
                )
                checkStop(emit)
                delay(250)
            }
        }
        reasoningFinal = reasoningStartMs?.let {
            ReasoningState(lastReasoningText, (System.currentTimeMillis() - it) / 1000.0, true)
        }

        val pollIntervalMs = 400L
        val confirmDelayMs = 1200L
        val stableChecksNeeded = 3
        var deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var lastFullLen = -1
        var stableCount = 0

        fun blocksFrom(poll: JSONObject): List<CodeBlock> {
            val arr = poll.optJSONArray("code_blocks") ?: JSONArray()
            return (0 until arr.length()).map { CodeBlock.fromJs(arr.getJSONObject(it)) }
        }

        while (System.currentTimeMillis() < deadline) {
            onTick?.invoke()
            val poll = callLma("poll", "{\"reasoning\":false}") as? JSONObject ?: JSONObject()
            if (poll.optBoolean("captcha", false)) {
                if (!revealedForCaptcha) { revealedForCaptcha = true; uiHost?.onRevealAutomation("보안 확인이 필요합니다") }
                stableCount = 0
                deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
                emit(
                    JSONObject().put("type", "delta").put("plain_text", lastPlain)
                        .put("code_blocks", JSONArray(lastBlocks.map { codeBlockToDict(it) }))
                        .put("reasoning", reasoningFinal?.toJson()).put("done", false)
                )
                checkStop(emit)
                delay(pollIntervalMs)
                continue
            } else if (revealedForCaptcha) {
                revealedForCaptcha = false; uiHost?.onHideAutomation()
            }

            val hasContainer = poll.optBoolean("container", false)
            val generating = poll.optBoolean("generating", false)
            val plain = if (hasContainer) poll.optString("plain", "") else lastPlain
            val blocks = if (hasContainer) blocksFrom(poll) else lastBlocks
            val fullLen = poll.optInt("full_len", 0)

            val changed = plain != lastPlain || blocks.map { it.language to it.code } != lastBlocks.map { it.language to it.code }
            if (changed) {
                lastPlain = plain; lastBlocks = blocks
                emit(
                    JSONObject().put("type", "delta").put("plain_text", plain)
                        .put("code_blocks", JSONArray(blocks.map { codeBlockToDict(it) }))
                        .put("reasoning", reasoningFinal?.toJson()).put("done", false)
                )
            }
            checkStop(emit)

            if (!generating && fullLen == lastFullLen && fullLen > 0) {
                stableCount++
                if (stableCount >= stableChecksNeeded) {
                    delay(confirmDelayMs)
                    val recheck = callLma("poll", "{\"reasoning\":false}") as? JSONObject ?: JSONObject()
                    val recheckGenerating = recheck.optBoolean("generating", false)
                    val recheckHasContainer = recheck.optBoolean("container", false)
                    val recheckPlain = if (recheckHasContainer) recheck.optString("plain", "") else lastPlain
                    val recheckBlocks = if (recheckHasContainer) blocksFrom(recheck) else lastBlocks
                    val recheckFullLen = recheck.optInt("full_len", 0)
                    if (!recheckGenerating && recheckFullLen == fullLen) {
                        emit(
                            JSONObject().put("type", "delta").put("plain_text", recheckPlain)
                                .put("code_blocks", JSONArray(recheckBlocks.map { codeBlockToDict(it) }))
                                .put("reasoning", reasoningFinal?.toJson()).put("done", true)
                        )
                        return finalizeTurn(agent, recheckPlain, recheckBlocks)
                    }
                    if (recheckPlain != lastPlain || recheckBlocks != lastBlocks) {
                        lastPlain = recheckPlain; lastBlocks = recheckBlocks
                        emit(
                            JSONObject().put("type", "delta").put("plain_text", recheckPlain)
                                .put("code_blocks", JSONArray(recheckBlocks.map { codeBlockToDict(it) }))
                                .put("reasoning", reasoningFinal?.toJson()).put("done", false)
                        )
                    }
                    lastFullLen = recheckFullLen
                    stableCount = 0
                    continue
                }
            } else {
                stableCount = 0
            }
            lastFullLen = fullLen
            delay(pollIntervalMs)
        }
        return finalizeTurn(agent, lastPlain, lastBlocks)
    }

    private suspend fun finalizeTurn(agent: Boolean, plain: String, blocks: List<CodeBlock>): Pair<String, List<CodeBlock>> {
        val parsed = callLma("parseLast") as? JSONObject
        if (parsed != null) {
            val textParts = parsed.optJSONArray("text_parts")
            val finalPlain = if (textParts != null && textParts.length() > 0) {
                (0 until textParts.length()).joinToString("\n\n") { textParts.getString(it) }
            } else plain
            val cbArr = parsed.optJSONArray("code_blocks") ?: JSONArray()
            val finalBlocks = (0 until cbArr.length()).map { CodeBlock.fromJs(cbArr.getJSONObject(it)) }
            return finalPlain to finalBlocks
        }
        return plain to blocks
    }

    private suspend fun handleDirectPrompt(prompt: String, emit: suspend (JSONObject) -> Unit) {
        val workspaceContext = shareWorkspaceIfNeeded(emit)
        val outgoing = PromptAugment.buildOutgoingPrompt(
            prompt, settings.enforceFilenameComments, workspaceContext, settings.shareWorkspace,
        )
        performSend(outgoing, agent = false)
        val (finalPlain, finalBlocks) = streamTurn(agent = false, timeoutSeconds = 600, emit = emit)

        var writtenFiles: List<String> = emptyList()
        if (finalBlocks.isNotEmpty() && settings.autoWriteFiles) {
            writtenFiles = FileWriterUtil.writeCodeBlocks(targetDir(), finalBlocks).map { it.relativePath }
        }
        appendHistory(
            JSONObject().put("role", "assistant").put("content", finalPlain)
                .put("code_blocks", JSONArray(finalBlocks.map { codeBlockToDict(it) }))
                .put("written_files", JSONArray(writtenFiles))
        )
        emit(
            JSONObject().put("type", "final").put("plain_text", finalPlain)
                .put("code_blocks", JSONArray(finalBlocks.map { codeBlockToDict(it) }))
                .put("written_files", JSONArray(writtenFiles))
        )
    }

    private suspend fun handleAgentPrompt(prompt: String, emit: suspend (JSONObject) -> Unit) {
        val workspaceContext = shareWorkspaceIfNeeded(emit)
        val outgoing = PromptAugment.buildOutgoingPrompt(
            prompt, settings.enforceFilenameComments, workspaceContext, settings.shareWorkspace,
        )
        performSend(outgoing, agent = true)

        var lastTreeSig: String? = null
        var lastTreeCheckMs = 0L
        val onTick: suspend () -> Unit = {
            val now = System.currentTimeMillis()
            if (now - lastTreeCheckMs >= 800) {
                lastTreeCheckMs = now
                val tree = callLma("readTree") as? JSONArray ?: JSONArray()
                val sig = tree.toString()
                if (sig != lastTreeSig) {
                    lastTreeSig = sig
                    emit(JSONObject().put("type", "agent_workspace").put("tree", tree))
                }
            }
        }

        val (finalPlain, finalBlocks) = streamTurn(agent = true, timeoutSeconds = 1800, emit = emit, onTick = onTick)

        var writtenFiles: List<String> = emptyList()
        if (finalBlocks.isNotEmpty() && settings.autoWriteFiles) {
            try {
                writtenFiles = FileWriterUtil.writeCodeBlocks(targetDir(), finalBlocks).map { it.relativePath }
            } catch (e: Exception) {
                emit(JSONObject().put("type", "agent_status").put("message", "파일 저장 중 오류: ${e.message}"))
            }
        }

        val finalTree = callLma("readTree") as? JSONArray ?: JSONArray()
        if (finalTree.toString() != lastTreeSig && finalTree.length() > 0) {
            emit(JSONObject().put("type", "agent_workspace").put("tree", finalTree))
        }

        appendHistory(
            JSONObject().put("role", "assistant").put("content", finalPlain)
                .put("code_blocks", JSONArray(finalBlocks.map { codeBlockToDict(it) }))
                .put("written_files", JSONArray(writtenFiles))
                .put("agent_tree", finalTree)
        )
        emit(
            JSONObject().put("type", "final").put("plain_text", finalPlain)
                .put("code_blocks", JSONArray(finalBlocks.map { codeBlockToDict(it) }))
                .put("written_files", JSONArray(writtenFiles))
        )

        val finished = waitForReview(12_000)
        if (finished) {
            if (settings.agentAutoDownload) {
                emit(JSONObject().put("type", "agent_status").put("message", "프로젝트가 끝났습니다. workspace를 내려받는 중..."))
                val dl = agentDownloadWorkspaceInternal()
                emit(JSONObject().put("type", "agent_download").apply { dl.keys().forEach { k -> put(k, dl.get(k)) } })
            }
            val options = callLma("readReviewOptions") as? JSONArray ?: JSONArray()
            setState(agentReviewPending2 = true)
            emit(
                JSONObject().put("type", "agent_review").put("question", "이 작업이 성공했습니까?")
                    .put("options", options)
            )
        }
    }

    private suspend fun waitForReview(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (callLma("reviewPending") as? Boolean == true) return true
            delay(300)
        }
        return false
    }

    /** WorkAgent에서 재사용하는 "한 턴 보내고 끝까지 받기" (webapp.py _handle_work_prompt.turn). */
    suspend fun runWorkTurn(outgoingPrompt: String, emit: suspend (JSONObject) -> Unit): Pair<String, List<CodeBlock>> {
        performSend(outgoingPrompt, agent = false)
        val (finalPlain, finalBlocks) = streamTurn(agent = false, timeoutSeconds = 600, emit = emit)
        appendHistory(
            JSONObject().put("role", "assistant").put("content", finalPlain)
                .put("code_blocks", JSONArray(finalBlocks.map { codeBlockToDict(it) }))
                .put("written_files", JSONArray())
        )
        emit(
            JSONObject().put("type", "final").put("plain_text", finalPlain)
                .put("code_blocks", JSONArray(finalBlocks.map { codeBlockToDict(it) }))
                .put("written_files", JSONArray())
        )
        return finalPlain to finalBlocks
    }

    suspend fun checkStopPublic(emit: suspend (JSONObject) -> Unit) = checkStop(emit)

    fun shareWorkspaceIfNeededPublic(): Boolean = settings.shareWorkspace && !workspaceSharedThisSession
    suspend fun shareWorkspaceForWork(emit: suspend (JSONObject) -> Unit): String? = shareWorkspaceIfNeeded(emit)

    fun buildOutgoingPromptForWork(prompt: String): String =
        PromptAugment.buildOutgoingPrompt(prompt, settings.enforceFilenameComments, null, false)
}
