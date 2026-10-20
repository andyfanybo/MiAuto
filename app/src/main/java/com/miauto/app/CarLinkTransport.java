package com.miauto.app;

import android.content.Context;
import android.os.Build;

public final class CarLinkTransport {
    public static final String PREFERENCES = "miauto_connection";
    public static final String KEY = "transport";
    public static final String AUTO = "auto";
    public static final String P2P = "p2p";
    public static final String LOCAL_HOTSPOT = "local_hotspot";
    public static final String SDK_AP_SERVICE = "com.share.connect.wifiap.WifiApService";
    private CarLinkTransport() { }

    public static String get(Context context) {
        return normalize(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY, AUTO), Build.VERSION.SDK_INT);
    }

    public static String normalize(String mode, int sdk) {
        if (LOCAL_HOTSPOT.equals(mode) && sdk >= 28) return LOCAL_HOTSPOT;
        if (P2P.equals(mode)) return P2P;
        return AUTO;
    }

    public static boolean supportsP2p(String mode) { return !LOCAL_HOTSPOT.equals(mode); }
    public static boolean supportsAp(String mode, int sdk) {
        return LOCAL_HOTSPOT.equals(mode) || AUTO.equals(mode) && IccoaConnectionPolicy.enableSoftAp(sdk);
    }
}
