# -*- coding: utf-8 -*-
"""Generate preview copies for each language x theme combo."""
import io, os, sys

SITE = r"D:\Codes\github_by_self\Barryyue\network-control\site"
src = io.open(os.path.join(SITE, "index.html"), encoding="utf-8").read()

def make(lang, theme, name):
    s = src
    # pre-paint script
    s = s.replace('var t = localStorage.getItem("nc.theme") || "auto";', 'var t = "%s";' % theme)
    s = s.replace('var l = localStorage.getItem("nc.lang") || "zh-Hans";', 'var l = "%s";' % lang)
    # runtime theme init
    s = s.replace('try { return localStorage.getItem("nc.theme") || "auto"; } catch (e) { return "auto"; }',
                  'try { return "%s"; } catch (e) { return "%s"; }' % (theme, theme))
    # runtime lang init
    s = s.replace('var l = localStorage.getItem("nc.lang");', 'var l = "%s";' % lang)
    p = os.path.join(SITE, name)
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)
    return p

for lang in ("zh-Hans", "zh-Hant", "en"):
    for theme in ("dark", "light"):
        make(lang, theme, "_t_%s_%s.html" % (lang, theme))
print("generated")
