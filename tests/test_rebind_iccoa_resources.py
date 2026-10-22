import hashlib
import importlib.util
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('rebind', ROOT / 'scripts/rebind_iccoa_resources.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture(class_name=module.CLASS):
    # A minimal generated-resource DEX layout, not proprietary SDK code.
    data = bytearray(184)
    strings = [class_name, 'I', 'other', 'ucar']
    for index, text in enumerate(strings):
        struct.pack_into('<I', data, 112 + index * 4, len(data))
        data.extend(bytes([len(text)]) + text.encode() + b'\0')
    struct.pack_into('<II', data, 128, 0, 1)
    struct.pack_into('<HHIHHI', data, 136, 0, 1, 2, 0, 1, 3)
    class_data = len(data)
    data.extend(bytes([2, 0, 0, 0, 0, 25, 1, 25]))
    values = len(data)
    data.extend(bytes([2, 0x24, 0xd2, 4, 0x64, 0xa8, 0, 7, 0x7f]))
    struct.pack_into('<IIIIIIII', data, 152, 0, 17, 0xffffffff, 0, 0xffffffff, 0, class_data, values)
    data[:8] = b'dex\n035\0'
    struct.pack_into('<IIII', data, 32, len(data), 112, 0x12345678, 0)
    for header, size, offset in [(56, 4, 112), (64, 2, 128), (80, 2, 136), (96, 1, 152)]:
        struct.pack_into('<II', data, header, size, offset)
    module.fix_checksum(data)
    return bytes(data)


class ResourceBindingTest(unittest.TestCase):
    def test_only_generated_value_and_checksums_change(self):
        original = fixture()
        changed, offset, old = module.rebind(original, 0x7f0f0002)
        self.assertEqual(0x7f0700a8, old)
        self.assertEqual(0x7f0f0002, struct.unpack_from('<I', changed, offset)[0])
        self.assertEqual(len(original), len(changed))
        self.assertTrue(all(original[i] == changed[i] for i in range(len(original))
                            if not 8 <= i < 32 and not offset <= i < offset + 4))
        self.assertEqual(hashlib.sha1(changed[32:]).digest(), changed[12:32])
        self.assertEqual(zlib.adler32(changed[12:]) & 0xffffffff, struct.unpack_from('<I', changed, 8)[0])

    def test_rebinding_is_idempotent(self):
        first, _, _ = module.rebind(fixture(), 0x7f0f0002)
        second, _, old = module.rebind(first, 0x7f0f0002)
        self.assertEqual(first, second)
        self.assertEqual(0x7f0f0002, old)

    def test_corrupted_source_is_rejected(self):
        data = bytearray(fixture())
        data[-1] ^= 1
        with self.assertRaisesRegex(ValueError, 'checksum'):
            module.rebind(bytes(data), 0x7f0f0002)

    def test_missing_resource_class_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'class not found'):
            module.rebind(fixture('Lsome/Other;'), 0x7f0f0002)

    def test_invalid_target_id_is_rejected(self):
        with self.assertRaises(ValueError):
            module.rebind(fixture(), 0)

    def test_cli_uses_agp_symbols_and_preserves_original_sdk(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, output, symbols = root / 'sdk.dex', root / 'bound.dex', root / 'R.txt'
            original = fixture()
            source.write_bytes(original)
            symbols.write_text('int mipmap ic_launcher_foreground 0x7f0f0002\n')
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/rebind_iccoa_resources.py'),
                                     str(source), str(output), '--symbols', str(symbols)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(0, result.returncode, result.stderr.decode())
            self.assertIn('field_offset=', result.stdout.decode())
            self.assertEqual(original, source.read_bytes())
            self.assertEqual(module.rebind(original, 0x7f0f0002)[0], output.read_bytes())


if __name__ == '__main__':
    unittest.main()
