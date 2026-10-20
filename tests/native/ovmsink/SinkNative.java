package com.ucarsink.sink.natives;
import java.util.HashMap;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
/** Signature-only SDK fixture. Never packaged into the application. */
public final class SinkNative {
    static { System.loadLibrary("ovmsink"); }
    public static native boolean start(String host, int port, int w, int h, int dpi, int fps, boolean hevc, boolean fusion, boolean raw);
    public static native boolean stop(String id);
    public static native boolean pause(String id);
    public static native boolean resume(String id);
    public static native boolean setParameter(String id, HashMap<String,String> values);
    public static native boolean onUibcEvent(String id, byte[] data);
    private static final CountDownLatch stopped = new CountDownLatch(1);
    public static void onNativeWfdSinkStarted(String id) { }
    public static void onUibcEncrypted(String id, boolean enabled) { }
    public static void onNativeCastVideoInitialized(String id, String mime, int w, int h) { }
    public static void onNativeVideoDataReceived(String id, int type, int size, byte[] data, long pts) { }
    public static void onNativeWfdSinkStopped(String id) { stopped.countDown(); }
    private static void check(boolean result, String message) { if(!result) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        HashMap<String,String> parameters = new HashMap<>(); parameters.put("kWhatAudioLatency","100");
        check(setParameter(null,parameters),"pre-session latency call must not crash");
        check(!start("127.0.0.1",-1,1280,720,160,30,false,false,false),"invalid peer must fail");
        check(!start("127.0.0.1",7236,1280,720,160,30,true,false,false),"unsupported HEVC must fail");
        check(!pause("missing"),"pause nonexistent session");
        check(!resume("missing"),"resume nonexistent session");
        check(!onUibcEvent("missing",new byte[1]),"input nonexistent session");
        try(ServerSocket source = new ServerSocket(0)) {
            source.setSoTimeout(2000);
            check(start("127.0.0.1",source.getLocalPort(),1280,720,160,30,false,false,true),"SDK raw capability must still allow MP2T AVC negotiation");
            try(Socket peer=source.accept()) {
                check(!stop("wrong-session"),"stop must preserve another session");
                check(stop("127.0.0.1:"+source.getLocalPort()),"close owned session");
                check(stopped.await(2,TimeUnit.SECONDS),"stop callback");
            }
        }
        check(stop(null),"close is idempotent");
        System.out.println("ovmsink JNI contract passed");
    }
}
