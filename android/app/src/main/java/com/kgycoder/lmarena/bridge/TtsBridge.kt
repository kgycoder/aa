package com.kgycoder.lmarena.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.Locale

/**
 * 최신 Android 시스템 WebView는 보통 speechSynthesis(TTS)를 이미 지원하므로 이 브리지는
 * lma_speech_shim.js가 window.speechSynthesis 부재를 감지했을 때만 쓰이는 안전망이다.
 */
class TtsBridge(context: Context, private val webViewProvider: () -> WebView?) {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { emit(utteranceId, "start") }
            override fun onDone(utteranceId: String?) { emit(utteranceId, "end") }
            @Deprecated("legacy") override fun onError(utteranceId: String?) { emit(utteranceId, "error") }
            override fun onError(utteranceId: String?, errorCode: Int) { emit(utteranceId, "error") }
        })
    }

    @JavascriptInterface
    fun speak(id: Int, text: String, lang: String) {
        handler.post {
            try {
                tts?.language = Locale.forLanguageTag(lang.ifBlank { "ko-KR" })
            } catch (_: Exception) {}
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString())
        }
    }

    @JavascriptInterface
    fun cancel() {
        handler.post { tts?.stop() }
    }

    private fun emit(utteranceId: String?, kind: String) {
        val id = utteranceId?.toIntOrNull() ?: return
        handler.post {
            webViewProvider()?.evaluateJavascript(
                "window.__lmaTtsEvent && window.__lmaTtsEvent($id,'$kind')", null,
            )
        }
    }

    fun release() {
        try { tts?.shutdown() } catch (_: Exception) {}
    }
}
