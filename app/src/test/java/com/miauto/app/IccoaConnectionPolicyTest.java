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

    @Test public void onlyConnectionAndInitializedVideoEstablishTheSession() {
        for (int state : new int[]{2, 4}) {
            assertTrue(IccoaConnectionPolicy.isConnectedState(state));
            assertFalse(IccoaConnectionPolicy.isTerminalState(state));
        }
    }

    @Test public void castTeardownAndNavigationNeverEstablishOrInitializeVideo() {
        for (int state : new int[]{1, 2, 3, 5, 6, 7}) {
            assertFalse(IccoaConnectionPolicy.isVideoReadyState(state));
        }
        for (int state : new int[]{5, 6, 7}) {
            assertFalse(IccoaConnectionPolicy.isConnectedState(state));
        }
        assertTrue(IccoaConnectionPolicy.isVideoReadyState(4));
    }

    @Test public void failureParametersAreNotSdkStates() {
        for (int parameter : new int[]{-1, 0, 10003, 10008}) {
            assertFalse(IccoaConnectionPolicy.isConnectedState(parameter));
            assertFalse(IccoaConnectionPolicy.isTerminalState(parameter));
        }
    }
}
