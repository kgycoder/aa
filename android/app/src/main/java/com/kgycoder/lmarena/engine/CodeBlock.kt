package com.kgycoder.lmarena.engine

import org.json.JSONObject

data class CodeBlock(
    val language: String,
    val filenameHint: String?,
    val code: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("language", language)
        .put("filename_hint", filenameHint)
        .put("code", code)

    companion object {
        fun fromJs(o: JSONObject): CodeBlock = CodeBlock(
            language = o.optString("language", "text").ifBlank { "text" },
            filenameHint = o.optString("filename", null).takeUnless { it.isNullOrBlank() },
            code = o.optString("code", ""),
        )
    }
}

data class ParsedResponse(
    val textParts: List<String>,
    val codeBlocks: List<CodeBlock>,
) {
    val plainText: String get() = textParts.filter { it.isNotBlank() }.joinToString("\n\n")
}

data class ReasoningState(
    val text: String,
    val elapsedSeconds: Double,
    val isDone: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("elapsed_seconds", Math.round(elapsedSeconds * 10.0) / 10.0)
        .put("is_done", isDone)
}
