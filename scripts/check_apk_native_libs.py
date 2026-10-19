#!/usr/bin/env python3
"""Reject APKs missing a library unconditionally loaded by the ICCOA SDK."""
import struct
import sys
from zipfile import ZipFile

MACHINES = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183),
            "x86": (1, 3), "x86_64": (2, 62)}


def check(path):
    with ZipFile(path) as apk:
        names = set(apk.namelist())
        abis = sorted({n.split("/")[1] for n in names if n.startswith("lib/") and n.endswith(".so")})
        if not abis:
            raise ValueError("APK contains no native libraries")
        for abi in abis:
            name = "lib/" + abi + "/libusbio.so"
            if name not in names:
                raise ValueError("missing " + name + "; ShareLinkService would crash on startup")
            data = apk.read(name)
            if len(data) < 20 or data[:4] != b"\x7fELF" or data[5] != 1:
                raise ValueError(name + " is not a little-endian ELF library")
            actual = (data[4], struct.unpack_from("<H", data, 18)[0])
            if actual != MACHINES.get(abi):
                raise ValueError(name + " has an incompatible ELF architecture: " + repr(actual))
        print(str(path) + ": usbio present with correct ELF architecture for " + ", ".join(abis))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: check_apk_native_libs.py APK [APK ...]")
    try:
        for path in sys.argv[1:]:
            check(path)
    except (ValueError, OSError) as error:
        sys.exit(str(error))
