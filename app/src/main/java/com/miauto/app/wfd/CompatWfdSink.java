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
        log("Start: hevc=" + hevc + ", fusion=" + fusion + ", raw=" + rawPackets
                + ", display=" + width + "x" + height + "@" + fps + ", dpi=" + dpi);
        if (hevc || fusion) { log("Unsupported mode; this compatibility backend supports plain AVC only"); return false; }
        stop(null);
        try {
            final RtspWfdSession[] owner = new RtspWfdSession[1];
            RtspWfdSession next = new RtspWfdSession(host, port, width, height, dpi, fps, rawPackets,
                    new RtspWfdSession.Listener() {
                private int width, height;
                private boolean videoAnnounced;
                public void ready(String id, int w, int h) {
                    width = w; height = h;
                    callback("onNativeWfdSinkStarted", new Class<?>[]{String.class}, id);
                    callback("onUibcEncrypted", new Class<?>[]{String.class, boolean.class}, id, false);
                }
                public void video(byte[] data, long pts) {
                    if (!videoAnnounced) {
                        videoAnnounced = true;
                        callback("onNativeCastVideoInitialized", new Class<?>[]{String.class, String.class, int.class, int.class},
                                owner[0].id(), "video/avc", width, height);
                    }
                    callback("onNativeVideoDataReceived", new Class<?>[]{String.class, int.class, int.class, byte[].class, long.class}, owner[0].id(), 1, data.length, data, pts);
                }
                public void audioFormat(int rate, int channels) {
                    callback("onNativeCastAudioInitialized", new Class<?>[]{String.class, String.class, int.class, int.class},
                            owner[0].id(), "audio/mp4a-latm", rate, channels);
                }
                public void audio(byte[] data, long pts) {
                    callback("onNativeAudioDataReceived", new Class<?>[]{String.class, int.class, int.class, byte[].class},
                            owner[0].id(), 16, data.length, data);
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
        // This receiver has no independent renderer/audio clock. The SDK owns
        // decoded-audio buffering; do not claim to apply native clock tuning.
        return false;
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
