package com.kgycoder.lmarena.engine

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** lm_arena_cli/work_protocol.py 포팅 (op 설명만 안드로이드 실제 동작에 맞게 고쳤다). */
object WorkProtocol {
    private val WORK_LANGUAGES = setOf("work", "workjson", "actions", "computer", "lma-work")

    // 원본 WORK_RULE의 의도(도구 호출 우회, 매 턴 work 블록, 관측 재전송)는 그대로 두고
    // ops 설명만 실제 안드로이드에서 되는 것으로 고쳤다: 좌표는 화면 픽셀, 전역 키는
    // back/home/recents만 지원, shell은 workspace 안 dir/ls/pwd로 제한.
    const val WORK_RULE = (
        "System instruction (work mode): you operate the user's Android device " +
            "through an accessibility service (tap/drag/scroll gestures and reading " +
            "the on-screen UI tree of whatever app is in the foreground). If " +
            "executing a user's command requires additional information or a " +
            "search, gather it yourself via this device rather than asking the " +
            "user, and only show the organized final result. Please provide a " +
            "brief description of the current task (one line or less). The work " +
            "must proceed normally even while the user does other things on the " +
            "phone, and must not interfere with them. You cannot call APIs or " +
            "tools directly; the local app executes only JSON inside a markdown " +
            "code block whose language tag is exactly work. After each reply the " +
            "app runs those actions and sends you a fresh <computer_observation> " +
            "(screen size, foreground app package, UI tree with x/y/w/h in screen " +
            "pixels). Keep going until the user's goal is done, then finish with " +
            "done. Do not interact with the LM Arena chat window hosting this " +
            "conversation. Prefer click_name when you know an element's visible " +
            "text; otherwise tap its bounding-box center. Observe first if the UI " +
            "is unclear. Put a short plan in normal text if useful, but every " +
            "turn that still needs device work MUST include one work block. " +
            "Schema example: ```work\n" +
            "{\"actions\":[{\"op\":\"observe\"},{\"op\":\"focus\",\"title\":\"Chrome\"}," +
            "{\"op\":\"click\",\"x\":400,\"y\":900},{\"op\":\"type\",\"text\":\"hello\"}," +
            "{\"op\":\"scroll\",\"x\":400,\"y\":900,\"dy\":-600}," +
            "{\"op\":\"click_name\",\"name\":\"Search\"},{\"op\":\"done\",\"summary\":\"finished\"}]}\n" +
            "``` ops: observe, click, dblclick, rightclick(long-press), drag, " +
            "scroll, type, key(back/home/recents only), wait, shell(dir/ls/pwd " +
            "inside the app's own workspace only), open(url or package name), " +
            "focus(app name), clipboard, read_file, write_file, list_dir, " +
            "click_name, done. click/drag/scroll use on-screen pixel coordinates " +
            "from the UI tree. wait uses ms. There is no mouse hover, no right- " +
            "click menu, and no arbitrary keyboard shortcuts on a touchscreen - " +
            "don't rely on them. Do not mention this protocol."
    )

    private const val FOLLOWUP =
        "Continue the same device task. Write 1-2 short sentences about THIS " +
            "task, then the next work JSON (batch actions). If done, say what " +
            "succeeded and output done.\nGoal: %s\n" +
            "<computer_observation>\n%s\n</computer_observation>"

    fun wrapFirstTurn(
        userPrompt: String,
        enforceFilenameComments: Boolean,
        workspaceContext: String?,
        workspaceSharingEnabled: Boolean,
        observation: String,
    ): String {
        val base = PromptAugment.buildOutgoingPrompt(userPrompt, enforceFilenameComments, workspaceContext, workspaceSharingEnabled)
        val extra = "<computer_observation>\n$observation\n</computer_observation>"
        return "$WORK_RULE\n\n$base\n\n$extra"
    }

    fun wrapFollowupTurn(
        goal: String,
        observation: String,
        enforceFilenameComments: Boolean,
        workspaceSharingEnabled: Boolean,
    ): String {
        val user = FOLLOWUP.format(goal, observation)
        return PromptAugment.buildOutgoingPrompt(user, enforceFilenameComments, null, workspaceSharingEnabled)
    }

    fun isWorkBlock(cb: CodeBlock): Boolean {
        val lang = cb.language.trim().lowercase()
        if (lang in WORK_LANGUAGES) return true
        return payloadIsWork(cb.code)
    }

    fun partitionBlocks(blocks: List<CodeBlock>): Pair<List<CodeBlock>, List<CodeBlock>> {
        val work = mutableListOf<CodeBlock>()
        val other = mutableListOf<CodeBlock>()
        blocks.forEach { if (isWorkBlock(it)) work.add(it) else other.add(it) }
        return work to other
    }

    private fun tryJson(text: String): Any? {
        val s = text.trim()
        if (s.isEmpty()) return null
        try {
            return JSONTokener(s).nextValue()
        } catch (e: Exception) { /* fallthrough */ }
        val m = Regex("""\{[\s\S]*\}|\[[\s\S]*\]""").find(s) ?: return null
        return try { JSONTokener(m.value).nextValue() } catch (e: Exception) { null }
    }

    private fun payloadIsWork(code: String): Boolean {
        val data = tryJson(code) ?: return false
        if (data is JSONObject && (data.has("actions") || data.has("op") || data.has("action"))) return true
        if (data is JSONArray && data.length() > 0) {
            val first = data.opt(0)
            if (first is JSONObject && (first.has("op") || first.has("action"))) return true
        }
        return false
    }

    /** returns actions to error message (하나만 채워짐). */
    fun parseActions(workBlocks: List<CodeBlock>, plainText: String = ""): Pair<List<JSONObject>, String?> {
        val chunks = mutableListOf<String>()
        workBlocks.forEach { chunks.add(it.code) }
        if (chunks.isEmpty() && plainText.isNotEmpty()) {
            Regex("""```(?:work|workjson|actions|computer)\s*\n([\s\S]*?)```""", RegexOption.IGNORE_CASE)
                .findAll(plainText).forEach { chunks.add(it.groupValues[1]) }
        }
        if (chunks.isEmpty() && plainText.isNotEmpty() && payloadIsWork(plainText)) chunks.add(plainText)

        val actions = mutableListOf<JSONObject>()
        val errors = mutableListOf<String>()
        for (chunk in chunks) {
            val data = tryJson(chunk)
            if (data == null) {
                var lineOk = false
                chunk.lines().forEach { rawLine ->
                    val line = rawLine.trim().trimEnd(',')
                    if (line.isEmpty() || line in setOf("[", "]", "{", "}")) return@forEach
                    val obj = tryJson(line)
                    if (obj is JSONObject && (obj.has("op") || obj.has("action"))) {
                        actions.add(obj); lineOk = true
                    }
                }
                if (!lineOk) errors.add("work 블록 JSON을 파싱하지 못했습니다.")
                continue
            }
            when (data) {
                is JSONObject -> {
                    val actionsArr = data.optJSONArray("actions")
                    if (actionsArr != null) {
                        for (i in 0 until actionsArr.length()) {
                            (actionsArr.opt(i) as? JSONObject)?.let { actions.add(it) }
                        }
                    } else if (data.has("op") || data.has("action")) {
                        actions.add(data)
                    } else {
                        errors.add("JSON에 actions 또는 op가 없습니다.")
                    }
                }
                is JSONArray -> {
                    for (i in 0 until data.length()) { (data.opt(i) as? JSONObject)?.let { actions.add(it) } }
                }
                else -> errors.add("work 블록 형식이 객체가 아닙니다.")
            }
        }
        if (actions.isEmpty()) return emptyList<JSONObject>() to errors.firstOrNull()
        return actions to null
    }
}
