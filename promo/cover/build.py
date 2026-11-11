"""把 _assets 里的真机截图内嵌进封面模板，生成自包含的封面页。

用法： python promo/cover/build.py
产出：
  cover.html          原版 1146x717（16:10，B 站标准）三套版式
  cover-ratios.html   同一套设计的比例自适应版，支持 16:10 / 16:9 / 4:3
                      预览页顶部含视频标题与封面方案命名表

封面文案、命名方案直接改 template.html / 本文件的 NAMING_HTML，重跑即可。
"""
from pathlib import Path
import base64
import re
from html import escape

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]

SHOTS = {
    "__IMG_DASH__": ROOT / "_assets" / "image-20261024221632265.png",
    "__IMG_SKIP__": ROOT / "_assets" / "image-20261024221742201.png",
}

# ---------- 比例自适应：把模板里所有 px 改为按 --u 缩放，再追加覆盖规则 ----------
# --u 为整版缩放系数；--dy 为 4:3 下补足的垂直居中偏移（画布变高但内容按宽缩放）
RATIO_CSS = """
/* ---------- 多比例支持（由 build.py 注入，勿手写进 template.html） ---------- */
:root{--W:1146px;--H:717px;--u:1;--dy:0px}
body[data-ratio="16x10"]{--W:1146px;--H:717px;--u:1;--dy:0px}
body[data-ratio="16x9"]{--W:1920px;--H:1080px;--u:1.5;--dy:0px}
body[data-ratio="4x3"]{--W:1200px;--H:900px;--u:1.045;--dy:64px}
.wrap{width:var(--W)}
.cover{width:var(--W);height:var(--H)}
/* 主要内容随 --dy 垂直居中；顶栏/页脚始终贴边 */
.v1 .left{top:calc(140px * var(--u) + var(--dy))}
.v1 .right{top:calc(96px * var(--u) + var(--dy))}
.v2 .left{top:calc(132px * var(--u) + var(--dy))}
.v2 .badge{top:calc(100px * var(--u) + var(--dy))}
/* 双手机改用 right 定位，画布变宽时才不会被甩在左侧 */
.v2 .p1{left:auto;right:calc(244px * var(--u));top:calc(146px * var(--u) + var(--dy))}
.v2 .p2{left:auto;right:calc(56px * var(--u));top:calc(210px * var(--u) + var(--dy))}
.v3 .left{top:calc(142px * var(--u) + var(--dy))}
.v3 .right{top:calc(92px * var(--u) + var(--dy))}
/* 预览页：命名方案 + 比例切换 */
#naming{max-width:1146px;margin:0 auto 46px;font-family:var(--sans);color:#15171A}
#naming h2{font-size:13px;letter-spacing:.22em;color:#12864B;font-family:var(--mono);font-weight:600;margin:0 0 16px}
#naming .card{border-top:1px solid #E4E6E3;border-bottom:1px solid #E4E6E3;padding:22px 2px 24px;margin-bottom:40px}
#naming .rec{font-size:24px;font-weight:700;letter-spacing:.01em;line-height:1.5}
#naming .rec small{display:block;font-size:11px;letter-spacing:.2em;color:#9BA0A6;font-family:var(--mono);font-weight:400;margin-bottom:10px}
#naming ul{list-style:none;margin-top:22px;border-top:1px dashed #E4E6E3;padding-top:18px}
#naming li{font-size:15px;color:#5F646B;line-height:2.1}
#naming li b{color:#12864B;font-family:var(--mono);font-size:12px;margin-right:12px;letter-spacing:.06em}
#naming table{width:100%;border-collapse:collapse;font-size:14px}
#naming th{text-align:left;font-size:11px;letter-spacing:.16em;color:#9BA0A6;font-weight:500;padding:0 14px 10px 0}
#naming td{border-top:1px solid #E4E6E3;padding:14px 14px 14px 0;color:#5F646B;vertical-align:top}
#naming td b{color:#15171A;font-size:17px;font-weight:700;display:block;margin-bottom:4px}
#naming code{font-family:var(--mono);font-size:12px;color:#12864B}
#naming .sec{margin:0 0 16px;font-size:13px;letter-spacing:.22em;color:#12864B;font-family:var(--mono);font-weight:600}
#naming .sec.first{margin-top:44px}
#naming pre{white-space:pre-wrap;word-break:break-word;font-family:var(--sans);font-size:14px;line-height:1.95;color:#3C4248;background:#fff;border:1px solid #E4E6E3;border-radius:6px;padding:24px 26px;margin:0 0 14px}
#naming .copy{border:1px solid #12864B;background:#12864B;color:#fff;border-radius:999px;padding:9px 22px;font-family:var(--mono);font-size:12px;letter-spacing:.1em;cursor:pointer}
#naming .copy.done{background:#fff;color:#12864B}
#naming .meta{font-size:14px;color:#5F646B;line-height:2.1;border-top:1px solid #E4E6E3;margin-top:34px;padding-top:18px}
#naming .meta b{color:#15171A;font-weight:600}
#naming .short{font-size:15px;color:#3C4248;line-height:1.9;background:#fff;border-left:3px solid #12864B;padding:16px 20px;margin:0}
#switch{max-width:1146px;margin:0 auto 26px;display:flex;align-items:center;gap:10px;font-family:var(--mono);font-size:12px;letter-spacing:.14em;color:#9BA0A6}
#switch button{border:1px solid #E4E6E3;background:#fff;color:#5F646B;border-radius:999px;padding:8px 18px;font:inherit;cursor:pointer}
#switch button.on{background:#12864B;border-color:#12864B;color:#fff}
body.export #naming,body.export #switch{display:none}
"""

NAMING_HTML = """
<div id="naming">
  <h2>VIDEO TITLE / 视频标题命名方案</h2>
  <div class="card">
    <p class="rec"><small>RECOMMENDED · 推荐投稿标题</small>不用 Root 也能去广告？我写了个双引擎净广告 App，开源免费</p>
    <ul>
      <li><b>备选 01</b>40 秒看懂：一个免 Root 的 Android 去广告 App 是怎么工作的</li>
      <li><b>备选 02</b>27.8 万条规则 + 204 项单测，纯 Kotlin 写的净广告 SkipAd 开源了</li>
      <li><b>备选 03</b>开屏广告自动跳过、广告域名直接失效｜净广告 SkipAd 开源发布</li>
      <li><b>备选 04</b>Android 去广告不靠 Root：DNS + 无障碍双引擎实现思路</li>
    </ul>
  </div>
  <h2>COVER NAMES / 三套封面方案名</h2>
  <table>
    <tr><th>方案名</th><th>定位</th><th>封面主标题</th><th>文件前缀</th></tr>
    <tr><td><b>净白</b></td><td>品牌一致版，与 App / 宣传片同一套极简白视觉</td><td>净广告</td><td><code>cover-1-brand</code></td></tr>
    <tr><td><b>暗夜绿</b></td><td>信息流版，深色高对比，缩略图最醒目（推荐）</td><td>不用 Root / 也能去广告</td><td><code>cover-2-dark</code></td></tr>
    <tr><td><b>双擎</b></td><td>技术向版，双引擎原理图 + 四栏数据</td><td>双引擎 / 干掉广告</td><td><code>cover-3-engine</code></td></tr>
  </table>

  <h2 class="sec first">VIDEO DESCRIPTION / 投稿简介</h2>
  <pre id="desc">__DESCRIPTION__</pre>
  <button class="copy" id="btn-copy">复制简介</button>
  <div class="meta">
    <b>推荐分区</b>：科技 → 软件应用（或 计算机技术）<br>
    <b>推荐标签</b>：Android / 去广告 / 开源 / Kotlin / SkipAd / 净广告 / 免Root / DNS / 无障碍 / Jetpack Compose<br>
    <b>建议置顶评论</b>：安装包在 Releases 里，源码与构建说明都在 GitHub；遇到拦不住的广告，欢迎带 App 名称和场景留言。
  </div>

  <h2 class="sec first">SHORT BLURB / 短版项目简介</h2>
  <p class="short">净广告 SkipAd 是一款免 Root 的 Android 去广告应用：用 DNS 拦截广告域名，用无障碍自动跳过开屏广告。纯 Kotlin 开源实现，1.5MB 安装包，GPL-3.0 完全免费。</p>
</div>
<div id="switch"><span>预览比例</span>
  <button data-r="16x10">16:10 · 1146×717</button>
  <button data-r="16x9">16:9 · 1920×1080</button>
  <button data-r="4x3">4:3 · 1200×900</button>
</div>
"""

RATIO_SCRIPT = """<script>
  var q = new URLSearchParams(location.search);
  var ratio = q.get('ratio') || '16x10';
  document.body.setAttribute('data-ratio', ratio);
  if (q.get('export') === '1') {
    document.body.classList.add('export');
    var v = q.get('v') || '1';
    Array.prototype.forEach.call(document.querySelectorAll('.wrap'), function (w) {
      w.classList.toggle('active', w.getAttribute('data-v') === v);
    });
  }
  Array.prototype.forEach.call(document.querySelectorAll('#switch button'), function (b) {
    if (b.getAttribute('data-r') === ratio) b.classList.add('on');
    b.addEventListener('click', function () {
      document.body.setAttribute('data-ratio', b.getAttribute('data-r'));
      Array.prototype.forEach.call(document.querySelectorAll('#switch button'), function (x) {
        x.classList.remove('on');
      });
      b.classList.add('on');
    });
  });
  var copyBtn = document.getElementById('btn-copy');
  if (copyBtn) copyBtn.addEventListener('click', function () {
    var txt = document.getElementById('desc').textContent;
    function done() {
      copyBtn.textContent = '已复制';
      copyBtn.classList.add('done');
      setTimeout(function () {
        copyBtn.textContent = '复制简介';
        copyBtn.classList.remove('done');
      }, 1800);
    }
    function fallback() {
      var ta = document.createElement('textarea');
      ta.value = txt;
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); done(); } catch (e) {}
      document.body.removeChild(ta);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(txt).then(done, fallback);
    } else { fallback(); }
  });
</script>"""


def scale_css(html: str) -> str:
    """把 <style> 内所有 px 长度改为按 --u 缩放，并注入比例规则。"""
    m = re.search(r"(<style>)([\s\S]*?)(</style>)", html)
    if not m:
        raise SystemExit("template.html 缺少 <style> 块")
    style = re.sub(
        r"(-?\d+(?:\.\d+)?)px",
        lambda x: "calc(%spx * var(--u))" % x.group(1),
        m.group(2),
    )
    html = html[: m.start()] + m.group(1) + style + RATIO_CSS + m.group(3) + html[m.end() :]
    html = html.replace("<body>", "<body>\n" + NAMING_HTML, 1)
    html, n = re.subn(r"<script>[\s\S]*?</script>", lambda _: RATIO_SCRIPT, html, count=1)
    if n != 1:
        raise SystemExit("template.html 脚本块替换失败")
    return html


def main():
    html = (HERE / "template.html").read_text(encoding="utf-8")
    for token, path in SHOTS.items():
        if not path.exists():
            raise SystemExit(f"缺少素材: {path}")
        data = "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")
        html = html.replace(token, data)

    out = HERE / "cover.html"
    out.write_text(html, encoding="utf-8")
    print(f"生成 {out.name}  ({out.stat().st_size / 1024:.0f} KB)")

    desc = (HERE / "bilibili-description.txt").read_text(encoding="utf-8").rstrip("\n")
    ratios = scale_css((HERE / "template.html").read_text(encoding="utf-8"))
    for token, path in SHOTS.items():
        data = "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")
        ratios = ratios.replace(token, data)
    ratios = ratios.replace("__DESCRIPTION__", escape(desc))
    if "__DESCRIPTION__" in ratios or "__IMG_" in ratios:
        raise SystemExit("占位符未全部替换")
    out2 = HERE / "cover-ratios.html"
    out2.write_text(ratios, encoding="utf-8")
    print(f"生成 {out2.name}  ({out2.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
