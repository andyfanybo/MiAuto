package com.miauto.app.wfd;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class AdtsAudioTest {
    private static class Receiver implements AdtsAudio.Listener {
        int announcements;
        final List<byte[]> frames = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        public void format(int rate, int channels) { assertEquals(48000, rate); assertEquals(2, channels); announcements++; }
        public void frame(byte[] bytes, long pts) { frames.add(bytes); times.add(pts); }
    }
    @Test public void keepsAdtsHeadersForSdkAacDecoder() {
        Receiver receiver = new Receiver(); AdtsAudio parser = new AdtsAudio(receiver);
        byte[] frame = frame(); parser.push(frame, 90000);
        assertEquals(1, receiver.announcements); assertEquals(1, receiver.frames.size());
        assertArrayEquals(frame, receiver.frames.get(0)); assertEquals(Long.valueOf(90000), receiver.times.get(0));
    }
    @Test public void reconstructsFramesSplitAcrossPesBoundaries() {
        Receiver receiver = new Receiver(); AdtsAudio parser = new AdtsAudio(receiver); byte[] frame = frame();
        parser.push(Arrays.copyOf(frame, 5), 1000000); assertTrue(receiver.frames.isEmpty());
        parser.push(Arrays.copyOfRange(frame, 5, frame.length), 2000000);
        assertArrayEquals(frame, receiver.frames.get(0)); assertEquals(Long.valueOf(1000000), receiver.times.get(0));
    }
    @Test public void splitsMultipleAccessUnitsAndAdvancesAudioClock() {
        Receiver receiver = new Receiver(); AdtsAudio parser = new AdtsAudio(receiver); byte[] frame = frame();
        byte[] both = new byte[frame.length * 2]; System.arraycopy(frame,0,both,0,frame.length);System.arraycopy(frame,0,both,frame.length,frame.length);
        parser.push(both, 0); assertEquals(2, receiver.frames.size()); assertEquals(1, receiver.announcements);
        assertEquals(Long.valueOf(21333), receiver.times.get(1));
    }
    @Test public void doesNotClaimUnsupportedSampleRates() {
        Receiver receiver = new Receiver(); AdtsAudio parser = new AdtsAudio(receiver); byte[] frame = frame();
        frame[2] = 0x50; // AAC-LC 44.1kHz, outside our negotiated 48kHz mode.
        parser.push(frame, 0); assertEquals(0, receiver.announcements); assertTrue(receiver.frames.isEmpty());
    }
    @Test public void audioPesDoesNotConsumeVideoAndPreservesAdts() {
        Receiver receiver = new Receiver(); AdtsAudio adts = new AdtsAudio(receiver);
        MpegTsAvc parser = new MpegTsAvc(adts::push, true);
        byte[] video = WfdProtocolTest.rtpPes(1,0,new byte[]{0,0,0,1,0x65});parser.rtp(video,video.length);
        assertTrue(receiver.frames.isEmpty());
        byte[] audio = audioPacket(2, frame()); parser.rtp(audio,audio.length);
        assertEquals(1, receiver.frames.size());assertArrayEquals(frame(),receiver.frames.get(0));
    }
    static byte[] frame() {
        return new byte[]{(byte)0xff,(byte)0xf1,0x4c,(byte)0x80,1,0x5f,(byte)0xfc,1,2,3};
    }
    static byte[] audioPacket(int sequence, byte[] frame) {
        byte[] packet = WfdProtocolTest.rtpPes(sequence,0,frame);
        packet[14]=1; // distinct PID 257
        int pes = 17 + (packet[16] & 255);
        packet[pes+3]=(byte)0xc0;
        return packet;
    }
}
