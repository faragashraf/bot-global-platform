#!/usr/bin/env python3
"""Generate Google Play graphics from current Nqrb Android screenshots."""

from __future__ import annotations

from base64 import b64encode
from html import escape
import os
from pathlib import Path
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "source"
DEFAULT_CAPTURE_ROOT = Path(
    "/Users/ashraffarag/Documents/Codex/2026-10-10/"
    "nqrb-chat-followup/outputs/google-play/mobile/raw-latest",
)
CAPTURE_ROOT = Path(os.environ.get("NQRB_PLAY_RAW_ROOT", DEFAULT_CAPTURE_ROOT))

BRAND_BLUE = "#3D5BD8"
BRAND_BLUE_DARK = "#2039B8"
BRAND_BLUE_LIGHT = "#E7ECFF"
BRAND_TEAL = "#32D8C4"
BACKGROUND = "#F8F9FD"
TEXT = "#182033"
MUTED = "#526079"

SOURCE_SIZE = (941, 1672)

LATEST_SOURCES = {
    "home-current.png": ("a24-home-latest-2.png", 0),
    "people-current.png": ("a24-people-latest.png", 0),
    "chats-current.png": ("a24-chats-latest.png", 0),
    "voice-current.png": ("a24-chat-thread-latest.png", 120),
}

SCREENS = {
    "en-US": [
        ("home-current.png", "Call your circle", "Start from the people you choose."),
        ("people-current.png", "People stay close", "Search, invite, message, or call."),
        ("chats-current.png", "Messages beside calls", "Text and voice threads in one place."),
        ("voice-current.png", "Voice notes feel natural", "Record, send, and listen clearly."),
    ],
    "ar": [
        ("home-current.png", "دائرتك أقرب", "ابدأ من الأشخاص الذين تختارهم."),
        ("people-current.png", "الأشخاص في مكان واحد", "ابحث، ادعُ، راسل، أو اتصل."),
        ("chats-current.png", "رسائل بجانب المكالمات", "نص وصوت داخل محادثة واحدة."),
        ("voice-current.png", "رسائل صوتية واضحة", "سجّل وأرسل واستمع بسهولة."),
    ],
}


def run_sips(svg: str, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="nqrb-play-art-") as work:
        source = Path(work) / "art.svg"
        source.write_text(svg, encoding="utf-8")
        subprocess.run(
            ["sips", "-s", "format", "png", str(source), "--out", str(output)],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        with Image.open(output) as rendered:
            rendered.convert("RGB").save(output, format="PNG", optimize=True)


def cover_resize(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    target_w, target_h = size
    src_w, src_h = image.size
    scale = max(target_w / src_w, target_h / src_h)
    resized = image.resize((round(src_w * scale), round(src_h * scale)), Image.Resampling.LANCZOS)
    left = (resized.width - target_w) // 2
    top = (resized.height - target_h) // 2
    return resized.crop((left, top, left + target_w, top + target_h))


def crop_capture(path: Path, y_offset: int) -> Image.Image:
    with Image.open(path) as raw:
        image = raw.convert("RGB")
    width, height = image.size
    target_h = round(width * SOURCE_SIZE[1] / SOURCE_SIZE[0])
    target_h = min(target_h, height)
    top = max(0, min(y_offset, height - target_h))
    cropped = image.crop((0, top, width, top + target_h))
    return cropped.resize(SOURCE_SIZE, Image.Resampling.LANCZOS)


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    name = "Arial Bold.ttf" if bold else "Arial.ttf"
    return ImageFont.truetype(f"/System/Library/Fonts/Supplemental/{name}", size)


def anonymize_voice_source(image: Image.Image) -> Image.Image:
    output = image.copy()
    draw = ImageDraw.Draw(output)
    draw.rounded_rectangle((355, 60, 650, 145), radius=18, fill=BACKGROUND)
    draw.text((430, 78), "Nqrb", fill=TEXT, font=font(40, bold=True))
    return output


def anonymize_chats_source(image: Image.Image) -> Image.Image:
    output = image.copy()
    draw = ImageDraw.Draw(output)
    rows = [
        (360, "Nqrb Contact", "Write a message"),
        (590, "Nqrb Contact", "Voice note"),
        (820, "Nqrb Contact", "Write a message"),
    ]
    for y, name, caption in rows:
        draw.rounded_rectangle((380, y - 24, 730, y + 148), radius=18, fill="#FFFFFF")
        draw.text((410, y), name, fill=TEXT, font=font(36, bold=True))
        draw.text((410, y + 48), caption, fill=MUTED, font=font(25))
    return output


def prepare_sources() -> None:
    SOURCE.mkdir(parents=True, exist_ok=True)
    for target, (capture_name, y_offset) in LATEST_SOURCES.items():
        capture = CAPTURE_ROOT / capture_name
        if not capture.exists():
            continue
        image = crop_capture(capture, y_offset)
        if target == "voice-current.png":
            image = anonymize_voice_source(image)
        elif target == "chats-current.png":
            image = anonymize_chats_source(image)
        image.save(SOURCE / target, format="PNG", optimize=True)


def image_data(image_file: str) -> str:
    return b64encode((SOURCE / image_file).read_bytes()).decode("ascii")


def brand_mark(x: int, y: int, scale: float = 1.0, include_word: bool = True) -> str:
    word = ""
    if include_word:
        word = f"""
        <text x="24" y="-4" fill="#F8F9FD" font-family="Arial, Helvetica"
          font-size="20" font-weight="700">NqrB</text>"""
    return f"""
    <g transform="translate({x} {y}) scale({scale})">
      <path d="M0 0h108v108h-108z" fill="{BRAND_BLUE}"/>
      <path d="M71 -9C103 5 119 36 111 73C105 100 85 116 60 118H108V0H71Z"
        fill="{BRAND_BLUE_DARK}" opacity=".55"/>
      <path d="M0 0h48C22 17 10 44 15 75C18 91 26 102 38 108H0Z"
        fill="#4F6CFF" opacity=".72"/>
      <g transform="translate(0 0)">
        <circle cx="12" cy="62.6" r="5.2" fill="#F8F9FD"/>
        <circle cx="96" cy="62.6" r="5.2" fill="#F8F9FD"/>
        <path d="M12 68.6C30 84 78 84 96 68.6" fill="none"
          stroke="{BRAND_TEAL}" stroke-linecap="round" stroke-width="5.2"/>
        {word}
      </g>
    </g>"""


def screenshot_svg(image_file: str, title: str, subtitle: str, index: int, arabic: bool) -> str:
    anchor = "end" if arabic else "start"
    title_x = 992 if arabic else 88
    title_font = "Geeza Pro, Arial Unicode, Arial" if arabic else "Arial, Helvetica"
    return f"""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1080" height="1920"
      viewBox="0 0 1080 1920">
      <defs>
        <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="{BACKGROUND}"/>
          <stop offset=".55" stop-color="#F1F4FF"/>
          <stop offset="1" stop-color="#E7ECFF"/>
        </linearGradient>
        <clipPath id="screen"><rect x="110" y="344" width="860" height="1528" rx="38"/></clipPath>
      </defs>
      <rect width="1080" height="1920" fill="url(#bg)"/>
      <circle cx="1008" cy="-90" r="350" fill="#DDE6FF" opacity=".65"/>
      <circle cx="-85" cy="1765" r="320" fill="#C9F6EF" opacity=".38"/>
      <g transform="translate(76 44) scale(.60)">{brand_mark(0, 0, 1, False)}</g>
      <text x="146" y="91" fill="{TEXT}" font-family="Arial, Helvetica"
        font-size="42" font-weight="700">Nqrb</text>
      <text x="991" y="85" text-anchor="end" fill="{BRAND_BLUE}" font-family="Arial"
        font-size="25" font-weight="700">{index:02d} / 04</text>
      <text x="{title_x}" y="205" text-anchor="{anchor}" fill="{TEXT}"
        font-family="{title_font}" font-size="62" font-weight="700">{escape(title)}</text>
      <text x="{title_x}" y="271" text-anchor="{anchor}" fill="{MUTED}"
        font-family="{title_font}" font-size="30">{escape(subtitle)}</text>
      <rect x="121" y="359" width="860" height="1528" rx="40" fill="#C8D3FF" opacity=".34"/>
      <rect x="109" y="343" width="862" height="1530" rx="39" fill="#ffffff"
        stroke="#DDE3F2" stroke-width="2"/>
      <image xlink:href="data:image/png;base64,{image_data(image_file)}" x="110" y="344"
        width="860" height="1528" clip-path="url(#screen)"/>
    </svg>"""


def feature_svg(arabic: bool) -> str:
    if arabic:
        title = "Nqrb يقرب دائرتك"
        subtitle = "مكالمات ورسائل صوتية مع الأشخاص الذين تختارهم"
        title_x, anchor, font_family = 938, "end", "Geeza Pro, Arial Unicode, Arial"
        hero_x = 92
        brand_x, brand_label_x = 780, 846
    else:
        title = "Your circle, closer."
        subtitle = "Calls, text, and voice notes for the people you choose."
        title_x, anchor, font_family = 80, "start", "Arial, Helvetica"
        hero_x = 604
        brand_x, brand_label_x = 78, 144
    return f"""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1024" height="500"
      viewBox="0 0 1024 500">
      <defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="{BACKGROUND}"/>
        <stop offset=".55" stop-color="#EEF2FF"/>
        <stop offset="1" stop-color="#DDE6FF"/>
      </linearGradient></defs>
      <rect width="1024" height="500" fill="url(#bg)"/>
      <circle cx="1010" cy="-112" r="330" fill="#C7D2FF" opacity=".55"/>
      <circle cx="-105" cy="515" r="300" fill="#BEF4EC" opacity=".38"/>
      <g transform="translate({hero_x} 80)">
        <circle cx="160" cy="170" r="138" fill="none" stroke="#CAD4FA" stroke-width="3"/>
        <circle cx="160" cy="170" r="87" fill="none" stroke="#B8C5F4" stroke-width="3"/>
        <circle cx="160" cy="170" r="62" fill="{BRAND_BLUE}"/>
        <path d="M140 147 C145 169 163 184 183 188 L194 176 L177 164 L168 172
          C157 168 151 160 148 150 L156 142 L144 132 Z" fill="none" stroke="#fff"
          stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>
        <rect x="-10" y="276" width="340" height="84" rx="28" fill="#FFFFFF" opacity=".92"/>
        <path d="M46 318h170" stroke="#AEB8D3" stroke-width="9" stroke-linecap="round"
          stroke-dasharray="2 20"/>
        <circle cx="268" cy="318" r="31" fill="{BRAND_BLUE_LIGHT}"/>
        <path d="M258 303l25 15l-25 15z" fill="none" stroke="{BRAND_BLUE}"
          stroke-width="6" stroke-linejoin="round"/>
      </g>
      <g transform="translate({brand_x} 64) scale(.52)">{brand_mark(0, 0, 1, False)}</g>
      <text x="{brand_label_x}" y="106" fill="{TEXT}" font-family="Arial" font-size="44"
        font-weight="700">Nqrb</text>
      <text x="{title_x}" y="305" text-anchor="{anchor}" fill="{TEXT}"
        font-family="{font_family}" font-size="55" font-weight="700">{escape(title)}</text>
      <text x="{title_x}" y="366" text-anchor="{anchor}" fill="{MUTED}"
        font-family="{font_family}" font-size="25">{escape(subtitle)}</text>
    </svg>"""


def tablet_svg(language: str, landscape: bool) -> str:
    arabic = language == "ar"
    width, height = ((2560, 1600) if landscape else (1600, 2560))
    title = "Nqrb على كل شاشاتك" if arabic else "Nqrb on every screen"
    subtitle = "مكالمات ورسائل بصوت واضح" if arabic else "Calls, text, and voice notes together"
    font_family = "Geeza Pro, Arial Unicode, Arial" if arabic else "Arial, Helvetica"
    anchor = "end" if arabic else "start"
    text_x = width - 150 if arabic else 150
    if landscape:
        phone_w, phone_h = 620, 1103
        first_x, second_x, phone_y = 1480, 780, 300
        if arabic:
            first_x, second_x = 330, 1030
    else:
        phone_w, phone_h = 760, 1351
        first_x, second_x, phone_y = 118, 720, 880
    return f"""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{width}" height="{height}"
      viewBox="0 0 {width} {height}">
      <defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="{BACKGROUND}"/>
        <stop offset=".52" stop-color="#EEF2FF"/>
        <stop offset="1" stop-color="#DDE6FF"/>
      </linearGradient>
      <clipPath id="p1"><rect x="{first_x}" y="{phone_y}" width="{phone_w}" height="{phone_h}" rx="58"/></clipPath>
      <clipPath id="p2"><rect x="{second_x}" y="{phone_y + 120}" width="{phone_w}" height="{phone_h}" rx="58"/></clipPath>
      </defs>
      <rect width="{width}" height="{height}" fill="url(#bg)"/>
      <circle cx="{width * .12}" cy="{height * .90}" r="{height * .18}" fill="#C9F6EF" opacity=".36"/>
      <circle cx="{width * .92}" cy="{height * .06}" r="{height * .20}" fill="#C7D2FF" opacity=".55"/>
      <g transform="translate(140 130) scale(.75)">{brand_mark(0, 0, 1, False)}</g>
      <text x="236" y="197" fill="{TEXT}" font-family="Arial" font-size="70"
        font-weight="700">Nqrb</text>
      <text x="{text_x}" y="{height * .28:.0f}" text-anchor="{anchor}" fill="{TEXT}"
        font-family="{font_family}" font-size="96" font-weight="700">{escape(title)}</text>
      <text x="{text_x}" y="{height * .28 + 86:.0f}" text-anchor="{anchor}" fill="{MUTED}"
        font-family="{font_family}" font-size="42">{escape(subtitle)}</text>
      <rect x="{second_x + 18}" y="{phone_y + 138}" width="{phone_w}" height="{phone_h}" rx="60"
        fill="#C8D3FF" opacity=".30"/>
      <rect x="{second_x}" y="{phone_y + 120}" width="{phone_w}" height="{phone_h}" rx="58"
        fill="#fff" stroke="#DDE3F2" stroke-width="3"/>
      <image xlink:href="data:image/png;base64,{image_data('voice-current.png')}" x="{second_x}" y="{phone_y + 120}"
        width="{phone_w}" height="{phone_h}" clip-path="url(#p2)"/>
      <rect x="{first_x + 18}" y="{phone_y + 18}" width="{phone_w}" height="{phone_h}" rx="60"
        fill="#C8D3FF" opacity=".36"/>
      <rect x="{first_x}" y="{phone_y}" width="{phone_w}" height="{phone_h}" rx="58"
        fill="#fff" stroke="#DDE3F2" stroke-width="3"/>
      <image xlink:href="data:image/png;base64,{image_data('home-current.png')}" x="{first_x}" y="{phone_y}"
        width="{phone_w}" height="{phone_h}" clip-path="url(#p1)"/>
    </svg>"""


def render_icon(output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", (512, 512), BRAND_BLUE)
    draw = ImageDraw.Draw(image)
    draw.pieslice((-90, -70, 640, 620), start=296, end=70, fill=BRAND_BLUE_DARK)
    draw.pieslice((-255, -55, 405, 650), start=110, end=250, fill="#4F6CFF")
    draw.arc((70, 296, 442, 470), start=15, end=165, fill=BRAND_TEAL, width=26)
    draw.ellipse((43, 292, 99, 348), fill=BACKGROUND)
    draw.ellipse((413, 292, 469, 348), fill=BACKGROUND)
    text_font = font(108, bold=True)
    text_box = draw.textbbox((0, 0), "NqrB", font=text_font)
    text_width = text_box[2] - text_box[0]
    draw.text(((512 - text_width) / 2, 194), "NqrB", fill=BACKGROUND, font=text_font)
    image.save(output, format="PNG", optimize=True)


def hex_rgb(value: str) -> tuple[int, int, int]:
    value = value.removeprefix("#")
    return tuple(int(value[index:index + 2], 16) for index in (0, 2, 4))


def gradient(size: tuple[int, int]) -> Image.Image:
    width, height = size
    top = hex_rgb(BACKGROUND)
    bottom = hex_rgb("#DDE6FF")
    image = Image.new("RGB", size)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = y / max(1, height - 1)
        color = tuple(round(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
        draw.line((0, y, width, y), fill=color)
    return image


def paste_round(base: Image.Image, image: Image.Image, box: tuple[int, int, int, int], radius: int) -> None:
    x, y, width, height = box
    content = cover_resize(image, (width, height))
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width, height), radius=radius, fill=255)
    base.paste(content, (x, y), mask)


def draw_brand(draw: ImageDraw.ImageDraw, x: int, y: int, scale: float = 1.0) -> None:
    size = round(76 * scale)
    draw.rounded_rectangle((x, y, x + size, y + size), radius=round(18 * scale), fill=BRAND_BLUE)
    draw.ellipse((x + round(8 * scale), y + round(45 * scale), x + round(18 * scale), y + round(55 * scale)), fill=BACKGROUND)
    draw.ellipse((x + round(58 * scale), y + round(45 * scale), x + round(68 * scale), y + round(55 * scale)), fill=BACKGROUND)
    draw.arc(
        (x + round(8 * scale), y + round(45 * scale), x + round(68 * scale), y + round(77 * scale)),
        start=15,
        end=165,
        fill=BRAND_TEAL,
        width=max(2, round(5 * scale)),
    )


def render_phone(image_file: str, title: str, subtitle: str, index: int, language: str, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    arabic = language == "ar"
    canvas = gradient((1080, 1920))
    draw = ImageDraw.Draw(canvas)
    draw.ellipse((760, -180, 1220, 280), fill="#DDE6FF")
    draw.ellipse((-170, 1600, 260, 2030), fill="#D8FAF5")
    draw_brand(draw, 76, 48, .68)
    draw.text((144, 66), "Nqrb", fill=TEXT, font=font(42, True))
    draw.text((990, 68), f"{index:02d} / 04", fill=BRAND_BLUE, font=font(25, True), anchor="ra")
    if arabic:
        draw.text((88, 198), "Nqrb", fill=TEXT, font=font(70, True))
        draw.text((88, 266), "Calls • Chat • Voice", fill=MUTED, font=font(30))
    else:
        draw.text((88, 198), title, fill=TEXT, font=font(62, True))
        draw.text((88, 266), subtitle, fill=MUTED, font=font(30))

    frame = (110, 344, 860, 1528)
    draw.rounded_rectangle((121, 359, 981, 1887), radius=42, fill="#C8D3FF")
    draw.rounded_rectangle((109, 343, 971, 1873), radius=40, fill="#FFFFFF", outline="#DDE3F2", width=2)
    with Image.open(SOURCE / image_file) as screen:
        paste_round(canvas, screen.convert("RGB"), frame, 38)
    canvas.save(output, format="PNG", optimize=True)


def render_feature(language: str, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    arabic = language == "ar"
    image = gradient((1024, 500))
    draw = ImageDraw.Draw(image)
    draw.ellipse((780, -160, 1200, 260), fill="#C7D2FF")
    draw.ellipse((-180, 350, 180, 710), fill="#D8FAF5")
    draw_brand(draw, 78 if not arabic else 770, 66, .72)
    draw.text((148 if not arabic else 840, 78), "Nqrb", fill=TEXT, font=font(46, True))
    hero_x = 620 if not arabic else 90
    draw.ellipse((hero_x + 12, 92, hero_x + 328, 408), outline="#CAD4FA", width=4)
    draw.ellipse((hero_x + 75, 155, hero_x + 265, 345), outline="#B8C5F4", width=4)
    draw.ellipse((hero_x + 112, 192, hero_x + 228, 308), fill=BRAND_BLUE)
    draw.rounded_rectangle((hero_x - 20, 365, hero_x + 360, 455), radius=30, fill="#FFFFFF")
    for index, x in enumerate(range(hero_x + 28, hero_x + 210, 24)):
        height = 24 + (index % 4) * 9
        draw.rounded_rectangle((x, 410 - height // 2, x + 7, 410 + height // 2), radius=4, fill="#AEB8D3")
    draw.ellipse((hero_x + 275, 381, hero_x + 337, 443), fill=BRAND_BLUE_LIGHT)
    draw.polygon([(hero_x + 299, 397), (hero_x + 299, 427), (hero_x + 322, 412)], outline=BRAND_BLUE)
    title_x = 80 if not arabic else 944
    anchor = "la" if not arabic else "ra"
    draw.text((title_x, 285), "Your circle, closer.", fill=TEXT, font=font(56, True), anchor=anchor)
    draw.text((title_x, 350), "Calls • text • voice notes", fill=MUTED, font=font(28), anchor=anchor)
    image.save(output, format="PNG", optimize=True)


def render_tablet(language: str, landscape: bool, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    size = (2560, 1600) if landscape else (1600, 2560)
    image = gradient(size)
    draw = ImageDraw.Draw(image)
    draw_brand(draw, 140, 130, 1.05)
    draw.text((245, 158), "Nqrb", fill=TEXT, font=font(78, True))
    if landscape:
        draw.text((150, 500), "Calls, chat, and voice", fill=TEXT, font=font(96, True))
        draw.text((150, 600), "A cleaner way to stay close.", fill=MUTED, font=font(44))
        first = (1480, 270, 620, 1103)
        second = (790, 390, 620, 1103)
    else:
        draw.text((150, 590), "Nqrb", fill=TEXT, font=font(110, True))
        draw.text((150, 700), "Calls • Chat • Voice", fill=MUTED, font=font(48))
        first = (135, 930, 760, 1351)
        second = (705, 1070, 760, 1351)
    for box, source_name in ((second, "voice-current.png"), (first, "home-current.png")):
        x, y, width, height = box
        draw.rounded_rectangle((x + 18, y + 18, x + width + 18, y + height + 18), radius=62, fill="#C8D3FF")
        draw.rounded_rectangle((x, y, x + width, y + height), radius=58, fill="#FFFFFF", outline="#DDE3F2", width=3)
        with Image.open(SOURCE / source_name) as screen:
            paste_round(image, screen.convert("RGB"), box, 58)
    image.save(output, format="PNG", optimize=True)


def main() -> None:
    prepare_sources()
    render_icon(ROOT / "icon-512.png")
    for language, screens in SCREENS.items():
        for index, (image, title, subtitle) in enumerate(screens, start=1):
            render_phone(image, title, subtitle, index, language, ROOT / language / f"phone-{index:02d}.png")
        render_feature(language, ROOT / language / "feature-graphic.png")
        render_tablet(language, landscape=False, output=ROOT / language / "tablet-7" / "tablet-01.png")
        render_tablet(language, landscape=True, output=ROOT / language / "tablet-10" / "tablet-01.png")


if __name__ == "__main__":
    main()
