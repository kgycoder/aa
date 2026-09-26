package com.kgycoder.lmarena

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class PopupWebViewActivity : AppCompatActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = PopupHost.pendingWebView
        if (wv == null) {
            finish()
            return
        }
        webView = wv
        val container = FrameLayout(this)
        setContentView(container)
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                finish()
            }
        }
        PopupHost.pendingWebView = null
    }

    override fun onDestroy() {
        webView?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        PopupHost.onClosed?.invoke()
        PopupHost.onClosed = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView?.canGoBack() == true) webView?.goBack() else super.onBackPressed()
    }
}
