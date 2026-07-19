from pathlib import Path
import struct

WIDTH = HEIGHT = 32


def write_bmp(path: Path):
    pixels = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            # Background and rounded-corner frame
            dx = x - 15
            dy = y - 15
            distance = (dx * dx) + (dy * dy)
            in_circle = distance <= 14 * 14
            if in_circle:
                bg = (255, 242, 200)
            else:
                bg = (255, 255, 255)

            # Pig face and ears
            if 8 <= x <= 24 and 8 <= y <= 24:
                bg = (252, 186, 95)
            if 11 <= x <= 13 and 10 <= y <= 12:
                bg = (255, 255, 255)
            if 19 <= x <= 21 and 10 <= y <= 12:
                bg = (255, 255, 255)
            if 12 <= x <= 20 and 15 <= y <= 18:
                bg = (255, 255, 255)
            if 13 <= x <= 19 and 14 <= y <= 17:
                bg = (255, 130, 180)

            # Border
            if x in (1, 30) or y in (1, 30):
                bg = (204, 102, 0)

            # Eye spots
            if (x, y) in {(11, 13), (21, 13)}:
                bg = (0, 0, 0)

            # Nose
            if (x, y) in {(15, 16), (16, 16)}:
                bg = (255, 255, 255)

            pixels.append((bg[2], bg[1], bg[0], 255))

    pixel_data = bytearray()
    for y in range(HEIGHT - 1, -1, -1):
        row = bytearray()
        for x in range(WIDTH):
            idx = y * WIDTH + x
            r, g, b, a = pixels[idx]
            row.extend([b, g, r, a])
        # Pad row to 4-byte boundary
        row.extend(b"\x00" * ((4 - (len(row) % 4)) % 4))
        pixel_data.extend(row)

    dib_header_size = 40
    file_header_size = 14
    image_size = len(pixel_data)
    file_size = file_header_size + dib_header_size + image_size
    with open(path, "wb") as fh:
        fh.write(b"BM")
        fh.write(struct.pack("<I", file_size))
        fh.write(struct.pack("<H", 0))
        fh.write(struct.pack("<H", 0))
        fh.write(struct.pack("<I", file_header_size + dib_header_size))
        fh.write(struct.pack("<I", dib_header_size))
        fh.write(struct.pack("<I", WIDTH))
        fh.write(struct.pack("<I", HEIGHT))
        fh.write(struct.pack("<H", 1))
        fh.write(struct.pack("<H", 32))
        fh.write(struct.pack("<I", 0))
        fh.write(struct.pack("<I", image_size))
        fh.write(struct.pack("<I", 2835))
        fh.write(struct.pack("<I", 2835))
        fh.write(struct.pack("<I", 0))
        fh.write(struct.pack("<I", 0))
        fh.write(pixel_data)


def write_ico(path: Path):
    bmp_path = path.with_suffix('.bmp')
    write_bmp(bmp_path)

    with open(bmp_path, 'rb') as fh:
        bmp_data = fh.read()

    header = struct.pack('<HHH', 0, 1, 1)
    entry = struct.pack('<BBBBHHII', 32, 32, 0, 0, 1, 32, len(bmp_data), 22)
    with open(path, 'wb') as fh:
        fh.write(header)
        fh.write(entry)
        fh.write(bmp_data)

    bmp_path.unlink(missing_ok=True)


if __name__ == '__main__':
    write_ico(Path('pig-purchases.ico'))
