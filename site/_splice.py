# -*- coding: utf-8 -*-
"""Splice i18n container + new runtime into index.html, add pre-paint script and CSS."""
import io, re

P = r"D:\Codes\github_by_self\Barryyue\network-control\site\index.html"
RT = r"D:\Codes\github_by_self\Barryyue\network-control\site\_runtime.js"

s = io.open(P, encoding="utf-8").read()
runtime = io.open(RT, encoding="utf-8").read()

# ── 1. TTL 段按钮的 data 值改成 key ─────────────────────────────
ttl_map = [("本次", "now"), ("直到退出", "exit"), ("5 分钟", "5m"), ("15 分钟", "15m"),
           ("1 小时", "1h"), ("直到重启", "reboot"), ("永久", "forever")]
for zh, key in ttl_map:
    s = s.replace('data-ttl="%s"' % zh, 'data-ttl="%s"' % key)

# ── 2. 用新运行时替换旧的 <script> 块 ──────────────────────────
m = list(re.finditer(r"<script>(.*?)</script>\s*(?=</body>)", s, re.S))
assert len(m) == 1, "expected exactly one main script, got %d" % len(m)
new_block = ('<script type="application/json" id="i18n-data">{}</script>\n\n'
             '<script>\n' + runtime.rstrip() + '\n</script>\n')
s = s[:m[0].start()] + new_block + s[m[0].end():]

# ── 3. 首屏前置脚本：避免主题/语言闪烁 ─────────────────────────
anchor = '<meta name="theme-color" content="#08090B">'
assert anchor in s
pre = anchor + """
<script>
(function () {
  try {
    var t = localStorage.getItem("nc.theme") || "auto";
    var light = window.matchMedia("(prefers-color-scheme: light)").matches;
    document.documentElement.setAttribute("data-theme", t === "auto" ? (light ? "light" : "dark") : t);
    var l = localStorage.getItem("nc.lang") || "zh-Hans";
    document.documentElement.lang = l === "en" ? "en" : (l === "zh-Hant" ? "zh-Hant" : "zh-Hans");
    if (l !== "zh-Hans") document.documentElement.classList.add("i18n-pending");
  } catch (e) { }
})();
</script>"""
s = s.replace(anchor, pre, 1)

# ── 4. i18n-pending 期间隐藏正文 ───────────────────────────────
s = s.replace("html{scroll-behavior:smooth}",
              "html{scroll-behavior:smooth}\nhtml.i18n-pending body{opacity:0}", 1)

io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("spliced; bytes:", len(s.encode("utf-8")))
