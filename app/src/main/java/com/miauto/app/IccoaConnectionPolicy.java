package com.miauto.app;

/** Transport and event policy for the injected ICCOA v1.5.3 SDK. */
public final class IccoaConnectionPolicy {
    private IccoaConnectionPolicy() { }

    public static final int DISCONNECTED = 1;
    public static final int CONNECTED = 2;
    public static final int CONNECT_FAILED = 3;
    public static final int CAST_CONNECTED = 4;
    public static final int CAST_DISCONNECTED = 5;
    public static final int GO_TO_HOME = 6;
    public static final int BACK_TO_FOREGROUND = 7;

    // The SDK's Android 9 tethering path calls privileged Wi-Fi APIs. Advertising
    // P2P only lets ShareLinkService select its existing createGroupForClient
    // fallback even when the phone initially requests an AP connection.
    public static boolean enableSoftAp(int sdkInt) {
        return sdkInt >= 29;
    }

    public static boolean isTerminalState(int state) {
        return state == DISCONNECTED || state == CONNECT_FAILED;
    }

    public static boolean isConnectedState(int state) {
        // Cast teardown and navigation events do not establish a connection.
        // They may occur before DISCONNECTED and must not revive a failed cast.
        return state == CONNECTED || state == CAST_CONNECTED;
    }

    public static boolean isVideoReadyState(int state) {
        return state == CAST_CONNECTED;
    }
}
