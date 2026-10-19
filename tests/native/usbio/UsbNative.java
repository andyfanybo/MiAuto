package com.ucar.connect.aoa;

import java.util.Arrays;

// Same class name, static initializer and private native signatures as the SDK.
// No SDK binary or Android runtime is needed for this JNI contract regression.
public class UsbNative {
    static { System.loadLibrary("usbio"); }
    private static native int nativeBulkRead(int fd, int ep, byte[] data,
                                            int offset, int length, long timeout);
    private static native int nativeBulkWrite(int fd, int ep, byte[] data,
                                             int offset, int length, long timeout);
    private static int checks;
    private static void eq(int expected, int actual) {
        checks++;
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    private static void bytes(byte[] expected, byte[] actual) {
        checks++;
        if (!Arrays.equals(expected, actual)) throw new AssertionError(Arrays.toString(actual));
    }
    public static void main(String[] args) {
        byte[] b = {1, 2, 3, 4, 5, 6};
        eq(3, nativeBulkRead(0, 0x81, b, 1, 3, 0));
        bytes(new byte[]{1, 0x5a, 0x5a, 0x5a, 5, 6}, b);
        b = new byte[]{1, 2, 3, 4, 5, 6};
        eq(2, nativeBulkRead(1, 0x81, b, 1, 4, 1000));
        bytes(new byte[]{1, 0x5a, 0x5a, 4, 5, 6}, b);
        byte[] before = b.clone();
        eq(-32, nativeBulkRead(2, 0x81, b, 0, 4, 1000)); // EPIPE
        bytes(before, b);
        eq(-110, nativeBulkRead(3, 0x81, b, 0, 4, 1000)); // ETIMEDOUT
        eq(-4, nativeBulkRead(4, 0x81, b, 0, 4, 1000)); // EINTR
        eq(-5, nativeBulkRead(5, 0x81, b, 0, 4, 1000)); // impossible driver result
        bytes(before, b);
        eq(1, nativeBulkRead(6, 0x81, b, 0, 1, 0xffffffffL));
        eq(3, nativeBulkWrite(0, 1, new byte[]{9, 97, 98, 99, 8}, 1, 3, 1000));
        eq(-22, nativeBulkRead(-1, 0x81, b, 0, 1, 0));
        eq(-22, nativeBulkRead(0, 0x81, null, 0, 1, 0));
        eq(-22, nativeBulkRead(0, 0x81, b, -1, 1, 0));
        eq(-22, nativeBulkRead(0, 0x81, b, 0, -1, 0));
        eq(-22, nativeBulkRead(0, 0x81, b, 1, Integer.MAX_VALUE, 0));
        eq(-22, nativeBulkRead(0, 0x81, b, Integer.MAX_VALUE, 1, 0));
        eq(-22, nativeBulkRead(0, 0x81, b, 0, 1, -1));
        eq(-22, nativeBulkRead(0, 0x81, b, 0, 1, 0x100000000L));
        eq(-22, nativeBulkRead(0, 1, b, 0, 1, 0));
        eq(-22, nativeBulkWrite(0, 0x81, b, 0, 1, 0));
        eq(-22, nativeBulkRead(0, 0x181, b, 0, 1, 0));
        eq(0, nativeBulkRead(0, 0x81, b, b.length, 0, 0));
        eq(-22, nativeBulkRead(0, 0x81, b, b.length + 1, 0, 0));
        System.out.println("usbio JNI regression: " + checks + " checks passed");
    }
}
