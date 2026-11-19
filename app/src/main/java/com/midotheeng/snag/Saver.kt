package com.midotheeng.snag

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/**
 * Hands each file to Android's DownloadManager: downloads continue in the background,
 * show in the notification shade, and land in Pictures/Snag and Movies/Snag.
 */
object Saver {
    fun fileName(post: Post, m: Media, ext: String = m.ext): String {
        val who = listOf(post.source, post.user).filter { it.isNotBlank() }
            .joinToString("_").replace(Regex("[^A-Za-z0-9_]"), "")
        val n = post.media.indexOf(m) + 1
        return buildString {
            append(who.ifBlank { "snag" }).append('_').append(post.id.take(24))
            if (post.media.size > 1) append('_').append(n)
            append('.').append(ext)
        }
    }

    fun enqueue(ctx: Context, post: Post, items: List<Media>) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        items.forEach { m ->
            val name = fileName(post, m)
            val photo = m.kind == Kind.PHOTO
            val mime = when {
                !photo -> "video/mp4"
                m.ext == "jpg" -> "image/jpeg"
                else -> "image/${m.ext}"
            }
            val req = DownloadManager.Request(Uri.parse(m.url))
                .setTitle(name)
                .setDescription("Snag")
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    if (photo) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES, "Snag/$name"
                )
                .addRequestHeader("User-Agent", MOBILE_UA)
            m.headers.forEach { (k, v) -> req.addRequestHeader(k, v) }
            dm.enqueue(req)
        }
    }
}
