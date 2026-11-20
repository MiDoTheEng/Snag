package com.midotheeng.snag

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Instagram's real sign-in page inside Snag. Snag never sees the password — it only
 * keeps the login cookie Instagram gives back, stored on this phone.
 */
class InstagramLogin : Activity() {
    private lateinit var web: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = MOBILE_UA
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = check()
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = check()
        }
        web.loadUrl("https://www.instagram.com/accounts/login/")
    }

    private fun check() {
        if (done) return
        val c = CookieManager.getInstance().getCookie("https://www.instagram.com").orEmpty()
        if (c.contains("sessionid=")) {
            done = true
            CookieManager.getInstance().flush()
            Toast.makeText(this, "Signed in to Instagram", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }

    companion object {
        fun signOut() {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }
}
