package com.miauto.app;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class CarLinkTransportTest {
    @Test public void automaticPreservesLegacyP2pDefault() {
        assertTrue(CarLinkTransport.supportsP2p(CarLinkTransport.AUTO));
        assertFalse(CarLinkTransport.supportsAp(CarLinkTransport.AUTO, 28));
        assertTrue(CarLinkTransport.supportsAp(CarLinkTransport.AUTO, 30));
    }
    @Test public void publicHotspotAdvertisesOnlyApCapability() {
        assertEquals(CarLinkTransport.LOCAL_HOTSPOT, CarLinkTransport.normalize("local_hotspot", 28));
        assertFalse(CarLinkTransport.supportsP2p(CarLinkTransport.LOCAL_HOTSPOT));
        assertTrue(CarLinkTransport.supportsAp(CarLinkTransport.LOCAL_HOTSPOT, 28));
    }
    @Test public void componentFactoryModeIsNotEnabledBeforeAndroidNine() {
        assertEquals(CarLinkTransport.AUTO, CarLinkTransport.normalize("local_hotspot", 27));
        assertEquals(CarLinkTransport.AUTO, CarLinkTransport.normalize("unknown", 28));
    }
    @Test public void p2pNeverEnablesTheApBackend() {
        assertFalse(CarLinkTransport.supportsAp(CarLinkTransport.P2P, 36));
    }
    @Test public void addressSelectionExcludesStationAndVpnAddresses() {
        assertFalse(HotspotNetworkInfo.candidate("wlan0", "192.168.68.242", Collections.singleton("192.168.68.242")));
        assertFalse(HotspotNetworkInfo.candidate("tun0", "10.8.0.2", Collections.emptySet()));
        assertFalse(HotspotNetworkInfo.candidate("ap0", "0.0.0.0", Collections.emptySet()));
        assertFalse(HotspotNetworkInfo.candidate("ap0", "169.254.1.1", Collections.emptySet()));
        assertTrue(HotspotNetworkInfo.candidate("ap0", "192.168.43.1", Collections.emptySet()));
        // The public API can share an existing local-only AP owned by another app.
        assertTrue(HotspotNetworkInfo.candidate("ap0", "192.168.43.1", Collections.singleton("192.168.43.1")));
    }
}
