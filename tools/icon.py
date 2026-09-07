#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
"""Render the launcher/store icon: a fan of zoomed card corners over felt with a gold plaque.

Same design language as 500's icon (whose generator was lost — this rebuilds it from measurements
of the shipped PNGs, parameterised by hand and plaque label so either game can use it). Card art is
cardkit's own card faces, so the icon shows exactly what the table shows.

    python3 cardkit/tools/icon.py --preset 500              # from a game repo root: writes every asset below
    python3 cardkit/tools/icon.py --preset euchre --preview out/   # only the flat design, for iterating

Outputs (paths relative to the repo root):
  fastlane/metadata/android/en-US/images/icon.png            512² flat store icon (the design frame)
  app/src/main/res/mipmap-*/ic_launcher.png                  legacy square (flat design)
  app/src/main/res/mipmap-*/ic_launcher_round.png            legacy round (flat design, circle mask)
  app/src/main/res/mipmap-*/ic_launcher_background.png       adaptive layer: felt gradient
  app/src/main/res/mipmap-*/ic_launcher_foreground.png       adaptive layer: fan + plaque, in the safe zone
  app/src/main/res/mipmap-*/ic_launcher_monochrome.png       themed-icon mask: silhouettes, index + label knocked out

Needs Pillow and DejaVu Sans (the plaque face and the felt watermark glyphs).
"""
from __future__ import annotations

import argparse
import math
import sys
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

# Lives in cardkit/tools; card art comes from the cardkit checkout this file sits in, and the
# OUTPUTS go to the calling game's repo (`--root`, default: the working directory).
CARDKIT = Path(__file__).resolve().parent.parent
CARD_DIR = CARDKIT / "cardkit-ui/src/commonMain/composeResources/drawable"
ROOT = Path.cwd()
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

FRAME = 512  # the design frame; everything below is expressed in it and scaled from it

# Felt: dark corners, lighter centre, plus two faint suit watermarks — matches 500's felt so the
# two icons read as siblings on a home screen.
FELT_CORNER = (16, 72, 22)
FELT_EDGE = (24, 87, 29)
FELT_CENTRE = (45, 123, 49)
WATERMARK_ALPHA = 30

# Plaque: 500's spans x 17→85 %, y 74→94 % of the frame.
PLAQUE_BOX = (82, 380, 430, 481)
PLAQUE_RADIUS = 8
PLAQUE_FILL = (11, 61, 18)
PLAQUE_GOLD = (215, 176, 55)
PLAQUE_BORDER = 3
PLAQUE_INSET = (145, 114, 26)

# Adaptive layers: 108dp canvas at xxxhdpi = 432px, of which launchers show a 72dp (288px) circle,
# squircle or rounded square out of the middle. The design frame is drawn at just over half size,
# centred and nudged up, so the outer indices and the plaque label all fall inside the circle — the
# strictest of the masks — with the fan filling as much of it as that allows.
ADAPTIVE = 432
FOREGROUND_SCALE = 0.56
FOREGROUND_LIFT = 14  # in 432-canvas px

# Legacy mipmap sizes (48dp at each density).
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

RANK_FILES = {"A": "ace", "K": "king", "Q": "queen", "J": "jack"}
SUIT_FILES = {"♠": "spades", "♥": "hearts", "♦": "diamonds", "♣": "clubs"}


@dataclass(frozen=True)
class FanCard:
    """One card of the fan. `pos` is where the card's visible top corner lands in the design frame
    AFTER rotation — the top-left corner, or the top-right for an `index_right` card — so a card can
    be placed by where its index shows; `angle` is degrees anticlockwise. Listed back to front."""

    label: str  # e.g. "J♠"
    pos: tuple[float, float]
    angle: float
    # Cards peeking out on the RIGHT of the front card show their top-right corner, where cardkit's
    # two-index faces carry nothing; this copies the top-left index into that blank margin (a
    # four-corner-index deck, so the glyphs stay unmirrored).
    index_right: bool = False


# The classic Euchre hand — Q♠ K♠ A♠ J♣ J♠ — as a held fan: the right bower front and centre (a
# court card is the interesting face), king and queen behind it to the left, the left bower and the
# ace behind it to the right. Listed back to front.
CARD_HEIGHT = 400  # default; a preset or --card-height overrides (500's fan is zoomed closer)
EUCHRE_HAND = [
    FanCard("Q♠", (36, 150), 14),
    FanCard("K♠", (76, 124), 7),
    FanCard("A♠", (476, 150), -14, index_right=True),
    FanCard("J♣", (436, 124), -7, index_right=True),
    FanCard("J♠", (118, 106), 0),
]
# 500's fan: the winning hand's bower in front, A♣ and K♥ behind it to the left, zoomed in closer
# than Euchre's so the J♠ face fills the frame (its original icon, rebuilt to measurement).
FIVEHUNDRED_HAND = [
    FanCard("A♣", (24, 196), 18),
    FanCard("K♥", (88, 152), 10),
    FanCard("J♠", (164, 116), 5),
]
@dataclass(frozen=True)
class Style:
    """Everything a game tunes besides the fan itself."""

    hand: list[FanCard]
    card_height: int
    label: str
    gold: tuple[int, int, int] = PLAQUE_GOLD
    border: int = PLAQUE_BORDER
    felt: tuple[tuple[int, int, int], tuple[int, int, int], tuple[int, int, int]] = (FELT_CENTRE, FELT_EDGE, FELT_CORNER)


# 500's shipped icon remains the canonical asset (Jack prefers it: brighter, a touch more zoom, the
# bower held at a slight angle, a heavier plaque). This preset is an APPROXIMATION tuned side by
# side — re-rendering from it would change the icon visibly, so don't without asking.
PRESETS: dict[str, Style] = {
    "euchre": Style(EUCHRE_HAND, 400, "EUCHRE"),
    "500": Style(
        FIVEHUNDRED_HAND, 880, "500",
        gold=(252, 224, 120), border=4,
        felt=((58, 138, 62), (32, 100, 38), (20, 80, 26)),
    ),
}
FRONT_INDEX_BOX = (0.02, 0.01, 0.125, 0.26)  # the top-left index, as fractions of a card face
# Source-card pixels (256×372 faces): the region the top-left index lives in, and the border to keep
# clear of when copying it into the top-right corner for `index_right` cards. The index's own ink
# bounds are measured per card — the ace's pip is wider than a court card's.
INDEX_REGION = (2, 4, 60, 130)  # starts just inside the card outline, which must not be copied
INDEX_MARGIN = 3
CORNER_CLEAR = 14  # wedge of INDEX_REGION's top-left corner that the card's rounded outline crosses
INDEX_MAX_HEIGHT = 76  # court indices are 71–77px tall; the ace's is 87 and would look outsized beside them


def card_image(card: FanCard | str) -> Image.Image:
    label = card if isinstance(card, str) else card.label
    rank, suit = label[:-1], label[-1]
    path = CARD_DIR / f"card_{RANK_FILES.get(rank, rank)}_of_{SUIT_FILES[suit]}.png"
    img = Image.open(path).convert("RGBA")
    if not isinstance(card, str) and card.index_right:
        return index_right_card(img)
    return img


def index_right_card(img: Image.Image) -> Image.Image:
    """A blank card carrying only the source card's index, in the top-RIGHT corner. Everything else
    is dropped: a court card's inner frame runs right beside its index, so copying a rectangle of
    it drags frame and art along, and a card peeking out from behind the front one shows nothing
    but that corner anyway."""
    grey = img.convert("L")
    # A court card's inner frame is a near-solid dark column just right of the index; the ace has none.
    x_cap = INDEX_REGION[2]
    for x in range(24, INDEX_REGION[2]):
        dark = sum(1 for y in range(60, img.height - 60) if grey.getpixel((x, y)) < 160)
        if dark > (img.height - 120) * 0.9:
            x_cap = x
            break
    region = grey.crop((INDEX_REGION[0], INDEX_REGION[1], x_cap, INDEX_REGION[3]))
    ink_mask = region.point(lambda v: 255 if v < 160 else 0)
    # The card outline's rounded corner cuts across the region's top-left; clear that wedge or a
    # pixel of it anchors the bounding box and comes along as a speck.
    ImageDraw.Draw(ink_mask).polygon([(0, 0), (CORNER_CLEAR, 0), (0, CORNER_CLEAR)], fill=0)
    ink = ink_mask.getbbox()
    pad = 2
    crop = (
        INDEX_REGION[0] + max(ink[0] - pad, 0),
        INDEX_REGION[1] + max(ink[1] - pad, 0),
        min(INDEX_REGION[0] + ink[2] + pad, x_cap),
        INDEX_REGION[1] + ink[3] + pad,
    )
    # Transfer the ink only, through its own anti-aliased coverage, rather than a white rectangle:
    # the rectangle would carry any outline pixel at its edge along as a speck.
    index_alpha = ImageOps.invert(grey.crop(crop))
    ImageDraw.Draw(index_alpha).polygon(
        [(0, 0), (CORNER_CLEAR - (crop[0] - INDEX_REGION[0]), 0), (0, CORNER_CLEAR - (crop[1] - INDEX_REGION[1]))], fill=0
    )
    if index_alpha.height > INDEX_MAX_HEIGHT:
        w = round(index_alpha.width * INDEX_MAX_HEIGHT / index_alpha.height)
        index_alpha = index_alpha.resize((w, INDEX_MAX_HEIGHT), Image.LANCZOS)
    blank = Image.new("RGBA", img.size, (250, 250, 250, 255))
    blank.putalpha(img.getchannel("A"))
    dest = (img.width - INDEX_MARGIN - index_alpha.width, crop[1])
    # Masked by the card's own alpha there too, so the rounded corner stays rounded.
    corner_alpha = blank.getchannel("A").crop((dest[0], dest[1], dest[0] + index_alpha.width, dest[1] + index_alpha.height))
    ink_alpha = Image.eval(index_alpha, lambda v: v)
    ink_alpha.paste(0, mask=ImageOps.invert(corner_alpha))
    blank.paste((0, 0, 0, 255), dest, ink_alpha)
    return blank


def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_BOLD, size)


def felt(size: int) -> Image.Image:
    """Radial-ish gradient: FELT_CENTRE in the middle falling to FELT_EDGE then FELT_CORNER."""
    img = Image.new("RGB", (size, size), FELT_CORNER)
    px = img.load()
    half = size / 2
    corner = math.hypot(half, half)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - half, y - half) / corner  # 0 centre → 1 corner
            if d < 0.7:
                t = d / 0.7
                c = tuple(round(a + (b - a) * t) for a, b in zip(FELT_CENTRE, FELT_EDGE))
            else:
                t = (d - 0.7) / 0.3
                c = tuple(round(a + (b - a) * t) for a, b in zip(FELT_EDGE, FELT_CORNER))
            px[x, y] = c
    return img


def watermarks(size: int) -> Image.Image:
    """Two faint suit glyphs in the empty felt — the bowers' suits for Euchre."""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    s = size / FRAME
    f = font(round(110 * s))
    colour = (90, 160, 95, WATERMARK_ALPHA)
    draw.text((28 * s, 8 * s), "♥", font=f, fill=colour)
    draw.text((26 * s, 300 * s), "♦", font=f, fill=colour)
    return layer


def rotated_card(card: FanCard, scale: float, img: Image.Image | None = None) -> tuple[Image.Image, tuple[int, int]]:
    """The card scaled and rotated, with the top-left at which to paste it so that the card's
    visible top corner (see [FanCard.pos]) lands where asked. PIL rotates about the centre and
    `expand=True` grows the canvas about it, so the corner's rotated offset from the centre is what
    places the card."""
    src = img if img is not None else card_image(card)
    h = round(CARD_HEIGHT * scale)
    w = round(src.width * h / src.height)
    src = src.resize((w, h), Image.LANCZOS)
    rot = src.rotate(card.angle, resample=Image.BICUBIC, expand=True)
    theta = math.radians(card.angle)
    cx, cy = (w / 2 if card.index_right else -w / 2), -h / 2
    # Anticlockwise on screen (y down): (x, y) → (x cosθ + y sinθ, −x sinθ + y cosθ).
    dx, dy = cx * math.cos(theta) + cy * math.sin(theta), -cx * math.sin(theta) + cy * math.cos(theta)
    centre_x, centre_y = card.pos[0] * scale - dx, card.pos[1] * scale - dy
    return rot, (round(centre_x - rot.width / 2), round(centre_y - rot.height / 2))


def draw_fan(canvas: Image.Image, hand: list[FanCard], scale: float, shadow: bool = True) -> None:
    for card in hand:
        rot, at = rotated_card(card, scale)
        if shadow:
            sh = Image.new("RGBA", rot.size, (0, 0, 0, 0))
            sh.putalpha(rot.getchannel("A").point(lambda a: a * 110 // 255))
            sh = sh.filter(ImageFilter.GaussianBlur(6 * scale))
            canvas.alpha_composite(sh, (at[0] - round(3 * scale), at[1] + round(5 * scale)))
        canvas.alpha_composite(rot, at)


def plaque_text_size(label: str, box_w: int, box_h: int) -> int:
    """Largest DejaVu Sans Bold that leaves the label a comfortable margin inside the plaque."""
    size = box_h
    while size > 8:
        f = font(size)
        l, t, r, b = f.getbbox(label)
        if r - l <= box_w * 0.82 and b - t <= box_h * 0.56:
            return size
        size -= 1
    return size


def plaque_box(scale: float) -> tuple[int, int, int, int]:
    return tuple(round(v * scale) for v in PLAQUE_BOX)


def draw_plaque(canvas: Image.Image, label: str, scale: float, knockout: bool = False) -> None:
    """Gold-bordered plaque. With `knockout`, draws a solid white plaque with the label transparent
    — the shape the themed-icon mask needs."""
    x0, y0, x1, y1 = plaque_box(scale)
    r = round(PLAQUE_RADIUS * scale)
    bw = max(1, round(PLAQUE_BORDER * scale))
    size = plaque_text_size(label, x1 - x0, y1 - y0)
    f = font(round(size))
    l, t, rr, b = f.getbbox(label)
    tx = (x0 + x1) / 2 - (l + rr) / 2
    ty = (y0 + y1) / 2 - (t + b) / 2
    if knockout:
        shape = Image.new("L", canvas.size, 0)
        d = ImageDraw.Draw(shape)
        d.rounded_rectangle((x0, y0, x1, y1), radius=r, fill=255)
        d.text((tx, ty), label, font=f, fill=0)
        white = Image.new("RGBA", canvas.size, (255, 255, 255, 255))
        white.putalpha(shape)
        canvas.alpha_composite(white)
        return
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle((x0, y0, x1, y1), radius=r, fill=PLAQUE_FILL, outline=PLAQUE_GOLD, width=bw)
    inset = bw + max(1, round(1.5 * scale))
    draw.rounded_rectangle(
        (x0 + inset, y0 + inset, x1 - inset, y1 - inset),
        radius=max(1, r - inset),
        outline=PLAQUE_INSET,
        width=max(1, round(scale)),
    )
    # Soft drop under the letters so the gold reads on the dark green at 48px.
    draw.text((tx + 1.5 * scale, ty + 2 * scale), label, font=f, fill=(0, 0, 0, 110))
    draw.text((tx, ty), label, font=f, fill=PLAQUE_GOLD)


def design(size: int, hand: list[FanCard], label: str) -> Image.Image:
    """The flat design at `size` px: felt, watermarks, fan, plaque."""
    scale = size / FRAME
    canvas = felt(size).convert("RGBA")
    canvas.alpha_composite(watermarks(size))
    draw_fan(canvas, hand, scale)
    draw_plaque(canvas, label, scale)
    return canvas


def foreground(size: int, hand: list[FanCard], label: str) -> Image.Image:
    """Adaptive foreground: the design frame at FOREGROUND_SCALE, centred."""
    scale = size / ADAPTIVE * FOREGROUND_SCALE
    origin = round((size - FRAME * scale) / 2)
    origin_y = origin - round(FOREGROUND_LIFT * size / ADAPTIVE)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw_fan(inner, hand, scale)
    draw_plaque(inner, label, scale)
    layer.alpha_composite(inner, (origin, origin_y))
    return layer


def monochrome(size: int, hand: list[FanCard], label: str) -> Image.Image:
    """Alpha-only mask: the union of the card silhouettes and the plaque solid, with the front card's
    index and the plaque label cut out of the union afterwards (cutting them out of the front card
    alone would just show the card behind). Same geometry as the foreground, flat fill."""
    scale = size / ADAPTIVE * FOREGROUND_SCALE
    origin = round((size - FRAME * scale) / 2)
    origin_y = origin - round(FOREGROUND_LIFT * size / ADAPTIVE)
    shape = Image.new("L", (size, size), 0)
    cut = Image.new("L", (size, size), 0)
    for card in hand:
        src = card_image(card)
        solid = Image.new("RGBA", src.size, (255, 255, 255, 255))
        solid.putalpha(src.getchannel("A"))
        rot, at = rotated_card(card, scale, solid)
        # Hard alpha: a themed icon wants a mask, not a soft edge.
        shape.paste(255, at, rot.getchannel("A").point(lambda a: 255 if a > 96 else 0))
    front = hand[-1]
    src = card_image(front)
    ink = src.convert("L").point(lambda v: 255 if v < 128 else 0)
    fx0, fy0, fx1, fy1 = FRONT_INDEX_BOX
    box = (round(fx0 * src.width), round(fy0 * src.height), round(fx1 * src.width), round(fy1 * src.height))
    index = Image.new("L", src.size, 0)
    index.paste(ink.crop(box), box[:2])
    index_rgba = Image.new("RGBA", src.size, (255, 255, 255, 255))
    index_rgba.putalpha(index)
    rot, at = rotated_card(front, scale, index_rgba)
    cut.paste(255, at, rot.getchannel("A").point(lambda a: 255 if a > 128 else 0))
    plaque = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw_plaque(plaque, label, scale, knockout=True)
    x0, y0, x1, y1 = plaque_box(scale)
    plaque_shape = Image.new("L", (size, size), 0)
    ImageDraw.Draw(plaque_shape).rounded_rectangle((x0, y0, x1, y1), radius=round(PLAQUE_RADIUS * scale), fill=255)
    # Inside the plaque, the label (transparent in the knockout draw) is what gets cut.
    label_cut = Image.eval(plaque.getchannel("A"), lambda a: 255 - a)
    label_cut.paste(0, mask=Image.eval(plaque_shape, lambda v: 255 - v))
    shape.paste(255, mask=plaque_shape)
    cut.paste(255, mask=label_cut)
    shape.paste(0, mask=cut)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    white = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    white.putalpha(shape)
    layer.alpha_composite(white, (origin, origin_y))
    return layer


def round_mask(img: Image.Image) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, img.width - 1, img.height - 1), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def main() -> int:
    global ROOT, CARD_HEIGHT, PLAQUE_GOLD, PLAQUE_BORDER, FELT_CENTRE, FELT_EDGE, FELT_CORNER
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--preset", choices=sorted(PRESETS), default="euchre", help="which game's hand/label/zoom")
    ap.add_argument("--label", help="plaque text (default: the preset's)")
    ap.add_argument("--hand", help='override the fan: "A♣@12,150,12;K♥@62,118,6;J♠@122,96,0[,r]" (label@x,y,angle[,r=index_right]), back to front')
    ap.add_argument("--card-height", type=int, help="card height in frame px (default: the preset's)")
    ap.add_argument("--root", type=Path, default=Path.cwd(), help="game repo to write into (default: cwd)")
    ap.add_argument("--preview", metavar="DIR", help="write only the 512px flat design to DIR/icon.png")
    args = ap.parse_args()
    ROOT = args.root.resolve()
    style = PRESETS[args.preset]
    hand, CARD_HEIGHT, label = style.hand, style.card_height, style.label
    PLAQUE_GOLD, PLAQUE_BORDER = style.gold, style.border
    FELT_CENTRE, FELT_EDGE, FELT_CORNER = style.felt
    if args.card_height:
        CARD_HEIGHT = args.card_height
    if args.label:
        label = args.label
    args.label = label
    if args.hand:
        hand = []
        for spec in args.hand.split(";"):
            card, rest = spec.split("@")
            parts = rest.split(",")
            hand.append(FanCard(card, (float(parts[0]), float(parts[1])), float(parts[2]), index_right=len(parts) > 3 and parts[3] == "r"))

    if args.preview:
        out = Path(args.preview)
        out.mkdir(parents=True, exist_ok=True)
        design(FRAME, hand, args.label).convert("RGB").save(out / "icon.png")
        foreground(ADAPTIVE, hand, args.label).save(out / "foreground.png")
        mono = monochrome(ADAPTIVE, hand, args.label)
        mono.save(out / "monochrome.png")
        # The mask is white-on-transparent; a grey ground makes the preview readable.
        grey = Image.new("RGBA", mono.size, (90, 90, 90, 255))
        grey.alpha_composite(mono)
        grey.convert("RGB").save(out / "monochrome-preview.png")
        print(f"wrote {out}/icon.png, foreground.png, monochrome.png")
        return 0

    store = ROOT / "fastlane/metadata/android/en-US/images/icon.png"
    store.parent.mkdir(parents=True, exist_ok=True)
    flat = design(FRAME, hand, args.label)
    flat.convert("RGB").save(store)
    print(store.relative_to(ROOT))

    res = ROOT / "app/src/main/res"
    for density, px in DENSITIES.items():
        d = res / f"mipmap-{density}"
        d.mkdir(exist_ok=True)
        legacy = design(px, hand, args.label)
        legacy.convert("RGB").save(d / "ic_launcher.png")
        round_mask(legacy).save(d / "ic_launcher_round.png")
        a = ADAPTIVE_SIZES[density]
        felt(a).save(d / "ic_launcher_background.png")
        foreground(a, hand, args.label).save(d / "ic_launcher_foreground.png")
        monochrome(a, hand, args.label).save(d / "ic_launcher_monochrome.png")
        print(d.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
