package com.midotheeng.snag

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Reads frames out of an MP4 for GIF making.
 * Primary: hardware decoder, frame by frame (works on every phone).
 * Backup: MediaMetadataRetriever.
 */
object FrameReader {
    class Size(val w: Int, val h: Int)

    /** Why the last read failed — shown to the user so problems can be fixed. */
    var lastError = ""

    /** Calls [onFrame] with ARGB pixels (already scaled) roughly every [stepUs]. Returns output size. */
    fun read(path: String, maxSide: Int, stepUs: Long, maxUs: Long, onFrame: (IntArray, Int, Int) -> Unit): Size? {
        val errs = mutableListOf<String>()
        var count = 0
        var got: Size? = null
        val counted: (IntArray, Int, Int) -> Unit = { p, w, h -> count++; got = Size(w, h); onFrame(p, w, h) }

        // 1) Our own MP4 reader → decoder (doesn't depend on Android's extractor)
        try {
            val d = Mp4Demux(path)
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, d.width, d.height)
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(d.sps))
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(d.pps))
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (d.samples.maxOf { it[1] } + 4096).toInt())
                var i = 0
                decode(fmt, maxSide, stepUs, maxUs, counted) { buf ->
                    if (i >= d.samples.size) null
                    else {
                        val data = d.frame(i); val pts = d.samples[i][2]; i++
                        buf.clear(); buf.put(data); data.size to pts
                    }
                }
            } finally { d.close() }
            if (count > 0) return got
            errs += "mp4: no frames"
        } catch (e: Throwable) { errs += "mp4: ${e.javaClass.simpleName} ${e.message}" }

        // 2) Android's extractor → decoder
        try {
            val ex = MediaExtractor()
            FileInputStream(path).use { fis -> ex.setDataSource(fis.fd) }
            try {
                var track = -1
                for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; break }
                if (track < 0) throw Exception("no video track")
                ex.selectTrack(track)
                decode(ex.getTrackFormat(track), maxSide, stepUs, maxUs, counted) { buf ->
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) null else (n to ex.sampleTime).also { ex.advance() }
                }
            } finally { ex.release() }
            if (count > 0) return got
            errs += "extractor: no frames"
        } catch (e: Throwable) { errs += "extractor: ${e.javaClass.simpleName} ${e.message}" }

        // 3) MediaMetadataRetriever
        try {
            val s = viaRetriever(path, maxSide, stepUs, maxUs, onFrame)
            if (s != null) return s
            errs += "retriever: no frames"
        } catch (e: Throwable) { errs += "retriever: ${e.javaClass.simpleName} ${e.message}" }

        lastError = errs.joinToString("; ")
        return null
    }

    private fun outSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        return maxOf(2, (w * scale).toInt()) to maxOf(2, (h * scale).toInt())
    }

    /** Runs a decoder; [feed] fills the input buffer and returns (size, ptsUs), or null at the end. */
    private fun decode(
        fmt: MediaFormat, maxSide: Int, stepUs: Long, maxUs: Long,
        onFrame: (IntArray, Int, Int) -> Unit,
        feed: (ByteBuffer) -> Pair<Int, Long>?,
    ) {
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
        var px = IntArray(0)
        var idle = 0
        try {
            loop@ while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val r = feed(codec.getInputBuffer(ii)!!)
                        if (r == null) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else codec.queueInputBuffer(ii, 0, r.first, r.second, 0)
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi < 0) { if (inputDone && ++idle > 300) break@loop; continue@loop }
                idle = 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) {
                    if (base < 0) base = info.presentationTimeUs
                    val t = info.presentationTimeUs - base
                    if (t > maxUs) { codec.releaseOutputBuffer(oi, false); break@loop }
                    if (t >= next) {
                        val img = try { codec.getOutputImage(oi) } catch (e: Exception) { null }
                        if (img != null) {
                            val crop = img.cropRect
                            val s = size ?: outSize(crop.width(), crop.height(), maxSide).let { (w, h) -> Size(w, h) }.also { size = it; px = IntArray(it.w * it.h) }
                            toArgb(img, s, px)
                            img.close()
                            onFrame(px, s.w, s.h)
                        } else {
                            // Some decoders only hand out raw buffers
                            val of = codec.outputFormat
                            val bw = of.getInteger(MediaFormat.KEY_WIDTH); val bh = of.getInteger(MediaFormat.KEY_HEIGHT)
                            val stride = if (of.containsKey(MediaFormat.KEY_STRIDE)) of.getInteger(MediaFormat.KEY_STRIDE) else bw
                            val slice = if (of.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) of.getInteger(MediaFormat.KEY_SLICE_HEIGHT) else bh
                            val cf = of.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                            val vw = if (of.containsKey("crop-right")) of.getInteger("crop-right") - of.getInteger("crop-left") + 1 else bw
                            val vh = if (of.containsKey("crop-bottom")) of.getInteger("crop-bottom") - of.getInteger("crop-top") + 1 else bh
                            val s = size ?: outSize(vw, vh, maxSide).let { (w, h) -> Size(w, h) }.also { size = it; px = IntArray(it.w * it.h) }
                            val buf = codec.getOutputBuffer(oi)!!
                            rawToArgb(buf, info.offset, cf, stride, maxOf(slice, bh), vw, vh, s, px)
                            onFrame(px, s.w, s.h)
                        }
                        while (next <= t) next += stepUs
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if (eos) break@loop
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    /** Planar (I420) or semi-planar (NV12) buffers → ARGB. */
    private fun rawToArgb(b: ByteBuffer, off: Int, cf: Int, stride: Int, slice: Int, vw: Int, vh: Int, s: Size, out: IntArray) {
        val semi = cf != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val uvStart = off + stride * slice
        for (oy in 0 until s.h) {
            val sy = oy * vh / s.h
            for (ox in 0 until s.w) {
                val sx = ox * vw / s.w
                val y = (b.get(off + sy * stride + sx).toInt() and 255) - 16
                val u: Int; val v: Int
                if (semi) {
                    val i = uvStart + (sy / 2) * stride + (sx / 2) * 2
                    u = (b.get(i).toInt() and 255) - 128; v = (b.get(i + 1).toInt() and 255) - 128
                } else {
                    val cs = stride / 2
                    val i = (sy / 2) * cs + sx / 2
                    u = (b.get(uvStart + i).toInt() and 255) - 128
                    v = (b.get(uvStart + cs * (slice / 2) + i).toInt() and 255) - 128
                }
                out[oy * s.w + ox] = yuv(y, u, v)
            }
        }
    }

    private fun yuv(y: Int, u: Int, v: Int): Int {
        val yy = 1192 * maxOf(0, y)
        val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
        val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
        val bl = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
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
            FileInputStream(path).use { r.setDataSource(it.fd) }
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
