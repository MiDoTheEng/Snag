package com.midotheeng.snag

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/**
 * Hands each file to Android's DownloadManager, so downloads keep going even
 * after the share sheet closes, show a progress notification, and land in the
 * gallery (Pictures/Snag and Movies/Snag).
 */
object Saver {
    fun enqueue(ctx: Context, tweet: Tweet, items: List<Media>): List<Long> {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val who = tweet.user.ifBlank { "x" }.replace(Regex("[^A-Za-z0-9_]"), "")
        val multi = tweet.media.size > 1
        return items.map { m ->
            val n = tweet.media.indexOf(m) + 1
            val name = buildString {
                append(who).append('_').append(tweet.id)
                if (multi) append('_').append(n)
                if (m.kind == Kind.GIF) append("_gif")
                append('.').append(m.ext)
            }
            val dir = if (m.kind == Kind.PHOTO) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
            val req = DownloadManager.Request(Uri.parse(m.url))
                .setTitle(name)
                .setDescription("Snag · @$who")
                .setMimeType(if (m.kind == Kind.PHOTO) "image/${if (m.ext == "jpg") "jpeg" else m.ext}" else "video/mp4")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(dir, "Snag/$name")
                .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            dm.enqueue(req)
        }
    }
}
