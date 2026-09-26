package com.kgycoder.lmarena.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * lm_arena_cli/config.py 의 Settings 데이터클래스를 그대로 옮긴 것.
 * JSON 파일 대신 SharedPreferences에 저장한다는 점만 다르고, 키/기본값/의미는 동일하다.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("lm_arena_settings", Context.MODE_PRIVATE)

    var targetDir: String
        get() = prefs.getString(KEY_TARGET_DIR, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_TARGET_DIR, value).apply()

    var autoWriteFiles: Boolean
        get() = prefs.getBoolean(KEY_AUTO_WRITE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_WRITE, value).apply()

    var enforceFilenameComments: Boolean
        get() = prefs.getBoolean(KEY_ENFORCE_FILENAME, true)
        set(value) = prefs.edit().putBoolean(KEY_ENFORCE_FILENAME, value).apply()

    var shareWorkspace: Boolean
        get() = prefs.getBoolean(KEY_SHARE_WORKSPACE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHARE_WORKSPACE, value).apply()

    var workMode: Boolean
        get() = prefs.getBoolean(KEY_WORK_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_WORK_MODE, value).apply()

    var chatMode: String
        get() = prefs.getString(KEY_CHAT_MODE, CHAT_MODE_DIRECT) ?: CHAT_MODE_DIRECT
        set(value) = prefs.edit().putString(KEY_CHAT_MODE, value).apply()

    var agentAutoDownload: Boolean
        get() = prefs.getBoolean(KEY_AGENT_AUTO_DL, true)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_AUTO_DL, value).apply()

    var agentStripRootFolder: Boolean
        get() = prefs.getBoolean(KEY_AGENT_STRIP, true)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_STRIP, value).apply()

    companion object {
        const val CHAT_MODE_DIRECT = "direct"
        const val CHAT_MODE_AGENT = "agent"

        fun normalizeChatMode(value: String?): String {
            val v = value?.trim()?.lowercase()
            return if (v == CHAT_MODE_AGENT) CHAT_MODE_AGENT else CHAT_MODE_DIRECT
        }

        private const val KEY_TARGET_DIR = "target_dir"
        private const val KEY_AUTO_WRITE = "auto_write_files"
        private const val KEY_ENFORCE_FILENAME = "enforce_filename_comments"
        private const val KEY_SHARE_WORKSPACE = "share_workspace"
        private const val KEY_WORK_MODE = "work_mode"
        private const val KEY_CHAT_MODE = "chat_mode"
        private const val KEY_AGENT_AUTO_DL = "agent_auto_download"
        private const val KEY_AGENT_STRIP = "agent_strip_root_folder"
    }
}

const val DIRECT_MODE_URL = "https://arena.ai/text/direct"
const val AGENT_MODE_URL = "https://arena.ai/agent/"

val MODE_URLS = mapOf(
    Settings.CHAT_MODE_DIRECT to DIRECT_MODE_URL,
    Settings.CHAT_MODE_AGENT to AGENT_MODE_URL,
)
