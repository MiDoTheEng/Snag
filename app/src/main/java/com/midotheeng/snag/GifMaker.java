package com.midotheeng.snag;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small animated-GIF writer: one shared 256-colour palette (median cut over all
 * frames, so colours don't flicker), infinite loop, LZW-compressed frames.
 */
public final class GifMaker {
    private final int w, h, delayCs;
    private final List<short[]> frames = new ArrayList<>();
    private final int[] hist = new int[32768];

    public GifMaker(int width, int height, int delayCentiseconds) {
        w = width; h = height; delayCs = delayCentiseconds;
    }

    /** argb must be width*height pixels. */
    public void addFrame(int[] argb) {
        short[] f = new short[w * h];
        for (int i = 0; i < f.length; i++) {
            int c = argb[i];
            int v = ((c >> 19) & 31) << 10 | ((c >> 11) & 31) << 5 | ((c >> 3) & 31);
            f[i] = (short) v;
            hist[v]++;
        }
        frames.add(f);
    }

    public int frameCount() { return frames.size(); }

    public void write(OutputStream out) throws IOException {
        int[] palette = medianCut();
        int[] lut = buildLut(palette);
        byte[] pal = new byte[768];
        for (int i = 0; i < 256; i++) {
            int c = i < palette.length ? palette[i] : 0;
            pal[i * 3] = (byte) (c >> 16); pal[i * 3 + 1] = (byte) (c >> 8); pal[i * 3 + 2] = (byte) c;
        }

        out.write("GIF89a".getBytes("US-ASCII"));
        le16(out, w); le16(out, h);
        out.write(0xF7); out.write(0); out.write(0);           // global colour table, 256 entries
        out.write(pal);
        out.write(new byte[]{0x21, (byte) 0xFF, 0x0B});         // loop forever
        out.write("NETSCAPE2.0".getBytes("US-ASCII"));
        out.write(new byte[]{3, 1, 0, 0, 0});

        byte[] idx = new byte[w * h];
        for (short[] f : frames) {
            for (int i = 0; i < idx.length; i++) idx[i] = (byte) lut[f[i] & 0x7FFF];
            out.write(new byte[]{0x21, (byte) 0xF9, 4, 0x04});  // graphic control: keep previous
            le16(out, delayCs);
            out.write(0); out.write(0);
            out.write(0x2C); le16(out, 0); le16(out, 0); le16(out, w); le16(out, h); out.write(0);
            lzw(out, idx);
        }
        out.write(0x3B);
        out.flush();
    }

    // ---------------- palette ----------------

    private int[] medianCut() {
        int n = 0;
        for (int v : hist) if (v > 0) n++;
        int[] cols = new int[n];
        n = 0;
        for (int i = 0; i < hist.length; i++) if (hist[i] > 0) cols[n++] = i;

        List<int[]> boxes = new ArrayList<>();               // {start, end} into cols
        boxes.add(new int[]{0, cols.length});
        while (boxes.size() < 256) {
            int best = -1, bestRange = 0, bestCh = 0;
            for (int b = 0; b < boxes.size(); b++) {
                int[] bx = boxes.get(b);
                if (bx[1] - bx[0] < 2) continue;
                for (int ch = 0; ch < 3; ch++) {
                    int lo = 31, hi = 0;
                    for (int i = bx[0]; i < bx[1]; i++) { int c = comp(cols[i], ch); if (c < lo) lo = c; if (c > hi) hi = c; }
                    if (hi - lo > bestRange) { bestRange = hi - lo; best = b; bestCh = ch; }
                }
            }
            if (best < 0) break;
            int[] bx = boxes.get(best);
            final int ch = bestCh;
            Integer[] seg = new Integer[bx[1] - bx[0]];
            for (int i = 0; i < seg.length; i++) seg[i] = cols[bx[0] + i];
            Arrays.sort(seg, (a, b) -> comp(a, ch) - comp(b, ch));
            for (int i = 0; i < seg.length; i++) cols[bx[0] + i] = seg[i];
            long total = 0;
            for (int i = bx[0]; i < bx[1]; i++) total += hist[cols[i]];
            long acc = 0; int cut = bx[0] + 1;
            for (int i = bx[0]; i < bx[1] - 1; i++) { acc += hist[cols[i]]; if (acc * 2 >= total) { cut = i + 1; break; } cut = i + 1; }
            boxes.set(best, new int[]{bx[0], cut});
            boxes.add(new int[]{cut, bx[1]});
        }
        int[] pal = new int[boxes.size()];
        for (int b = 0; b < boxes.size(); b++) {
            int[] bx = boxes.get(b);
            long r = 0, g = 0, bl = 0, cnt = 0;
            for (int i = bx[0]; i < bx[1]; i++) {
                int c = cols[i], k = hist[c];
                r += (long) expand(comp(c, 0)) * k; g += (long) expand(comp(c, 1)) * k; bl += (long) expand(comp(c, 2)) * k; cnt += k;
            }
            if (cnt == 0) cnt = 1;
            pal[b] = (int) (r / cnt) << 16 | (int) (g / cnt) << 8 | (int) (bl / cnt);
        }
        if (pal.length == 0) pal = new int[]{0};
        return pal;
    }

    private int[] buildLut(int[] pal) {
        int[] lut = new int[32768];
        for (int v = 0; v < 32768; v++) {
            if (hist[v] == 0) continue;
            int r = expand(comp(v, 0)), g = expand(comp(v, 1)), b = expand(comp(v, 2));
            int best = 0, bestD = Integer.MAX_VALUE;
            for (int i = 0; i < pal.length; i++) {
                int dr = r - (pal[i] >> 16 & 255), dg = g - (pal[i] >> 8 & 255), db = b - (pal[i] & 255);
                int d = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
                if (d < bestD) { bestD = d; best = i; }
            }
            lut[v] = best;
        }
        return lut;
    }

    private static int comp(int v, int ch) { return ch == 0 ? (v >> 10) & 31 : ch == 1 ? (v >> 5) & 31 : v & 31; }
    private static int expand(int c5) { return (c5 << 3) | (c5 >> 2); }

    // ---------------- LZW ----------------

    private byte[] block = new byte[255];
    private int blockLen, bitBuf, bitCnt;

    private void lzw(OutputStream out, byte[] px) throws IOException {
        final int minCode = 8, clear = 256, eoi = 257;
        out.write(minCode);
        blockLen = 0; bitBuf = 0; bitCnt = 0;
        int[] table = new int[4096 * 256];                    // (prefix<<8|k) -> code+1
        int size = 9, next = 258;
        emit(out, clear, size);
        int prefix = px[0] & 255;
        for (int i = 1; i < px.length; i++) {
            int k = px[i] & 255;
            int key = prefix << 8 | k;
            int code = table[key];
            if (code != 0) { prefix = code - 1; continue; }
            emit(out, prefix, size);
            if (next < 4096) {
                table[key] = next + 1;
                if (next == (1 << size) && size < 12) size++;
                next++;
            } else {
                emit(out, clear, size);
                Arrays.fill(table, 0);
                size = 9; next = 258;
            }
            prefix = k;
        }
        emit(out, prefix, size);
        emit(out, eoi, size);
        if (bitCnt > 0) put(out, bitBuf & 255);
        flushBlock(out);
        out.write(0);
    }

    private void emit(OutputStream out, int code, int size) throws IOException {
        bitBuf |= code << bitCnt;
        bitCnt += size;
        while (bitCnt >= 8) { put(out, bitBuf & 255); bitBuf >>>= 8; bitCnt -= 8; }
    }

    private void put(OutputStream out, int b) throws IOException {
        block[blockLen++] = (byte) b;
        if (blockLen == 255) flushBlock(out);
    }

    private void flushBlock(OutputStream out) throws IOException {
        if (blockLen == 0) return;
        out.write(blockLen);
        out.write(block, 0, blockLen);
        blockLen = 0;
    }

    private static void le16(OutputStream o, int v) throws IOException { o.write(v & 255); o.write(v >> 8 & 255); }
}
