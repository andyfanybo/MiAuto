/*
 * ICCOA USB bulk-transfer compatibility implementation for ABIs without the
 * vendor libusbio.so. The SDK supplies an already-open, permission-checked
 * UsbDeviceConnection file descriptor; ownership stays with Java.
 *
 * JNI contract: com.ucar.connect.aoa.UsbNative
 * nativeBulkRead / nativeBulkWrite (II[BIIJ)I
 */
#include <jni.h>
#include <errno.h>
#include <limits.h>
#include <linux/usbdevice_fs.h>
#include <stdlib.h>
#include <sys/ioctl.h>

static jint bulk_transfer(JNIEnv *env, jint fd, jint endpoint, jbyteArray data,
                          jint offset, jint length, jlong timeout, int input) {
    if (fd < 0 || data == NULL || offset < 0 || length < 0 ||
        timeout < 0 || (unsigned long long)timeout > UINT_MAX ||
        (endpoint & ~0x8f) != 0 || !!(endpoint & 0x80) != input) {
        return -EINVAL;
    }
    jsize size = (*env)->GetArrayLength(env, data);
    // Subtract after checking offset, so offset + length cannot overflow.
    if (offset > size || length > size - offset) return -EINVAL;
    if (length == 0) return 0;

    // Do not pin a Java array across a potentially indefinite blocking ioctl.
    void *buffer = malloc((size_t)length);
    if (buffer == NULL) return -ENOMEM;
    if (!input) {
        (*env)->GetByteArrayRegion(env, data, offset, length, buffer);
        if ((*env)->ExceptionCheck(env)) {
            free(buffer);
            return -EINVAL;
        }
    }
    struct usbdevfs_bulktransfer transfer = {
        .ep = (unsigned int)endpoint,
        .len = (unsigned int)length,
        .timeout = (unsigned int)timeout,
        .data = buffer,
    };
    int result = ioctl(fd, USBDEVFS_BULK, &transfer);
    if (result < 0) {
        int error = errno;
        free(buffer);
        return -error;
    }
    if (result > length) {
        free(buffer);
        return -EIO;
    }
    if (input && result > 0) {
        (*env)->SetByteArrayRegion(env, data, offset, result, buffer);
    }
    free(buffer);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_ucar_connect_aoa_UsbNative_nativeBulkRead(
        JNIEnv *env, jclass type, jint fd, jint endpoint, jbyteArray data,
        jint offset, jint length, jlong timeout) {
    (void)type;
    return bulk_transfer(env, fd, endpoint, data, offset, length, timeout, 1);
}

JNIEXPORT jint JNICALL
Java_com_ucar_connect_aoa_UsbNative_nativeBulkWrite(
        JNIEnv *env, jclass type, jint fd, jint endpoint, jbyteArray data,
        jint offset, jint length, jlong timeout) {
    (void)type;
    return bulk_transfer(env, fd, endpoint, data, offset, length, timeout, 0);
}
