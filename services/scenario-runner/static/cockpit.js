// Ops cockpit: polls the scenario runner for live state, draws the probes, runs scenarios and shows their output.
"use strict";
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const WINDOW = 90000;
const secs = (ms) => (ms / 1000).toFixed(1) + " s";
const clock = (ms) => new Date(ms).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const mmss = (ms) => { const s = Math.max(0, Math.floor(ms / 1000)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
const rwf = (n) => Number(n || 0).toLocaleString("en-US");
let scenarios = [], run = { id: null, from: 0 }, last = null;

// ---------------------------------------------------------------- probes

function outage(samples, now) {
  // current: failing since the first failure at the tail; previous: the last completed run of failures
  let cur = null, prev = null, i = samples.length - 1;
  if (i >= 0 && !samples[i][1]) {
    while (i >= 0 && !samples[i][1]) i--;
    cur = now - samples[i + 1][0];
  }
  for (let j = i; j >= 0; j--) {
    if (!samples[j][1]) {
      let k = j; while (k >= 0 && !samples[k][1]) k--;
      prev = { ms: samples[j + 1][0] - samples[k + 1][0], at: samples[k + 1][0] };
      break;
    }
  }
  return { cur, prev };
}

function drawProbe(svgId, noteId, samples, now, label) {
  const recent = samples.filter((s) => s[0] > now - WINDOW);
  const max = 400;  // ms at full height; slower samples are clipped
  const bars = recent.map((s) => {
    const x = ((s[0] - (now - WINDOW)) / WINDOW) * 720;
    const h = s[1] ? Math.max(6, Math.min(1, Math.log10(1 + s[2]) / Math.log10(1 + max)) * 92) : 92;
    return `<rect x="${x.toFixed(1)}" y="${96 - h}" width="1.6" height="${h}" fill="${s[1] ? "#0f996d" : "#dc3545"}" opacity="${s[1] ? 0.85 : 0.9}"><title>${clock(s[0])} ${s[1] ? s[2] + " ms" : "failed: " + esc(s[3])}</title></rect>`;
  }).join("");
  $(svgId).innerHTML = bars;
  const { cur, prev } = outage(recent, now);
  const ok = recent.filter((s) => s[1]);
  const p50 = ok.length ? ok.map((s) => s[2]).sort((a, b) => a - b)[Math.floor(ok.length / 2)] : 0;
  const note = $(noteId);
  if (cur !== null) {
    note.className = "probe-note bad";
    note.textContent = `${label} failing for ${secs(cur)}`;
  } else {
    note.className = "probe-note";
    note.textContent = `${ok.length} of ${recent.length} ok · median ${p50} ms` + (prev ? ` · last gap ${secs(prev.ms)} at ${clock(prev.at)}` : "");
  }
  return { ok: ok.length, total: recent.length, failing: cur !== null };
}

// ---------------------------------------------------------------- tiles, nodes, pools

const alive = (s, svc) => s.containers[svc]?.state === "running" && s.containers[svc]?.networked;

function tiles(s, db, edge) {
  const p = s.patroni, k = s.kafka, m = s.model;
  const etcdOk = Object.values(s.etcd || {}).filter(Boolean).length;
  const t = [
    p?.leader && !alive(s, p.leader)
      ? { b: `${p.leader}?`, span: "leader key held by a node that is down", cls: "bad" }
      : { b: p?.leader ?? "none", span: p?.leader ? `DB leader for ${mmss(s.now - p.since)}` : "no database leader", cls: p?.leader ? "" : "bad" },
    { b: `${etcdOk} of 3`, span: "etcd quorum", cls: etcdOk >= 2 ? (etcdOk === 3 ? "" : "warn") : "bad" },
    { b: k ? `ISR ${Math.min(...k.isr)}` : "…", span: "Kafka min in-sync", cls: !k ? "" : Math.min(...k.isr) >= 3 ? "" : Math.min(...k.isr) >= 2 ? "warn" : "bad" },
    { b: db.failing ? "failing" : `${db.total ? Math.round((db.ok / db.total) * 100) : 0}%`, span: "writes ok (90 s)", cls: db.failing ? "bad" : "" },
    { b: m?.mode ?? "…", span: m ? `model ${m.version}` : "AI model", cls: m && m.mode !== "ACTIVE" ? "warn" : "" },
  ];
  $("tiles").innerHTML = t.map((x) => `<div class="tile ${x.cls}"><b>${esc(x.b)}</b><span>${esc(x.span)}</span></div>`).join("");
}

function roleBadge(m) {
  if (!m) return '<span class="badge b-bad">UNREACHABLE</span>';
  if (m.role === "leader") return '<span class="badge b-brand">LEADER</span>';
  if (m.state === "streaming") return `<span class="badge b-ok">${esc(m.role.replace("_", " ").toUpperCase())}</span>`;
  return `<span class="badge b-warn">${esc(m.state.toUpperCase())}</span>`;
}

function nodes(s) {
  const members = Object.fromEntries((s.patroni?.members || []).map((m) => [m.name, m]));
  $("nodes").innerHTML = [1, 2, 3].map((n) => {
    const pg = s.containers[`pg-${n}`] || {}, et = s.containers[`etcd-${n}`] || {}, ka = s.containers[`kafka-${n}`] || {};
    const m = members[`pg-${n}`];
    const cut = [pg, et, ka].some((x) => x.state === "running" && !x.networked);
    const down = pg.state && pg.state !== "running";
    const dot = (ok, warn) => `<span class="dot ${ok ? "" : warn ? "warn" : "bad"}"></span>`;
    const kafkaUp = ka.state === "running" && ka.networked;
    return `<div class="node ${m?.role === "leader" ? "leader" : ""} ${cut ? "cut" : ""}">
      <div class="node-head"><b>Node ${n}</b>${cut ? '<span class="badge b-bad">CUT OFF</span>' : down ? '<span class="badge b-bad">PG STOPPED</span>' : roleBadge(m)}</div>
      <dl>
        <dt>pg-${n}</dt><dd>${down ? dot(false) + "stopped" : m ? dot(m.state === "running" || m.state === "streaming", true) + esc(m.state) : dot(false) + "no answer"}</dd>
        <dt>lag</dt><dd>${m && m.role !== "leader" ? esc(m.lag) + " B" : "–"}</dd>
        <dt>timeline</dt><dd>${m?.timeline ?? "–"}</dd>
        <dt>etcd-${n}</dt><dd>${dot(s.etcd?.[`etcd-${n}`])}${s.etcd?.[`etcd-${n}`] ? "healthy" : "no quorum"}</dd>
        <dt>kafka-${n}</dt><dd>${dot(kafkaUp)}${ka.state !== "running" ? "stopped" : ka.networked ? "running" : "cut off"}</dd>
      </dl></div>`;
  }).join("");
  const pool = s.edge?.pool || [];
  const total = pool.reduce((a, p) => a + (p.status === "UP" ? p.weight : 0), 0) || 1;
  $("pools").innerHTML = `
    <div class="pool"><h3>Edge pool (HAProxy → core)</h3>${pool.map((p) => `<div class="wrow"><span><span class="dot ${p.status === "UP" ? "" : p.status.startsWith("MAINT") ? "off" : "bad"}"></span>${esc(p.name)}</span>
      <div class="wbar"><i class="${p.name.includes("green") ? "green" : ""}" style="width:${p.status === "UP" ? (p.weight / total) * 100 : 0}%"></i></div><span>${p.status === "UP" ? Math.round((p.weight / total) * 100) + "%" : esc(p.status.split(" ")[0])}</span></div>`).join("")}</div>
    <div class="pool"><h3>Database proxy (pgproxy)</h3><div class="wrow"><span>primary</span><b>${esc(s.pgproxy?.primary ?? "none")}</b><span></span></div>
      <div class="muted small">The core always connects here; pgproxy follows the Patroni leader.</div></div>`;
}

function recon(s) {
  const r = s.recon;
  if (!r) return;
  const c = r.core, p = r.provider;
  const paid = c.payments.SUCCEEDED || 0;
  const balanced = p && p.count === paid && Number(p.total) === Number(c.paidAmount);
  $("recon-badge").innerHTML = p ? (balanced ? '<span class="badge b-ok">BALANCED</span>' : '<span class="badge b-warn">MISMATCH</span>') : '<span class="badge b-muted">PROVIDER UNREACHABLE</span>';
  const states = Object.entries(c.consultations).sort((a, b) => b[1] - a[1]);
  $("recon").innerHTML = `
    <div class="rbox"><h3>Payments</h3><div class="big">${paid} <small>in core</small> · ${p ? p.count : "?"} <small>at IremboPay</small></div>
      <p>${rwf(c.paidAmount)} RWF vs ${p ? rwf(p.total) : "?"} RWF settled</p></div>
    <div class="rbox"><h3>Webhooks</h3><div class="big">${c.webhooks} <small>processed once</small></div>
      <p>${r.deliveries.duplicate} duplicates ignored · ${r.deliveries.rejected} forged rejected</p></div>
    <div class="rbox"><h3>Outbox to Kafka</h3><div class="big">${c.outboxPending} <small>pending</small></div>
      <p>${c.outboxDone} events published</p></div>
    <div class="rbox"><h3>Consultations</h3><div class="chips">${states.map(([k, v]) => `<span class="badge ${k === "QUEUED" ? "b-ok" : k === "EXPIRED" ? "b-muted" : "b-violet"}">${esc(k)} ${v}</span>`).join("") || '<span class="muted small">none yet</span>'}</div></div>`;
}

function emr(s) {
  const e = s.emr;
  $("emr-card").hidden = !e;
  if (!e) return;
  const svc = (k, label) => {
    const x = e.services[k] || {};
    const ok = x.state === "running" && (x.health === "healthy" || x.health === "");
    return `<div class="rbox"><h3>${label}</h3><div class="big"><span class="dot ${ok ? "" : x.state === "running" ? "warn" : "bad"}"></span>${x.state === "running" ? (x.health || "running") : (x.state || "absent")}</div></div>`;
  };
  const q = e.sync?.queue || {};
  const pending = q.PENDING || 0, dead = q.DEAD || 0;
  const ident = e.sync?.identity || {};
  $("emr").innerHTML = `<div class="recon">
      ${svc("ebuzima-backend", "eBuzima")}
      ${svc("openmrs-backend", "OpenMRS (DH)")}
      ${svc("openfn", "OpenFn Lightning")}
      <div class="rbox"><h3>Writes to the EMRs</h3><div class="big">${pending} <small>waiting</small> · ${dead} <small>dead</small></div>
        <p>${q.DONE || 0} delivered${pending ? ` · ${Object.entries(e.sync.pendingBy || {}).map(([k, v]) => `${esc(k)} ${v}`).join(", ")}` : ""}${pending ? ` · oldest ${Math.round(e.sync.oldestSeconds)} s` : ""}${e.sync?.lastError ? `<br><span class="muted small">${esc(e.sync.lastError.slice(0, 80))}</span>` : ""}</p></div>
      <div class="rbox"><h3>Identity</h3><div class="big">${ident.PENDING || 0} <small>to review</small></div>
        <p>${e.sync?.links || 0} linked · ${ident.BLOCKED || 0} never offered</p></div>
    </div>
    <table class="emr-events">${(e.sync?.events || []).map((x) => `<tr><td>${esc(x.at)}</td><td>${esc(x.system || "")}</td><td>${esc(x.direction.toLowerCase())}</td>
      <td><span class="badge ${x.outcome === "ECHO_DROPPED" ? "b-muted" : x.outcome === "APPLIED" || x.outcome === "CREATED" || x.outcome.includes("LINK") ? "b-ok" : x.outcome === "REVIEW" ? "b-warn" : x.outcome === "BLOCKED" ? "b-bad" : "b-muted"}">${esc(x.outcome.replace("_", " "))}</span></td>
      <td class="muted">${esc(x.detail || "")}</td></tr>`).join("")}</table>`;
}

function events(s) {
  $("events").innerHTML = s.events.length ? s.events.map((e) => `<li><time>${clock(e.at)}</time><span class="${e.level}">${esc(e.text)}</span></li>`).join("")
    : '<li><span></span><span class="muted">Nothing yet. Run a scenario.</span></li>';
}

// ---------------------------------------------------------------- scenarios and runs

let rendered = null;
function renderScenarios(busy) {
  const key = `${scenarios.length}:${busy}:${last?.run?.scenario}`;
  if (key === rendered) return;  // rebuild only on change, so a click is never lost
  rendered = key;
  $("scenarios").innerHTML = scenarios.map((s) => `
    <div class="scn ${last?.run?.scenario === s.id && busy ? "active" : ""}">
      <div><div class="tags"><span class="badge b-muted">Scene ${esc(s.scene)}</span>${s.kind === "failure" ? '<span class="badge b-bad">failure</span>' : s.kind === "security" ? '<span class="badge b-violet">security</span>' : s.kind === "release" ? '<span class="badge b-ok">release</span>' : s.kind === "sync" ? '<span class="badge b-brand">sync</span>' : ""}</div><b>${esc(s.title)}</b></div>
      <button class="run ${s.kind === "failure" ? "failure" : ""}" data-run="${s.id}" ${busy ? "disabled" : ""}>Run</button>
      <p>${esc(s.detail)}</p>
    </div>`).join("");
  document.querySelectorAll("[data-run]").forEach((b) => b.onclick = () => start(b.dataset.run));
}

async function start(id) {
  const r = await fetch(`/api/run/${id}`, { method: "POST" });
  const d = await r.json();
  if (!r.ok) { alert(d.error); return; }
  run = { id: d.id, from: 0 };
  $("console").innerHTML = "";
  poll();
}

async function followRun() {
  if (!run.id) return;
  const r = await fetch(`/api/runs/${run.id}?from=${run.from}`);
  if (!r.ok) return;
  const d = await r.json();
  const box = $("console"), stick = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
  for (const l of d.lines) {
    const t = `<time>${((l.at - d.started) / 1000).toFixed(1)}s</time>`;
    box.insertAdjacentHTML("beforeend", `<span class="${l.kind}">${t}${esc(l.text)}</span>\n`);
  }
  run.from += d.lines.length;
  if (stick) box.scrollTop = box.scrollHeight;
  $("run-title").textContent = d.title;
  const el = (d.ended || Date.now()) - d.started;
  $("run-status").innerHTML = d.status === "running" ? `<span class="badge b-brand">RUNNING ${mmss(el)}</span>`
    : d.status === "passed" ? `<span class="badge b-ok">PASSED in ${secs(el)}</span>` : `<span class="badge b-bad">FAILED after ${secs(el)}</span>`;
}

// ---------------------------------------------------------------- loop

async function poll() {
  try {
    const s = await (await fetch("/api/state")).json();
    last = s;
    const db = drawProbe("db-chart", "db-note", s.probes.db, s.now, "writes");
    const edge = drawProbe("edge-chart", "edge-note", s.probes.edge, s.now, "requests");
    tiles(s, db, edge);
    nodes(s);
    recon(s);
    emr(s);
    events(s);
    // Which releases answered the last 10 s of edge probes: one during normal running, two during a canary
    const served = s.probes.edge.filter((p) => p[1] && p[0] > s.now - 10000).map((p) => p[3]);
    const mix = Object.entries(served.reduce((a, v) => ({ ...a, [v]: (a[v] || 0) + 1 }), {})).sort((a, b) => b[1] - a[1]);
    $("release").textContent = !mix.length ? "release unknown" : mix.length === 1 ? `release ${mix[0][0]}`
      : mix.map(([v, n]) => `${v} ${Math.round((n / served.length) * 100)}%`).join(" · ");
    const busy = s.run?.status === "running";
    renderScenarios(busy);
    if (s.run && !run.id) run = { id: s.run.id, from: 0 };  // page opened while a run is going
    if (s.run && s.run.id !== run.id && busy) { run = { id: s.run.id, from: 0 }; $("console").innerHTML = ""; }
    await followRun();
  } catch (e) {
    $("db-note").className = "probe-note bad";
    $("db-note").textContent = "cockpit cannot reach the scenario runner";
  }
}

(async () => {
  scenarios = await (await fetch("/api/scenarios")).json();
  setInterval(() => { $("clock").textContent = clock(Date.now()); }, 1000);
  poll();
  setInterval(poll, 1000);
})();
