package com.ucarsink.sink.natives;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

/** Callback-only test fixture; the real SDK binary is never included in the test sources. */
public final class SinkNative {
    public static final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
    public static CountDownLatch started, video, audio, stopped;
    public static void reset() {
        events.clear();started=new CountDownLatch(1);video=new CountDownLatch(1);
        audio=new CountDownLatch(1);stopped=new CountDownLatch(1);
    }
    public static void onNativeWfdSinkStarted(String id) { events.add("start");started.countDown(); }
    public static void onUibcEncrypted(String id, boolean enabled) { if(enabled)throw new AssertionError();events.add("uibc"); }
    public static void onNativeCastVideoInitialized(String id,String mime,int w,int h) {
        if(!mime.equals("video/avc")||w!=1280||h!=720)throw new AssertionError();events.add("video-format");
    }
    public static void onNativeVideoDataReceived(String id,int type,int size,byte[] bytes,long pts) {
        if(type!=1||size!=bytes.length||!events.contains("video-format"))throw new AssertionError();
        events.add("video-frame");video.countDown();
    }
    public static void onNativeCastAudioInitialized(String id,String mime,int rate,int channels) {
        if(!mime.equals("audio/mp4a-latm")||rate!=48000||channels!=2)throw new AssertionError();events.add("audio-format");
    }
    public static void onNativeAudioDataReceived(String id,int type,int size,byte[] bytes) {
        if(type!=16||size!=bytes.length||!events.contains("audio-format"))throw new AssertionError();events.add("audio-frame");audio.countDown();
    }
    public static void onNativeWfdSinkStopped(String id) { events.add("stop");stopped.countDown(); }
}
