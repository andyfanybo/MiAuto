package com.miauto.app.wfd;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;

/** Minimal unencrypted WFD 1.0 AVC receiver; video decoding remains in the SDK's MediaCodec path. */
public final class RtspWfdSession implements AutoCloseable {
    public interface Listener {
        void ready(String session, int width, int height);
        void video(byte[] data, long ptsUs);
        default void audioFormat(int sampleRate, int channels) { }
        default void audio(byte[] data, long ptsUs) { }
        void stopped(String reason);
        void log(String message);
    }
    private final String host, id;
    private final int port;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch playReady = new CountDownLatch(1);
    private final Map<Integer, String> pending = new HashMap<>();
    private final DatagramSocket rtp, rtcp;
    private volatile Socket control, uibc;
    private OutputStream output;
    private int sequence = 1, uibcPort;
    private String presentation, rtspSession;
    private boolean optionsSent;
    private volatile boolean ready;
    private int width = 1280, height = 720;
    public RtspWfdSession(String host, int port, Listener listener) throws IOException {
        if (host == null || host.isEmpty() || port < 1 || port > 65535) throw new IOException("Invalid WFD peer");
        this.host = host; this.port = port; this.id = host + ":" + port; this.listener = listener;
        presentation = "rtsp://" + host + "/wfd1.0/streamid=0";
        DatagramSocket[] ports = allocatePorts();
        rtp = ports[0]; rtcp = ports[1];
        try { rtp.setReceiveBufferSize(1024 * 1024); }
        catch (SocketException error) { rtp.close(); rtcp.close(); throw error; }
    }
    public String id() { return id; }
    public void start() {
        Thread controlThread = new Thread(this::run, "MiAuto-WFD-RTSP"); controlThread.setDaemon(true); controlThread.start();
        Thread receiver = new Thread(this::receive, "MiAuto-WFD-RTP"); receiver.setDaemon(true); receiver.start();
        Thread reports = new Thread(this::receiveReports, "MiAuto-WFD-RTCP"); reports.setDaemon(true); reports.start();
    }
    private void run() {
        String failure = "RTSP connection closed";
        try {
            Socket socket = new Socket(); control = socket;
            if (closed.get()) { socket.close(); return; }
            socket.connect(new InetSocketAddress(host, port), 7000);
            socket.setSoTimeout(60000); socket.setTcpNoDelay(true);
            output = socket.getOutputStream();
            listener.log("RTSP connected to " + id + ", RTP port=" + rtp.getLocalPort());
            InputStream input = socket.getInputStream();
            while (!closed.get()) {
                RtspMessage message = RtspMessage.read(input);
                if (message == null) break;
                listener.log("RTSP " + message.start + " cseq=" + message.header("cseq"));
                if (message.start.startsWith("RTSP/")) response(message); else request(message);
            }
        } catch (IOException | RuntimeException error) {
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally { finish(failure); }
    }
    private void request(RtspMessage message) throws IOException {
        String method = message.start.split(" ", 2)[0];
        Map<String, String> headers = new LinkedHashMap<>();
        String cseq = message.header("cseq");
        if (cseq == null) throw new IOException("Missing RTSP CSeq");
        headers.put("CSeq", cseq);
        switch (method) {
            case "OPTIONS":
                headers.put("Public", "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN");
                RtspMessage.write(output, "RTSP/1.0 200 OK", headers, "");
                if (!optionsSent) { optionsSent = true; send("OPTIONS", "*", ""); }
                break;
            case "GET_PARAMETER":
                StringBuilder body = new StringBuilder();
                Set<String> requested = new HashSet<>();
                for (String key : message.body.split("\\r?\\n")) {
                    key = key.trim().toLowerCase(Locale.US);
                    int colon = key.indexOf(':');
                    if (colon >= 0) key = key.substring(0, colon).trim();
                    if (key.isEmpty()) continue;
                    requested.add(key);
                    String value = capability(key);
                    body.append(key).append(": ").append(value).append("\r\n");
                }
                listener.log("Requested WFD parameters: " + requested);
                if (!requested.isEmpty() && !requested.contains("wfd_car_display_mode"))
                    body.append("wfd_car_display_mode: ").append(capability("wfd_car_display_mode")).append("\r\n");
                RtspMessage.write(output, "RTSP/1.0 200 OK", headers, body.toString());
                break;
            case "SET_PARAMETER":
                Map<String, String> values = parameters(message.body);
                if (values.containsKey("wfd_presentation_url")) {
                    String uri = values.get("wfd_presentation_url").split(" ")[0];
                    if (!uri.startsWith("rtsp://") || uri.indexOf('\r') >= 0 || uri.indexOf('\n') >= 0) throw new IOException("Invalid presentation URL");
                    presentation = uri;
                }
                if (values.containsKey("wfd_video_formats")) listener.log("Selected AVC format: " + values.get("wfd_video_formats"));
                if (values.containsKey("wfd_audio_codecs")) listener.log("Selected audio: " + values.get("wfd_audio_codecs"));
                if (values.containsKey("wfd_uibc_capability")) {
                    for (String part : values.get("wfd_uibc_capability").split(";")) {
                        part = part.trim();
                        if (part.startsWith("port=")) {
                            try { uibcPort = Integer.parseInt(part.substring(5)); } catch (NumberFormatException ignored) { }
                        }
                    }
                }
                RtspMessage.write(output, "RTSP/1.0 200 OK", headers, "");
                String trigger = values.get("wfd_trigger_method");
                if ("SETUP".equalsIgnoreCase(trigger)) send("SETUP", presentation, "");
                else if ("TEARDOWN".equalsIgnoreCase(trigger)) finish("Source teardown");
                else if ("PAUSE".equalsIgnoreCase(trigger)) send("PAUSE", presentation, "");
                else if ("PLAY".equalsIgnoreCase(trigger) || "RESUME".equalsIgnoreCase(trigger)) send("PLAY", presentation, "");
                break;
            case "TEARDOWN":
                RtspMessage.write(output, "RTSP/1.0 200 OK", headers, ""); finish("Source teardown"); break;
            default: RtspMessage.write(output, "RTSP/1.0 405 Method Not Allowed", headers, "");
        }
    }
    private void response(RtspMessage message) throws IOException {
        int cseq;
        try { cseq = Integer.parseInt(message.header("cseq")); } catch (RuntimeException e) { throw new IOException("Invalid response CSeq", e); }
        String method;
        synchronized (this) { method = pending.remove(cseq); }
        if (method == null) return;
        if (!message.start.startsWith("RTSP/1.0 200")) throw new IOException(method + " failed: " + message.start);
        if ("SETUP".equals(method)) {
            String transport = message.header("transport");
            listener.log("Selected transport: " + transport);
            if (transport != null) {
                String kind = transport.toUpperCase(Locale.US);
                if (!kind.startsWith("RTP/AVP") || kind.contains("TCP") || kind.contains("INTERLEAVED"))
                    throw new IOException("Unsupported WFD media transport: " + transport);
            }
            rtspSession = message.header("session");
            if (rtspSession == null) throw new IOException("SETUP response has no session");
            rtspSession = rtspSession.split(";", 2)[0].trim();
            send("PLAY", presentation, "");
        } else if ("PLAY".equals(method) && !ready) {
            listener.ready(id, width, height);
            ready = true;
            playReady.countDown();
            listener.log("WFD PLAY accepted; awaiting AVC RTP");
        }
    }
    private String capability(String key) {
        switch (key) {
            // Native CEA index 5 (720p30), constrained baseline, level 3.2.
            case "wfd_video_formats": return "28 00 01 02 00000020 00000000 00000000 00 0000 0000 00 none none";
            case "wfd_audio_codecs": return "AAC 00000001 00"; // AAC-LC, 48kHz stereo, decoded by the SDK MediaCodec path.
            case "wfd_client_rtp_ports": return "RTP/AVP/UDP;unicast " + rtp.getLocalPort() + " 0 mode=play";
            case "wfd_car_display_mode": return "mode=0;display=1280:720;dpi=160;fps=30";
            case "wfd_av_raw_packet": return "false";
            case "wfd_standby_resume_capability": return "supported";
            case "wfd_audio_latency": return "0";
            case "wfd_content_protection": return "none";
            case "wfd_display_edid": return "none";
            case "wfd_coupled_sink": return "00 none";
            case "wfd_connector_type": return "05";
            case "wfd_uibc_capability": return "input_category_list=GENERIC;generic_cap_list=Keyboard, Mouse, SingleTouch, MultiTouch;hidc_cap_list=none;port=none";
            default: return "none";
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
        if ("SETUP".equals(method)) headers.put("Transport", "RTP/AVP/UDP;unicast;client_port=" + rtp.getLocalPort() + "-" + rtcp.getLocalPort());
        pending.put(number, method);
        RtspMessage.write(output, method + " " + uri + " RTSP/1.0", headers, body);
    }
    private void receive() {
        AtomicBoolean firstFrame = new AtomicBoolean();
        MpegTsAvc parser = new MpegTsAvc((data, pts) -> {
            if (!closed.get() && ready) {
                if (firstFrame.compareAndSet(false, true)) listener.log("First AVC access unit: bytes=" + data.length + ", ptsUs=" + pts);
                listener.video(data, pts);
            }
        });
        AdtsAudio audio = new AdtsAudio(new AdtsAudio.Listener() {
            public void format(int sampleRate, int channels) {
                listener.log("AAC format: " + sampleRate + " Hz, channels=" + channels);
                listener.audioFormat(sampleRate, channels);
            }
            public void frame(byte[] data, long pts) { if (!closed.get() && ready) listener.audio(data, pts); }
        });
        MpegTsAvc audioPes = new MpegTsAvc(audio::push, true);
        byte[] bytes = new byte[65535];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        try {
            InetAddress peer = InetAddress.getByName(host);
            boolean firstPacket = true;
            try { playReady.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            while (!closed.get()) {
                packet.setLength(bytes.length); rtp.receive(packet);
                if (peer.equals(packet.getAddress())) {
                    if (firstPacket && packet.getLength() >= 2) {
                        firstPacket = false;
                        listener.log("First RTP packet: bytes=" + packet.getLength() + ", payloadType=" + (bytes[1] & 127));
                    }
                    parser.rtp(bytes, packet.getLength());
                    audioPes.rtp(bytes, packet.getLength());
                }
            }
        } catch (IOException | RuntimeException error) {
            if (!closed.get()) finish("RTP: " + error.getMessage());
        }
    }
    private static DatagramSocket[] allocatePorts() throws IOException {
        for (int port = 19000; port < 19100; port += 2) {
            DatagramSocket media = null;
            try {
                media = new DatagramSocket(port);
                return new DatagramSocket[]{media, new DatagramSocket(port + 1)};
            } catch (SocketException error) { if (media != null) media.close(); }
        }
        throw new IOException("No free WFD RTP/RTCP port pair");
    }
    private void receiveReports() {
        byte[] bytes = new byte[4096];
        DatagramPacket report = new DatagramPacket(bytes, bytes.length);
        int receiverSsrc = new Random().nextInt();
        byte[] cname = "MiAuto-AVC".getBytes(StandardCharsets.US_ASCII);
        int sdesSize = (4 + 4 + 2 + cname.length + 1 + 3) & ~3;
        ByteBuffer reply = ByteBuffer.allocate(8 + sdesSize);
        // Empty receiver report followed by SDES/CNAME. We do not fabricate
        // packet-loss or jitter measurements in an unimplemented report block.
        reply.put((byte)0x80).put((byte)201).putShort((short)1).putInt(receiverSsrc);
        reply.put((byte)0x81).put((byte)202).putShort((short)(sdesSize / 4 - 1)).putInt(receiverSsrc);
        reply.put((byte)1).put((byte)cname.length).put(cname);
        try {
            InetAddress peer = InetAddress.getByName(host);
            while (!closed.get()) {
                report.setLength(bytes.length); rtcp.receive(report);
                int size = 4 * ((((bytes[2] & 255) << 8) | (bytes[3] & 255)) + 1);
                if (peer.equals(report.getAddress()) && report.getLength() >= 28 && size <= report.getLength()
                        && size >= 28 && (bytes[0] & 0xc0) == 0x80 && (bytes[1] & 255) == 200) {
                    rtcp.send(new DatagramPacket(reply.array(), reply.capacity(), peer, report.getPort()));
                }
            }
        } catch (IOException error) { if (!closed.get()) finish("RTCP: " + error.getMessage()); }
    }
    public synchronized boolean pause(boolean paused) {
        try { send(paused ? "PAUSE" : "PLAY", presentation, ""); return true; }
        catch (IOException e) { return false; }
    }
    public synchronized boolean input(byte[] data) {
        if (closed.get() || !ready || uibcPort < 1 || uibcPort > 65535 || data == null || data.length > 65535) return false;
        try {
            if (uibc == null) {
                uibc = new Socket();
                if (closed.get()) { uibc.close(); return false; }
                uibc.connect(new InetSocketAddress(host, uibcPort), 1500);
            }
            uibc.getOutputStream().write(data); uibc.getOutputStream().flush(); return true;
        } catch (IOException error) { closeSocket(uibc); uibc = null; return false; }
    }
    @Override public void close() { finish("Closed by app"); }
    private void finish(String reason) {
        if (closed.compareAndSet(false, true)) {
            rtp.close(); rtcp.close(); closeSocket(control); closeSocket(uibc); playReady.countDown();
        }
        if (stopped.compareAndSet(false, true)) { listener.log(reason); listener.stopped(reason); }
    }
    private static void closeSocket(Socket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
    private static Map<String, String> parameters(String body) {
        Map<String, String> result = new HashMap<>();
        for (String line : body.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) result.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
        }
        return result;
    }
}
