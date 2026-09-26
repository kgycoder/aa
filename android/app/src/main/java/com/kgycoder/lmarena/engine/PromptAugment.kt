package com.kgycoder.lmarena.engine

/** lm_arena_cli/prompt_augment.py 포팅. 문구는 원본과 동일(영문 지침 유지 - LM Arena에 어떤 모델이
 *  걸리든 안정적으로 이해되도록 원본 저자가 의도적으로 선택한 문구이므로 그대로 둔다). */
object PromptAugment {
    private const val SEPARATOR = "\n\n"
    private const val USER_REQUEST_LABEL = "User request:\n"

    private const val FILENAME_ONLY_RULE =
        "System instruction: whenever your answer includes a code block, put a " +
            "filename comment as its very first line, using that language's normal " +
            "comment syntax - for example # filename: app.py, // filename: app.js, " +
            "or <!-- filename: index.html -->. Give it a real, descriptive filename " +
            "with the correct extension (never \"generated\" or \"untitled\"), and " +
            "reuse the exact same filename if you're editing a file mentioned " +
            "earlier in this chat. Follow this instruction for every code block in " +
            "your reply, but don't mention it or explain it - just apply it " +
            "silently while answering the user's request normally."

    private const val WORKSPACE_ONLY_RULE =
        "System instruction: whenever you add or edit a file, put a filename " +
            "comment as the first line of its code block. For a file inside a " +
            "subfolder of the workspace shared with you below, use its full path " +
            "relative to the workspace root - for example # filename: src/App.js " +
            "- matching the folder structure you were shown. Reuse the exact same " +
            "path when editing a file you were already shown. Follow this " +
            "instruction for every code block in your reply, but don't mention it " +
            "or explain it - just apply it silently while answering the user's " +
            "request normally."

    private const val MERGED_RULE =
        "System instruction: whenever your answer includes a code block, put a " +
            "filename comment as its very first line, using that language's normal " +
            "comment syntax - for example # filename: app.py, // filename: " +
            "app.js. Give it a real, descriptive filename with the correct " +
            "extension (never \"generated\" or \"untitled\"). For a file inside a " +
            "subfolder of the workspace shared with you below, use its full path " +
            "instead - for example # filename: src/App.js - matching the folder " +
            "structure you were shown. Reuse the exact same filename or path when " +
            "editing a file you were already shown or discussed earlier in this " +
            "chat. Follow this instruction for every code block in your reply, but " +
            "don't mention it or explain it - just apply it silently while " +
            "answering the user's request normally."

    private const val WORKSPACE_WRAPPER_HEAD =
        "<workspace_context>\n" +
            "Full folder structure and file contents of the workspace folder the " +
            "user shared with you, provided once for this chat session.\n\n"
    private const val WORKSPACE_WRAPPER_TAIL = "\n</workspace_context>"

    fun buildOutgoingPrompt(
        userPrompt: String,
        enabled: Boolean,
        workspaceContext: String?,
        workspaceSharingEnabled: Boolean,
    ): String {
        val ruleText = when {
            workspaceSharingEnabled && enabled -> MERGED_RULE
            workspaceSharingEnabled -> WORKSPACE_ONLY_RULE
            enabled -> FILENAME_ONLY_RULE
            else -> null
        }
        val blocks = mutableListOf<String>()
        if (ruleText != null) blocks.add(ruleText)
        if (!workspaceContext.isNullOrEmpty()) {
            blocks.add(WORKSPACE_WRAPPER_HEAD + workspaceContext + WORKSPACE_WRAPPER_TAIL)
        }
        if (blocks.isEmpty()) return userPrompt
        return blocks.joinToString(SEPARATOR) + SEPARATOR + USER_REQUEST_LABEL + userPrompt
    }
}
