import struct
import unittest
from verify_release_apk import load_alignments


class ReleasePackagingTests(unittest.TestCase):
    @staticmethod
    def elf(alignment=16384, address=16384, offset=0):
        data = bytearray(120)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, 1)
        struct.pack_into('<I', data, 64, 1)
        struct.pack_into('<QQ', data, 72, offset, address)
        struct.pack_into('<Q', data, 112, alignment)
        return bytes(data)

    def test_modern_page_alignment_passes(self):
        self.assertEqual([16384], load_alignments(self.elf()))

    def test_legacy_four_k_library_is_rejected(self):
        with self.assertRaises(AssertionError):
            load_alignments(self.elf(4096))

    def test_incongruent_load_offset_is_rejected(self):
        with self.assertRaises(AssertionError):
            load_alignments(self.elf(offset=4096))

    def test_non_elf_is_rejected(self):
        with self.assertRaises(AssertionError):
            load_alignments(b'not a library')
