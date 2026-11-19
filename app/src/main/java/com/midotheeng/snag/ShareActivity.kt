package com.midotheeng.snag

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible: receives "Share → Snag", passes it to the background service and
 * closes instantly, so you go straight back to what you were doing.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33)
            i?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i?.getParcelableExtra(Intent.EXTRA_STREAM)
        val text = i?.getStringExtra(Intent.EXTRA_TEXT) ?: i?.dataString
        try {
            when {
                text != null && Net.firstUrl(text) != null -> SnagService.startLink(this, text)
                stream != null -> SnagService.startFile(this, stream, i?.type)
                text != null && XApi.extractId(text) != null -> SnagService.startLink(this, text)
                else -> Toast.makeText(this, "Snag: no link found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Snag: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
