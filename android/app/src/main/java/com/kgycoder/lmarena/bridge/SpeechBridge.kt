package com.kgycoder.lmarena.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * app.js의 JARVIS 음성 모드가 기대하는 표준 Web Speech API(SpeechRecognition)를,
 * WebView에는 없는 기능이므로 안드로이드 네이티브 [SpeechRecognizer]로 대신 구현해
 * 보이지 않게 이어준다. JS 쪽 폴리필은 lma_speech_shim.js 참고.
 *
 * "continuous" 모드는 안드로이드 SpeechRecognizer에 직접적인 대응 기능이 없으므로,
 * 무음으로 세션이 끝날 때마다(사용자가 stop()을 부르기 전까지) 자동으로 다시
 * startListening()을 걸어 Chrome의 연속 인식과 체감상 동일하게 동작하게 만든다.
 */
class SpeechBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var lastIntent: Intent? = null
    private var activeId: Int = -1
    private var continuous = false
    private var userStopped = true

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            val code = mapError(error)
            // 무음/타임아웃으로 끝난 것은 진짜 오류가 아니라 "이번 발화 구간이 끝났다"는
            // 신호로 취급한다 - continuous 모드라면 곧장 다시 듣기 시작한다.
            if ((code == "no-speech" || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && continuous && !userStopped) {
                restart()
                return
            }
            emitError(activeId, code)
            emitEnd(activeId)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) emitResult(activeId, text, isFinal = true)
            restart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) emitResult(activeId, text, isFinal = false)
        }
    }

    @JavascriptInterface
    fun start(id: Int, lang: String, continuousFlag: Boolean, interimResults: Boolean) {
        handler.post { startInternal(id, lang, continuousFlag) }
    }

    @JavascriptInterface
    fun stop(id: Int) {
        handler.post {
            userStopped = true
            try { recognizer?.stopListening() } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun abort(id: Int) {
        handler.post {
            userStopped = true
            try { recognizer?.cancel() } catch (_: Exception) {}
            emitEnd(id)
        }
    }

    private fun startInternal(id: Int, lang: String, continuousFlag: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            emitError(id, "service-not-allowed"); emitEnd(id); return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            emitError(id, "not-allowed"); emitEnd(id); return
        }
        activeId = id
        continuous = continuousFlag
        userStopped = false

        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
        lastIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer?.startListening(lastIntent)
        emitJs("window.__lmaSpeechEvent && window.__lmaSpeechEvent($id,'start',null)")
    }

    private fun restart() {
        if (continuous && !userStopped) {
            handler.postDelayed({
                if (!userStopped) {
                    try { recognizer?.startListening(lastIntent) } catch (_: Exception) { emitEnd(activeId) }
                }
            }, 120)
        } else {
            emitEnd(activeId)
        }
    }

    private fun emitResult(id: Int, text: String, isFinal: Boolean) {
        val payload = JSONObject().put(
            "chunks",
            JSONArray().put(JSONObject().put("text", text).put("isFinal", isFinal)),
        )
        emitJs("window.__lmaSpeechEvent && window.__lmaSpeechEvent($id,'result',${payload})")
    }

    private fun emitError(id: Int, code: String) {
        emitJs("window.__lmaSpeechEvent && window.__lmaSpeechEvent($id,'error',${JSONObject().put("error", code)})")
    }

    private fun emitEnd(id: Int) {
        emitJs("window.__lmaSpeechEvent && window.__lmaSpeechEvent($id,'end',null)")
    }

    private fun emitJs(js: String) {
        handler.post { webViewProvider()?.evaluateJavascript(js, null) }
    }

    private fun mapError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "aborted"
        SpeechRecognizer.ERROR_CLIENT -> "aborted"
        SpeechRecognizer.ERROR_SERVER -> "network"
        else -> "unknown"
    }

    fun release() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }
}
