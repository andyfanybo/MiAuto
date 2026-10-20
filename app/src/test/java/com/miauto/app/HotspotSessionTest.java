package com.miauto.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class HotspotSessionTest {
    private static class Handle implements HotspotSession.Reservation {
        int closes;
        @Override public void close() { closes++; }
    }
    private static class Fixture implements HotspotSession.Listener {
        final List<HotspotSession.Callback> requests = new ArrayList<>();
        final HotspotSession session = new HotspotSession(requests::add, this);
        int ready, failed, stopped;
        @Override public void ready(String ssid, String password) { ready++; }
        @Override public void failed(int reason) { failed++; }
        @Override public void stopped() { stopped++; }
    }
    @Test public void duplicateStartDoesNotRequestAnotherHotspot() {
        Fixture f = new Fixture(); f.session.start(); f.session.start();
        assertEquals(1, f.requests.size());
    }
    @Test public void closingDuringStartupClosesLateReservation() {
        Fixture f = new Fixture(); f.session.start(); f.session.stop();
        Handle handle = new Handle(); f.requests.get(0).started(handle, "generated", "password");
        assertEquals(1, handle.closes); assertEquals(0, f.ready);
    }
    @Test public void stoppingReadySessionReleasesExactlyOnce() {
        Fixture f = new Fixture(); f.session.start();
        Handle handle = new Handle(); f.requests.get(0).started(handle, "generated", "password");
        f.session.stop(); f.session.stop();
        assertEquals(1, handle.closes); assertEquals(1, f.ready);
    }
    @Test public void staleFailureCannotCancelNewRequest() {
        Fixture f = new Fixture(); f.session.start(); f.session.stop(); f.session.start();
        long token = f.session.generation(); f.requests.get(0).failed(3);
        assertTrue(f.session.isActive(token)); assertEquals(0, f.failed);
    }
    @Test public void missingCredentialsReleaseReservationAndReportFailure() {
        Fixture f = new Fixture(); f.session.start();
        Handle handle = new Handle(); f.requests.get(0).started(handle, "", "password");
        assertEquals(1, handle.closes); assertEquals(1, f.failed); assertEquals(0, f.ready);
    }
    @Test public void platformStopNotifiesOnlyOnceAndReleasesReservation() {
        Fixture f = new Fixture(); f.session.start();
        Handle handle = new Handle(); f.requests.get(0).started(handle, "generated", "password");
        f.requests.get(0).stopped(); f.requests.get(0).stopped();
        assertEquals(1, handle.closes); assertEquals(1, f.stopped);
    }
    @Test public void repeatedSameStartedCallbackDoesNotCloseActiveHandle() {
        Fixture f = new Fixture(); f.session.start(); Handle handle = new Handle();
        f.requests.get(0).started(handle, "generated", "password");
        f.requests.get(0).started(handle, "generated", "password");
        assertEquals(0, handle.closes); assertEquals(1, f.ready);
    }
    @Test public void synchronousPlatformRejectionIsReported() {
        Fixture f = new Fixture();
        HotspotSession session = new HotspotSession(callback -> { throw new SecurityException(); }, f);
        session.start(); assertEquals(1, f.failed);
        assertFalse(session.isActive(session.generation()));
    }
}
