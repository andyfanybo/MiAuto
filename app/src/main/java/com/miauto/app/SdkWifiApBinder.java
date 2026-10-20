package com.miauto.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.concurrent.CopyOnWriteArrayList;

/** Wire adapter for ICCOA v1.5.3 IWifiAp; no SDK binary classes are linked here. */
public final class SdkWifiApBinder extends Binder {
    public static final String DESCRIPTOR = "com.share.connect.wifiap.IWifiAp";
    static final String OBSERVER = "com.share.connect.wifiap.WifiApObserver";
    public interface Commands {
        void open();
        void close();
        void create(int channel, String ssid, String password, boolean coexist, boolean prefix);
        boolean isUserClose();
    }
    public interface Writer { void write(Parcel parcel); }
    private final Commands commands;
    private final CopyOnWriteArrayList<IBinder> observers = new CopyOnWriteArrayList<>();
    public SdkWifiApBinder(Commands commands) {
        this.commands = commands;
        attachInterface(() -> this, DESCRIPTOR);
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code < 1 || code > 7) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        switch (code) {
            case 1: commands.open(); break;
            case 2: case 4: commands.close(); break;
            case 3:
                commands.create(data.readInt(), data.readString(), data.readString(),
                        data.readInt() != 0, data.readInt() != 0);
                break;
            case 5:
                reply.writeNoException(); reply.writeInt(commands.isUserClose() ? 1 : 0); return true;
            case 6:
                IBinder observer = data.readStrongBinder();
                if (observer != null) observers.addIfAbsent(observer);
                break;
            case 7: observers.remove(data.readStrongBinder()); break;
            default: return false;
        }
        reply.writeNoException();
        return true;
    }
    public void notifyEvent(int event) { notifyEvent(event, parcel -> { }); }
    public void notifyEvent(int event, Writer writer) {
        for (IBinder observer : observers) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(OBSERVER);
                writer.write(data);
                if (!observer.transact(event, data, reply, 0)) { observers.remove(observer); continue; }
                reply.readException();
            } catch (RemoteException | RuntimeException error) {
                observers.remove(observer);
            } finally { data.recycle(); reply.recycle(); }
        }
    }
    public void created(String ip, String ssid, String password, String mac, int frequency) {
        notifyEvent(3, data -> {
            data.writeString(ip); data.writeString(ssid); data.writeString(password);
            data.writeString(mac); data.writeInt(frequency);
        });
    }
    public void clearObservers() { observers.clear(); }
}
