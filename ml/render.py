"""Correct Devanagari rendering with HarfBuzz (shaping) + FreeType (rasterising).

Pillow only shapes Indic scripts when libraqm is present (it is not on Windows),
so matras and conjuncts would be drawn wrongly. This renderer works the same on
Windows, Linux and Colab.
"""
import freetype
import numpy as np
import uharfbuzz as hb


class Font:
    def __init__(self, path: str, index: int = 0):
        self.path = path
        face = hb.Face(hb.Blob.from_file_path(path), index)
        self.upem = face.upem
        self.hb_font = hb.Font(face)
        self.ft = freetype.Face(path, index=index)

    def covers(self, text: str) -> bool:
        return all(
            ch.isspace() or self.hb_font.get_nominal_glyph(ord(ch)) for ch in text
        )

    def render(self, text: str, size: int) -> np.ndarray:
        """Return a uint8 coverage mask (0 = background, 255 = ink), tightly cropped."""
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(self.hb_font, buf, {})

        self.ft.set_pixel_sizes(0, size)
        scale = size / self.upem
        ascent = int(size * 1.4)
        height = int(size * 2.2)
        width = int(sum(p.x_advance for p in buf.glyph_positions) * scale) + size * 2
        canvas = np.zeros((height, max(width, size)), dtype=np.uint8)

        pen_x = size * 0.5
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            self.ft.load_glyph(info.codepoint, freetype.FT_LOAD_RENDER)
            g = self.ft.glyph
            bmp = g.bitmap
            if bmp.width and bmp.rows:
                glyph = np.array(bmp.buffer, dtype=np.uint8).reshape(bmp.rows, bmp.pitch)[:, : bmp.width]
                x = int(round(pen_x + pos.x_offset * scale)) + g.bitmap_left
                y = ascent - int(round(pos.y_offset * scale)) - g.bitmap_top
                x0, y0 = max(x, 0), max(y, 0)
                x1 = min(x + bmp.width, canvas.shape[1])
                y1 = min(y + bmp.rows, canvas.shape[0])
                if x1 > x0 and y1 > y0:
                    region = canvas[y0:y1, x0:x1]
                    np.maximum(region, glyph[y0 - y : y1 - y, x0 - x : x1 - x], out=region)
            pen_x += pos.x_advance * scale

        rows = np.where(canvas.max(axis=1) > 0)[0]
        cols = np.where(canvas.max(axis=0) > 0)[0]
        if not len(rows):
            return canvas[:size, :size]
        return canvas[rows[0] : rows[-1] + 1, cols[0] : cols[-1] + 1]
