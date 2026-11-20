// 用 Chrome DevTools Protocol 檢查頁面：腳本錯誤、橫向溢出、三語切換、截圖（只渲染頁面本身）。
// 用法：node tools/site/check-page.mjs site/android.html <輸出目錄> [寬,高 ...]（預設 1440,900 與 390,844）
// 需要本機安裝 Chrome；輸出目錄放在倉庫之外（例如臨時目錄），截圖不要提交。
import { spawn } from "node:child_process";
import { writeFileSync, mkdirSync } from "node:fs";
import { pathToFileURL } from "node:url";
import path from "node:path";

const [, , file, outDir, ...sizes] = process.argv;
mkdirSync(outDir, { recursive: true });
const chrome = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const port = 9333 + Math.floor(Math.random() * 500);
const profile = path.join(outDir, "profile");
const proc = spawn(chrome, ["--headless=new", `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
  "--no-first-run", "--disable-gpu", "--hide-scrollbars", "--no-proxy-server", "about:blank"], { stdio: "ignore" });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let wsUrl;
for (let i = 0; i < 50 && !wsUrl; i++) {
  await sleep(200);
  try {
    const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
    wsUrl = list.find((t) => t.type === "page")?.webSocketDebuggerUrl;
  } catch { }
}
if (!wsUrl) { console.error("no chrome"); proc.kill(); process.exit(1); }
const ws = new WebSocket(wsUrl);
await new Promise((r) => ws.addEventListener("open", r));
let id = 0; const pending = new Map(); const errors = [];
ws.addEventListener("message", (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  if (m.method === "Runtime.exceptionThrown") errors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
  if (m.method === "Runtime.consoleAPICalled" && m.params.type === "error") errors.push(m.params.args.map((a) => a.value).join(" "));
  if (m.method === "Log.entryAdded" && m.params.entry.level === "error") errors.push(m.params.entry.text + " " + (m.params.entry.url || ""));
});
const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const evaluate = async (expr) => (await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true })).result?.result?.value;

await send("Runtime.enable"); await send("Log.enable"); await send("Page.enable");
const url = pathToFileURL(path.resolve(file)).href;
const report = {};
for (const s of (sizes.length ? sizes : ["1440,900", "390,844"])) {
  const [w, h] = s.split(",").map(Number);
  await send("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 1, mobile: w < 600 });
  for (const lang of ["zh-Hans", "zh-Hant", "en"]) {
    for (const theme of (lang === "zh-Hans" ? ["dark", "light"] : ["dark"])) {
      await send("Page.navigate", { url });
      await sleep(900);
      await evaluate(`localStorage.setItem("nc.lang","${lang}");localStorage.setItem("nc.theme","${theme}");1`);
      await send("Page.reload", {});
      await sleep(1300);
      // 讓所有 reveal 立即可見，再量測。
      await evaluate(`document.querySelectorAll(".reveal").forEach(e=>e.classList.add("in"));document.querySelectorAll("details").forEach(d=>d.open=true);1`);
      await sleep(900);
      const info = await evaluate(`(()=>{const de=document.documentElement;const over=[];document.querySelectorAll("body *").forEach(el=>{const r=el.getBoundingClientRect();if(r.right>de.clientWidth+1&&r.width>0&&!el.closest(".tablewrap"))over.push(el.tagName+"."+el.className+" "+Math.round(r.right))});return {title:document.title,lang:de.lang,scrollW:de.scrollWidth,clientW:de.clientWidth,height:de.scrollHeight,over:over.slice(0,8),pending:de.classList.contains("i18n-pending")}})()`);
      const key = `${w}-${lang}-${theme}`;
      report[key] = info;
      const height = Math.min(info.height, 16000);
      await send("Emulation.setDeviceMetricsOverride", { width: w, height, deviceScaleFactor: 1, mobile: w < 600 });
      await sleep(400);
      const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
      writeFileSync(path.join(outDir, `${key}.png`), Buffer.from(shot.result.data, "base64"));
      await send("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 1, mobile: w < 600 });
    }
  }
}
console.log(JSON.stringify({ errors: [...new Set(errors)], report }, null, 1));
ws.close(); proc.kill();
process.exit(0);
