# -*- coding: utf-8 -*-
"""Merge locale JSON files into the inline <script id="i18n-data"> block."""
import io, json, os, re, sys

SITE = r"D:\Codes\github_by_self\Barryyue\network-control\site"
P = os.path.join(SITE, "index.html")

def load(name):
    p = os.path.join(SITE, name)
    if not os.path.exists(p):
        return None
    with io.open(p, encoding="utf-8-sig") as f:
        return json.load(f)

en = load("_i18n_en.json")
hant = load("_i18n_zh_hant.json")
keys = load("_i18n_keys.json")

data = {}
if hant:
    data["zh-Hant"] = hant
if en:
    data["en"] = en

# 校验：键集合与页面原文一致
if keys:
    for lang, d in data.items():
        missing = [k for k in keys if k not in d]
        extra = [k for k in d if k not in set(keys)]
        print("%s: %d keys, missing=%d, extra=%d, empty=%d" %
              (lang, len(d), len(missing), len(extra), sum(1 for v in d.values() if not str(v).strip())))
        for k in missing[:8]:
            print("   missing:", k[:60])
        for k in extra[:8]:
            print("   extra  :", k[:60])

payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
s = io.open(P, encoding="utf-8").read()
new, n = re.subn(r'(<script type="application/json" id="i18n-data">)(.*?)(</script>)',
                 lambda m: m.group(1) + payload + m.group(3), s, count=1, flags=re.S)
assert n == 1, "i18n-data block not found"
io.open(P, "w", encoding="utf-8", newline="\n").write(new)
print("merged languages:", list(data.keys()), "| payload bytes:", len(payload.encode("utf-8")))
