#!/usr/bin/env python3
"""Relocate the SDK's generated R.drawable.ucar constant without changing SDK code."""
import argparse
import hashlib
import re
import struct
import zlib
from pathlib import Path

CLASS = 'Lcom/ucar/sdk/R$drawable;'


def u32(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        b = data[offset]
        offset += 1
        value |= (b & 127) << shift
        if not b & 128:
            return value, offset
    raise ValueError('Invalid DEX uleb128')


def fix_checksum(data):
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into('<I', data, 8, zlib.adler32(data[12:]) & 0xffffffff)


def rebind(data, resource_id):
    if not 0x7f000000 <= resource_id <= 0x7fffffff:
        raise ValueError('Expected an application resource ID')
    if data[:4] != b'dex\n' or len(data) < 112 or u32(data, 32) != len(data):
        raise ValueError('Invalid DEX header/size')
    if u32(data, 40) != 0x12345678:
        raise ValueError('Unsupported DEX byte order')
    if hashlib.sha1(data[32:]).digest() != data[12:32] or (zlib.adler32(data[12:]) & 0xffffffff) != u32(data, 8):
        raise ValueError('Invalid source DEX signature/checksum')
    strings, types, fields = u32(data, 60), u32(data, 68), u32(data, 84)

    def string(index):
        start = u32(data, strings + index * 4)
        _, start = uleb(data, start)
        end = data.index(b'\0', start)
        return data[start:end].decode('utf-8')  # descriptors/field names are ASCII

    def typename(index):
        return string(u32(data, types + index * 4))

    target = None
    for index in range(u32(data, 96)):
        cls = u32(data, 100) + index * 32
        if typename(u32(data, cls)) == CLASS:
            target = cls
            break
    if target is None:
        raise ValueError('SDK drawable resource class not found')
    offset = u32(data, target + 24)
    count, offset = uleb(data, offset)
    for _ in range(3):
        _, offset = uleb(data, offset)
    field_index = 0
    target_value = None
    for index in range(count):
        delta, offset = uleb(data, offset)
        _, offset = uleb(data, offset)
        field_index += delta
        if string(u32(data, fields + field_index * 8 + 4)) == 'ucar':
            target_value = index
    if target_value is None:
        raise ValueError('SDK ucar field not found')
    offset = u32(data, target + 28)
    values, offset = uleb(data, offset)
    if target_value >= values:
        raise ValueError('SDK ucar has no encoded resource value')
    for index in range(target_value + 1):
        header = data[offset]
        offset += 1
        width = (header >> 5) + 1
        if header & 31 != 4 or width > 4:
            raise ValueError('Unexpected generated resource value type')
        if index == target_value:
            if width != 4:
                raise ValueError('Resource value requires a DEX rebuild')
            old = u32(data, offset)
            result = bytearray(data)
            struct.pack_into('<I', result, offset, resource_id)
            fix_checksum(result)
            return bytes(result), offset, old
        offset += width
    raise ValueError('SDK resource value not found')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--resource-id', type=lambda s: int(s, 0))
    group.add_argument('--symbols', type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve():
        parser.error('Keep the original SDK untouched; use a separate output file')
    resource_id = args.resource_id
    if args.symbols:
        match = re.search(r'^int mipmap ic_launcher_foreground (0x[0-9a-fA-F]+)\s*$', args.symbols.read_text(), re.MULTILINE)
        if not match:
            raise ValueError('Bitmap launcher foreground resource missing from R.txt')
        resource_id = int(match.group(1), 16)
    result, offset, old = rebind(args.source.read_bytes(), resource_id)
    args.output.write_bytes(result)
    print('SDK resource binding field_offset=%d old=0x%08x new=0x%08x' % (offset, old, resource_id))


if __name__ == '__main__':
    main()
