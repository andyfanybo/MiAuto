package com.miauto.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class IccoaConnectionPolicyTest {
    @Test public void androidNineUsesP2pInsteadOfPrivilegedTethering() {
        assertFalse(IccoaConnectionPolicy.enableSoftAp(28));
        assertTrue(IccoaConnectionPolicy.enableSoftAp(29));
        assertTrue(IccoaConnectionPolicy.enableSoftAp(36));
    }

    @Test public void failureEventIsNeverReportedAsEstablished() {
        assertTrue(IccoaConnectionPolicy.isTerminalState(3));
        assertFalse(IccoaConnectionPolicy.isConnectedState(3));
        assertTrue(IccoaConnectionPolicy.isTerminalState(1));
    }

    @Test public void connectionAndCastEventsKeepTheSessionActive() {
        for (int state : new int[]{2, 4, 5, 6, 7}) {
            assertTrue(IccoaConnectionPolicy.isConnectedState(state));
            assertFalse(IccoaConnectionPolicy.isTerminalState(state));
        }
    }

    @Test public void failureParametersAreNotSdkStates() {
        for (int parameter : new int[]{-1, 0, 10003, 10008}) {
            assertFalse(IccoaConnectionPolicy.isConnectedState(parameter));
            assertFalse(IccoaConnectionPolicy.isTerminalState(parameter));
        }
    }
}
