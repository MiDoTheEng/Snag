package com.midotheeng.snag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Does all the work after you share: finds the media, hands normal files to
 * Android's downloader, and turns X/other GIFs into real .gif files.
 * Runs in the background with a quiet notification, so you can keep using your phone.
 */
class SnagService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground("Finding media…")
        val text = intent?.getStringExtra(EXTRA_TEXT)
        val stream = intent?.data
        jobs.incrementAndGet()
        scope.launch {
            try {
                when {
                    stream != null -> saveSharedFile(stream, intent?.type)
                    text != null -> handleLink(text)
                }
            } catch (e: Exception) {
                toast(e.message ?: "Snag couldn't save that.")
            } finally {
                if (jobs.decrementAndGet() == 0) withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun handleLink(text: String) {
        val post = Router.resolve(text)
        val (gifs, rest) = post.media.partition { it.kind == Kind.GIF }
        if (rest.isNotEmpty()) Saver.enqueue(this, post, rest)
        toast("Saving ${label(post.media)}")
        gifs.forEach { g ->
            goForeground("Making GIF…")
            makeGif(post, g)
        }
        if (gifs.isNotEmpty()) toast(if (gifs.size == 1) "GIF saved" else "${gifs.size} GIFs saved")
    }

    // ------------------------------------------------ GIF: download MP4 → real .gif
    private fun makeGif(post: Post, m: Media) {
        val tmp = File.createTempFile("snag", ".mp4", cacheDir)
        try {
            download(m.url, m.headers, tmp)
            val r = MediaMetadataRetriever()
            r.setDataSource(tmp.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val srcW = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val srcH = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (vw, vh) = if (rot == 90 || rot == 270) srcH to srcW else srcW to srcH
            if (dur <= 0 || vw <= 0) throw Exception("Couldn't read that GIF.")

            // Up to 480px on the long side (WhatsApp-friendly), ~14 fps, max 15 s.
            val scale = minOf(1f, 480f / maxOf(vw, vh))
            val w = maxOf(2, (vw * scale).toInt()); val h = maxOf(2, (vh * scale).toInt())
            val stepMs = 70L
            val total = minOf(dur, 15_000L)
            val gif = GifMaker(w, h, (stepMs / 10).toInt())
            val px = IntArray(w * h)
            var t = 0L
            while (t < total) {
                val bmp = r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                if (bmp != null) {
                    val b = if (bmp.width != w || bmp.height != h) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
                    b.getPixels(px, 0, w, 0, 0, w, h)
                    gif.addFrame(px)
                    if (b !== bmp) b.recycle()
                    bmp.recycle()
                }
                t += stepMs
            }
            r.release()
            if (gif.frameCount() == 0) throw Exception("Couldn't read that GIF.")

            val name = Saver.fileName(post, m, "gif")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Snag")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Couldn't save the GIF.")
            contentResolver.openOutputStream(uri)!!.buffered().use { gif.write(it) }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } finally { tmp.delete() }
    }

    private fun download(url: String, headers: Map<String, String>, to: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", MOBILE_UA)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode !in 200..299) throw Exception("Download failed (${c.responseCode})")
            c.inputStream.use { i -> to.outputStream().use { o -> i.copyTo(o) } }
        } finally { c.disconnect() }
    }

    // ------------------------------------------------ an image/video file shared directly (e.g. from Google)
    private fun saveSharedFile(src: Uri, typeHint: String?) {
        val type = contentResolver.getType(src) ?: typeHint ?: "image/jpeg"
        val isVideo = type.startsWith("video")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: if (isVideo) "mp4" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "snag_${System.currentTimeMillis()}.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Snag")
        }
        val coll = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val dst = contentResolver.insert(coll, values) ?: throw Exception("Couldn't save.")
        contentResolver.openInputStream(src)!!.use { i -> contentResolver.openOutputStream(dst)!!.use { o -> i.copyTo(o) } }
        toast(if (isVideo) "Video saved" else "Photo saved")
    }

    // ------------------------------------------------ helpers
    private fun toast(msg: String) = main.post { Toast.makeText(applicationContext, "Snag: $msg", Toast.LENGTH_SHORT).show() }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Working", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Snag")
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    companion object {
        private const val CH = "snag_work"
        const val EXTRA_TEXT = "text"

        fun startLink(ctx: Context, text: String) =
            ctx.startForegroundService(Intent(ctx, SnagService::class.java).putExtra(EXTRA_TEXT, text))

        fun startFile(ctx: Context, uri: Uri, type: String?) =
            ctx.startForegroundService(
                Intent(ctx, SnagService::class.java).setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
    }
}

fun label(media: List<Media>): String {
    val photos = media.count { it.kind == Kind.PHOTO }
    val videos = media.count { it.kind == Kind.VIDEO }
    val gifs = media.count { it.kind == Kind.GIF }
    return listOfNotNull(
        photos.takeIf { it > 0 }?.let { "$it photo${if (it > 1) "s" else ""}" },
        videos.takeIf { it > 0 }?.let { "$it video${if (it > 1) "s" else ""}" },
        gifs.takeIf { it > 0 }?.let { "$it GIF${if (it > 1) "s" else ""}" },
    ).joinToString(" + ")
}
