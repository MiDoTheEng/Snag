package com.midotheeng.snag;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal MP4 reader for the first H.264 video track: finds every frame in the
 * file and returns it in Annex-B form, ready for MediaCodec. Used so GIF making
 * doesn't depend on Android's own extractor.
 */
public final class Mp4Demux {
    public int width, height;
    public byte[] sps, pps;                 // with 00 00 00 01 start code
    public final List<long[]> samples = new ArrayList<>();   // {offset, size, ptsUs, isKey}
    private int nalLen = 4;
    private final RandomAccessFile f;

    public Mp4Demux(String path) throws IOException {
        f = new RandomAccessFile(path, "r");
        boolean ok = false;
        long moov[] = find(0, f.length(), "moov");
        if (moov == null) throw new IOException("no moov");
        long pos = moov[0];
        while (pos < moov[1]) {
            long[] b = box(pos, moov[1]);
            if (b == null) break;
            if (type(pos).equals("trak") && readTrack(b[0], b[1])) { ok = true; break; }
            pos = b[2];
        }
        if (!ok) throw new IOException("no H.264 track");
    }

    public void close() { try { f.close(); } catch (IOException ignored) {} }

    /** Frame i converted to Annex-B (start codes instead of lengths). */
    public byte[] frame(int i) throws IOException {
        long[] s = samples.get(i);
        byte[] raw = new byte[(int) s[1]];
        f.seek(s[0]); f.readFully(raw);
        byte[] out = new byte[raw.length + 64];
        int o = 0, p = 0;
        while (p + nalLen <= raw.length) {
            int n = 0;
            for (int k = 0; k < nalLen; k++) n = n << 8 | (raw[p + k] & 255);
            p += nalLen;
            if (n <= 0 || p + n > raw.length) break;
            if (o + 4 + n > out.length) { byte[] g = new byte[(o + 4 + n) * 2]; System.arraycopy(out, 0, g, 0, o); out = g; }
            out[o++] = 0; out[o++] = 0; out[o++] = 0; out[o++] = 1;
            System.arraycopy(raw, p, out, o, n); o += n; p += n;
        }
        byte[] r = new byte[o];
        System.arraycopy(out, 0, r, 0, o);
        return r;
    }

    // ------------------------------------------------------------------ parsing

    private boolean readTrack(long start, long end) throws IOException {
        long[] mdia = find(start, end, "mdia"); if (mdia == null) return false;
        long[] hdlr = find(mdia[0], mdia[1], "hdlr"); if (hdlr == null) return false;
        f.seek(hdlr[0] + 8);
        if (!str4().equals("vide")) return false;
        long[] mdhd = find(mdia[0], mdia[1], "mdhd"); if (mdhd == null) return false;
        f.seek(mdhd[0]);
        int ver = f.readUnsignedByte(); f.skipBytes(3);
        f.skipBytes(ver == 1 ? 16 : 8);
        long timescale = f.readInt() & 0xFFFFFFFFL;
        long[] minf = find(mdia[0], mdia[1], "minf"); if (minf == null) return false;
        long[] stbl = find(minf[0], minf[1], "stbl"); if (stbl == null) return false;

        // stsd → avc1 → avcC
        long[] stsd = find(stbl[0], stbl[1], "stsd"); if (stsd == null) return false;
        long entry = stsd[0] + 8;                           // version/flags + count
        long[] e = box(entry, stsd[1]); if (e == null) return false;
        String fmt = type(entry);
        if (!fmt.equals("avc1") && !fmt.equals("avc3")) return false;
        f.seek(e[0] + 24); width = f.readUnsignedShort(); height = f.readUnsignedShort();
        long[] avcC = find(e[0] + 78, e[1], "avcC"); if (avcC == null) return false;
        f.seek(avcC[0] + 4);
        nalLen = (f.readUnsignedByte() & 3) + 1;
        int nSps = f.readUnsignedByte() & 31;
        for (int i = 0; i < nSps; i++) { byte[] d = new byte[f.readUnsignedShort()]; f.readFully(d); if (sps == null) sps = withStart(d); }
        int nPps = f.readUnsignedByte();
        for (int i = 0; i < nPps; i++) { byte[] d = new byte[f.readUnsignedShort()]; f.readFully(d); if (pps == null) pps = withStart(d); }
        if (sps == null || pps == null) return false;

        // sample sizes
        long[] stsz = find(stbl[0], stbl[1], "stsz"); if (stsz == null) return false;
        f.seek(stsz[0] + 4);
        int fixed = f.readInt(), count = f.readInt();
        long[] sizes = new long[count];
        for (int i = 0; i < count; i++) sizes[i] = fixed != 0 ? fixed : (f.readInt() & 0xFFFFFFFFL);

        // chunk offsets
        long[] chunks;
        long[] stco = find(stbl[0], stbl[1], "stco");
        long[] co64 = find(stbl[0], stbl[1], "co64");
        if (stco != null) { f.seek(stco[0] + 4); int n = f.readInt(); chunks = new long[n]; for (int i = 0; i < n; i++) chunks[i] = f.readInt() & 0xFFFFFFFFL; }
        else if (co64 != null) { f.seek(co64[0] + 4); int n = f.readInt(); chunks = new long[n]; for (int i = 0; i < n; i++) chunks[i] = f.readLong(); }
        else return false;

        // sample-to-chunk
        long[] stsc = find(stbl[0], stbl[1], "stsc"); if (stsc == null) return false;
        f.seek(stsc[0] + 4);
        int nsc = f.readInt();
        int[] firstChunk = new int[nsc], perChunk = new int[nsc];
        for (int i = 0; i < nsc; i++) { firstChunk[i] = f.readInt(); perChunk[i] = f.readInt(); f.readInt(); }

        long[] offsets = new long[count];
        int s = 0;
        for (int c = 0; c < chunks.length && s < count; c++) {
            int per = perChunk[0];
            for (int k = 0; k < nsc; k++) if (firstChunk[k] <= c + 1) per = perChunk[k];
            long off = chunks[c];
            for (int j = 0; j < per && s < count; j++) { offsets[s] = off; off += sizes[s]; s++; }
        }

        // decode times
        long[] dts = new long[count];
        long[] stts = find(stbl[0], stbl[1], "stts");
        if (stts != null) {
            f.seek(stts[0] + 4); int n = f.readInt(); long t = 0; int i = 0;
            for (int k = 0; k < n; k++) { int c = f.readInt(); long d = f.readInt() & 0xFFFFFFFFL; for (int j = 0; j < c && i < count; j++) { dts[i++] = t; t += d; } }
        }
        // composition offsets (B-frames)
        long[] cts = new long[count];
        long[] ctts = find(stbl[0], stbl[1], "ctts");
        if (ctts != null) {
            f.seek(ctts[0] + 4); int n = f.readInt(); int i = 0;
            for (int k = 0; k < n; k++) { int c = f.readInt(); int d = f.readInt(); for (int j = 0; j < c && i < count; j++) cts[i++] = d; }
        }
        // sync samples
        boolean[] key = new boolean[count];
        long[] stss = find(stbl[0], stbl[1], "stss");
        if (stss != null) { f.seek(stss[0] + 4); int n = f.readInt(); for (int k = 0; k < n; k++) { int idx = f.readInt() - 1; if (idx >= 0 && idx < count) key[idx] = true; } }
        else java.util.Arrays.fill(key, true);

        for (int i = 0; i < count; i++) {
            long pts = (dts[i] + cts[i]) * 1_000_000L / Math.max(1, timescale);
            samples.add(new long[]{offsets[i], sizes[i], pts, key[i] ? 1 : 0});
        }
        return count > 0;
    }

    private static byte[] withStart(byte[] d) {
        byte[] r = new byte[d.length + 4];
        r[3] = 1;
        System.arraycopy(d, 0, r, 4, d.length);
        return r;
    }

    /** {contentStart, end, next} for the box at pos, or null. */
    private long[] box(long pos, long limit) throws IOException {
        if (pos + 8 > limit) return null;
        f.seek(pos);
        long size = f.readInt() & 0xFFFFFFFFL;
        f.skipBytes(4);
        long header = 8;
        if (size == 1) { size = f.readLong(); header = 16; }
        else if (size == 0) size = limit - pos;
        if (size < header || pos + size > limit) return null;
        return new long[]{pos + header, pos + size, pos + size};
    }

    private String type(long pos) throws IOException { f.seek(pos + 4); return str4(); }

    private String str4() throws IOException { byte[] b = new byte[4]; f.readFully(b); return new String(b, "ISO-8859-1"); }

    /** First child box of the given type between start and end. */
    private long[] find(long start, long end, String want) throws IOException {
        long pos = start;
        while (pos < end) {
            long[] b = box(pos, end);
            if (b == null) return null;
            if (type(pos).equals(want)) return b;
            pos = b[2];
        }
        return null;
    }
}
