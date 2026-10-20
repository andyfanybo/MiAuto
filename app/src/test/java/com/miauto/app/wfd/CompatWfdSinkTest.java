package com.miauto.app.wfd;

import org.junit.Test;
import static org.junit.Assert.*;
import com.ucarsink.sink.natives.SinkNative;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class CompatWfdSinkTest {
    @Test public void sdkVideoReadyWaitsForActualAvcAndAudioUsesAdtsCallbacks() throws Exception {
        SinkNative.reset();
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket source=new ServerSocket(0)) {
            source.setSoTimeout(3000);
            Future<?> peer=worker.submit(()->{
                try(Socket tcp=source.accept();DatagramSocket udp=new DatagramSocket()) {
                    tcp.setSoTimeout(3000);InputStream in=tcp.getInputStream();OutputStream out=tcp.getOutputStream();
                    WfdProtocolTest.request(out,"OPTIONS",10,"");RtspMessage.read(in);
                    RtspMessage options=RtspMessage.read(in);WfdProtocolTest.reply(out,options.header("cseq"),Collections.emptyMap());
                    WfdProtocolTest.request(out,"SET_PARAMETER",11,"wfd_trigger_method: SETUP\r\n");RtspMessage.read(in);
                    RtspMessage setup=RtspMessage.read(in);
                    int port=Integer.parseInt(setup.header("transport").split("client_port=")[1].split("-")[0]);
                    WfdProtocolTest.reply(out,setup.header("cseq"),Collections.singletonMap("Session","real-packets"));
                    RtspMessage play=RtspMessage.read(in);WfdProtocolTest.reply(out,play.header("cseq"),Collections.emptyMap());
                    assertTrue(SinkNative.started.await(2,TimeUnit.SECONDS));
                    assertFalse("PLAY acceptance alone is not an initialized video stream",SinkNative.events.contains("video-format"));
                    byte[] video=WfdProtocolTest.rtpPes(1,0,new byte[]{0,0,0,1,0x65,9});
                    udp.send(new DatagramPacket(video,video.length,InetAddress.getLoopbackAddress(),port));
                    assertTrue(SinkNative.video.await(2,TimeUnit.SECONDS));
                    byte[] audio=AdtsAudioTest.audioPacket(2,AdtsAudioTest.frame());
                    udp.send(new DatagramPacket(audio,audio.length,InetAddress.getLoopbackAddress(),port));
                    assertTrue(SinkNative.audio.await(2,TimeUnit.SECONDS));
                    WfdProtocolTest.request(out,"TEARDOWN",12,"");RtspMessage.read(in);
                    assertTrue(SinkNative.stopped.await(2,TimeUnit.SECONDS));
                }catch(Exception e){throw new RuntimeException(e);}
            });
            try {
                assertTrue(CompatWfdSink.start("127.0.0.1",source.getLocalPort(),1280,720,160,30,false,false,true));
                peer.get(8,TimeUnit.SECONDS);
                assertTrue(SinkNative.events.indexOf("video-format")<SinkNative.events.indexOf("video-frame"));
                assertTrue(SinkNative.events.indexOf("audio-format")<SinkNative.events.indexOf("audio-frame"));
            }finally{CompatWfdSink.stop(null);}
        }finally{worker.shutdownNow();}
    }
}
