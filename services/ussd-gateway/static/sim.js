// Phone simulator: plays the telecom aggregator (sends USSD callbacks) and the phone (shows pages, MoMo prompt, SMS).
"use strict";
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const BUDGET = 1500;
let session = null; // {id, answers: []}
let seenSms = null, momoShown = null;

// Keypad
const KEYS = [["1", ""], ["2", "ABC"], ["3", "DEF"], ["4", "GHI"], ["5", "JKL"], ["6", "MNO"], ["7", "PQRS"], ["8", "TUV"], ["9", "WXYZ"], ["*", ""], ["0", "+"], ["#", ""]];
$("keypad").innerHTML = KEYS.map(([k, l]) => `<button class="key" data-k="${k}"><b>${k}</b><small>${l}</small></button>`).join("");
document.querySelectorAll(".key").forEach((b) => b.onclick = () => { $("display").textContent += b.dataset.k; });
$("del").onclick = () => { $("display").textContent = $("display").textContent.slice(0, -1); };
const tick = () => { $("clock").textContent = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); };
tick(); setInterval(tick, 10000);

async function send(input) {
  const answers = input === null ? [] : session.answers.concat([input]);
  $("running").hidden = false;
  $("ussd-text").textContent = "";
  $("ussd-input").hidden = true;
  const body = new URLSearchParams({ sessionId: session.id, serviceCode: "*909*8#", phoneNumber: $("msisdn").value, text: answers.join("*") });
  const started = performance.now();
  let reply;
  try {
    const r = await fetch("/ussd/callback", { method: "POST", body });
    reply = await r.text();
  } catch (e) {
    reply = "END Connection problem. Please dial again.";
  }
  session.rtt = Math.round(performance.now() - started);
  session.answers = answers;
  $("running").hidden = true;
  const isEnd = reply.startsWith("END");
  $("ussd-text").textContent = reply.replace(/^(CON|END) /, "");
  $("ussd-input").hidden = isEnd;
  $("ussd-input").value = "";
  $("ussd-send").textContent = isEnd ? "OK" : "Send";
  $("ussd-cancel").hidden = isEnd;
  session.ended = isEnd;
  if (!isEnd) $("ussd-input").focus();
  refresh();
}

$("call").onclick = () => {
  if (!$("display").textContent.trim()) return;
  session = { id: "sim-" + Math.random().toString(36).slice(2, 10), answers: [] };
  $("ussd").hidden = false;
  send(null);
};
$("ussd-send").onclick = () => {
  if (session.ended) { $("ussd").hidden = true; return; }
  const v = $("ussd-input").value.trim();
  if (v) send(v);
};
$("ussd-input").addEventListener("keydown", (e) => { if (e.key === "Enter") $("ussd-send").click(); });
$("ussd-cancel").onclick = () => { $("ussd").hidden = true; session = null; };
$("msisdn").onchange = () => { seenSms = null; momoShown = null; $("sms-banner").hidden = true; refresh(); };

// Mobile-money prompt
$("momo-ok").onclick = async () => {
  $("momo").hidden = true;
  await fetch(`/sim/approve/${momoShown}`, { method: "POST" });
  refresh();
};
$("momo-reject").onclick = () => { $("momo").hidden = true; };

function chart(log) {
  const steps = log.slice(0, 10).reverse();
  if (!steps.length) return;
  const max = Math.max(BUDGET * 1.1, ...steps.map((s) => s.ms));
  const line = `bottom:calc(24px + (100% - 24px) * ${BUDGET / max})`;
  $("chart").innerHTML = `
    <span class="axis" style="${line}">1.5 s</span>
    <div class="budget" style="${line}"><span>budget 1,500 ms</span></div>
    ${steps.map((s) => `<div class="barw"><b>${s.ms}</b><i style="height:${Math.max((s.ms / max) * 100, 2)}%"></i><span>${esc(s.step)}</span></div>`).join("")}`;
  const ms = log.map((s) => s.ms).sort((a, b) => a - b);
  const p95 = ms[Math.min(ms.length - 1, Math.floor(ms.length * 0.95))];
  $("kpis").innerHTML = `
    <div class="kpi ok"><b>${p95} ms</b><span>p95 gateway step (last ${ms.length})</span></div>
    <div class="kpi"><b>${session?.rtt ?? "–"} ms</b><span>last round trip from this page</span></div>
    <div class="kpi"><b>${Math.max(...log.map((s) => s.chars))}</b><span>longest page (limit 182)</span></div>`;
}

async function refresh() {
  const r = await fetch(`/sim/state?msisdn=${encodeURIComponent($("msisdn").value)}`);
  if (!r.ok) return;
  const s = await r.json();
  chart(s.log);
  $("log").innerHTML = s.log.slice(0, 14).map((l) => `<tr><td>${new Date(l.at).toLocaleTimeString()}</td><td>${esc(l.step)}</td>
    <td>${esc(l.input) || '<span class="muted">dial</span>'}</td><td class="reply">${l.end ? '<span class="end">END</span>' : ""}${esc(l.reply)}</td>
    <td>${l.chars}</td><td><b>${l.ms}</b></td></tr>`).join("");
  $("sms").innerHTML = s.sms.length ? s.sms.map((m) => `<li>${esc(m.text)}<time>${new Date(m.at).toLocaleTimeString()}</time></li>`).join("")
    : '<li class="muted small">No messages yet.</li>';
  // Only messages that arrive while the page is open pop up on the phone.
  if (seenSms !== null && s.sms.length > seenSms) {
    $("sms-banner").innerHTML = `<b>SMS from TeleClinic</b>${esc(s.sms[0].text)}`;
    $("sms-banner").hidden = false;
    setTimeout(() => { $("sms-banner").hidden = true; }, 7000);
  }
  seenSms = s.sms.length;
  const p = s.pending[0];
  if (p && momoShown !== p.paymentId) {
    momoShown = p.paymentId;
    $("momo-text").innerHTML = `Pay <b>${Number(p.amount).toLocaleString("en-US")} ${esc(p.currency)}</b> to <b>IremboPay</b> for ${esc(p.description || "TeleClinic")}?`;
    $("momo-pin").value = "";
    $("momo").hidden = false;
  }
}
refresh();
setInterval(refresh, 1500);
