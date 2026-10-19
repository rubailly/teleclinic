// Renders SVG files to PNG with headless Chrome (D2's own PNG export needs a Playwright download).
// Usage: node --experimental-websocket svg2png.mjs <scale> <in.svg> <out.png> [<in.svg> <out.png> ...]
import { spawn } from "node:child_process";
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const [scaleArg, ...files] = process.argv.slice(2);
const scale = Number(scaleArg) || 2;
const port = 19700 + Math.floor(Math.random() * 200);
const profile = mkdtempSync(join(tmpdir(), "tc-svg-"));
const chrome = spawn("google-chrome", ["--headless=new", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
  `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "--no-first-run", "about:blank"], { stdio: "ignore" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let ws, id = 0;
const pending = new Map();
for (let i = 0; i < 50 && !ws; i++) {
  try {
    const page = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find((t) => t.type === "page");
    if (page) ws = new WebSocket(page.webSocketDebuggerUrl);
  } catch (e) { await sleep(200); }
}
await new Promise((r) => ws.addEventListener("open", r, { once: true }));
ws.addEventListener("message", (m) => { const msg = JSON.parse(m.data); if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); } });
const send = (method, params = {}) => new Promise((r) => { const n = ++id; pending.set(n, r); ws.send(JSON.stringify({ id: n, method, params })); });
await send("Page.enable");
for (let i = 0; i < files.length; i += 2) {
  const svg = readFileSync(files[i], "utf8");
  const vb = /viewBox="([\d.\-]+) ([\d.\-]+) ([\d.]+) ([\d.]+)"/.exec(svg);
  const w = Math.ceil(Number(vb[3])), h = Math.ceil(Number(vb[4]));
  await send("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: scale, mobile: false });
  await send("Page.navigate", { url: "file://" + resolve(files[i]) });
  await sleep(1200);
  const shot = await send("Page.captureScreenshot", { format: "png", clip: { x: 0, y: 0, width: w, height: h, scale: 1 } });
  writeFileSync(files[i + 1], Buffer.from(shot.result.data, "base64"));
  console.log(`${files[i + 1]} ${w * scale}x${h * scale}`);
}
chrome.kill();
process.exit(0);
