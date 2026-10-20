package com.miauto.app.wfd;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** RTP/MP2T -> complete AVC PES access units. Malformed/lost packets never reach the decoder. */
public final class MpegTsAvc {
    public interface Listener { void video(byte[] data, long ptsUs); }
    private final Listener listener;
    private int pmtPid = -1, videoPid = -1, lastSequence = -1, continuity = -1;
    private final ByteArrayOutputStream pes = new ByteArrayOutputStream();
    private boolean collecting;
    private long pts;
    private int remaining = -1;
    public MpegTsAvc(Listener listener) { this.listener = listener; }
    public void rtp(byte[] data, int length) {
        if (length < 12 || length > data.length || (data[0] & 0xc0) != 0x80 || (data[1] & 0x7f) != 33) return;
        int offset = 12 + (data[0] & 15) * 4;
        if (offset > length) return;
        if ((data[0] & 0x10) != 0) {
            if (offset + 4 > length) return;
            offset += 4 + u16(data, offset + 2) * 4;
        }
        if ((data[0] & 0x20) != 0) {
            int padding = data[length - 1] & 255;
            if (padding == 0 || padding > length - offset) return;
            length -= padding;
        }
        if (offset > length || (length - offset) % 188 != 0) return;
        int sequence = u16(data, 2);
        if (sequence == lastSequence) return;
        if (lastSequence != -1 && sequence != ((lastSequence + 1) & 65535)) resetPes();
        lastSequence = sequence;
        for (; offset + 188 <= length; offset += 188) packet(data, offset);
    }
    private void packet(byte[] data, int base) {
        if ((data[base] & 255) != 0x47 || (data[base + 1] & 0x80) != 0 || (data[base + 3] & 0xc0) != 0) { resetPes(); return; }
        int pid = ((data[base + 1] & 31) << 8) | (data[base + 2] & 255);
        boolean start = (data[base + 1] & 0x40) != 0;
        int mode = (data[base + 3] >> 4) & 3;
        if (mode == 0 || mode == 2) return;
        int offset = base + 4, end = base + 188;
        if (mode == 3) {
            int count = data[offset++] & 255;
            if (offset + count > end) { resetPes(); return; }
            if (count > 0 && (data[offset] & 0x80) != 0) resetPes();
            offset += count;
        }
        if (offset >= end) return;
        if (pid == 0 || pid == pmtPid) { if (start) table(data, offset, end, pid); return; }
        // PAT/PMT normally precede video; accepting an AVC PES stream id also
        // permits a late join. Only the chosen video PID is assembled.
        if (videoPid == -1 && start && offset + 9 <= end && data[offset] == 0 && data[offset + 1] == 0
                && data[offset + 2] == 1 && (data[offset + 3] & 0xf0) == 0xe0) videoPid = pid;
        if (pid != videoPid) return;
        int counter = data[base + 3] & 15;
        if (continuity == counter) return;
        if (continuity != -1 && counter != ((continuity + 1) & 15)) resetPes();
        continuity = counter;
        if (start) {
            if (collecting && remaining < 0) emit();
            resetPes();
            if (offset + 9 > end || data[offset] != 0 || data[offset + 1] != 0 || data[offset + 2] != 1) return;
            int length = u16(data, offset + 4), extra = data[offset + 8] & 255;
            if (offset + 9 + extra > end) return;
            if ((data[offset + 7] & 0x80) == 0 || extra < 5) return;
            int p = offset + 9;
            if ((data[p] & 1) == 0 || (data[p + 2] & 1) == 0 || (data[p + 4] & 1) == 0) return;
            long timestamp = ((long) ((data[p] >> 1) & 7) << 30) | ((long) (data[p + 1] & 255) << 22)
                    | ((long) ((data[p + 2] >> 1) & 127) << 15) | ((data[p + 3] & 255) << 7) | ((data[p + 4] >> 1) & 127);
            pts = timestamp * 1000000L / 90000L;
            remaining = length == 0 ? -1 : length - 3 - extra;
            if (remaining < 0 && length != 0) return;
            collecting = true;
            offset += 9 + extra;
        }
        if (!collecting) return;
        int count = remaining < 0 ? end - offset : Math.min(remaining, end - offset);
        if (pes.size() + count > 2 * 1024 * 1024) { resetPes(); return; }
        pes.write(data, offset, count);
        if (remaining >= 0) {
            remaining -= count;
            if (remaining == 0) { emit(); resetPes(); }
        }
    }
    private void table(byte[] data, int offset, int end, int pid) {
        int pointer = data[offset++] & 255;
        offset += pointer;
        if (offset + 12 > end) return;
        int length = ((data[offset + 1] & 15) << 8) | (data[offset + 2] & 255);
        int limit = offset + 3 + length - 4; // skip CRC; ignore split/invalid sections
        if (limit > end || length < 9 || (data[offset + 5] & 1) == 0) return;
        if (pid == 0 && data[offset] == 0) {
            for (int p = offset + 8; p + 4 <= limit; p += 4)
                if (u16(data, p) != 0) { pmtPid = ((data[p + 2] & 31) << 8) | (data[p + 3] & 255); break; }
        } else if (pid == pmtPid && data[offset] == 2) {
            int p = offset + 12 + (((data[offset + 10] & 15) << 8) | (data[offset + 11] & 255));
            while (p + 5 <= limit) {
                if ((data[p] & 255) == 0x1b) { videoPid = ((data[p + 1] & 31) << 8) | (data[p + 2] & 255); break; }
                p += 5 + (((data[p + 3] & 15) << 8) | (data[p + 4] & 255));
            }
        }
    }
    private void emit() {
        byte[] bytes = pes.toByteArray();
        if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == 0 && (bytes[2] == 1 || (bytes[2] == 0 && bytes[3] == 1))) listener.video(bytes, pts);
    }
    private void resetPes() { collecting = false; remaining = -1; pes.reset(); }
    private static int u16(byte[] data, int offset) { return ((data[offset] & 255) << 8) | (data[offset + 1] & 255); }
}
