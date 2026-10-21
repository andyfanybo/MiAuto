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

    @Test public void videoReadinessSurvivesConnectedAfterCastConnected() {
        // Real order seen on the device: onWfdSinkReady -> state 4 -> state 2 ->
        // projection Surface created. State 2 must not clear the decoder request.
        boolean ready = IccoaConnectionPolicy.videoReadyAfter(false, IccoaConnectionPolicy.CAST_CONNECTED);
        assertTrue(ready);
        assertTrue(IccoaConnectionPolicy.videoReadyAfter(ready, IccoaConnectionPolicy.CONNECTED));
        assertTrue(IccoaConnectionPolicy.videoReadyAfter(ready, IccoaConnectionPolicy.GO_TO_HOME));
        assertTrue(IccoaConnectionPolicy.videoReadyAfter(ready, IccoaConnectionPolicy.BACK_TO_FOREGROUND));
        assertFalse(IccoaConnectionPolicy.videoReadyAfter(ready, IccoaConnectionPolicy.CAST_DISCONNECTED));
        assertFalse(IccoaConnectionPolicy.videoReadyAfter(ready, IccoaConnectionPolicy.DISCONNECTED));
        assertFalse(IccoaConnectionPolicy.videoReadyAfter(ready, IccoaConnectionPolicy.CONNECT_FAILED));
        assertFalse(IccoaConnectionPolicy.videoReadyAfter(false, IccoaConnectionPolicy.CONNECTED));
        assertTrue(IccoaConnectionPolicy.videoReadyAfter(false, IccoaConnectionPolicy.CAST_CONNECTED));
    }

    @Test public void failureParametersAreNotSdkStates() {
        for (int parameter : new int[]{-1, 0, 10003, 10008}) {
            assertFalse(IccoaConnectionPolicy.isConnectedState(parameter));
            assertFalse(IccoaConnectionPolicy.isTerminalState(parameter));
        }
    }
}
