package com.miauto.app.wfd;

import java.lang.reflect.*;
import java.util.*;

/** JNI target for our ARMv7 compatibility library. Uses the SDK's public callback ABI. */
public final class CompatWfdSink {
    private static RtspWfdSession session;
    private static final Map<String, Method> callbacks = new HashMap<>();
    private CompatWfdSink() { }
    public static synchronized boolean start(String host, int port, int width, int height,
            int dpi, int fps, boolean hevc, boolean fusion, boolean rawPackets) {
        if (hevc || fusion || rawPackets) { log("Unsupported mode; this compatibility backend supports plain AVC only"); return false; }
        stop(null);
        try {
            final RtspWfdSession[] owner = new RtspWfdSession[1];
            RtspWfdSession next = new RtspWfdSession(host, port, new RtspWfdSession.Listener() {
                public void ready(String id, int w, int h) {
                    callback("onNativeWfdSinkStarted", new Class<?>[]{String.class}, id);
                    callback("onUibcEncrypted", new Class<?>[]{String.class, boolean.class}, id, false);
                    callback("onNativeCastVideoInitialized", new Class<?>[]{String.class, String.class, int.class, int.class}, id, "video/avc", w, h);
                }
                public void video(byte[] data, long pts) {
                    callback("onNativeVideoDataReceived", new Class<?>[]{String.class, int.class, int.class, byte[].class, long.class}, owner[0].id(), 1, data.length, data, pts);
                }
                public void stopped(String reason) {
                    callback("onNativeWfdSinkStopped", new Class<?>[]{String.class}, owner[0].id());
                }
                public void log(String message) { CompatWfdSink.log(message); }
            });
            owner[0] = next; session = next; next.start(); return true;
        } catch (Exception error) { log("Start failed: " + error); return false; }
    }
    public static synchronized boolean stop(String id) {
        if (session == null) return true;
        if (id != null && !id.equals(session.id())) return false;
        RtspWfdSession old = session; session = null; old.close(); return true;
    }
    public static synchronized boolean pause(String id) { return matches(id) && session.pause(true); }
    public static synchronized boolean resume(String id) { return matches(id) && session.pause(false); }
    public static synchronized boolean input(String id, byte[] data) { return matches(id) && session.input(data); }
    public static synchronized boolean parameter(String id, HashMap<?, ?> values) {
        // The SDK updates audio latency even before a WFD session exists. This
        // video-only path has no audio clock to update; report that explicitly.
        return values != null && values.containsKey("kWhatAudioLatency");
    }
    private static boolean matches(String id) { return session != null && id != null && id.equals(session.id()); }
    private static void callback(String name, Class<?>[] signature, Object... args) {
        try {
            Method method;
            synchronized (callbacks) {
                method = callbacks.get(name);
                if (method == null) { method = Class.forName("com.ucarsink.sink.natives.SinkNative").getMethod(name, signature); callbacks.put(name, method); }
            }
            method.invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException error) { log("SDK callback " + name + " failed: " + error); }
    }
    private static void log(String message) { System.out.println("MiAuto/WfdCompat: " + message); }
}
