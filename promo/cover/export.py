"""用无头 Chrome 把 cover.html 的三套版式导出为 1146x717 PNG，并生成 JPG 版。

用法： python promo/cover/export.py
输出： cover-1-brand.png / cover-2-dark.png / cover-3-engine.png 及同名 .jpg
B 站要求：1146x717（16:10）、PNG 或 JPG、< 5MB。
"""
from pathlib import Path
import os
import shutil
import struct
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
HTML = HERE / "cover.html"

VARIANTS = [
    ("1", "cover-1-brand"),
    ("2", "cover-2-dark"),
    ("3", "cover-3-engine"),
]

WIDTH, HEIGHT = 1146, 717


def find_chrome():
    candidates = [
        Path(os.environ.get("PROGRAMFILES", r"C:\Program Files")) / "Google/Chrome/Application/chrome.exe",
        Path(os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)")) / "Microsoft/Edge/Application/msedge.exe",
    ]
    for p in candidates:
        if p.exists():
            return p
    raise SystemExit("找不到 Chrome / Edge，无法导出封面")


def find_ffmpeg():
    hits = sorted((ROOT / ".workbuddy/tmp/anim/pydeps").glob("**/ffmpeg*.exe"))
    if hits:
        return hits[0]
    return shutil.which("ffmpeg")


def png_size(path):
    d = path.read_bytes()[:24]
    return struct.unpack(">II", d[16:24])


def main():
    if not HTML.exists():
        raise SystemExit("请先运行 build.py 生成 cover.html")
    chrome = find_chrome()
    ffmpeg = find_ffmpeg()
    url = HTML.as_uri()

    for v, name in VARIANTS:
        png = HERE / f"{name}.png"
        with tempfile.TemporaryDirectory(prefix="skipad-cover-") as profile:
            cmd = [
                str(chrome),
                "--headless=new",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                "--hide-scrollbars",
                "--force-device-scale-factor=1",
                f"--window-size={WIDTH},{HEIGHT}",
                "--virtual-time-budget=5000",
                f"--user-data-dir={profile}",
                f"--screenshot={png}",
                f"{url}?export=1&v={v}",
            ]
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
            if not png.exists():
                raise SystemExit(f"变体 {v} 截图失败:\n{r.stderr[-800:]}")
        w, h = png_size(png)
        print(f"[{v}] {png.name}: {w}x{h}, {png.stat().st_size / 1024:.0f} KB")
        if (w, h) != (WIDTH, HEIGHT):
            raise SystemExit(f"变体 {v} 尺寸不符：{w}x{h}，期望 {WIDTH}x{HEIGHT}")
        if ffmpeg:
            jpg = HERE / f"{name}.jpg"
            subprocess.run(
                [str(ffmpeg), "-y", "-hide_banner", "-loglevel", "error",
                 "-i", str(png), "-q:v", "2", str(jpg)],
                check=True, capture_output=True,
            )
            print(f"    -> {jpg.name}: {jpg.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    main()
