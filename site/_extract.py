# -*- coding: utf-8 -*-
"""Extract translatable visible text from site/index.html into a JSON key list."""
import io, json, re
from lxml import html as LH

SRC = r"D:\Codes\github_by_self\Barryyue\network-control\site\index.html"
OUT = r"D:\Codes\github_by_self\Barryyue\network-control\site\_i18n_keys.json"

s = io.open(SRC, encoding="utf-8").read()
body = re.sub(r"<script\b.*?</script>", "", s, flags=re.S)
body = re.sub(r"<style\b.*?</style>", "", body, flags=re.S)
body = re.sub(r"<head\b.*?</head>", "", body, flags=re.S)

tree = LH.fromstring(body)
strings, seen = [], set()

def add(t):
    core = t.strip()
    if not core or core in seen:
        return
    if not re.search(r"[A-Za-z\u4e00-\u9fff]", core):
        return
    seen.add(core)
    strings.append(core)

for el in tree.iter():
    if not isinstance(el.tag, str):
        continue
    if el.text:
        add(el.text)
    if el.tail:
        add(el.tail)
    for attr in ("aria-label", "title", "placeholder", "alt"):
        v = el.get(attr)
        if v:
            add(v)

io.open(OUT, "w", encoding="utf-8", newline="\n").write(
    json.dumps(strings, ensure_ascii=False, indent=1))
print("unique strings:", len(strings))
print("total chars:", sum(len(x) for x in strings))
chars = sorted({c for x in strings for c in x if "\u4e00" <= c <= "\u9fff"})
print("distinct CJK chars:", len(chars))
print("".join(chars))
