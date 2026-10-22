package com.miauto.app.wfd;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class WfdProtocolTest {
    @Test public void rtspFramingPreservesPipelinedBodies() throws Exception {
        String first = "GET_PARAMETER rtsp://x RTSP/1.0\r\nCSeq: 3\r\nContent-Length: 5\r\n\r\nhello";
        InputStream input = new ByteArrayInputStream((first + "RTSP/1.0 200 OK\r\nCseq: 4\r\n\r\n").getBytes(StandardCharsets.US_ASCII)) {
            @Override public synchronized int read(byte[] b, int offset, int length) { return super.read(b, offset, Math.min(length, 1)); }
        };
        assertEquals("hello", RtspMessage.read(input).body);
        assertEquals("4", RtspMessage.read(input).header("CSeq"));
        assertNull(RtspMessage.read(input));
    }
    @Test public void parserRejectsUnboundedAndTruncatedBodies() throws Exception {
        for (String body : new String[]{"Content-Length: -1\r\n\r\n", "Content-Length: 999999\r\n\r\n", "Content-Length: 5\r\n\r\nx"}) {
            try { RtspMessage.read(new ByteArrayInputStream(("RTSP/1.0 200 OK\r\n" + body).getBytes(StandardCharsets.US_ASCII))); fail(); }
            catch (IOException expected) { }
        }
    }
    @Test public void demuxesAvcPesAndPtsAndDropsDuplicates() {
        List<byte[]> frames = new ArrayList<>(); List<Long> stamps = new ArrayList<>();
        MpegTsAvc parser = new MpegTsAvc((data, pts) -> { frames.add(data); stamps.add(pts); });
        byte[] nal = {0, 0, 0, 1, 0x65, 1, 2, 3};
        byte[] packet = rtpPes(10, 0, nal);
        parser.rtp(packet, packet.length); parser.rtp(packet, packet.length);
        assertEquals(1, frames.size()); assertArrayEquals(nal, frames.get(0)); assertEquals(Long.valueOf(1000000), stamps.get(0));
    }
    @Test public void malformedRtpNeverProducesDecoderInput() {
        List<byte[]> frames = new ArrayList<>(); MpegTsAvc parser = new MpegTsAvc((d, p) -> frames.add(d));
        byte[] packet = rtpPes(10, 0, new byte[]{0, 0, 1, 0x65});
        packet[0] = (byte)0x90; // extension length overlaps payload and exceeds packet
        packet[14] = (byte)0xff; packet[15] = (byte)0xff;
        parser.rtp(packet, packet.length);
        parser.rtp(new byte[3], 3);
        assertTrue(frames.isEmpty());
    }
    @Test public void fullRtspNegotiationAcceptsRtpAndClosesSockets() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch ready = new CountDownLatch(1), frame = new CountDownLatch(1), audio = new CountDownLatch(1), stopped = new CountDownLatch(1);
        try (ServerSocket source = new ServerSocket(0); ServerSocket inputServer = new ServerSocket(0)) {
            source.setSoTimeout(3000);
            inputServer.setSoTimeout(2000);
            RtspWfdSession sink = new RtspWfdSession("127.0.0.1", source.getLocalPort(), new RtspWfdSession.Listener() {
                public void ready(String id, int w, int h) { assertEquals(1280, w); ready.countDown(); }
                public void video(byte[] data, long pts) { frame.countDown(); }
                public void audioFormat(int rate, int channels) { assertEquals(48000, rate); assertEquals(2, channels); }
                public void audio(byte[] bytes, long pts) { assertArrayEquals(AdtsAudioTest.frame(), bytes); audio.countDown(); }
                public void stopped(String reason) { stopped.countDown(); }
                public void log(String message) { }
            });
            Future<?> script = executor.submit(() -> {
                try (Socket socket = source.accept()) {
                    socket.setSoTimeout(3000);
                    InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
                    request(out, "OPTIONS", 10, "");
                    assertEquals("10", RtspMessage.read(in).header("cseq"));
                    RtspMessage m2 = RtspMessage.read(in);
                    reply(out, m2.header("cseq"), Collections.emptyMap());
                    request(out, "GET_PARAMETER", 11, "wfd_video_formats\r\nwfd_client_rtp_ports\r\nwfd_audio_codecs\r\n");
                    RtspMessage capabilities = RtspMessage.read(in);
                    assertTrue(capabilities.body.contains("00000020"));
                    assertTrue(capabilities.body.contains("wfd_audio_codecs: AAC 00000001 00"));
                    assertTrue(capabilities.body.contains("wfd_car_display_mode: mode=0;display=1280:720"));
                    int port = Integer.parseInt(capabilities.body.split("unicast ")[1].split(" ")[0]);
                    request(out, "SET_PARAMETER", 12, "wfd_presentation_URL: rtsp://127.0.0.1/wfd1.0/streamid=0 none\r\n"
                            + "wfd_uibc_capability: input_category_list=GENERIC;generic_cap_list=SingleTouch;port="
                            + inputServer.getLocalPort() + "\r\nwfd_trigger_method: SETUP\r\n");
                    assertEquals("12", RtspMessage.read(in).header("cseq"));
                    RtspMessage setup = RtspMessage.read(in);
                    assertTrue(setup.start.startsWith("SETUP ")); assertEquals(0, port % 2);
                    assertTrue(setup.header("Transport").contains("client_port="+port+"-"+(port+1)));
                    reply(out, setup.header("cseq"), Collections.singletonMap("Session", "fake-session;timeout=30"));
                    // A source may wait for the advertised input channel before
                    // accepting PLAY. No user input has happened at this point.
                    try (Socket inputSocket = inputServer.accept()) {
                        inputSocket.setSoTimeout(2000);
                        RtspMessage play = RtspMessage.read(in);
                        assertTrue(play.start.startsWith("PLAY ")); assertEquals("fake-session", play.header("session"));
                        reply(out, play.header("cseq"), Collections.emptyMap());
                        assertTrue(ready.await(2, TimeUnit.SECONDS));
                        assertTrue(sink.input(new byte[]{1,2,3}));
                        for (int expected : new int[]{1,2,3}) assertEquals(expected, inputSocket.getInputStream().read());
                        try (DatagramSocket udp = new DatagramSocket()) {
                            byte[] video = rtpPes(1, 0, new byte[]{0, 0, 0, 1, 0x65, 9});
                            udp.send(new DatagramPacket(video, video.length, InetAddress.getLoopbackAddress(), port));
                            byte[] aac = AdtsAudioTest.audioPacket(2, AdtsAudioTest.frame());
                            udp.send(new DatagramPacket(aac,aac.length,InetAddress.getLoopbackAddress(),port));
                            byte[] sr = new byte[28];sr[0]=(byte)0x80;sr[1]=(byte)200;sr[3]=6;sr[4]=1;
                            udp.setSoTimeout(2000);
                            udp.send(new DatagramPacket(sr,sr.length,InetAddress.getLoopbackAddress(),port+1));
                            DatagramPacket rr = new DatagramPacket(new byte[128],128);udp.receive(rr);
                            assertEquals(201,rr.getData()[1]&255);assertEquals(202,rr.getData()[9]&255);
                        }
                        assertTrue(frame.await(2, TimeUnit.SECONDS));
                        assertTrue(audio.await(2, TimeUnit.SECONDS));
                        request(out, "TEARDOWN", 13, ""); assertEquals("13", RtspMessage.read(in).header("cseq"));
                        assertTrue(stopped.await(2, TimeUnit.SECONDS));
                        assertEquals(-1, inputSocket.getInputStream().read());
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            try { sink.start(); script.get(8, TimeUnit.SECONDS); } finally { sink.close(); }
        } finally { executor.shutdownNow(); }
    }
    static void request(OutputStream out, String method, int number, String body) throws IOException {
        RtspMessage.write(out, method + " * RTSP/1.0", Collections.singletonMap("CSeq", Integer.toString(number)), body);
    }
    static void reply(OutputStream out, String number, Map<String, String> extra) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(extra); headers.put("CSeq", number);
        RtspMessage.write(out, "RTSP/1.0 200 OK", headers, "");
    }
    static byte[] rtpPes(int sequence, int counter, byte[] nal) {
        byte[] data = new byte[200]; Arrays.fill(data, (byte)0xff);
        data[0]=(byte)0x80; data[1]=33; data[2]=(byte)(sequence>>8);data[3]=(byte)sequence;
        data[12]=0x47;data[13]=0x41;data[14]=0;data[15]=(byte)(0x30|counter);
        int pesLength = 14 + nal.length;
        int adaptation = 188 - 4 - 1 - pesLength;
        data[16]=(byte)adaptation;data[17]=0;
        int p = 17 + adaptation;
        byte[] pes = {0,0,1,(byte)0xe0,0,(byte)(8+nal.length),(byte)0x80,(byte)0x80,5,0x21,0,5,(byte)0xbf,0x21}; // PTS=90000
        System.arraycopy(pes,0,data,p,pes.length);System.arraycopy(nal,0,data,p+14,nal.length);
        return data;
    }
}
