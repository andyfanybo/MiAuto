package com.miauto.app.wfd;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RawWfdSessionTest {
    private static int sequence = 1;

    @Test
    public void rawTcpNegotiationDeliversAnnexBAndAdtsAudio() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch video = new CountDownLatch(1);
        CountDownLatch audioFormat = new CountDownLatch(1);
        CountDownLatch audio = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<byte[]> videoRef = new AtomicReference<>();
        AtomicReference<byte[]> audioRef = new AtomicReference<>();
        try (ServerSocket source = new ServerSocket(0); ServerSocket inputServer = new ServerSocket(0)) {
            source.setSoTimeout(5000);
            inputServer.setSoTimeout(5000);
            RtspWfdSession sink = new RtspWfdSession("127.0.0.1", source.getLocalPort(),
                    1280, 800, 320, 60, true, new RtspWfdSession.Listener() {
                public void ready(String id, int width, int height) {
                    assertEquals("127.0.0.1:" + source.getLocalPort(), id);
                    assertEquals(1280, width);
                    assertEquals(800, height);
                    ready.countDown();
                }

                public void video(byte[] data, long ptsUs) {
                    videoRef.set(data);
                    video.countDown();
                }

                public void audioFormat(int sampleRate, int channels) {
                    assertEquals(48000, sampleRate);
                    assertEquals(2, channels);
                    audioFormat.countDown();
                }

                public void audio(byte[] data, long ptsUs) {
                    audioRef.set(data);
                    audio.countDown();
                }

                public void stopped(String reason) {
                    stopped.countDown();
                }

                public void log(String message) {
                }
            });
            Future<?> script = executor.submit(() -> {
                try (Socket rtsp = source.accept()) {
                    rtsp.setSoTimeout(5000);
                    InputStream in = rtsp.getInputStream();
                    OutputStream out = rtsp.getOutputStream();

                    WfdProtocolTest.request(out, "OPTIONS", 10, "");
                    assertEquals("10", RtspMessage.read(in).header("cseq"));
                    RtspMessage sinkOptions = RtspMessage.read(in);
                    assertTrue(sinkOptions.start.startsWith("OPTIONS "));
                    WfdProtocolTest.reply(out, sinkOptions.header("cseq"), Collections.emptyMap());

                    WfdProtocolTest.request(out, "GET_PARAMETER", 11,
                            "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\nwfd_uibc_capability\r\n");
                    RtspMessage capabilities = RtspMessage.read(in);
                    assertTrue(capabilities.body.contains("RTP/AVP/TCP;unicast"));
                    assertTrue(capabilities.body.contains("wfd_av_raw_packet: true"));
                    assertTrue(capabilities.body.contains("display=1280:800"));
                    assertTrue(capabilities.body.contains("AAC 0000000F 00"));
                    int mediaPort = Integer.parseInt(capabilities.body.split("unicast ")[1].split(" ")[0]);

                    WfdProtocolTest.request(out, "SET_PARAMETER", 12,
                            "wfd_presentation_URL: rtsp://127.0.0.1/wfd1.0/streamid=0 none\r\n"
                                    + "wfd_uibc_capability: input_category_list=GENERIC;"
                                    + "generic_cap_list=SingleTouch;port=" + inputServer.getLocalPort()
                                    + "\r\nwfd_trigger_method: SETUP\r\n");
                    assertEquals("12", RtspMessage.read(in).header("cseq"));
                    RtspMessage setup = RtspMessage.read(in);
                    assertTrue(setup.start.startsWith("SETUP "));
                    String transport = setup.header("Transport");
                    assertTrue(transport, transport.startsWith("RTP/AVP/TCP;unicast;client_port="));
                    assertEquals(mediaPort,
                            Integer.parseInt(transport.substring(transport.lastIndexOf('=') + 1)));

                    Socket media = new Socket("127.0.0.1", mediaPort);
                    media.setTcpNoDelay(true);
                    OutputStream mediaOut = media.getOutputStream();
                    WfdProtocolTest.reply(out, setup.header("cseq"),
                            Collections.singletonMap("Session", "raw-session;timeout=30"));
                    try (Socket input = inputServer.accept()) {
                        input.setSoTimeout(3000);
                        RtspMessage play = RtspMessage.read(in);
                        assertTrue(play.start.startsWith("PLAY "));
                        assertEquals("raw-session", play.header("session"));
                        WfdProtocolTest.reply(out, play.header("cseq"), Collections.emptyMap());
                        assertTrue(ready.await(2, TimeUnit.SECONDS));

                        sendRtp(mediaOut, 96, false, 90000, new byte[]{0x67, 0x42, 0x00, 0x1f});
                        sendRtp(mediaOut, 96, false, 90000, new byte[]{0x68, (byte) 0xce, 0x3c, (byte) 0x80});
                        sendRtp(mediaOut, 96, true, 90000, new byte[]{0x65, 1, 2, 3});
                        assertTrue(video.await(2, TimeUnit.SECONDS));
                        assertArrayEquals(new byte[]{
                                0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f,
                                0, 0, 0, 1, 0x68, (byte) 0xce, 0x3c, (byte) 0x80,
                                0, 0, 0, 1, 0x65, 1, 2, 3
                        }, videoRef.get());

                        byte[] aac = {0, 0x10, 0, 0x20, 0x11, 0x22, 0x33, 0x44};
                        sendRtp(mediaOut, 97, true, 90000, aac);
                        assertTrue(audioFormat.await(2, TimeUnit.SECONDS));
                        assertTrue(audio.await(2, TimeUnit.SECONDS));
                        byte[] adts = audioRef.get();
                        assertEquals(11, adts.length);
                        assertEquals(0xff, adts[0] & 255);
                        assertEquals(0x4c, adts[2] & 255);
                        assertEquals(0x80, adts[3] & 255);

                        WfdProtocolTest.request(out, "TEARDOWN", 13, "");
                        assertEquals("13", RtspMessage.read(in).header("cseq"));
                        assertTrue(stopped.await(2, TimeUnit.SECONDS));
                    }
                    media.close();
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            try {
                sink.start();
                script.get(12, TimeUnit.SECONDS);
            } finally {
                sink.close();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static synchronized void sendRtp(OutputStream out, int payloadType, boolean marker,
                                             long timestamp, byte[] payload) throws Exception {
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) ((marker ? 0x80 : 0) | (payloadType & 0x7f));
        int number = sequence++;
        packet[2] = (byte) (number >> 8);
        packet[3] = (byte) number;
        packet[4] = (byte) (timestamp >> 24);
        packet[5] = (byte) (timestamp >> 16);
        packet[6] = (byte) (timestamp >> 8);
        packet[7] = (byte) timestamp;
        packet[11] = 1;
        System.arraycopy(payload, 0, packet, 12, payload.length);
        out.write((packet.length >> 8) & 0xff);
        out.write(packet.length & 0xff);
        out.write(packet);
        out.flush();
    }
}
