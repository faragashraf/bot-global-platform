#!/usr/bin/env python3
"""Compose Play graphics around anonymized captures of the real Android UI."""

from base64 import b64encode
from html import escape
from pathlib import Path
import subprocess
import tempfile
from PIL import Image


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "source"

SCREENS = {
    "en-US": [
        ("home-en-anonymous.png", "Your circle, one tap away", "Call the people you choose."),
        ("people-en-anonymous.png", "Keep your people close", "Find a Nqrb account or share an invite."),
        ("history-en-anonymous.png", "Pick up where you left off", "See your calls and call back with ease."),
        ("settings-en.png", "Make every call yours", "Choose the look and ringtone you love."),
    ],
    "ar": [
        ("home-ar-anonymous.png", "دائرتك على بُعد لمسة", "اتصل بمن تختار بسهولة."),
        ("people-ar-anonymous.png", "اللي يهمّوك أقرب", "ابحث عن حساب Nqrb أو شارك دعوة."),
        ("history-ar-anonymous.png", "مكالمة جديدة في لحظة", "راجع السجل واتصل مرة أخرى بسهولة."),
        ("settings-ar.png", "خلّي Nqrb على ذوقك", "اختار المظهر والنغمة اللي تحبها."),
    ],
}


def brand_mark(x: int, y: int, scale: float = 1.0) -> str:
    return f"""
    <g transform="translate({x} {y}) scale({scale})" fill="none" stroke="#127347"
       stroke-linecap="round" stroke-linejoin="round">
      <path d="M8 32 C16 8 48 8 56 32" stroke-width="6"/>
      <circle cx="4" cy="34" r="9" fill="#127347" stroke="none"/>
      <circle cx="60" cy="34" r="9" fill="#127347" stroke="none"/>
    </g>"""


def render(svg: str, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="nqrb-play-art-") as work:
        source = Path(work) / "art.svg"
        source.write_text(svg, encoding="utf-8")
        subprocess.run(
            ["sips", "-s", "format", "png", str(source), "--out", str(output)],
            check=True, stdout=subprocess.DEVNULL,
        )
        with Image.open(output) as rendered:
            rendered.convert("RGB").save(output, format="PNG", optimize=True)


def screenshot_svg(image_file: str, title: str, subtitle: str, index: int, arabic: bool) -> str:
    image = SOURCE / image_file
    image_data = b64encode(image.read_bytes()).decode("ascii")
    text_anchor = "end" if arabic else "start"
    title_x = 992 if arabic else 88
    title_font = "Geeza Pro, Arial" if arabic else "Arial, Helvetica"
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1920"
      viewBox="0 0 1080 1920">
      <defs>
        <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#fbfffc"/>
          <stop offset="0.56" stop-color="#eefaf2"/>
          <stop offset="1" stop-color="#d9f2e2"/>
        </linearGradient>
        <clipPath id="screen"><rect x="110" y="344" width="860" height="1528" rx="38"/></clipPath>
      </defs>
      <rect width="1080" height="1920" fill="url(#bg)"/>
      <circle cx="1013" cy="-70" r="350" fill="#c9edd9" opacity=".45"/>
      <circle cx="-75" cy="1760" r="320" fill="#c7ead7" opacity=".43"/>
      {brand_mark(83, 47, .62)}
      <text x="142" y="89" fill="#123a28" font-family="Arial, Helvetica"
        font-size="42" font-weight="700">Nqrb</text>
      <text x="991" y="85" text-anchor="end" fill="#408967" font-family="Arial"
        font-size="25" font-weight="700">{index:02d} / 04</text>
      <text x="{title_x}" y="205" text-anchor="{text_anchor}" fill="#103d28"
        font-family="{title_font}" font-size="62" font-weight="700">{escape(title)}</text>
      <text x="{title_x}" y="271" text-anchor="{text_anchor}" fill="#4a6858"
        font-family="{title_font}" font-size="30">{escape(subtitle)}</text>
      <rect x="121" y="359" width="860" height="1528" rx="40"
        fill="#9ccfb0" opacity=".35"/>
      <rect x="109" y="343" width="862" height="1530" rx="39"
        fill="#ffffff" stroke="#c1dfcc" stroke-width="2"/>
      <image href="data:image/png;base64,{image_data}" x="110" y="344"
        width="860" height="1528" clip-path="url(#screen)"/>
    </svg>"""


def feature_svg(arabic: bool) -> str:
    if arabic:
        title = "الصوت يقرّبنا"
        subtitle = "مكالمات تجمعك بمن يهمّك"
        title_x, anchor, font = 946, "end", "Geeza Pro, Arial"
        illustration_shift = -540
        brand_x, brand_text_x = 814, 881
    else:
        title = "Closer, by voice."
        subtitle = "Call the people in your circle."
        title_x, anchor, font = 80, "start", "Arial, Helvetica"
        illustration_shift = 60
        brand_x, brand_text_x = 81, 148
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500"
      viewBox="0 0 1024 500">
      <defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#fbfffc"/>
        <stop offset="1" stop-color="#d7f1e2"/>
      </linearGradient></defs>
      <rect width="1024" height="500" fill="url(#bg)"/>
      <circle cx="1010" cy="-105" r="330" fill="#bce6cc" opacity=".55"/>
      <circle cx="-100" cy="510" r="300" fill="#bce6cc" opacity=".40"/>
      <g transform="translate({illustration_shift} 0)">
        <circle cx="740" cy="266" r="202" fill="none" stroke="#afd8be" stroke-width="2"/>
        <circle cx="740" cy="266" r="147" fill="none" stroke="#96cda9" stroke-width="2"/>
        <circle cx="740" cy="266" r="93" fill="#127347"/>
        <path d="M713 237 C718 267 743 289 770 293 L782 279 L760 263 L749 272
          C735 266 729 257 725 245 L736 235 L721 222 Z" fill="none" stroke="#fff"
          stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>
        <circle cx="539" cy="266" r="25" fill="#127347"/>
        <circle cx="941" cy="266" r="25" fill="#127347"/>
      </g>
      {brand_mark(brand_x, 67, .72)}
      <text x="{brand_text_x}" y="114" fill="#103d28" font-family="Arial" font-size="45"
        font-weight="700">Nqrb</text>
      <text x="{title_x}" y="326" text-anchor="{anchor}" fill="#103d28"
        font-family="{font}" font-size="57" font-weight="700">{escape(title)}</text>
      <text x="{title_x}" y="380" text-anchor="{anchor}" fill="#4a6858"
        font-family="{font}" font-size="27">{escape(subtitle)}</text>
    </svg>"""


def main() -> None:
    for language, screens in SCREENS.items():
        arabic = language == "ar"
        for index, (image, title, subtitle) in enumerate(screens, start=1):
            render(screenshot_svg(image, title, subtitle, index, arabic),
                   ROOT / language / f"phone-{index:02d}.png")
        render(feature_svg(arabic), ROOT / language / "feature-graphic.png")


if __name__ == "__main__":
    main()
