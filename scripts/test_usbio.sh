#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
jdk=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fPIC -shared \
  -Dioctl=usbio_test_ioctl -I"$jdk/include" -I"$jdk/include/linux" \
  "$repo/app/src/main/cpp/usbio_compat.c" \
  "$repo/tests/native/usbio/usbio_test_ioctl.c" -o "$out/libusbio.so"
"$jdk/bin/javac" -d "$out" "$repo/tests/native/usbio/UsbNative.java"
"$jdk/bin/java" -Djava.library.path="$out" -cp "$out" com.ucar.connect.aoa.UsbNative
