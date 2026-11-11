"""用无头 Chrome 把三套版式导出为多比例 PNG，并生成 JPG 版。

用法： python promo/cover/export.py
输出： cover-{1-brand|2-dark|3-engine}[-16x9|-4x3].{png,jpg}

比例：
  16:10  1146x717   B 站标准封面（无后缀，原始交付物）
  16:9   1920x1080  通用视频平台 / 播放器缩略图
  4:3    1200x900   方屏展示位 / 部分社区
所有尺寸均 < 5MB；左下、右下角已避开分类标签与时长角标。
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
HTML_RATIOS = HERE / "cover-ratios.html"

VARIANTS = [
    ("1", "cover-1-brand"),
    ("2", "cover-2-dark"),
    ("3", "cover-3-engine"),
]

RATIOS = [
    ("16x10", 1146, 717, ""),
    ("16x9", 1920, 1080, "-16x9"),
    ("4x3", 1200, 900, "-4x3"),
]


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
    if not HTML.exists() or not HTML_RATIOS.exists():
        raise SystemExit("请先运行 build.py 生成 cover.html 与 cover-ratios.html")
    chrome = find_chrome()
    ffmpeg = find_ffmpeg()

    for ratio, width, height, suffix in RATIOS:
        page = HTML if ratio == "16x10" else HTML_RATIOS
        url = page.as_uri()
        query = f"?export=1&v={{v}}" + (f"&ratio={ratio}" if ratio != "16x10" else "")
        for v, name in VARIANTS:
            png = HERE / f"{name}{suffix}.png"
            with tempfile.TemporaryDirectory(prefix="skipad-cover-") as profile:
                cmd = [
                    str(chrome),
                    "--headless=new",
                    "--disable-gpu",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--hide-scrollbars",
                    "--force-device-scale-factor=1",
                    f"--window-size={width},{height}",
                    "--virtual-time-budget=5000",
                    f"--user-data-dir={profile}",
                    f"--screenshot={png}",
                    url + query.format(v=v),
                ]
                r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
                if not png.exists():
                    raise SystemExit(f"{ratio} 变体 {v} 截图失败:\n{r.stderr[-800:]}")
            w, h = png_size(png)
            ok = (w, h) == (width, height) and png.stat().st_size < 5 * 1024 * 1024
            print(f"[{ratio:5s} / {v}] {png.name}: {w}x{h}, {png.stat().st_size / 1024:.0f} KB {'OK' if ok else 'FAIL'}")
            if not ok:
                raise SystemExit(f"{png.name} 尺寸或体积不符")
            if ffmpeg:
                jpg = HERE / f"{name}{suffix}.jpg"
                subprocess.run(
                    [str(ffmpeg), "-y", "-hide_banner", "-loglevel", "error",
                     "-i", str(png), "-q:v", "2", str(jpg)],
                    check=True, capture_output=True,
                )


if __name__ == "__main__":
    main()
