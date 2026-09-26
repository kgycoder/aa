package com.kgycoder.lmarena

import android.webkit.WebView

/**
 * WebView가 window.open()(예: "Google로 로그인" 팝업)을 만나면 onCreateWindow에서
 * 새 WebView를 만들어야 한다. 그 WebView 인스턴스를 [PopupWebViewActivity]가 시작될
 * 때까지 잠깐 들고 있는 곳.
 */
object PopupHost {
    @Volatile var pendingWebView: WebView? = null
    @Volatile var onClosed: (() -> Unit)? = null
}
