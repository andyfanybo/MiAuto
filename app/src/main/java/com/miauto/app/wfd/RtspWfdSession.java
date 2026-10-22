package com.miauto.app.wfd;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal Wi-Fi Display sink for the ARMv7 ICCOA compatibility path.
 *
 * <p>Two media modes are supported:
 * <ul>
 *   <li><b>raw</b> (default, mirrors the vendor arm64 sink): {@code RTP/AVP/TCP},
 *       {@code wfd_av_raw_packet: true}, raw H.264 access units plus AAC. The source
 *       opens a TCP connection to the sink's {@code client_port} and prefixes each
 *       RTP/RTCP packet with a two byte length (RFC 4571).</li>
 *   <li><b>legacy</b>: {@code RTP/AVP/UDP} with MPEG-TS and ADTS payloads. Kept as a
 *       fallback and covered by the original protocol tests.</li>
 * </ul>
 *
 * <p>Video decoding remains in the SDK's MediaCodec path; this class only performs
 * RTSP negotiation and RTP depacketization.
 */
public final class RtspWfdSession implements AutoCloseable {
    public interface Listener {
        void ready(String session, int width, int height);
        void video(byte[] data, long ptsUs);
        default void audioFormat(int sampleRate, int channels) { }
        default void audio(byte[] data, long ptsUs) { }
        void stopped(String reason);
        void log(String message);
    }

    private static final int MEDIA_PORT_FIRST = 19000;
    private static final int MEDIA_PORT_LAST = 19100;
    private static final int MEDIA_ACCEPT_TIMEOUT_MS = 15000;
    private static final int MAX_PENDING_FRAMES = 90;

    private final String host, id;
    private final int port;
    private final Listener listener;
    private final boolean raw;
    private final int displayW, displayH, displayDpi, displayFps;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch playReady = new CountDownLatch(1);
    private final Map<Integer, String> pending = new HashMap<>();
    private final List<Frame> pendingVideo = new ArrayList<>();
    private final List<Frame> pendingAudio = new ArrayList<>();
    private final DatagramSocket rtp, rtcp;
    private final ServerSocket mediaServer;
    private volatile Socket control, uibc, media;
    private OutputStream output;
    private int sequence = 1, uibcPort;
    private String presentation, rtspSession;
    private boolean optionsSent;
    private volatile boolean ready;
    private int width, height;
    private RawAvc avc;
    private RawAac aac;
    private MpegTsAvc mpegTsVideo, mpegTsAudio;

    public RtspWfdSession(String host, int port, Listener listener) throws IOException {
        this(host, port, 1280, 720, 160, 30, false, listener);
    }

    public RtspWfdSession(String host, int port, int width, int height, int dpi, int fps,
                          boolean raw, Listener listener) throws IOException {
        if (host == null || host.isEmpty() || port < 1 || port > 65535) {
            throw new IOException("Invalid WFD peer");
        }
        this.host = host;
        this.port = port;
        this.id = host + ":" + port;
        this.listener = listener;
        this.raw = raw;
        this.displayW = width > 0 ? width : 1280;
        this.displayH = height > 0 ? height : 720;
        this.displayDpi = dpi > 0 ? dpi : 160;
        this.displayFps = fps > 0 ? fps : 30;
        this.width = displayW;
        this.height = displayH;
        this.presentation = "rtsp://" + host + "/wfd1.0/streamid=0";
        if (raw) {
            rtp = null;
            rtcp = null;
            mediaServer = allocateTcpPort();
        } else {
            DatagramSocket[] ports = allocateUdpPorts();
            rtp = ports[0];
            rtcp = ports[1];
            mediaServer = null;
            try {
                rtp.setReceiveBufferSize(1024 * 1024);
            } catch (SocketException error) {
                rtp.close();
                rtcp.close();
                throw error;
            }
        }
    }

    public String id() {
        return id;
    }

    public boolean raw() {
        return raw;
    }

    public void start() {
        if (raw) prepareRawParsers();
        Thread controlThread = new Thread(this::run, "MiAuto-WFD-RTSP");
        Thread receiver = new Thread(this::receive, "MiAuto-WFD-RTP");
        controlThread.setDaemon(true);
        receiver.setDaemon(true);
        controlThread.start();
        receiver.start();
        if (!raw) {
            Thread reports = new Thread(this::receiveReports, "MiAuto-WFD-RTCP");
            reports.setDaemon(true);
            reports.start();
        }
    }

    private void run() {
        String failure = "RTSP connection closed";
        try {
            Socket socket = new Socket();
            control = socket;
            if (closed.get()) {
                socket.close();
                return;
            }
            socket.connect(new InetSocketAddress(host, port), 7000);
            socket.setSoTimeout(60000);
            socket.setTcpNoDelay(true);
            output = socket.getOutputStream();
            listener.log("RTSP connected to " + id + ", RTP port="
                    + (raw ? mediaServer.getLocalPort() : rtp.getLocalPort()));
            InputStream input = socket.getInputStream();
            while (!closed.get()) {
                RtspMessage message = RtspMessage.read(input);
                if (message == null) break;
                listener.log("RTSP " + message.start + " cseq=" + message.header("cseq"));
                if (message.start.startsWith("RTSP/")) response(message);
                else request(message);
            }
        } catch (IOException | RuntimeException error) {
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally {
            finish(failure);
        }
    }

    private void request(RtspMessage message) throws IOException {
        String method = message.start.split(" ", 2)[0];
        Map<String, String> headers = new LinkedHashMap<>();
        String cseq = message.header("cseq");
        if (cseq == null) throw new IOException("Missing RTSP CSeq");
        headers.put("CSeq", cseq);
        switch (method) {
            case "OPTIONS":
                headers.put("Public", "org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER");
                RtspMessage.write(output, "RTSP/1.0 200 OK", headers, "");
                if (!optionsSent) {
                    optionsSent = true;
                    send("OPTIONS", "*", "");
                }
                break;
            case "GET_PARAMETER": {
                Map<String, String> values = capabilities();
                Set<String> requested = new LinkedHashSet<>();
                for (String line : message.body.split("\\r?\\n")) {
                    String key = line.trim().toLowerCase(Locale.US);
                    int colon = key.indexOf(':');
                    if (colon >= 0) key = key.substring(0, colon).trim();
                    if (!key.isEmpty()) requested.add(key);
                }
                StringBuilder body = new StringBuilder();
                for (String key : requested) {
                    String value = values.get(key);
                    if (value != null) body.append(key).append(": ").append(value).append("\r\n");
                }
                // Advertise the complete set, exactly like the vendor sink does.
                // In particular the source only selects raw packets when it sees
                // wfd_av_raw_packet even if it never requested that key.
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (!requested.contains(entry.getKey())) {
                        body.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                    }
                }
                listener.log("WFD parameters requested: " + requested);
                RtspMessage.write(output, "RTSP/1.0 200 OK", headers, body.toString());
                break;
            }
            case "SET_PARAMETER": {
                Map<String, String> values = parameters(message.body);
                if (values.containsKey("wfd_presentation_url")) {
                    String uri = values.get("wfd_presentation_url").split(" ")[0];
                    if (!uri.startsWith("rtsp://") || uri.indexOf('\r') >= 0 || uri.indexOf('\n') >= 0) {
                        throw new IOException("Invalid presentation URL");
                    }
                    presentation = uri;
                }
                if (values.containsKey("wfd_video_formats")) {
                    listener.log("Selected AVC format: " + values.get("wfd_video_formats"));
                }
                if (values.containsKey("wfd_audio_codecs")) {
                    listener.log("Selected audio: " + values.get("wfd_audio_codecs"));
                }
                if (values.containsKey("wfd_client_rtp_ports")) {
                    listener.log("Selected media transport: " + values.get("wfd_client_rtp_ports"));
                }
                if (values.containsKey("wfd_av_raw_packet")) {
                    listener.log("Selected raw AV packets: " + values.get("wfd_av_raw_packet"));
                }
                if (values.containsKey("wfd_uibc_capability")) {
                    listener.log("Selected UIBC: " + values.get("wfd_uibc_capability"));
                    for (String part : values.get("wfd_uibc_capability").split(";")) {
                        part = part.trim();
                        if (part.startsWith("port=")) {
                            try {
                                uibcPort = Integer.parseInt(part.substring(5));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
                RtspMessage.write(output, "RTSP/1.0 200 OK", headers, "");
                String trigger = values.get("wfd_trigger_method");
                if ("SETUP".equalsIgnoreCase(trigger)) send("SETUP", presentation, "");
                else if ("TEARDOWN".equalsIgnoreCase(trigger)) finish("Source teardown");
                else if ("PAUSE".equalsIgnoreCase(trigger)) send("PAUSE", presentation, "");
                else if ("PLAY".equalsIgnoreCase(trigger) || "RESUME".equalsIgnoreCase(trigger)) {
                    send("PLAY", presentation, "");
                }
                break;
            }
            case "TEARDOWN":
                RtspMessage.write(output, "RTSP/1.0 200 OK", headers, "");
                finish("Source teardown");
                break;
            default:
                RtspMessage.write(output, "RTSP/1.0 405 Method Not Allowed", headers, "");
        }
    }

    private void response(RtspMessage message) throws IOException {
        int cseq;
        try {
            cseq = Integer.parseInt(message.header("cseq"));
        } catch (RuntimeException error) {
            throw new IOException("Invalid response CSeq", error);
        }
        String method;
        synchronized (this) {
            method = pending.remove(cseq);
        }
        if (method == null) return;
        if (!message.start.startsWith("RTSP/1.0 200")) throw new IOException(method + " failed: " + message.start);
        if ("SETUP".equals(method)) {
            String transport = message.header("transport");
            listener.log("Selected transport: " + transport);
            if (transport != null) {
                String kind = transport.toUpperCase(Locale.US);
                if (raw) {
                    if (!kind.startsWith("RTP/AVP/TCP") || kind.contains("INTERLEAVED")) {
                        throw new IOException("Source rejected RTP/AVP/TCP: " + transport);
                    }
                } else if (!kind.startsWith("RTP/AVP") || kind.contains("TCP") || kind.contains("INTERLEAVED")) {
                    throw new IOException("Unsupported WFD media transport: " + transport);
                }
            }
            rtspSession = message.header("session");
            if (rtspSession == null) throw new IOException("SETUP response has no session");
            rtspSession = rtspSession.split(";", 2)[0].trim();
            // The source opens its negotiated input server during setup. Connect
            // before PLAY even when the user has not touched the display.
            connectUibc();
            send("PLAY", presentation, "");
        } else if ("PLAY".equals(method) && !ready) {
            ready = true;
            playReady.countDown();
            listener.ready(id, width, height);
            listener.log(raw
                    ? "WFD PLAY accepted; awaiting raw AVC RTP"
                    : "WFD PLAY accepted; awaiting AVC RTP");
            List<Frame> flush;
            synchronized (pendingVideo) {
                flush = new ArrayList<>(pendingVideo);
                pendingVideo.clear();
            }
            for (Frame frame : flush) listener.video(frame.data, frame.ptsUs);
            synchronized (pendingAudio) {
                flush = new ArrayList<>(pendingAudio);
                pendingAudio.clear();
            }
            for (Frame frame : flush) listener.audio(frame.data, frame.ptsUs);
        }
    }

    private synchronized void send(String method, String uri, String body) throws IOException {
        if (closed.get() || output == null) throw new IOException("RTSP session unavailable");
        int number = sequence++;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("CSeq", Integer.toString(number));
        headers.put("User-Agent", "MiAuto-AVC-Sink/1.0");
        if (rtspSession != null) headers.put("Session", rtspSession);
        if ("OPTIONS".equals(method)) headers.put("Require", "org.wfa.wfd1.0");
        if ("SETUP".equals(method)) {
            headers.put("Transport", raw
                    ? "RTP/AVP/TCP;unicast;client_port=" + mediaServer.getLocalPort()
                    : "RTP/AVP/UDP;unicast;client_port=" + rtp.getLocalPort() + "-" + rtcp.getLocalPort());
        }
        pending.put(number, method);
        RtspMessage.write(output, method + " " + uri + " RTSP/1.0", headers, body);
    }

    private Map<String, String> capabilities() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("wfd_car_display_mode",
                "mode=0;display=" + displayW + ":" + displayH + ";dpi=" + displayDpi + ";fps=" + displayFps);
        // The vendor sink offers every profile/resolution; the phone scales the
        // stream to the display mode above. Restricting the mask to 720p30 made
        // some CarWith versions negotiate a stream they then refused to start.
        values.put("wfd_video_formats", raw
                ? "28 00 01 01 ffffffff ffffffff ffffffff 00 0000 0000 00 none none"
                : "28 00 01 02 00000020 00000000 00000000 00 0000 0000 00 none none");
        values.put("wfd_audio_codecs", raw ? "AAC 0000000F 00" : "AAC 00000001 00");
        values.put("wfd_client_rtp_ports", raw
                ? "RTP/AVP/TCP;unicast " + mediaServer.getLocalPort() + " 0 mode=play"
                : "RTP/AVP/UDP;unicast " + rtp.getLocalPort() + " 0 mode=play");
        values.put("wfd_uibc_capability",
                "input_category_list=GENERIC;generic_cap_list=Keyboard, Mouse, SingleTouch, MultiTouch;"
                        + "hidc_cap_list=none;port=none");
        values.put("wfd_standby_resume_capability", "supported");
        values.put("wfd_audio_latency", "0");
        values.put("wfd_av_raw_packet", raw ? "true" : "false");
        return values;
    }

    private void receive() {
        if (raw) {
            receiveTcp();
            return;
        }
        receiveUdp();
    }

    private void receiveTcp() {
        try {
            mediaServer.setSoTimeout(MEDIA_ACCEPT_TIMEOUT_MS);
            Socket socket = mediaServer.accept();
            media = socket;
            socket.setTcpNoDelay(true);
            listener.log("Media TCP connected from " + socket.getRemoteSocketAddress());
            InputStream input = new BufferedInputStream(socket.getInputStream(), 65536);
            byte[] prefix = new byte[2];
            byte[] buffer = new byte[65535];
            while (!closed.get()) {
                readFully(input, prefix, 2);
                int length;
                if ((prefix[0] & 255) == 0x24) {
                    // RTSP interleaved framing on the media connection (fallback).
                    readFully(input, prefix, 2);
                    length = ((prefix[0] & 255) << 8) | (prefix[1] & 255);
                } else {
                    length = ((prefix[0] & 255) << 8) | (prefix[1] & 255);
                }
                if (length < 12 || length > buffer.length) {
                    if (length <= 0) throw new IOException("Invalid media packet length " + length);
                    skipFully(input, length);
                    continue;
                }
                readFully(input, buffer, length);
                dispatch(buffer, length);
            }
        } catch (SocketTimeoutException error) {
            if (!closed.get()) finish("Media TCP connection timed out");
        } catch (IOException | RuntimeException error) {
            if (!closed.get()) finish("Media TCP: " + error.getMessage());
        }
    }

    private void dispatch(byte[] data, int length) {
        if (length < 12) return;
        int first = data[0] & 255;
        if ((first >> 6) != 2) return;
        boolean padding = (first & 0x20) != 0;
        boolean extension = (first & 0x10) != 0;
        int csrc = first & 0x0f;
        int payloadType = data[1] & 0x7f;
        boolean marker = (data[1] & 0x80) != 0;
        long timestamp = ((long) (data[4] & 255) << 24) | ((long) (data[5] & 255) << 16)
                | ((long) (data[6] & 255) << 8) | (data[7] & 255);
        int offset = 12 + csrc * 4;
        if (extension) {
            if (offset + 4 > length) return;
            int words = ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
            offset += 4 + words * 4;
        }
        int end = length;
        if (padding) {
            int pad = data[length - 1] & 255;
            if (pad == 0 || pad > end - offset) return;
            end -= pad;
        }
        if (offset >= end) return;
        if (payloadType >= 200 && payloadType <= 204) return; // RTCP
        if (payloadType == 33) { // source ignored raw mode and sent MPEG-TS
            if (mpegTsVideo != null) mpegTsVideo.rtp(data, length);
            if (mpegTsAudio != null) mpegTsAudio.rtp(data, length);
            return;
        }
        if (isH264(data, offset)) {
            if (avc != null) avc.packet(data, offset, end - offset, marker, timestamp);
        } else if (aac != null) {
            aac.packet(data, offset, end - offset, marker, timestamp);
        }
    }

    private static boolean isH264(byte[] data, int offset) {
        int header = data[offset] & 255;
        if ((header & 0x80) != 0) return false;
        int type = header & 0x1f;
        return (type >= 1 && type <= 23) || type == 24 || type == 28 || type == 29;
    }

    private void receiveUdp() {
        MpegTsAvc videoParser = new MpegTsAvc(this::deliverVideo);
        AdtsAudio audioParser = new AdtsAudio(new AdtsAudio.Listener() {
            public void format(int sampleRate, int channels) {
                listener.log("AAC format: " + sampleRate + " Hz, channels=" + channels);
                listener.audioFormat(sampleRate, channels);
            }

            public void frame(byte[] data, long pts) {
                deliverAudio(data, pts);
            }
        });
        MpegTsAvc audioPes = new MpegTsAvc(audioParser::push, true);
        avc = null;
        aac = null;
        mpegTsVideo = null;
        mpegTsAudio = null;
        byte[] bytes = new byte[65535];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        try {
            InetAddress peer = InetAddress.getByName(host);
            boolean firstPacket = true;
            try {
                playReady.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            while (!closed.get()) {
                packet.setLength(bytes.length);
                rtp.receive(packet);
                if (peer.equals(packet.getAddress())) {
                    if (firstPacket && packet.getLength() >= 2) {
                        firstPacket = false;
                        listener.log("First RTP packet: bytes=" + packet.getLength()
                                + ", payloadType=" + (bytes[1] & 127));
                    }
                    videoParser.rtp(bytes, packet.getLength());
                    audioPes.rtp(bytes, packet.getLength());
                }
            }
        } catch (IOException | RuntimeException error) {
            if (!closed.get()) finish("RTP: " + error.getMessage());
        }
    }

    private void prepareRawParsers() {
        if (avc != null) return;
        avc = new RawAvc(this::deliverVideo);
        aac = new RawAac(new RawAac.Listener() {
            public void format(int sampleRate, int channels) {
                listener.log("AAC format: " + sampleRate + " Hz, channels=" + channels);
                listener.audioFormat(sampleRate, channels);
            }

            public void frame(byte[] data, long pts) {
                deliverAudio(data, pts);
            }
        });
        mpegTsVideo = new MpegTsAvc(this::deliverVideo);
        AdtsAudio adts = new AdtsAudio(new AdtsAudio.Listener() {
            public void format(int sampleRate, int channels) {
                listener.audioFormat(sampleRate, channels);
            }

            public void frame(byte[] data, long pts) {
                deliverAudio(data, pts);
            }
        });
        mpegTsAudio = new MpegTsAvc(adts::push, true);
    }

    private void deliverVideo(byte[] data, long pts) {
        if (data == null || data.length == 0) return;
        if (ready) {
            listener.video(data, pts);
            return;
        }
        synchronized (pendingVideo) {
            if (pendingVideo.size() >= MAX_PENDING_FRAMES) pendingVideo.remove(0);
            pendingVideo.add(new Frame(data, pts));
        }
    }

    private void deliverAudio(byte[] data, long pts) {
        if (data == null || data.length == 0) return;
        if (ready) {
            listener.audio(data, pts);
            return;
        }
        synchronized (pendingAudio) {
            if (pendingAudio.size() >= MAX_PENDING_FRAMES) pendingAudio.remove(0);
            pendingAudio.add(new Frame(data, pts));
        }
    }

    private synchronized boolean connectUibc() throws IOException {
        if (uibcPort < 1 || uibcPort > 65535 || uibc != null) return false;
        if (closed.get()) throw new IOException("UIBC session closed");
        Socket socket = new Socket();
        uibc = socket;
        try {
            socket.connect(new InetSocketAddress(host, uibcPort), 1500);
            socket.setTcpNoDelay(true);
            if (closed.get()) throw new IOException("UIBC session closed during connection");
            listener.log("UIBC connected to " + host + ":" + uibcPort + " before media playback");
            return true;
        } catch (IOException error) {
            closeSocket(socket);
            uibc = null;
            throw new IOException("UIBC connection failed: " + error.getMessage(), error);
        }
    }

    public synchronized boolean pause(boolean paused) {
        try {
            send(paused ? "PAUSE" : "PLAY", presentation, "");
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    public synchronized boolean input(byte[] data) {
        if (closed.get() || !ready || uibcPort < 1 || uibcPort > 65535 || data == null || data.length > 65535) {
            return false;
        }
        try {
            connectUibc();
            uibc.getOutputStream().write(data);
            uibc.getOutputStream().flush();
            return true;
        } catch (IOException error) {
            closeSocket(uibc);
            uibc = null;
            return false;
        }
    }

    @Override
    public void close() {
        finish("Closed by app");
    }

    private void finish(String reason) {
        if (closed.compareAndSet(false, true)) {
            closeServer(mediaServer);
            closeSocket(media);
            closeSocket(control);
            closeSocket(uibc);
            if (rtp != null) rtp.close();
            if (rtcp != null) rtcp.close();
            playReady.countDown();
        }
        if (stopped.compareAndSet(false, true)) {
            listener.log(reason);
            listener.stopped(reason);
        }
    }

    private static void readFully(InputStream input, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int count = input.read(buffer, offset, length - offset);
            if (count < 0) throw new EOFException("Media stream truncated");
            offset += count;
        }
    }

    private static void skipFully(InputStream input, int length) throws IOException {
        byte[] scratch = new byte[Math.min(length, 8192)];
        int remaining = length;
        while (remaining > 0) {
            int count = input.read(scratch, 0, Math.min(scratch.length, remaining));
            if (count < 0) throw new EOFException("Media stream truncated");
            remaining -= count;
        }
    }

    private void receiveReports() {
        byte[] bytes = new byte[4096];
        DatagramPacket report = new DatagramPacket(bytes, bytes.length);
        int receiverSsrc = new Random().nextInt();
        byte[] cname = "MiAuto-AVC".getBytes(StandardCharsets.US_ASCII);
        int sdesSize = (4 + 4 + 2 + cname.length + 1 + 3) & ~3;
        ByteBuffer reply = ByteBuffer.allocate(8 + sdesSize);
        reply.put((byte) 0x80).put((byte) 201).putShort((short) 1).putInt(receiverSsrc);
        reply.put((byte) 0x81).put((byte) 202).putShort((short) (sdesSize / 4 - 1)).putInt(receiverSsrc);
        reply.put((byte) 1).put((byte) cname.length).put(cname);
        try {
            InetAddress peer = InetAddress.getByName(host);
            while (!closed.get()) {
                report.setLength(bytes.length);
                rtcp.receive(report);
                int size = 4 * ((((bytes[2] & 255) << 8) | (bytes[3] & 255)) + 1);
                if (peer.equals(report.getAddress()) && report.getLength() >= 28 && size <= report.getLength()
                        && size >= 28 && (bytes[0] & 0xc0) == 0x80 && (bytes[1] & 255) == 200) {
                    rtcp.send(new DatagramPacket(reply.array(), reply.capacity(), peer, report.getPort()));
                }
            }
        } catch (IOException error) {
            if (!closed.get()) finish("RTCP: " + error.getMessage());
        }
    }

    private static ServerSocket allocateTcpPort() throws IOException {
        IOException failure = null;
        for (int candidate = MEDIA_PORT_FIRST; candidate < MEDIA_PORT_LAST; candidate++) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(candidate), 4);
                return socket;
            } catch (IOException error) {
                failure = error;
            }
        }
        throw failure != null ? failure : new IOException("No free WFD media TCP port");
    }

    private static DatagramSocket[] allocateUdpPorts() throws IOException {
        for (int candidate = MEDIA_PORT_FIRST; candidate < MEDIA_PORT_LAST; candidate += 2) {
            DatagramSocket media = null;
            try {
                media = new DatagramSocket(candidate);
                return new DatagramSocket[]{media, new DatagramSocket(candidate + 1)};
            } catch (SocketException error) {
                if (media != null) media.close();
            }
        }
        throw new IOException("No free WFD RTP/RTCP port pair");
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeServer(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Map<String, String> parameters(String body) {
        Map<String, String> result = new HashMap<>();
        for (String line : body.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) result.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
        }
        return result;
    }

    private static final class Frame {
        final byte[] data;
        final long ptsUs;

        Frame(byte[] data, long ptsUs) {
            this.data = data;
            this.ptsUs = ptsUs;
        }
    }
}
