"""Draws the app icon, the TV banner and the site's images (Pillow). Usage: python3 tools/make_art.py"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/assets/fonts/InterVariable.ttf")
STOPS = [(0.0, (8, 20, 35)), (0.5, (12, 45, 60)), (1.0, (19, 72, 76))]
MINT = (140, 255, 214, 255)
ICE = (231, 255, 249, 255)


def gradient(w, h, angle=35):
    """Diagonal three-color gradient."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    a = math.radians(angle)
    dx, dy = math.cos(a), math.sin(a)
    lo = min(0, w * dx, h * dy, w * dx + h * dy)
    hi = max(0, w * dx, h * dy, w * dx + h * dy)
    for y in range(h):
        for x in range(w):
            t = ((x * dx + y * dy) - lo) / (hi - lo)
            for i in range(len(STOPS) - 1):
                t0, c0 = STOPS[i]
                t1, c1 = STOPS[i + 1]
                if t <= t1:
                    f = (t - t0) / (t1 - t0)
                    px[x, y] = tuple(round(c0[k] + (c1[k] - c0[k]) * f) for k in range(3))
                    break
    return img


def mark(size, bg=True):
    """A mint play glyph framed by two open corners."""
    s = size * 4
    img = gradient(s, s).convert("RGBA") if bg else Image.new("RGBA", (s, s), (0, 0, 0, 0))
    if bg:
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.23), fill=255)
        img.putalpha(mask)
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    stroke = round(s * 0.066)
    for points in [((0.20, 0.52), (0.20, 0.29), (0.48, 0.29)),
                   ((0.80, 0.48), (0.80, 0.71), (0.52, 0.71))]:
        coordinates = [(round(x * s), round(y * s)) for x, y in points]
        d.line(coordinates, fill=ICE, width=stroke, joint="curve")
        for x, y in coordinates:
            r = stroke / 2
            d.ellipse((x - r, y - r, x + r, y + r), fill=ICE)
    d.polygon([(s * 0.43, s * 0.37), (s * 0.43, s * 0.63), (s * 0.65, s * 0.50)], fill=MINT)
    img.alpha_composite(layer)
    return img.resize((size, size), Image.LANCZOS)


def font(px, weight=600):
    f = ImageFont.truetype(FONT, px)
    try:
        axes = f.get_variation_axes()
        values = []
        for a in axes:
            name = a["name"] if isinstance(a["name"], str) else a["name"].decode()
            values.append(weight if "eight" in name else 32 if "ptical" in name else a.get("default", 0))
        f.set_variation_by_axes(values)
    except Exception:
        pass
    return f


def banner(w=640, h=360):
    s = 4
    img = gradient(w * s, h * s, angle=20).convert("RGBA")
    m = mark(int(h * 0.5 * s), bg=False)
    d = ImageDraw.Draw(img)
    f = font(int(h * 0.15 * s), 650)
    text = "Clean TV"
    box = d.textbbox((0, 0), text, font=f)
    gap = int(w * 0.02 * s)
    total = m.width + gap + (box[2] - box[0])
    x = (w * s - total) // 2
    img.alpha_composite(m, (x, int((h * s - m.height) / 2)))
    y = (h * s - (box[3] - box[1])) // 2 - box[1]
    d.text((x + m.width + gap - box[0], y), text, font=f, fill=(255, 255, 255, 255))
    return img.resize((w, h), Image.LANCZOS)


if __name__ == "__main__":
    res = os.path.join(ROOT, "app/res/drawable-nodpi")
    os.makedirs(res, exist_ok=True)
    mark(192).save(os.path.join(res, "icon.png"))
    banner().save(os.path.join(res, "banner.png"))
    site = os.path.join(ROOT, "docs/assets")
    os.makedirs(site, exist_ok=True)
    mark(512).save(os.path.join(site, "icon-512.png"))
    mark(180).save(os.path.join(site, "apple-touch-icon.png"))
    mark(64).save(os.path.join(site, "favicon-64.png"))
    banner(1200, 630).convert("RGB").save(os.path.join(site, "og.jpg"), quality=92)
    mark(512).save(os.path.join(ROOT, "tizen", "icon.png"))
    mark(80).save(os.path.join(ROOT, "webos", "icon.png"))
    mark(130).save(os.path.join(ROOT, "webos", "largeIcon.png"))
    symbol = Image.new("RGBA", (160, 160), "white")
    symbol.putalpha(mark(160, bg=False).getchannel("A"))
    symbol.save(os.path.join(ROOT, "app/assets/symbols", "clean_tv.png"))
    print("ok")
