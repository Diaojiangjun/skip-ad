"""把 _assets 里的真机截图内嵌进封面模板，生成自包含的 cover.html。

用法： python promo/cover/build.py
封面文案直接改 template.html，改完重跑本脚本即可。
"""
from pathlib import Path
import base64

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]

SHOTS = {
    "__IMG_DASH__": ROOT / "_assets" / "image-20261024221632265.png",
    "__IMG_SKIP__": ROOT / "_assets" / "image-20261024221742201.png",
}


def main():
    html = (HERE / "template.html").read_text(encoding="utf-8")
    for token, path in SHOTS.items():
        if not path.exists():
            raise SystemExit(f"缺少素材: {path}")
        data = "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")
        html = html.replace(token, data)
    out = HERE / "cover.html"
    out.write_text(html, encoding="utf-8")
    print(f"生成 {out}  ({out.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
