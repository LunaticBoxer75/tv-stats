"""Writes res/drawable/banner.png (320x180): the launcher tile, a bar chart on dark blue."""
import struct, zlib
W, H = 320, 180
bars = [(70, 60), (110, 95), (150, 40), (190, 120), (230, 80)]  # (x, height)
rows = []
for y in range(H):
    row = bytearray([0])
    for x in range(W):
        r, g, b = 0x14, 0x1C, 0x2E
        for bx, bh in bars:
            if bx <= x < bx + 26 and H - 30 - bh <= y < H - 30:
                r, g, b = 0x79, 0xC0, 0xFF
        if 50 <= x < 276 and H - 30 <= y < H - 26:
            r, g, b = 0x8B, 0x94, 0x9E
        row += bytes((r, g, b))
    rows.append(bytes(row))
def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0)) \
    + chunk(b'IDAT', zlib.compress(b''.join(rows), 9)) + chunk(b'IEND', b'')
open('res/drawable/banner.png', 'wb').write(png)
