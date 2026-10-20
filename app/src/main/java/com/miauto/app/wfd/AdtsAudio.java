package com.miauto.app.wfd;

import java.util.Arrays;

/** Bounded AAC-LC/48kHz/stereo ADTS framing, matching the advertised WFD audio mode. */
public final class AdtsAudio {
    public interface Listener {
        void format(int sampleRate, int channels);
        void frame(byte[] adts, long ptsUs);
    }
    private final Listener listener;
    private byte[] pending = new byte[0];
    private boolean announced;
    private long timestamp;
    public AdtsAudio(Listener listener) { this.listener = listener; }
    public void push(byte[] data, long ptsUs) {
        if (data.length > 65536 || pending.length + data.length > 73728) { pending = new byte[0]; return; }
        if (pending.length == 0) timestamp = ptsUs;
        byte[] bytes = Arrays.copyOf(pending, pending.length + data.length);
        System.arraycopy(data, 0, bytes, pending.length, data.length);
        int offset = 0;
        while (offset + 7 <= bytes.length) {
            if ((bytes[offset] & 255) != 255 || (bytes[offset + 1] & 0xf6) != 0xf0) { offset++; continue; }
            int header = (bytes[offset + 1] & 1) == 1 ? 7 : 9;
            int length = ((bytes[offset + 3] & 3) << 11) | ((bytes[offset + 4] & 255) << 3) | ((bytes[offset + 5] >> 5) & 7);
            if (length < header || length > 8191) { offset++; continue; }
            if (offset + length > bytes.length) break;
            int profile = ((bytes[offset + 2] >> 6) & 3) + 1;
            int frequencyIndex = (bytes[offset + 2] >> 2) & 15;
            int channels = ((bytes[offset + 2] & 1) << 2) | ((bytes[offset + 3] >> 6) & 3);
            if (profile == 2 && frequencyIndex == 3 && channels == 2) {
                if (!announced) { announced = true; listener.format(48000, 2); }
                listener.frame(Arrays.copyOfRange(bytes, offset, offset + length), timestamp);
                int blocks = (bytes[offset + 6] & 3) + 1;
                timestamp += blocks * 1024L * 1000000L / 48000L;
            }
            offset += length;
        }
        pending = Arrays.copyOfRange(bytes, offset, bytes.length);
    }
}
