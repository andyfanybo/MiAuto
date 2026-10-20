package com.miauto.app;

/** Owns exactly one reservation. All callbacks and operations run on the main thread. */
public final class HotspotSession {
    public interface Reservation { void close(); }
    public interface Callback {
        void started(Reservation reservation, String ssid, String password);
        void failed(int reason);
        void stopped();
    }
    public interface Platform { void request(Callback callback); }
    public interface Listener {
        void ready(String ssid, String password);
        void failed(int reason);
        void stopped();
    }
    private final Platform platform;
    private final Listener listener;
    private Reservation reservation;
    private long generation;
    private boolean active;
    public HotspotSession(Platform platform, Listener listener) {
        this.platform = platform;
        this.listener = listener;
    }
    public boolean isActive(long token) { return active && generation == token; }
    public long generation() { return generation; }
    public void start() {
        if (active) return;
        active = true;
        final long token = ++generation;
        try {
            platform.request(new Callback() {
                @Override public void started(Reservation handle, String ssid, String password) {
                    if (!isActive(token)) { handle.close(); return; }
                    if (reservation != null) { if (reservation != handle) handle.close(); return; }
                    reservation = handle;
                    if (ssid == null || ssid.isEmpty() || password == null || password.isEmpty()) {
                        fail(-1); return;
                    }
                    listener.ready(ssid, password);
                }
                @Override public void failed(int reason) { if (isActive(token)) fail(reason); }
                @Override public void stopped() {
                    if (!isActive(token)) return;
                    stop();
                    listener.stopped();
                }
            });
        } catch (RuntimeException error) {
            if (isActive(token)) fail(-2);
        }
    }
    public void fail(int reason) {
        if (!active) return;
        stop();
        listener.failed(reason);
    }
    public void stop() {
        active = false;
        ++generation;
        Reservation old = reservation;
        reservation = null;
        if (old != null) old.close();
    }
}
