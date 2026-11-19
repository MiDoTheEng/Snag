package com.midotheeng.snag

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever

/**
 * Reads frames out of an MP4 for GIF making.
 * Primary: hardware decoder, frame by frame (works on every phone).
 * Backup: MediaMetadataRetriever.
 */
object FrameReader {
    class Size(val w: Int, val h: Int)

    /** Calls [onFrame] with ARGB pixels (already scaled) roughly every [stepUs]. Returns output size. */
    fun read(path: String, maxSide: Int, stepUs: Long, maxUs: Long, onFrame: (IntArray, Int, Int) -> Unit): Size? {
        var count = 0
        var got: Size? = null
        try {
            viaCodec(path, maxSide, stepUs, maxUs) { p, w, h -> count++; got = Size(w, h); onFrame(p, w, h) }
        } catch (e: Exception) { }
        if (count > 0) return got
        return viaRetriever(path, maxSide, stepUs, maxUs, onFrame)
    }

    private fun outSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        return maxOf(2, (w * scale).toInt()) to maxOf(2, (h * scale).toInt())
    }

    private fun viaCodec(path: String, maxSide: Int, stepUs: Long, maxUs: Long, onFrame: (IntArray, Int, Int) -> Unit): Size? {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        var track = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; fmt = f; break }
        }
        if (track < 0 || fmt == null) { ex.release(); return null }
        ex.selectTrack(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var base = -1L
        var next = 0L
        var size: Size? = null
        var px: IntArray? = null
        try {
            loop@ while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        if (base < 0) base = info.presentationTimeUs
                        val t = info.presentationTimeUs - base
                        if (t > maxUs) { codec.releaseOutputBuffer(oi, false); break@loop }
                        if (t >= next) {
                            val img = codec.getOutputImage(oi)
                            if (img != null) {
                                val crop = img.cropRect
                                if (size == null) {
                                    val (w, h) = outSize(crop.width(), crop.height(), maxSide)
                                    size = Size(w, h); px = IntArray(w * h)
                                }
                                toArgb(img, size, px!!)
                                img.close()
                                onFrame(px, size.w, size.h)
                            }
                            while (next <= t) next += stepUs
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (eos) break@loop
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release(); ex.release()
        }
        return size
    }

    /** YUV_420_888 → ARGB with nearest-neighbour scaling to [s]. */
    private fun toArgb(img: Image, s: Size, out: IntArray) {
        val crop = img.cropRect
        val (yP, uP, vP) = img.planes
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        val cw = crop.width(); val ch = crop.height()
        for (oy in 0 until s.h) {
            val sy = crop.top + oy * ch / s.h
            val cy = sy / 2
            for (ox in 0 until s.w) {
                val sx = crop.left + ox * cw / s.w
                val cx = sx / 2
                val y = (yB.get(sy * yRow + sx * yPix).toInt() and 255) - 16
                val u = (uB.get(cy * uRow + cx * uPix).toInt() and 255) - 128
                val v = (vB.get(cy * vRow + cx * vPix).toInt() and 255) - 128
                val yy = 1192 * maxOf(0, y)
                val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                out[oy * s.w + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun viaRetriever(path: String, maxSide: Int, stepUs: Long, maxUs: Long, onFrame: (IntArray, Int, Int) -> Unit): Size? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val first = r.getFrameAtTime(0) ?: r.getFrameAtIndex(0) ?: return null
            val (w, h) = outSize(first.width, first.height, maxSide)
            first.recycle()
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3000L) * 1000
            val px = IntArray(w * h)
            var t = 0L; var got = 0
            while (t < minOf(dur, maxUs)) {
                val bmp = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    val b = Bitmap.createScaledBitmap(bmp, w, h, true)
                    b.getPixels(px, 0, w, 0, 0, w, h)
                    onFrame(px, w, h); got++
                    if (b !== bmp) b.recycle()
                    bmp.recycle()
                }
                t += stepUs
            }
            return if (got > 0) Size(w, h) else null
        } finally { try { r.release() } catch (_: Exception) {} }
    }
}
