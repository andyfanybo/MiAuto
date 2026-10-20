package com.miauto.app;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import com.andrerinas.openheadunit.BuildConfig;
import java.util.Collections;
import java.util.Set;

/** SDK-compatible hotspot transport backed exclusively by LocalOnlyHotspot. */
public class PublicHotspotService extends Service {
    private static final String TAG = "MiAuto/PublicHotspot";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler platformCallbacks = new Handler(Looper.getMainLooper());
    private final Handler probeCleanup = new Handler(Looper.getMainLooper());
    private HotspotSession session;
    private Set<String> before = Collections.emptySet();
    private volatile boolean userClose;
    private long announced = -1;
    private boolean probing;
    private final SdkWifiApBinder binder = new SdkWifiApBinder(new SdkWifiApBinder.Commands() {
        @Override public void open() { main.post(() -> { userClose = false; binder.notifyEvent(1); }); }
        @Override public void close() { main.post(() -> stopHotspot()); }
        @Override public void create(int channel, String ssid, String password, boolean coexist, boolean prefix) {
            // Public LOHS generates its own credentials and chooses the radio band.
            main.post(() -> {
                if (probing) {
                    probeCleanup.removeCallbacksAndMessages(null);
                    stopHotspot();
                }
                startHotspot();
            });
        }
        @Override public boolean isUserClose() { return userClose; }
    });

    @Override public void onCreate() {
        super.onCreate();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        session = new HotspotSession(callback -> request(wifi, callback), new HotspotSession.Listener() {
            @Override public void ready(String ssid, String password) {
                final long token = session.generation();
                waitForAddress(token, ssid, password, 20);
            }
            @Override public void failed(int reason) {
                main.removeCallbacksAndMessages(null);
                Log.w(TAG, "LocalOnlyHotspot failed, reason=" + reason);
                if (!probing) binder.notifyEvent(4);
            }
            @Override public void stopped() {
                userClose = true;
                announced = -1;
                main.removeCallbacksAndMessages(null);
                Log.i(TAG, "LocalOnlyHotspot stopped by platform");
                if (!probing) binder.notifyEvent(6, data -> data.writeInt(1));
            }
        });
        Log.i(TAG, "Public LocalOnlyHotspot backend created");
    }

    @SuppressLint("MissingPermission")
    private void request(WifiManager wifi, HotspotSession.Callback callback) {
        if (wifi == null) { callback.failed(-3); return; }
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    try {
                        String[] credentials;
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            credentials = Api30.credentials(reservation);
                        } else {
                            WifiConfiguration config = reservation.getWifiConfiguration();
                            credentials = new String[]{config == null ? null : config.SSID,
                                    config == null ? null : config.preSharedKey};
                        }
                        callback.started(reservation::close, credentials[0], credentials[1]);
                    } catch (RuntimeException error) {
                        reservation.close();
                        callback.failed(-6);
                    }
                }
                @Override public void onStopped() { callback.stopped(); }
                @Override public void onFailed(int reason) { callback.failed(reason); }
            }, platformCallbacks);
        } catch (RuntimeException error) {
            Log.w(TAG, "Public hotspot request rejected", error);
            callback.failed(-2);
        }
    }

    private void startHotspot() {
        if (session.isActive(session.generation())) return;
        before = HotspotNetworkInfo.addresses();
        userClose = false;
        announced = -1;
        session.start();
        final long token = session.generation();
        main.postDelayed(() -> { if (session.isActive(token) && announced != token) session.fail(-4); }, 15000);
    }

    private void waitForAddress(long token, String ssid, String password, int attempts) {
        if (!session.isActive(token) || announced == token) return;
        HotspotNetworkInfo network = HotspotNetworkInfo.find(before);
        if (network == null) {
            if (attempts == 0) { session.fail(-5); return; }
            main.postDelayed(() -> waitForAddress(token, ssid, password, attempts - 1), 500);
            return;
        }
        announced = token;
        Log.i(TAG, "LocalOnlyHotspot ready, address=" + network.address + ", generated credentials available");
        // SDK v1.5.3 order is IP, SSID, password, MAC, frequency. Empty MAC and
        // zero frequency mean unknown: never substitute the station's BSSID/IP.
        if (probing) return; // Do not signal a fictitious SDK session during a platform smoke test.
        binder.created(network.address, ssid, password, network.mac, 0);
        // This signals transport readiness, not successful phone authentication.
        // The SDK's auth server (started by onApCreated) owns the phone session.
        binder.notifyEvent(5);
    }

    private void stopHotspot() {
        userClose = false;
        probing = false;
        main.removeCallbacksAndMessages(null);
        if (session != null) session.stop();
        announced = -1;
        Log.i(TAG, "LocalOnlyHotspot reservation released");
    }

    @androidx.annotation.RequiresApi(30)
    private static class Api30 {
        @androidx.annotation.DoNotInline
        static String[] credentials(WifiManager.LocalOnlyHotspotReservation reservation) {
            android.net.wifi.SoftApConfiguration config = reservation.getSoftApConfiguration();
            return new String[]{config == null ? null : config.getSsid(),
                    config == null ? null : config.getPassphrase()};
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (BuildConfig.DEBUG && intent != null &&
                (getPackageName() + ".PROBE_PUBLIC_HOTSPOT").equals(intent.getAction())) {
            if (session.isActive(session.generation())) {
                Log.i(TAG, "Probe skipped: a hotspot request is already active");
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            probing = true;
            startHotspot();
            // Bounded device smoke test; cleanup remains local if wireless ADB drops.
            probeCleanup.postDelayed(() -> { stopHotspot(); stopSelf(startId); }, 10000);
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        stopHotspot();
        binder.clearObservers();
        probeCleanup.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
