# -*- coding: utf-8 -*-
"""Tokenize colors + add light theme."""
import io, re

P = r"D:\Codes\github_by_self\Barryyue\network-control\site\index.html"
s = io.open(P, encoding="utf-8").read()

# ── 1. new :root ────────────────────────────────────────────────
OLD_ROOT = """:root{
  --ink:#08090B;
  --ink-2:#0C0E11;
  --ink-3:#11141A;
  --line:rgba(255,255,255,.085);
  --line-2:rgba(255,255,255,.16);
  --fg:#F2F3F4;
  --fg-2:#9AA1AB;
  --fg-3:#666D77;
  --allow:#C9F45A;
  --deny:#FF6A55;
  --r:14px;"""

NEW_ROOT = """:root{
  color-scheme:dark;
  --ink-rgb:255 255 255;
  --bg:#08090B;
  --bg-2:#0C0E11;
  --bg-3:#11141A;
  --fg:#F2F3F4;
  --fg-2:#9AA1AB;
  --fg-3:#666D77;
  --brand-dim:#8B9199;
  --fg-mono:#7E858F;
  --fg-mono-strong:#C8CDD4;
  --icon-bg:#1D222B;
  --icon-fg:#B9C4D2;
  --info-rgb:143 180 232;
  --line:rgb(var(--ink-rgb) / .085);
  --line-2:rgb(var(--ink-rgb) / .16);
  --acc-rgb:201 244 90;
  --acc-solid:#C9F45A;
  --acc-on:#0A0B0D;
  --acc:rgb(var(--acc-rgb));
  --deny-rgb:255 106 85;
  --deny:rgb(var(--deny-rgb));
  --allow:var(--acc-solid);
  --bar-bg:rgba(8,9,11,.72);
  --bar-bg-solid:rgba(8,9,11,.9);
  --overlay:rgba(0,0,0,.24);
  --prompt-bg:linear-gradient(180deg,#14171D 0%,#0B0D10 62%,#0A0C0E 100%);
  --prompt-shadow:0 40px 80px -40px rgba(0,0,0,.95),0 0 0 1px rgba(0,0,0,.4),0 1px 0 rgba(255,255,255,.05) inset;
  --r:14px;"""

assert OLD_ROOT in s, "root block not found"
s = s.replace(OLD_ROOT, NEW_ROOT, 1)

# ── 2. light theme block appended after :root ───────────────────
LIGHT = """
[data-theme="light"]{
  color-scheme:light;
  --ink-rgb:16 18 22;
  --bg:#FAFAF8;
  --bg-2:#FFFFFF;
  --bg-3:#F1F2F0;
  --fg:#101214;
  --fg-2:#565C64;
  --fg-3:#878D96;
  --brand-dim:#6E747C;
  --fg-mono:#5B616A;
  --fg-mono-strong:#23262B;
  --icon-bg:#ECEEF0;
  --icon-fg:#4B5158;
  --info-rgb:47 107 214;
  --acc-rgb:74 102 0;
  --acc-solid:#C9F45A;
  --acc-on:#12160A;
  --deny-rgb:197 45 30;
  --bar-bg:rgba(250,250,248,.78);
  --bar-bg-solid:rgba(250,250,248,.94);
  --overlay:rgba(16,18,22,.035);
  --prompt-bg:linear-gradient(180deg,#FFFFFF 0%,#F9FAFA 62%,#F5F6F6 100%);
  --prompt-shadow:0 30px 60px -34px rgba(16,18,22,.28),0 0 0 1px rgba(16,18,22,.08),0 1px 0 rgba(255,255,255,.9) inset;
}
"""
marker = "*,*::before,*::after{box-sizing:border-box}"
assert marker in s
s = s.replace(marker, LIGHT.strip() + "\n\n" + marker, 1)

# ── 3. literal → token ──────────────────────────────────────────
pairs = [
    # neutral ink alpha
    ("rgba(255,255,255,.085)", "rgb(var(--ink-rgb) / .085)"),
    ("rgba(255,255,255,.16)",  "rgb(var(--ink-rgb) / .16)"),
    ("rgba(255,255,255,.34)",  "rgb(var(--ink-rgb) / .34)"),
    ("rgba(255,255,255,.055)", "rgb(var(--ink-rgb) / .055)"),
    ("rgba(255,255,255,.05)",  "rgb(var(--ink-rgb) / .05)"),
    ("rgba(255,255,255,.04)",  "rgb(var(--ink-rgb) / .04)"),
    ("rgba(255,255,255,.03)",  "rgb(var(--ink-rgb) / .03)"),
    ("rgba(255,255,255,.022)", "rgb(var(--ink-rgb) / .022)"),
    ("rgba(255,255,255,.02)",  "rgb(var(--ink-rgb) / .02)"),
    ("rgba(255,255,255,.018)", "rgb(var(--ink-rgb) / .018)"),
    ("rgba(255,255,255,.12)",  "rgb(var(--ink-rgb) / .12)"),
    # accent alpha
    ("rgba(201,244,90,.9)",  "rgb(var(--acc-rgb) / .9)"),
    ("rgba(201,244,90,.8)",  "rgb(var(--acc-rgb) / .8)"),
    ("rgba(201,244,90,.7)",  "rgb(var(--acc-rgb) / .7)"),
    ("rgba(201,244,90,.5)",  "rgb(var(--acc-rgb) / .5)"),
    ("rgba(201,244,90,.25)", "rgb(var(--acc-rgb) / .25)"),
    ("rgba(201,244,90,.15)", "rgb(var(--acc-rgb) / .15)"),
    ("rgba(201,244,90,.13)", "rgb(var(--acc-rgb) / .13)"),
    ("rgba(201,244,90,.12)", "rgb(var(--acc-rgb) / .12)"),
    ("rgba(201,244,90,.11)", "rgb(var(--acc-rgb) / .11)"),
    ("rgba(201,244,90,.10)", "rgb(var(--acc-rgb) / .10)"),
    ("rgba(201,244,90,.1)",  "rgb(var(--acc-rgb) / .1)"),
    ("rgba(201,244,90,.07)", "rgb(var(--acc-rgb) / .07)"),
    ("rgba(201,244,90,.05)", "rgb(var(--acc-rgb) / .05)"),
    # deny / info alpha
    ("rgba(255,106,85,.5)",   "rgb(var(--deny-rgb) / .5)"),
    ("rgba(255,106,85,.07)",  "rgb(var(--deny-rgb) / .07)"),
    ("rgba(143,180,232,.32)", "rgb(var(--info-rgb) / .32)"),
    # named colors
    ("--ink:#08090B;", "x"),  # handled above; keep safe
    ("background:var(--ink)}", "background:var(--bg)}"),
    ("background:#1D222B;border:1px solid var(--line-2);font-family:var(--mono);font-size:11px;color:#B9C4D2;",
     "background:var(--icon-bg);border:1px solid var(--line-2);font-family:var(--mono);font-size:11px;color:var(--icon-fg);"),
    ("background:rgba(8,9,11,.72);backdrop-filter:blur(14px) saturate(140%);",
     "background:var(--bar-bg);backdrop-filter:blur(14px) saturate(140%);"),
    (".topbar.scrolled{border-bottom-color:var(--line);background:rgba(8,9,11,.9)}",
     ".topbar.scrolled{border-bottom-color:var(--line);background:var(--bar-bg-solid)}"),
    ("background:rgba(0,0,0,.24);position:relative}", "background:var(--overlay);position:relative}"),
    ("background:rgba(0,0,0,.24);\n  border-top:1px solid transparent;margin-top:-1px;",
     "background:var(--overlay);\n  border-top:1px solid transparent;margin-top:-1px;"),
    ("background:linear-gradient(180deg,#14171D 0%,#0B0D10 62%,#0A0C0E 100%);",
     "background:var(--prompt-bg);"),
    ("box-shadow:0 40px 80px -40px rgba(0,0,0,.95),0 0 0 1px rgba(0,0,0,.4),0 1px 0 rgba(255,255,255,.05) inset;",
     "box-shadow:var(--prompt-shadow);"),
    ("--brand-dim", "--brand-dim"),
    (".brand em{font-style:normal;color:#8B9199}", ".brand em{font-style:normal;color:var(--brand-dim)}"),
    ("font-family:var(--mono);font-size:10.5px;color:#7E858F;line-height:1.75;",
     "font-family:var(--mono);font-size:10.5px;color:var(--fg-mono);line-height:1.75;"),
    (".rule-preview b{color:#C8CDD4;font-weight:400}", ".rule-preview b{color:var(--fg-mono-strong);font-weight:400}"),
    ("td .p{color:#8FB4E8}", "td .p{color:rgb(var(--info-rgb))}"),
    (".tag.p1{color:#8FB4E8;border:1px solid rgb(var(--info-rgb) / .32)}",
     ".tag.p1{color:rgb(var(--info-rgb));border:1px solid rgb(var(--info-rgb) / .32)}"),
    ("background:var(--allow);color:#0A0B0D;", "background:var(--acc-solid);color:var(--acc-on);"),
    ("background:var(--allow);color:#0A0B0D}", "background:var(--acc-solid);color:var(--acc-on)}"),
    ("::selection{background:var(--allow);color:#0A0B0D}", "::selection{background:var(--acc-solid);color:var(--acc-on)}"),
    (".v.allow{background:var(--allow);color:#0A0B0D}", ".v.allow{background:var(--acc-solid);color:var(--acc-on)}"),
    ("--allow:#C9F45A;", "--allow:var(--acc-solid);"),
]
for old, new in pairs:
    if old == new:
        continue
    s = s.replace(old, new)

io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("done")
left = re.findall(r"#[0-9A-Fa-f]{6}\b|rgba?\(\s*\d+,\s*\d+,\s*\d+", s.split("<style>")[1].split("</style>")[0])
import collections
print(collections.Counter(left))
