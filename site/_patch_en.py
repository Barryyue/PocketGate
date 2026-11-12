# -*- coding: utf-8 -*-
"""Patch English spacing where Chinese text nodes are split by inline tags (prefix match)."""
import io, json, os

SITE = r"D:\Codes\github_by_self\Barryyue\network-control\site"
P = os.path.join(SITE, "_i18n_en.json")
d = json.load(io.open(P, encoding="utf-8-sig"))

lead_prefix = [
    "同一应用的多个待决连接",
    "每个配置文件有自己的模式",
    "macOS 版需要 Apple",
    "服务连续崩溃时自动降级",
    "规则引擎与平台无关",
    "预览版面向 Windows 10",
]

hit = 0
for k in list(d.keys()):
    for pre in lead_prefix:
        if k.startswith(pre):
            d[k] = " " + d[k].lstrip()
            hit += 1
            print("patched:", pre, "->", d[k][:40])
            break

json.dump(d, io.open(P, "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=1)
io.open(P, "a", encoding="utf-8", newline="\n").write("\n")
print("patched", hit)
