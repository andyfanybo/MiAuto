package com.miauto.app.wfd;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RawAvcTest {
    private static final class Receiver implements RawAvc.Listener {
        final List<byte[]> units = new ArrayList<>();
        final List<Long> stamps = new ArrayList<>();

        public void accessUnit(byte[] annexB, long ptsUs) {
            units.add(annexB);
            stamps.add(ptsUs);
        }
    }

    @Test
    public void singleNalBecomesAnnexB() {
        Receiver receiver = new Receiver();
        RawAvc avc = new RawAvc(receiver);
        avc.packet(new byte[]{0x65, 1, 2, 3}, 0, 4, true, 90000);
        assertEquals(1, receiver.units.size());
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 1, 2, 3}, receiver.units.get(0));
        assertEquals(Long.valueOf(0L), receiver.stamps.get(0)); // relative to the first RTP timestamp
    }

    @Test
    public void stapAAggregatesParameterSetsAndIdr() {
        Receiver receiver = new Receiver();
        RawAvc avc = new RawAvc(receiver);
        byte[] payload = {
                0x78,
                0x00, 0x04, 0x67, 0x42, 0x00, 0x1f,
                0x00, 0x04, 0x68, (byte) 0xce, 0x3c, (byte) 0x80,
                0x00, 0x04, 0x65, 1, 2, 3
        };
        avc.packet(payload, 0, payload.length, true, 90000);
        assertEquals(1, receiver.units.size());
        assertArrayEquals(new byte[]{
                0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f,
                0, 0, 0, 1, 0x68, (byte) 0xce, 0x3c, (byte) 0x80,
                0, 0, 0, 1, 0x65, 1, 2, 3
        }, receiver.units.get(0));
    }

    @Test
    public void fuAReassemblesFragmentedNal() {
        Receiver receiver = new Receiver();
        RawAvc avc = new RawAvc(receiver);
        // FU indicator 0x7c (NRI=3, type 28), FU header: start+type5, middle, end.
        avc.packet(new byte[]{(byte) 0x7c, (byte) 0x85, 1, 2}, 0, 4, false, 90000);
        avc.packet(new byte[]{0x7c, 0x05, 3, 4}, 0, 4, false, 90000);
        avc.packet(new byte[]{0x7c, 0x45, 5, 6}, 0, 4, true, 90000);
        assertEquals(1, receiver.units.size());
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 1, 2, 3, 4, 5, 6}, receiver.units.get(0));
    }

    @Test
    public void timestampChangeFlushesWithoutMarker() {
        Receiver receiver = new Receiver();
        RawAvc avc = new RawAvc(receiver);
        avc.packet(new byte[]{0x41, 1}, 0, 2, false, 90000);
        avc.packet(new byte[]{0x41, 2}, 0, 2, false, 93000);
        assertEquals(1, receiver.units.size());
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x41, 1}, receiver.units.get(0));
        assertEquals(Long.valueOf(0L), receiver.stamps.get(0));
    }

    @Test
    public void ignoresForbiddenAndReservedTypes() {
        Receiver receiver = new Receiver();
        RawAvc avc = new RawAvc(receiver);
        avc.packet(new byte[]{(byte) 0x80, 1}, 0, 2, true, 90000);
        avc.packet(new byte[]{0x00, 1}, 0, 2, true, 90000);
        assertEquals(0, receiver.units.size());
    }
}
