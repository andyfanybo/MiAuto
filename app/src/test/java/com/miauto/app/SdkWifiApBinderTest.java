package com.miauto.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class SdkWifiApBinderTest {
    private static class Commands implements SdkWifiApBinder.Commands {
        int opened, closed, channel;
        String ssid, password;
        boolean coexist, prefix;
        public void open() { opened++; }
        public void close() { closed++; }
        public boolean isUserClose() { return true; }
        public void create(int c, String s, String p, boolean a, boolean b) {
            channel=c; ssid=s; password=p; coexist=a; prefix=b;
        }
    }
    private static Parcel call(SdkWifiApBinder binder, int code, SdkWifiApBinder.Writer writer) throws Exception {
        Parcel data=Parcel.obtain(), reply=Parcel.obtain();
        data.writeInterfaceToken(SdkWifiApBinder.DESCRIPTOR); writer.write(data);
        data.setDataPosition(0);
        assertTrue(binder.onTransact(code,data,reply,0));
        data.recycle(); reply.setDataPosition(0); reply.readException(); return reply;
    }
    @Test public void sdkRequestLayoutAndBooleanResultArePreserved() throws Exception {
        Commands commands=new Commands(); SdkWifiApBinder binder=new SdkWifiApBinder(commands);
        call(binder,1,data -> {}).recycle();
        call(binder,3,data -> { data.writeInt(36); data.writeString("requested"); data.writeString("requested-password"); data.writeInt(1); data.writeInt(0); }).recycle();
        assertEquals(1,commands.opened); assertEquals(36,commands.channel);
        assertEquals("requested",commands.ssid); assertEquals("requested-password",commands.password);
        assertTrue(commands.coexist); assertFalse(commands.prefix);
        Parcel reply=call(binder,5,data -> {}); assertEquals(1,reply.readInt()); reply.recycle();
        call(binder,4,data -> {}).recycle(); call(binder,2,data -> {}).recycle(); assertEquals(2,commands.closed);
    }
    @Test public void observerGetsActualCredentialsInSdkOrderAndCanUnregister() throws Exception {
        Commands commands=new Commands(); SdkWifiApBinder binder=new SdkWifiApBinder(commands);
        int[] count={0};
        Binder observer=new Binder() {
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) {
                data.enforceInterface(SdkWifiApBinder.OBSERVER);
                assertEquals(3,code); assertEquals("192.168.43.1",data.readString());
                assertEquals("generated-ssid",data.readString()); assertEquals("generated-password",data.readString());
                assertEquals("",data.readString()); assertEquals(0,data.readInt());
                count[0]++; reply.writeNoException(); return true;
            }
        };
        call(binder,6,data -> data.writeStrongBinder(observer)).recycle();
        call(binder,6,data -> data.writeStrongBinder(observer)).recycle();
        binder.created("192.168.43.1","generated-ssid","generated-password","",0); assertEquals(1,count[0]);
        call(binder,7,data -> data.writeStrongBinder(observer)).recycle();
        binder.created("192.168.43.1","generated-ssid","generated-password","",0); assertEquals(1,count[0]);
    }
    @Test public void wrongInterfaceTokenCannotExecuteCommands() throws Exception {
        Commands commands=new Commands(); SdkWifiApBinder binder=new SdkWifiApBinder(commands);
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            data.writeInterfaceToken("wrong.interface"); data.setDataPosition(0);
            try { binder.onTransact(1,data,reply,0); fail("wrong token accepted"); }
            catch (SecurityException expected) { assertEquals(0,commands.opened); }
        } finally { data.recycle(); reply.recycle(); }
    }
}
