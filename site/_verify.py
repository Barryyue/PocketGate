# -*- coding: utf-8 -*-
"""Assert rendered text per language. Prints ASCII-only results."""
import io, os, re

TMP = os.environ["TEMP"]
SIMPLIFIED_ONLY = set("决线盘网设应连时护权过关对导内个将实现产检记许议统断变览组点显图标准级条项优选择号码启动执监视侦协错误读写储库杂么几为与从会后们处务单并况减扩树测环画节范筛签类终经罚义联职脑举旧艺虫补装见规认说调请诺谢让车较轻载达迁边邮释针钟铁长门闭间阅阵队随难电静页顶顺须预领频题额愿风飞验体据录够只仅删传双亿伙")

def body_text(name):
    s = io.open(os.path.join(TMP, name + ".dom.html"), encoding="utf-8", errors="replace").read()
    body = s.split("<body", 1)[-1]
    body = re.sub(r"(?s)<script.*?</script>", "", body)
    body = re.sub(r"(?s)<style.*?</style>", "", body)
    body = re.sub(r"(?s)<svg.*?</svg>", "", body)
    return re.sub(r"<[^>]+>", " ", body)

for name in ("_t_en_dark", "_t_zh-Hant_light", "_t_zh-Hans_dark"):
    t = body_text(name)
    cjk = [c for c in t if "\u4e00" <= c <= "\u9fff"]
    simp = sorted({c for c in t if c in SIMPLIFIED_ONLY})
    print("==", name)
    print("   text chars:", len(t.strip()))
    print("   CJK chars :", len(cjk))
    print("   simplified-only distinct:", len(simp))
    if name == "_t_en_dark":
        sample = re.findall(r"[A-Za-z][A-Za-z' -]{18,60}", t)
        print("   sample:", sample[0] if sample else "-")
