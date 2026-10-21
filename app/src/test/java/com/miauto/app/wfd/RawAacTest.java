package com.miauto.app.wfd;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RawAacTest {
    private static final class Receiver implements RawAac.Listener {
        int formats;
        final List<byte[]> frames = new ArrayList<>();
        final List<Long> stamps = new ArrayList<>();

        public void format(int sampleRate, int channels) {
            assertEquals(48000, sampleRate);
            assertEquals(2, channels);
            formats++;
        }

        public void frame(byte[] adts, long ptsUs) {
            frames.add(adts);
            stamps.add(ptsUs);
        }
    }

    @Test
    public void rfc3640SingleAccessUnitGetsAdtsHeader() {
        Receiver receiver = new Receiver();
        RawAac aac = new RawAac(receiver);
        byte[] raw = {0x11, 0x22, 0x33, 0x44};
        byte[] payload = {0x00, 0x10, 0x00, 0x20, raw[0], raw[1], raw[2], raw[3]};
        // AU-size = 4 -> 4 << 3 = 0x0020, AU-index = 0.
        aac.packet(payload, 0, payload.length, true, 90000);
        assertEquals(1, receiver.formats);
        assertEquals(1, receiver.frames.size());
        byte[] frame = receiver.frames.get(0);
        assertEquals(11, frame.length);
        assertEquals(0xff, frame[0] & 255);
        assertEquals(0xf1, frame[1] & 255);
        assertEquals(0x4c, frame[2] & 255); // AAC-LC, 48 kHz
        assertEquals(0x80, frame[3] & 255); // stereo
        assertEquals(0x01, frame[4] & 0xff); // (11 >> 3) & 0xff == 1
        assertEquals(0x7f, frame[5] & 0xff); // ((11 & 7) << 5) | 0x1f
        assertEquals(0xfc, frame[6] & 0xff);
        assertArrayEquals(raw, new byte[]{frame[7], frame[8], frame[9], frame[10]});
        assertEquals(Long.valueOf(0L), receiver.stamps.get(0));
    }

    @Test
    public void adtsPassthroughIsPreserved() {
        Receiver receiver = new Receiver();
        RawAac aac = new RawAac(receiver);
        byte[] frame = {(byte) 0xff, (byte) 0xf1, 0x4c, (byte) 0x80, 1, 0x5f, (byte) 0xfc, 1, 2, 3};
        aac.packet(frame, 0, frame.length, true, 0);
        assertEquals(1, receiver.frames.size());
        assertArrayEquals(frame, receiver.frames.get(0));
    }

    @Test
    public void skipsAudioSpecificConfigAccessUnit() {
        Receiver receiver = new Receiver();
        RawAac aac = new RawAac(receiver);
        byte[] payload = {0x00, 0x10, 0x00, 0x10, 0x11, (byte) 0x90};
        // AU-size = 2, AU-index = 0 -> the AudioSpecificConfig itself.
        aac.packet(payload, 0, payload.length, true, 0);
        assertEquals(0, receiver.formats); // the ASC is not an audio frame
        assertEquals(0, receiver.frames.size());
    }

    @Test
    public void malformedFramingNeverProducesDecoderInput() {
        Receiver receiver = new Receiver();
        RawAac aac = new RawAac(receiver);
        byte[] truncated = {0x00, 0x10, 0x00, 0x40, 1, 2};
        aac.packet(truncated, 0, truncated.length, true, 0);
        assertEquals(0, receiver.frames.size());
        byte[] zero = {0x00, 0x10, 0x00, 0x00, 1, 2};
        aac.packet(zero, 0, zero.length, true, 0);
        assertEquals(0, receiver.frames.size());
    }
}
