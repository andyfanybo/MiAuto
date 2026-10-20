#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <stdarg.h>
#include <string.h>
#include <sys/ioctl.h>

int usbio_test_ioctl(int fd, unsigned long request, ...) {
    va_list args;
    va_start(args, request);
    struct usbdevfs_bulktransfer *transfer = va_arg(args, void *);
    va_end(args);
    if (request != USBDEVFS_BULK) { errno = EINVAL; return -1; }
    if (fd == 2) { errno = EPIPE; return -1; }
    if (fd == 3) { errno = ETIMEDOUT; return -1; }
    if (fd == 4) { errno = EINTR; return -1; }
    if (fd == 5) return (int)transfer->len + 1;
    if (fd == 6 && transfer->timeout != 0xffffffffU) { errno = EINVAL; return -1; }
    unsigned int length = transfer->len;
    if (fd == 1 && length > 2) length = 2;
    if (transfer->ep & 0x80) {
        memset(transfer->data, 0x5a, length);
    } else if (length != 3 || memcmp(transfer->data, "abc", 3) != 0) {
        errno = EINVAL; return -1;
    }
    return (int)length;
}
