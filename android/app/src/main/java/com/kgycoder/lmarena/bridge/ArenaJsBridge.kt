package com.kgycoder.lmarena.bridge

import android.util.Base64
import android.webkit.JavascriptInterface
import com.kgycoder.lmarena.engine.DownloadRegistry
import java.util.concurrent.atomic.AtomicReference

/**
 * arena_lib.js가 window.LmaArena 로 호출하는 브리지.
 *
 * - takePrompt(): 매우 긴 프롬프트(워크스페이스 공유 시 수십만 자)를 JS 문자열 리터럴로
 *   임베드하지 않고, Kotlin 쪽에서 미리 세팅해 둔 값을 JS가 "당겨가게" 한다
 *   (browser.py가 paste 이벤트를 쓴 이유와 동일: 큰 문자열을 evaluateJavascript 인자로
 *   직접 넣으면 이스케이프/성능 문제가 생긴다).
 * - onDownloadChunk / onDownloadError: workspace zip 다운로드(agent_mode.py의
 *   download_workspace에 대응)를 위해 JS의 fetch(blob) 결과를 base64 청크로 받는다.
 *
 * 이 인터페이스의 메서드는 WebView 내부 스레드에서 호출될 수 있으므로 스레드 안전해야 한다.
 */
class ArenaJsBridge {
    private val pendingPrompt = AtomicReference("")

    fun setPendingPrompt(text: String) {
        pendingPrompt.set(text)
    }

    @JavascriptInterface
    fun takePrompt(): String {
        return pendingPrompt.getAndSet("")
    }

    /** beginTurn()의 에코 비교용: 소비하지 않고 현재 대기 중인 프롬프트를 그대로 읽는다. */
    @JavascriptInterface
    fun peekPrompt(): String {
        return pendingPrompt.get()
    }

    @JavascriptInterface
    fun onDownloadChunk(id: String, name: String, base64: String, last: Boolean) {
        val bytes = if (base64.isEmpty()) ByteArray(0) else Base64.decode(base64, Base64.NO_WRAP)
        DownloadRegistry.appendChunk(id, name, bytes, last)
    }

    @JavascriptInterface
    fun onDownloadError(id: String, message: String) {
        DownloadRegistry.fail(id, message)
    }
}
