package com.kgycoder.lmarena.work

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import com.kgycoder.lmarena.engine.AutomationEngine
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

class ComputerError(message: String) : Exception(message)

/**
 * lm_arena_cli/computer.py 포팅. Windows 전용 ops(레지스트리, PowerShell UIA 등)는
 * 안드로이드에서 기술적으로 재현할 수 없는 부분과, AccessibilityService로 실제
 * 동작하는 부분을 명확히 나눠 처리한다 - README의 "기술적으로 불가능한 부분"과 1:1 대응.
 */
object ComputerAndroid {

    private const val MAX_OBS_CHARS = 12_000

    private fun svc(): WorkAccessibilityService =
        WorkAccessibilityService.instance
            ?: throw ComputerError(
                "접근성 서비스가 꺼져 있습니다. 설정 > 접근성 > LM Arena 에서 켠 뒤 다시 시도해 주세요."
            )

    // ── 관측 (computer.py의 observe_text) ──────────────────────────
    suspend fun observeText(): String {
        val parts = mutableListOf<String>()
        parts.add("os=android")
        parts.add("workspace=${AutomationEngine.targetDir().absolutePath}")
        val s = WorkAccessibilityService.instance
        if (s == null) {
            parts.add("note=accessibility_service_off: 접근성 서비스가 꺼져 있어 화면 조작/관찰이 불가능합니다. " +
                "설정 > 접근성 > LM Arena 를 켜 달라고 사용자에게 안내하세요.")
        } else {
            val (w, h) = s.screenSize()
            parts.add("screen=w$w,h$h")
            val fg = s.foregroundPackage()
            parts.add("foreground_package=${fg ?: "?"}")
            val tree = s.dumpTree()
            if (tree != null) parts.add("foreground_ui=" + tree.toString())
        }
        var text = parts.joinToString("\n")
        if (text.length > MAX_OBS_CHARS) text = text.substring(0, MAX_OBS_CHARS - 20) + "\n...[truncated]"
        return text
    }

    // ── 실행 (computer.py의 perform) ───────────────────────────────
    suspend fun perform(action: JSONObject): String {
        val op = (action.optString("op", action.optString("action", ""))).trim().lowercase()
        if (op.isEmpty()) throw ComputerError("op 필드가 없습니다.")

        return when (op) {
            "observe", "screenshot", "look" -> "observed"
            "wait", "sleep" -> {
                val ms = (action.optDouble("ms", action.optDouble("seconds", 0.0) * 1000.0))
                    .let { if (it <= 0) 400.0 else it }.coerceIn(0.0, 30_000.0)
                delay(ms.toLong())
                "waited ${ms.toLong()}ms"
            }
            "click", "left_click", "leftclick" -> click(action, clicks = action.optInt("clicks", 1))
            "dblclick", "double_click", "doubleclick" -> click(action, clicks = 2)
            "rightclick", "right_click", "contextclick" -> {
                // 터치 화면에는 오른쪽 클릭이 없다 - 길게 누르기(컨텍스트 메뉴 여는 표준 제스처)로 대응한다.
                val (x, y) = xy(action)
                if (!svc().longPress(x, y)) throw ComputerError("long-press 실패")
                "long-pressed $x,$y (우클릭 대응)"
            }
            "middleclick", "middle_click" -> throw ComputerError("가운데 클릭은 터치 화면에 대응 동작이 없습니다.")
            "move", "mousemove", "hover" -> {
                // 터치 화면에는 호버 개념이 없다. 좌표만 기록하고 넘어간다(모델이 다음 click에 재사용).
                val (x, y) = xy(action)
                "no-op on touch screens: $x,$y (hover has no touchscreen equivalent)"
            }
            "drag" -> {
                val x1 = action.optInt("x1"); val y1 = action.optInt("y1")
                val x2 = action.optInt("x2"); val y2 = action.optInt("y2")
                if (!svc().swipe(x1, y1, x2, y2, 350)) throw ComputerError("drag 실패")
                "dragged $x1,$y1 -> $x2,$y2"
            }
            "scroll" -> {
                val (x, y) = xy(action, defaultCenter = true)
                val dy = action.optInt("dy", 0)
                if (!svc().scrollAt(x, y, dy)) throw ComputerError("scroll 실패")
                "scrolled dy=$dy at $x,$y"
            }
            "type", "typewrite", "insert" -> {
                val text = action.optString("text", action.optString("value", ""))
                if (!svc().typeIntoFocused(text)) {
                    throw ComputerError("포커스된 입력창을 찾지 못했습니다. 먼저 입력창을 click 하세요.")
                }
                "typed ${text.length} chars"
            }
            "key", "hotkey", "press", "keys" -> pressKey(action)
            "shell" -> shell(action.optString("cmd", action.optString("command", "")))
            "open" -> open(action.optString("target", action.optString("path", action.optString("url", ""))))
            "focus", "activate", "activate_window" -> focusApp(action.optString("title", action.optString("name", "")))
            "clipboard" -> clipboard(action)
            "read_file", "read" -> readFile(action.optString("path", ""))
            "write_file", "write" -> writeFile(action.optString("path", ""), action.optString("content", action.optString("text", "")))
            "list_dir", "ls" -> listDir(action.optString("path", "."))
            "click_name", "click_element", "uia_click" ->
                clickName(action.optString("name", action.optString("title", "")))
            "done" -> action.optString("summary", "done")
            else -> throw ComputerError("알 수 없는 op: $op (안드로이드에서 지원하지 않을 수 있습니다)")
        }
    }

    private fun xy(action: JSONObject, defaultCenter: Boolean = false): Pair<Int, Int> {
        if (action.has("x") && action.has("y")) return action.optInt("x") to action.optInt("y")
        if (defaultCenter) {
            val s = WorkAccessibilityService.instance
            if (s != null) { val (w, h) = s.screenSize(); return w / 2 to h / 2 }
        }
        throw ComputerError("x,y 좌표가 필요합니다.")
    }

    private suspend fun click(action: JSONObject, clicks: Int): String {
        val (x, y) = xy(action)
        if (!svc().tap(x, y, clicks)) throw ComputerError("탭 실패")
        return "clicked $x,$y x$clicks"
    }

    // 키 조합: Win32 SendInput처럼 임의의 하드웨어 키 콤보를 다른 앱에 주입하는 것은
    // 루팅되지 않은 안드로이드에서 서드파티 앱에게 허용되지 않는다(README 참고). 대신
    // AccessibilityService가 공식적으로 제공하는 전역 액션으로 의미가 통하는 것만 매핑한다.
    private fun pressKey(action: JSONObject): String {
        val raw = action.opt("keys") ?: action.opt("key") ?: action.opt("combo")
        val keys = when (raw) {
            is org.json.JSONArray -> (0 until raw.length()).map { raw.getString(it).lowercase() }
            is String -> raw.lowercase().split("+", ",", " ").filter { it.isNotBlank() }
            else -> emptyList()
        }
        val joined = keys.joinToString("+")
        val mapped = when {
            keys.size == 1 && keys[0] in setOf("esc", "escape", "back") -> AccessibilityService.GLOBAL_ACTION_BACK
            keys.size == 1 && keys[0] == "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            keys.contains("recents") || joined == "alt+tab" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> null
        }
        if (mapped != null) {
            if (!svc().globalAction(mapped)) throw ComputerError("key '$joined' 실행 실패")
            return "pressed $joined (global action)"
        }
        throw ComputerError(
            "key '$joined' 는 안드로이드에서 지원하지 않습니다: 서드파티 앱은 임의의 하드웨어 " +
                "키 조합을 다른 앱에 주입할 수 없습니다(OS 보안 제약, 루팅 필요). " +
                "back/home/recents 같은 전역 동작이나, type으로 텍스트를 직접 입력하는 방식을 쓰세요."
        )
    }

    // 셸: 루팅되지 않은 안드로이드에서는 앱이 자기 자신의 샌드박스 밖 명령을 실행할 수
    // 없다. 아주 제한된 안전한 명령(현재 workspace 안 파일 나열 등)만 흉내 낸다.
    private fun shell(cmd: String): String {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) throw ComputerError("cmd가 비어 있습니다.")
        val parts = trimmed.split(Regex("\\s+"))
        return when (parts.getOrNull(0)?.lowercase()) {
            "dir", "ls" -> listDir(parts.getOrElse(1) { "." })
            "pwd", "cd" -> "workspace=${AutomationEngine.targetDir().absolutePath}"
            else -> throw ComputerError(
                "shell은 안드로이드 앱 샌드박스 밖 명령을 실행할 수 없습니다(루팅 필요, OS 보안 제약). " +
                    "허용되는 것은 'dir/ls [경로]', 'pwd' 뿐입니다. 다른 작업은 open/focus/click 등으로 해주세요."
            )
        }
    }

    private fun open(target: String): String {
        if (target.isBlank()) throw ComputerError("target/url이 비어 있습니다.")
        val ctx = AutomationEngine.appContext
        val intent = if (target.contains("://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(target))
        } else {
            ctx.packageManager.getLaunchIntentForPackage(target)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$target"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "opened $target"
        } catch (e: Exception) {
            throw ComputerError("열지 못했습니다: $target (${e.message})")
        }
    }

    // 창 포커스: 데스크톱처럼 "이미 떠 있는 임의의 창"을 강제로 앞으로 가져오는 API는
    // 서드파티 앱에게 없다. 대신 이름으로 설치된 앱을 찾아 새로 실행한다(이미 실행
    // 중이면 대부분의 안드로이드 앱이 기존 태스크를 앞으로 가져온다).
    private fun focusApp(title: String): String {
        if (title.isBlank()) throw ComputerError("title/name이 비어 있습니다.")
        val ctx = AutomationEngine.appContext
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(title, ignoreCase = true) }
            ?: throw ComputerError("'$title' 과(와) 일치하는 설치된 앱을 찾지 못했습니다.")
        val intent = pm.getLaunchIntentForPackage(match.packageName)
            ?: throw ComputerError("'$title' 을(를) 실행할 방법이 없습니다.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        ctx.startActivity(intent)
        return "focused/launched ${pm.getApplicationLabel(match)}"
    }

    private fun clipboard(action: JSONObject): String {
        val mode = action.optString("mode", action.optString("op2", "read"))
        val s = svc()
        return if (mode.equals("write", true) || action.has("text")) {
            val text = action.optString("text", action.optString("content", ""))
            s.setClipboard(text)
            "clipboard set (${text.length} chars)"
        } else {
            val text = s.getClipboard()
            "clipboard: $text"
        }
    }

    // ── 파일 IO: 안드로이드 scoped storage 대응, 앱 workspace 안으로만 제한 ─────
    private fun resolveInWorkspace(path: String): File {
        val root = AutomationEngine.workspaceRoot()
        val clean = path.trim().removePrefix("/").ifBlank { "." }
        val candidate = File(root, clean).canonicalFile
        val rootCanonical = root.canonicalFile
        if (candidate.path != rootCanonical.path && !candidate.path.startsWith(rootCanonical.path + File.separator)) {
            throw ComputerError("workspace 밖 경로는 허용되지 않습니다(안드로이드 앱 샌드박스 제약): $path")
        }
        return candidate
    }

    private fun readFile(path: String): String {
        val f = resolveInWorkspace(path)
        if (!f.exists() || !f.isFile) throw ComputerError("파일이 없습니다: $path")
        val text = f.readText(Charsets.UTF_8)
        return if (text.length > 6000) text.substring(0, 6000) + "\n...[truncated]" else text
    }

    private fun writeFile(path: String, content: String): String {
        val f = resolveInWorkspace(path)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return "wrote ${content.length} chars to $path"
    }

    private fun listDir(path: String): String {
        val f = resolveInWorkspace(path)
        if (!f.exists()) throw ComputerError("경로가 없습니다: $path")
        if (f.isFile) return f.name
        val entries = (f.listFiles() ?: emptyArray()).sortedBy { it.name }
        return entries.joinToString("\n") { (if (it.isDirectory) "d " else "f ") + it.name }
            .ifBlank { "(empty)" }
    }

    private fun clickName(name: String): String {
        if (name.isBlank()) throw ComputerError("name이 비어 있습니다.")
        if (!svc().clickByName(name)) throw ComputerError("'$name' 요소를 화면에서 찾지 못했습니다.")
        return "clicked element named '$name'"
    }
}
