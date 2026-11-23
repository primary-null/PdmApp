from pathlib import Path

from PIL import Image

SRC = Path(__file__).with_name("icon_out") / "pdm_icon_fg.png"
RES = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
HULL = (5, 5, 5, 255)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def fit(img: Image.Image, size: int) -> Image.Image:
    return img.resize((size, size), Image.Resampling.LANCZOS)


def main() -> None:
    fg = Image.open(SRC).convert("RGBA")
    hull = Image.new("RGBA", fg.size, HULL)
    full = Image.alpha_composite(hull, fg)
    for density, (launcher, foreground) in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        fit(full, launcher).save(folder / "ic_launcher.png")
        fit(full, launcher).save(folder / "ic_launcher_round.png")
        fit(fg, foreground).save(folder / "ic_launcher_foreground.png")
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/black"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""
    (anydpi / "ic_launcher.xml").write_text(xml, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(xml, encoding="utf-8")
    print("packed mipmaps into", RES)


if __name__ == "__main__":
    main()
