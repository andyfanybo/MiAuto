package com.miauto.app.wfd;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded RTSP framing: TCP reads need not align with headers or message bodies. */
public final class RtspMessage {
    public final String start;
    public final Map<String, String> headers;
    public final String body;
    RtspMessage(String start, Map<String, String> headers, String body) {
        this.start = start; this.headers = headers; this.body = body;
    }
    public String header(String name) { return headers.get(name.toLowerCase(Locale.US)); }
    public static RtspMessage read(InputStream in) throws IOException {
        String start = line(in);
        if (start == null) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        int total = start.length();
        for (;;) {
            String line = line(in);
            if (line == null) throw new EOFException("RTSP headers truncated");
            total += line.length();
            if (total > 32768) throw new IOException("RTSP headers too large");
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("Invalid RTSP header");
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
        }
        int length = 0;
        try { if (headers.containsKey("content-length")) length = Integer.parseInt(headers.get("content-length")); }
        catch (NumberFormatException e) { throw new IOException("Invalid RTSP body length", e); }
        if (length < 0 || length > 65536) throw new IOException("RTSP body too large");
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException("RTSP body truncated");
            offset += count;
        }
        return new RtspMessage(start, headers, new String(bytes, StandardCharsets.UTF_8));
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (;;) {
            int value = in.read();
            if (value < 0) {
                if (out.size() == 0) return null;
                throw new EOFException("RTSP line truncated");
            }
            if (value == '\n') {
                byte[] bytes = out.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            if (out.size() >= 8192) throw new IOException("RTSP line too large");
            out.write(value);
        }
    }
    static synchronized void write(OutputStream out, String start, Map<String, String> headers, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder(start).append("\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) text.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        if (bytes.length != 0) text.append("Content-Type: text/parameters\r\nContent-Length: ").append(bytes.length).append("\r\n");
        text.append("\r\n");
        out.write(text.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bytes); out.flush();
    }
}
