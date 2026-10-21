package com.miauto.app.wfd;

import java.io.ByteArrayOutputStream;

/**
 * RFC 6184 H.264 RTP depacketizer for the Wi-Fi Display raw-packet mode.
 *
 * <p>The vendor ICCOA sink advertises {@code wfd_av_raw_packet: true} and runs the
 * source in AOSP's {@code MODE_ELEMENTARY_STREAMS}: RTP payloads are H.264 NAL units
 * instead of MPEG-TS. The SDK's {@code VideoQueueManager} then expects Annex-B access
 * units (it detects key frames by looking for the {@code 00 00 01} / {@code 00 00 00 01}
 * start code), so this class rebuilds them from single-NAL, STAP-A and FU-A packets.
 */
public final class RawAvc {
    public interface Listener {
        void accessUnit(byte[] annexB, long ptsUs);
    }

    private static final byte[] START_CODE = {0, 0, 0, 1};
    private static final int MAX_ACCESS_UNIT = 4 * 1024 * 1024;
    private static final long CLOCK_HZ = 90000L;

    private final Listener listener;
    private final ByteArrayOutputStream accessUnit = new ByteArrayOutputStream();
    private final ByteArrayOutputStream fragment = new ByteArrayOutputStream();
    private boolean fragmenting;
    private long accessUnitTimestamp = -1;
    private long timestampBase = -1;

    public RawAvc(Listener listener) {
        this.listener = listener;
    }

    /**
     * Feeds one RTP payload.
     *
     * @param data      packet buffer (the RTP header is already removed)
     * @param offset    payload offset
     * @param length    payload length
     * @param marker    RTP marker bit - the last packet of an access unit
     * @param timestamp RTP timestamp (90 kHz)
     */
    public void packet(byte[] data, int offset, int length, boolean marker, long timestamp) {
        if (data == null || length <= 0 || offset < 0 || offset + length > data.length) return;
        if ((data[offset] & 0x80) != 0) return; // forbidden_zero_bit
        if (accessUnitTimestamp != timestamp && accessUnit.size() > 0) emit();
        accessUnitTimestamp = timestamp;

        int type = data[offset] & 0x1f;
        switch (type) {
            case 24:
                stapA(data, offset, length);
                break;
            case 28:
                fragmented(data, offset, length, 2);
                break;
            case 29:
                // FU-B carries a 16-bit DON after the FU header.
                fragmented(data, offset, length, 3);
                break;
            default:
                if (type >= 1 && type <= 23) append(data, offset, length);
        }
        if (marker) emit();
    }

    private void stapA(byte[] data, int offset, int length) {
        int position = offset + 1;
        int end = offset + length;
        while (position + 2 <= end) {
            int size = ((data[position] & 255) << 8) | (data[position + 1] & 255);
            position += 2;
            if (size <= 0 || position + size > end) return;
            append(data, position, size);
            position += size;
        }
    }

    private void fragmented(byte[] data, int offset, int length, int headerBytes) {
        if (length < headerBytes + 1) return;
        int header = data[offset + 1] & 255;
        boolean start = (header & 0x80) != 0;
        boolean end = (header & 0x40) != 0;
        int type = header & 0x1f;
        if (start) {
            fragment.reset();
            fragmenting = true;
            fragment.write((data[offset] & 0xe0) | type); // FU indicator F|NRI + original type
        } else if (!fragmenting) {
            return;
        }
        fragment.write(data, offset + headerBytes, length - headerBytes);
        if (end) {
            byte[] nal = fragment.toByteArray();
            append(nal, 0, nal.length);
            fragment.reset();
            fragmenting = false;
        }
    }

    private void append(byte[] data, int offset, int length) {
        if (length <= 0) return;
        if (accessUnit.size() + length + START_CODE.length > MAX_ACCESS_UNIT) {
            accessUnit.reset();
            return;
        }
        accessUnit.write(START_CODE, 0, START_CODE.length);
        accessUnit.write(data, offset, length);
    }

    private void emit() {
        if (accessUnit.size() == 0) return;
        byte[] bytes = accessUnit.toByteArray();
        accessUnit.reset();
        listener.accessUnit(bytes, ptsUs(accessUnitTimestamp));
    }

    private long ptsUs(long timestamp) {
        long value = timestamp & 0xffffffffL;
        if (timestampBase < 0) timestampBase = value;
        long delta = (value - timestampBase) & 0xffffffffL;
        return delta * 1000000L / CLOCK_HZ;
    }
}
