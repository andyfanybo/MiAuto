package com.miauto.app.wfd;

import java.util.Arrays;

/**
 * RFC 3640 AAC depacketizer for Wi-Fi Display raw-packet mode.
 *
 * <p>The SDK's AAC decoder is configured with {@code is-adts=1}; a raw RTP AAC
 * access unit therefore needs an ADTS header before it can be handed to
 * {@code onNativeAudioDataReceived}. Only the AAC-LC / 48 kHz / stereo mode that
 * the receiver advertises is accepted.
 */
public final class RawAac {
    public interface Listener {
        void format(int sampleRate, int channels);
        void frame(byte[] adts, long ptsUs);
    }

    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 2;
    private static final int FREQUENCY_INDEX = 3; // 48 kHz
    private static final long CLOCK_HZ = 90000L;

    private final Listener listener;
    private boolean announced;
    private long timestampBase = -1;

    public RawAac(Listener listener) {
        this.listener = listener;
    }

    public void packet(byte[] data, int offset, int length, boolean marker, long timestamp) {
        if (data == null || length <= 0 || offset < 0 || offset + length > data.length) return;
        if ((data[offset] & 255) == 0xff && length >= 7 && (data[offset + 1] & 0xf6) == 0xf0) {
            announce();
            listener.frame(Arrays.copyOfRange(data, offset, offset + length), ptsUs(timestamp));
            return;
        }
        // RFC 3640 high-bitrate framing: 16-bit AU-headers-length (bits), then
        // 16-bit AU headers (13-bit size, 3-bit index), then the raw AAC AUs.
        if (length < 4) return;
        int headerBits = ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
        int headerBytes = (headerBits + 7) / 8;
        if (headerBytes < 2 || headerBytes % 2 != 0 || 2 + headerBytes >= length) return;
        int header = offset + 2;
        int payload = header + headerBytes;
        int end = offset + length;
        for (int index = 0; index + 2 <= headerBytes; index += 2) {
            int size = (((data[header + index] & 255) << 8) | (data[header + index + 1] & 255)) >> 3;
            if (size <= 0 || payload + size > end) return;
            // The first AU may carry AudioSpecificConfig (0x11 0x90 for LC/48k/stereo).
            if (!(size == 2 && (data[payload] & 255) == 0x11 && (data[payload + 1] & 255) == 0x90)) {
                announce();
                byte[] frame = adts(data, payload, size);
                if (frame.length > 0) listener.frame(frame, ptsUs(timestamp));
            }
            payload += size;
        }
    }

    private void announce() {
        if (announced) return;
        announced = true;
        listener.format(SAMPLE_RATE, CHANNELS);
    }

    static byte[] adts(byte[] raw, int offset, int length) {
        if (length <= 0 || length > 8184) return new byte[0];
        int frameLength = length + 7;
        byte[] out = new byte[frameLength];
        out[0] = (byte) 0xff;
        out[1] = (byte) 0xf1; // MPEG-4, layer 0, no CRC
        out[2] = (byte) ((1 << 6) | (FREQUENCY_INDEX << 2)); // AAC-LC, 48 kHz
        out[3] = (byte) (((CHANNELS & 3) << 6) | ((frameLength >> 11) & 3));
        out[4] = (byte) ((frameLength >> 3) & 0xff);
        out[5] = (byte) (((frameLength & 7) << 5) | 0x1f);
        out[6] = (byte) 0xfc; // fullness 0x7ff, one raw data block
        System.arraycopy(raw, offset, out, 7, length);
        return out;
    }

    private long ptsUs(long timestamp) {
        long value = timestamp & 0xffffffffL;
        if (timestampBase < 0) timestampBase = value;
        long delta = (value - timestampBase) & 0xffffffffL;
        return delta * 1000000L / CLOCK_HZ;
    }
}
