package com.kgycoder.lmarena.engine

import com.kgycoder.lmarena.work.ComputerAndroid
import org.json.JSONObject

/** lm_arena_cli/work_agent.py 포팅. */
object WorkAgent {
    private const val MAX_STEPS = 28
    private const val MAX_ACTIONS_PER_TURN = 16

    suspend fun run(userPrompt: String, emit: suspend (JSONObject) -> Unit) {
        emit(
            JSONObject().put("type", "work_status")
                .put("message", "Work 모드: LM Arena가 이 기기를 조작합니다. 채팅 창은 건드리지 마세요.")
        )

        val workspaceContext = AutomationEngine.shareWorkspaceForWork(emit)
        val enforceFilename = AutomationEngine.settings.enforceFilenameComments
        val shareEnabled = AutomationEngine.settings.shareWorkspace
        val autoWrite = AutomationEngine.settings.autoWriteFiles

        emit(JSONObject().put("type", "work_status").put("message", "화면과 창 상태를 읽는 중..."))
        var observation = ComputerAndroid.observeText()
        var outgoing = WorkProtocol.wrapFirstTurn(userPrompt, enforceFilename, workspaceContext, shareEnabled, observation)

        var done = false
        var summary = ""
        var steps = 0
        var reachedMax = true

        for (step in 1..MAX_STEPS) {
            steps = step
            emit(JSONObject().put("type", "work_turn").put("step", step).put("max", MAX_STEPS))

            val (finalPlain, finalBlocks) = AutomationEngine.runWorkTurn(outgoing, emit)
            val (workBlocks, otherBlocks) = WorkProtocol.partitionBlocks(finalBlocks)

            if (otherBlocks.isNotEmpty() && autoWrite) {
                try { FileWriterUtil.writeCodeBlocks(AutomationEngine.targetDir(), otherBlocks) } catch (_: Exception) {}
            }

            val (actions, parseErr) = WorkProtocol.parseActions(workBlocks, finalPlain)
            if (parseErr != null && actions.isEmpty()) {
                emit(JSONObject().put("type", "work_status").put("message", parseErr))
                observation = "PARSE_ERROR: $parseErr\nLast observation:\n${ComputerAndroid.observeText()}"
                outgoing = WorkProtocol.wrapFollowupTurn(userPrompt, observation, enforceFilename, shareEnabled)
                continue
            }

            if (actions.isEmpty()) {
                emit(JSONObject().put("type", "work_status").put("message", "Work 액션이 없어 이번 명령을 종료합니다."))
                summary = finalPlain.trim().take(500)
                reachedMax = false
                break
            }

            val logs = mutableListOf<String>()
            for (raw in actions.take(MAX_ACTIONS_PER_TURN)) {
                val op = (raw.optString("op", raw.optString("action", ""))).lowercase()
                if (op == "done") {
                    done = true
                    summary = raw.optString("summary", finalPlain.trim().take(500))
                    emit(JSONObject().put("type", "work_action").put("op", "done").put("ok", true).put("detail", summary))
                    break
                }
                var ok: Boolean
                var detail: String
                try {
                    detail = ComputerAndroid.perform(raw)
                    ok = true
                } catch (e: Exception) {
                    detail = e.message ?: e.toString()
                    ok = false
                }
                logs.add("${if (ok) "OK" else "FAIL"} $op: $detail")
                emit(JSONObject().put("type", "work_action").put("op", op).put("ok", ok).put("detail", short(detail)))
            }

            if (done) { reachedMax = false; break }

            observation = "action_log:\n" + logs.joinToString("\n") + "\n\n" + ComputerAndroid.observeText()
            emit(JSONObject().put("type", "work_status").put("message", "Work $step/$MAX_STEPS 관측을 다시 전송합니다."))
            outgoing = WorkProtocol.wrapFollowupTurn(userPrompt, observation, enforceFilename, shareEnabled)
        }

        if (reachedMax) {
            emit(JSONObject().put("type", "work_status").put("message", "최대 스텝($MAX_STEPS)에 도달해 중단합니다."))
        }

        emit(JSONObject().put("type", "work_done").put("steps", steps).put("done", done).put("summary", summary))
    }

    private fun short(text: String, n: Int = 240): String {
        val cleaned = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (cleaned.length <= n) cleaned else cleaned.substring(0, n - 3) + "..."
    }
}
