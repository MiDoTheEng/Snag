package com.midotheeng.snag

import android.app.DownloadManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

val Ink = Color(0xFF0F1419)
val Accent = Color(0xFF1D9BF0)
val Ok = Color(0xFF00BA7C)
val Bad = Color(0xFFF4212E)

private sealed interface Phase {
    data object Loading : Phase
    data class Failed(val msg: String) : Phase
    data class Got(val tweet: Tweet, val ids: List<Long>) : Phase
}

/** Fetches the post, starts the downloads, and shows live progress. */
@Composable
fun SnagPanel(tweetId: String, onAllDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    var phase by remember(tweetId) { mutableStateOf<Phase>(Phase.Loading) }
    var progress by remember(tweetId) { mutableStateOf<List<Float>>(emptyList()) } // 0..1, or -1 failed

    LaunchedEffect(tweetId) {
        phase = try {
            val t = XApi.fetch(tweetId)
            Phase.Got(t, Saver.enqueue(ctx, t, t.media))
        } catch (e: Exception) {
            Phase.Failed(e.message ?: "Something went wrong.")
        }
        val got = phase as? Phase.Got ?: return@LaunchedEffect
        val dm = ctx.getSystemService(DownloadManager::class.java)
        while (true) {
            progress = got.ids.map { id -> statusOf(dm, id) }
            if (progress.all { it >= 1f || it < 0f }) break
            delay(350)
        }
        delay(1200)
        onAllDone()
    }

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        when (val p = phase) {
            Phase.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Accent)
                Spacer(Modifier.width(14.dp))
                Text("Finding media…", fontSize = 16.sp, color = Ink)
            }
            is Phase.Failed -> {
                Text("Nothing saved", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text(p.msg, color = Color(0xFF536471), fontSize = 14.sp)
            }
            is Phase.Got -> {
                val t = p.tweet
                val done = progress.isNotEmpty() && progress.all { it >= 1f }
                val failed = progress.count { it < 0f }
                Text(
                    when {
                        done -> "Saved ${label(t.media)}"
                        failed > 0 && progress.all { it >= 1f || it < 0f } -> "$failed of ${t.media.size} failed"
                        else -> "Saving ${label(t.media)}…"
                    },
                    fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = if (done) Ok else Ink
                )
                if (t.user.isNotBlank() || t.text.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOf(if (t.user.isNotBlank()) "@${t.user}" else "", t.text.substringBefore("https://t.co").trim())
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color(0xFF536471), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(t.media) { i, m ->
                        Thumb(m, progress.getOrNull(i) ?: 0f)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Pictures/Snag · Movies/Snag", color = Color(0xFF8B98A5), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Thumb(m: Media, p: Float) {
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEFF3F4))) {
        AsyncImage(model = m.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (m.kind != Kind.PHOTO) {
            Text(
                if (m.kind == Kind.GIF) "GIF" else "VIDEO",
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        if (m.width > 0) {
            Text(
                "${m.width}×${m.height}", color = Color.White, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)
            )
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color(0x33000000))) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(if (p < 0f) 1f else p.coerceIn(0.03f, 1f))
                    .background(if (p < 0f) Bad else if (p >= 1f) Ok else Accent)
            )
        }
    }
}

private fun label(media: List<Media>): String {
    val photos = media.count { it.kind == Kind.PHOTO }
    val videos = media.count { it.kind == Kind.VIDEO }
    val gifs = media.count { it.kind == Kind.GIF }
    return listOfNotNull(
        photos.takeIf { it > 0 }?.let { "$it photo${if (it > 1) "s" else ""}" },
        videos.takeIf { it > 0 }?.let { "$it video${if (it > 1) "s" else ""}" },
        gifs.takeIf { it > 0 }?.let { "$it GIF${if (it > 1) "s" else ""}" },
    ).joinToString(" + ")
}

/** 0..1 progress, 1 = done, -1 = failed. */
private fun statusOf(dm: DownloadManager, id: Long): Float {
    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
        if (!c.moveToFirst()) return -1f
        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        if (status == DownloadManager.STATUS_SUCCESSFUL) return 1f
        if (status == DownloadManager.STATUS_FAILED) return -1f
        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        return if (total > 0) (got.toFloat() / total).coerceAtMost(0.99f) else 0f
    }
    return -1f
}
