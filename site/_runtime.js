(function () {
  "use strict";

  var root = document.documentElement;
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ═══ 0. 文案字典 ══════════════════════════════════════════════ */

  var DYN = {
    "zh-Hans": {
      "theme.auto": "跟随系统", "theme.light": "浅色", "theme.dark": "深色",
      "timer.wait": "{n} 秒后自动拒绝", "timer.paused": "已暂停 · {n} 秒",
      "status.waiting": "等待判决 · 倒计时结束按默认动作处理",
      "status.timeout": "已拒绝 · 超时（默认动作）· 已写入日志",
      "status.allowed": "已允许 · 规则已写入（{ttl} · {cond}）",
      "status.denied": "已拒绝 · 依据「用户判决」写入日志",
      "rule.tpl": "<b>将生成规则</b> · 应用 Code.exe（签名者 Microsoft Corporation） · 条件 {cond} · 动作 <b>允许</b> · 时效 <b>{ttl}</b>",
      "scope.app": "任意连接", "scope.domain": "域名 {v}", "scope.ip": "IP {v}",
      "scope.ipport": "IP + 端口 {v}", "scope.port": "端口 {v}",
      "ttl.now": "本次", "ttl.exit": "直到退出", "ttl.5m": "5 分钟", "ttl.15m": "15 分钟",
      "ttl.1h": "1 小时", "ttl.reboot": "直到重启", "ttl.forever": "永久",
      "mode.ask.on": "按规则判决", "mode.ask.no": "弹窗询问", "mode.ask.use": "日常使用",
      "mode.allowlist.on": "按规则判决", "mode.allowlist.no": "拒绝（可选通知）", "mode.allowlist.use": "严格控制、勿扰",
      "mode.blocklist.on": "按规则判决", "mode.blocklist.no": "允许", "mode.blocklist.use": "只想屏蔽少数应用",
      "mode.blockall.on": "忽略规则，只放行内置必要流量", "mode.blockall.no": "拒绝", "mode.blockall.use": "紧急断网",
      "mode.off.on": "忽略规则", "mode.off.no": "允许（仍记录日志）", "mode.off.use": "排查问题"
    },
    "zh-Hant": {
      "theme.auto": "跟隨系統", "theme.light": "淺色", "theme.dark": "深色",
      "timer.wait": "{n} 秒後自動拒絕", "timer.paused": "已暫停 · {n} 秒",
      "status.waiting": "等待判決 · 倒數結束後依預設動作處理",
      "status.timeout": "已拒絕 · 逾時（預設動作）· 已寫入日誌",
      "status.allowed": "已允許 · 規則已寫入（{ttl} · {cond}）",
      "status.denied": "已拒絕 · 依「使用者判決」寫入日誌",
      "rule.tpl": "<b>將產生規則</b> · 應用程式 Code.exe（簽署者 Microsoft Corporation） · 條件 {cond} · 動作 <b>允許</b> · 時效 <b>{ttl}</b>",
      "scope.app": "任意連線", "scope.domain": "網域 {v}", "scope.ip": "IP {v}",
      "scope.ipport": "IP + 埠 {v}", "scope.port": "埠 {v}",
      "ttl.now": "本次", "ttl.exit": "直到結束", "ttl.5m": "5 分鐘", "ttl.15m": "15 分鐘",
      "ttl.1h": "1 小時", "ttl.reboot": "直到重新啟動", "ttl.forever": "永久",
      "mode.ask.on": "依規則判決", "mode.ask.no": "彈窗詢問", "mode.ask.use": "日常使用",
      "mode.allowlist.on": "依規則判決", "mode.allowlist.no": "拒絕（可選通知）", "mode.allowlist.use": "嚴格控制、勿擾",
      "mode.blocklist.on": "依規則判決", "mode.blocklist.no": "允許", "mode.blocklist.use": "只想封鎖少數應用程式",
      "mode.blockall.on": "忽略規則，只放行內建必要流量", "mode.blockall.no": "拒絕", "mode.blockall.use": "緊急斷網",
      "mode.off.on": "忽略規則", "mode.off.no": "允許（仍記錄日誌）", "mode.off.use": "排查問題"
    },
    "en": {
      "theme.auto": "Follow system", "theme.light": "Light", "theme.dark": "Dark",
      "timer.wait": "Auto-deny in {n}s", "timer.paused": "Paused · {n}s",
      "status.waiting": "Waiting for a verdict · default action applies at zero",
      "status.timeout": "Denied · timed out (default action) · logged",
      "status.allowed": "Allowed · rule written ({ttl} · {cond})",
      "status.denied": "Denied · logged as a user verdict",
      "rule.tpl": "<b>Rule to be created</b> · app Code.exe (signed by Microsoft Corporation) · condition {cond} · action <b>allow</b> · lifetime <b>{ttl}</b>",
      "scope.app": "any connection", "scope.domain": "domain {v}", "scope.ip": "IP {v}",
      "scope.ipport": "IP + port {v}", "scope.port": "port {v}",
      "ttl.now": "this time", "ttl.exit": "until exit", "ttl.5m": "5 min", "ttl.15m": "15 min",
      "ttl.1h": "1 hour", "ttl.reboot": "until reboot", "ttl.forever": "permanent",
      "mode.ask.on": "Follow rules", "mode.ask.no": "Ask with a prompt", "mode.ask.use": "Everyday use",
      "mode.allowlist.on": "Follow rules", "mode.allowlist.no": "Deny (optional notice)", "mode.allowlist.use": "Strict control, quiet",
      "mode.blocklist.on": "Follow rules", "mode.blocklist.no": "Allow", "mode.blocklist.use": "Block only a few apps",
      "mode.blockall.on": "Ignore rules, allow built-in essentials only", "mode.blockall.no": "Deny", "mode.blockall.use": "Emergency cut-off",
      "mode.off.on": "Ignore rules", "mode.off.no": "Allow (still logged)", "mode.off.use": "Troubleshooting"
    }
  };

  var HEAD = {
    "zh-Hans": {
      title: "NetworkControl — 按应用联网控制，跨 Windows / Linux / macOS",
      desc: "NetworkControl 补上桌面系统缺失的出站防护：应用首次联网时弹窗询问，规则精确到域名、IP 与端口，三个平台一套规则模型，全程本地运行，不收集任何遥测数据。",
      ogTitle: "NetworkControl — 每一次联网，都由你决定",
      ogDesc: "跨 Windows / Linux / macOS 的按应用联网控制。弹窗询问、域名级规则、实时连接视图，本地运行，无遥测。"
    },
    "zh-Hant": {
      title: "NetworkControl — 依應用程式控管連線，跨 Windows / Linux / macOS",
      desc: "NetworkControl 補上桌面系統缺少的對外防護：應用程式首次連線時彈窗詢問，規則精確到網域、IP 與埠，三個平台共用一套規則模型，全程本機執行，不收集任何遙測資料。",
      ogTitle: "NetworkControl — 每一次連線，都由你決定",
      ogDesc: "跨 Windows / Linux / macOS 的依應用程式連線控管。彈窗詢問、網域層級規則、即時連線檢視，本機執行，無遙測。"
    },
    "en": {
      title: "NetworkControl — per-app network control for Windows, Linux and macOS",
      desc: "NetworkControl adds the outbound layer desktop systems leave out: a prompt on an app's first connection, rules down to domain, IP and port, one rule model across three platforms, fully local with zero telemetry.",
      ogTitle: "NetworkControl — every connection, decided by you",
      ogDesc: "Per-app network control for Windows, Linux and macOS. First-connection prompts, domain-level rules, a live connection view, all local, no telemetry."
    }
  };

  var DATA = {};
  try { DATA = JSON.parse(document.getElementById("i18n-data").textContent || "{}"); } catch (e) { DATA = {}; }

  var LANG = (function () {
    try {
      var l = localStorage.getItem("nc.lang");
      if (l === "zh-Hant" || l === "en") return l;
    } catch (e) { }
    return "zh-Hans";
  })();

  function t(key, vars) {
    var s = (DYN[LANG] && DYN[LANG][key]) || DYN["zh-Hans"][key] || key;
    if (vars) {
      Object.keys(vars).forEach(function (k) { s = s.split("{" + k + "}").join(vars[k]); });
    }
    return s;
  }

  /* ── 收集原文节点，语言切换时回填 ── */
  var textPairs = [], attrPairs = [];
  (function collect() {
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        var p = node.parentNode;
        if (!p) return NodeFilter.FILTER_REJECT;
        var tag = p.nodeName;
        if (tag === "SCRIPT" || tag === "STYLE" || tag === "TEXTAREA") return NodeFilter.FILTER_REJECT;
        return /\S/.test(node.nodeValue) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      }
    });
    var n;
    while ((n = walker.nextNode())) textPairs.push({ n: n, s: n.nodeValue });
    ["aria-label", "title", "placeholder", "alt"].forEach(function (name) {
      Array.prototype.forEach.call(document.body.querySelectorAll("[" + name + "]"), function (el) {
        attrPairs.push({ el: el, name: name, s: el.getAttribute(name) });
      });
    });
  })();

  function tr(s) {
    var d = LANG === "zh-Hans" ? null : DATA[LANG];
    if (!d) return s;
    var lead = s.match(/^\s*/)[0], trail = s.match(/\s*$/)[0], core = s.trim();
    var out = d[core];
    return out === undefined ? s : lead + out + trail;
  }

  function setMeta(kind, key, val) {
    var el = document.querySelector('meta[' + kind + '="' + key + '"]');
    if (el) el.setAttribute("content", val);
  }

  var langBtns = Array.prototype.slice.call(document.querySelectorAll(".lang button"));

  function applyLang() {
    var d = LANG === "zh-Hans" ? null : DATA[LANG];
    textPairs.forEach(function (p) { p.n.nodeValue = d ? tr(p.s) : p.s; });
    attrPairs.forEach(function (a) { a.el.setAttribute(a.name, d ? tr(a.s) : a.s); });
    var h = HEAD[LANG];
    document.title = h.title;
    setMeta("name", "description", h.desc);
    setMeta("property", "og:title", h.ogTitle);
    setMeta("property", "og:description", h.ogDesc);
    root.lang = LANG === "zh-Hant" ? "zh-Hant" : (LANG === "en" ? "en" : "zh-Hans");
    langBtns.forEach(function (b) {
      b.setAttribute("aria-pressed", String(b.getAttribute("data-lang") === LANG));
    });
    renderDynamic();
    updateThemeLabel();
  }

  /* ═══ 1. 主题 ══════════════════════════════════════════════════ */
  var themeBtn = document.getElementById("theme-btn");
  var mq = window.matchMedia("(prefers-color-scheme: light)");
  var themeMode = (function () {
    try { return localStorage.getItem("nc.theme") || "auto"; } catch (e) { return "auto"; }
  })();

  function resolved(m) { return m === "auto" ? (mq.matches ? "light" : "dark") : m; }

  function updateThemeLabel() {
    if (!themeBtn) return;
    var lbl = t("theme." + themeMode);
    themeBtn.title = lbl;
    themeBtn.setAttribute("aria-label", lbl);
  }

  function applyTheme() {
    var r = resolved(themeMode);
    root.setAttribute("data-theme", r);
    if (themeBtn) themeBtn.setAttribute("data-mode", themeMode);
    var tc = document.querySelector('meta[name="theme-color"]');
    if (tc) tc.setAttribute("content", r === "light" ? "#FAFAF8" : "#08090B");
    updateThemeLabel();
  }

  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      themeMode = themeMode === "auto" ? "light" : themeMode === "light" ? "dark" : "auto";
      try { localStorage.setItem("nc.theme", themeMode); } catch (e) { }
      applyTheme();
    });
  }
  if (mq.addEventListener) {
    mq.addEventListener("change", function () { if (themeMode === "auto") applyTheme(); });
  }
  applyTheme();

  langBtns.forEach(function (b) {
    b.addEventListener("click", function () {
      LANG = b.getAttribute("data-lang");
      try { localStorage.setItem("nc.lang", LANG); } catch (e) { }
      applyLang();
    });
  });

  /* ═══ 2. 顶栏 / 导航 / 滚动 ════════════════════════════════════ */
  var bar = document.getElementById("topbar");
  function onScrollBar() {
    if (!bar) return;
    bar.classList.toggle("scrolled", window.scrollY > 12);
  }

  var reveals = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && !reduce) {
    var rio = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add("in"); rio.unobserve(e.target); }
      });
    }, { rootMargin: "0px 0px -12% 0px", threshold: 0.08 });
    Array.prototype.forEach.call(reveals, function (el) { rio.observe(el); });
  } else {
    Array.prototype.forEach.call(reveals, function (el) { el.classList.add("in"); });
  }

  var navLinks = Array.prototype.slice.call(document.querySelectorAll(".nav a"));
  var sections = navLinks.map(function (a) { return document.querySelector(a.getAttribute("href")); }).filter(Boolean);
  function onScrollNav() {
    var y = window.scrollY + 120, current = null;
    sections.forEach(function (s) { if (s.offsetTop <= y) current = s.id; });
    navLinks.forEach(function (a) { a.classList.toggle("on", a.getAttribute("href") === "#" + current); });
  }

  var stages = Array.prototype.slice.call(document.querySelectorAll(".stage"));
  var railFill = document.getElementById("rail-fill");
  var pipeList = document.querySelector(".pipe-list");
  function onScrollPipe() {
    if (!pipeList) return;
    var r = pipeList.getBoundingClientRect();
    var p = (window.innerHeight * 0.62 - r.top) / (r.height || 1);
    p = Math.max(0, Math.min(1, p));
    if (railFill) railFill.style.height = (p * 100).toFixed(1) + "%";
    var idx = Math.min(stages.length - 1, Math.floor(p * stages.length));
    stages.forEach(function (s, i) { s.classList.toggle("on", i <= idx); });
  }

  var ticking = false;
  function onScroll() {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(function () {
      onScrollBar(); onScrollNav(); onScrollPipe();
      ticking = false;
    });
  }
  window.addEventListener("scroll", onScroll, { passive: true });
  window.addEventListener("resize", onScroll);
  onScroll();

  /* ═══ 3. 弹窗示意 ══════════════════════════════════════════════ */
  var prompt = document.getElementById("prompt");
  var timer = document.getElementById("timer");
  var timerText = document.getElementById("timer-text");
  var timerBar = document.getElementById("timer-bar");
  var statusEl = document.getElementById("p-status");
  var preview = document.getElementById("rule-preview");
  var LIMIT = 30;
  var left = LIMIT;
  var paused = false;
  var locked = false;
  var lockStatus = null;
  var state = { scope: "domain", ttl: "forever" };
  var SCOPE_VAL = {
    app: "", domain: "marketplace.visualstudio.com", ip: "13.107.42.18",
    ipport: "13.107.42.18:443", port: "443"
  };

  function scopeText() { return t("scope." + state.scope, { v: SCOPE_VAL[state.scope] }); }
  function ttlText() { return t("ttl." + state.ttl); }

  function renderPreview() {
    if (preview) preview.innerHTML = t("rule.tpl", { cond: scopeText(), ttl: ttlText() });
  }

  function paintTimer() {
    if (!timerBar) return;
    timerBar.style.width = (left / LIMIT * 100).toFixed(1) + "%";
    if (paused && !locked) {
      timer.classList.add("paused");
      timerText.textContent = t("timer.paused", { n: left });
    } else {
      timer.classList.remove("paused");
      timerText.textContent = t("timer.wait", { n: left });
    }
  }

  function setStatus(text, cls) {
    if (!statusEl) return;
    statusEl.textContent = text;
    statusEl.className = "p-status" + (cls ? " " + cls : "");
  }

  function statusTextFor(lock) {
    if (lock.kind === "timeout") return t("status.timeout");
    if (lock.kind === "allowed") return t("status.allowed", { ttl: ttlText(), cond: scopeText() });
    return t("status.denied");
  }

  function renderDynamic() {
    renderPreview();
    if (locked && lockStatus) setStatus(statusTextFor(lockStatus), lockStatus.cls);
    else setStatus(t("status.waiting"), "");
    paintTimer();
    paintMode();
  }

  function tick() {
    if (locked || paused) return;
    left -= 1;
    if (left <= 0) {
      left = 0; paintTimer();
      locked = true;
      lockStatus = { kind: "timeout", cls: "no" };
      setStatus(statusTextFor(lockStatus), "no");
      window.setTimeout(function () {
        locked = false; lockStatus = null; left = LIMIT;
        setStatus(t("status.waiting"), "");
        paintTimer();
      }, 2200);
      return;
    }
    paintTimer();
  }

  if (prompt) {
    paintTimer();
    window.setInterval(tick, 1000);

    prompt.addEventListener("mouseenter", function () { paused = true; paintTimer(); });
    prompt.addEventListener("mouseleave", function () { paused = false; paintTimer(); });
    prompt.addEventListener("focusin", function () { paused = true; paintTimer(); });
    prompt.addEventListener("focusout", function () { paused = false; paintTimer(); });

    function wireSegs(id, key, attr) {
      var box = document.getElementById(id);
      if (!box) return;
      box.addEventListener("click", function (ev) {
        var btn = ev.target.closest("[data-" + attr + "]");
        if (!btn) return;
        Array.prototype.forEach.call(box.querySelectorAll(".seg"), function (b) {
          b.setAttribute("aria-pressed", String(b === btn));
        });
        state[key] = btn.getAttribute("data-" + attr);
        renderPreview();
      });
    }
    wireSegs("scope-segs", "scope", "scope");
    wireSegs("ttl-segs", "ttl", "ttl");

    Array.prototype.forEach.call(prompt.querySelectorAll("[data-v]"), function (btn) {
      btn.addEventListener("click", function () {
        locked = true;
        if (btn.getAttribute("data-v") === "allow") {
          lockStatus = { kind: "allowed", cls: "ok" };
        } else {
          lockStatus = { kind: "denied", cls: "no" };
        }
        setStatus(statusTextFor(lockStatus), lockStatus.cls);
        window.setTimeout(function () {
          locked = false; lockStatus = null; left = LIMIT; paintTimer();
          setStatus(t("status.waiting"), "");
        }, 2400);
      });
    });
  }

  /* ═══ 4. 模式切换 ══════════════════════════════════════════════ */
  var tabs = Array.prototype.slice.call(document.querySelectorAll(".mode-tabs button"));
  var mOn = document.getElementById("mode-onrule");
  var mNo = document.getElementById("mode-norule");
  var mUse = document.getElementById("mode-use");
  var activeMode = "ask";

  function paintMode() {
    if (!mOn) return;
    mOn.textContent = t("mode." + activeMode + ".on");
    mNo.textContent = t("mode." + activeMode + ".no");
    var deny = activeMode === "allowlist" || activeMode === "blockall";
    mNo.className = "mv mv-" + (deny ? "deny" : "allow");
    mUse.textContent = t("mode." + activeMode + ".use");
  }

  tabs.forEach(function (tab) {
    tab.addEventListener("click", function () {
      tabs.forEach(function (x) { x.setAttribute("aria-selected", String(x === tab)); });
      activeMode = tab.getAttribute("data-mode");
      paintMode();
    });
  });

  /* ═══ 5. 首次应用语言 ══════════════════════════════════════════ */
  applyLang();
  root.classList.remove("i18n-pending");
  window.setTimeout(function () { root.classList.remove("i18n-pending"); }, 1200);
})();
