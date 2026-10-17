// Clinician queue. Talks only to the core's own API with the session cookie; no tokens in the browser.
"use strict";

const $ = (id) => document.getElementById(id);
const I = TC.icon;
const RANK = { EMERGENCY: 4, URGENT: 3, SEMI_URGENT: 2, ROUTINE: 1 };
const LABEL = { EMERGENCY: "Emergency", URGENT: "Urgent", SEMI_URGENT: "Semi-urgent", ROUTINE: "Routine" };
let selected = null;
const patients = {};

function xsrf() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return m ? decodeURIComponent(m[1]) : "";
}

async function api(method, path, body) {
  const res = await fetch(path, {
    method, credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": xsrf() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401) { location.href = "/login"; throw new Error("signed out"); }
  const data = (res.headers.get("content-type") || "").includes("json") ? await res.json() : null;
  return { ok: res.ok, status: res.status, data };
}

const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const urg = (u) => u ? `<span class="badge ${u}">${LABEL[u]}</span>` : `<span class="muted">none</span>`;
const statusLabel = (s) => s === "AI_PROPOSED" ? "AI-PROPOSED" : s.replace(/_/g, " ");
const initials = (n) => String(n || "?").split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase();

function due(c) {
  if (!c.slaDueAt) return "";
  const s = Math.round((new Date(c.slaDueAt) - Date.now()) / 1000);
  const a = Math.abs(s);
  const t = a >= 3600 ? `${Math.floor(a / 3600)}h ${String(Math.floor(a % 3600 / 60)).padStart(2, "0")}m` : `${Math.floor(a / 60)}:${String(a % 60).padStart(2, "0")}`;
  return s < 0 ? `<span class="late">${t} late</span>` : `<span class="due">${t}</span>`;
}

async function patient(id) {
  if (!patients[id]) {
    const r = await api("GET", `/api/clinician/patients/${id}`);
    patients[id] = r.ok ? r.data : { name: "Patient", ageBand: "?", sex: "" };
  }
  return patients[id];
}

async function refreshQueue() {
  const r = await api("GET", "/api/clinician/queue");
  if (!r.ok) { $("rows").innerHTML = `<tr><td colspan="6" class="refused">Queue unavailable (${r.status})</td></tr>`; return; }
  const q = r.data;
  const banner = $("banner");
  if (q.rulesOnlyMode) {
    banner.hidden = false;
    const why = q.model.mode === "UNREACHABLE" ? "the AI model service is not reachable" : `the AI model is switched off (${esc(q.model.reason || "no reason given")})`;
    banner.innerHTML = `<div class="wrap wide">${I("alert")} Rules-only mode: ${why}. Cases show rules results only; review continues as normal.</div>`;
  } else banner.hidden = true;

  const cases = q.cases.sort((a, b) => RANK[b.mergedUrgency] - RANK[a.mergedUrgency] || new Date(a.queuedAt) - new Date(b.queuedAt));
  const overdue = cases.filter((c) => c.slaDueAt && new Date(c.slaDueAt) < Date.now()).length;
  $("stats").innerHTML = `
    <div class="stat"><b>${cases.length}</b><span>waiting</span></div>
    <div class="stat"><b>${cases.filter((c) => c.status === "AI_PROPOSED").length}</b><span>AI-proposed</span></div>
    <div class="stat"><b>${overdue}</b><span>past deadline</span></div>`;
  if (!cases.length) {
    $("rows").innerHTML = `<tr><td colspan="6" class="muted" style="padding:28px;text-align:center">${I("check")} No cases waiting.</td></tr>`;
  } else {
    const rows = await Promise.all(cases.map(async (c) => {
      const p = await patient(c.patientId);
      return `<tr data-id="${c.consultationId}" class="${c.consultationId === selected ? "sel" : ""}">
        <td>${urg(c.mergedUrgency)}</td>
        <td class="pt"><b>${esc(p.name)}</b><span>${esc(p.ageBand)} · ${esc(p.sex)} · ${esc(c.channel)}</span></td>
        <td><span class="st ${c.status}">${statusLabel(c.status)}</span></td>
        <td>${urg(c.rulesUrgency)}</td><td>${c.modelUrgency ? urg(c.modelUrgency) : '<span class="muted small">pending</span>'}</td>
        <td>${due(c)}</td></tr>`;
    }));
    $("rows").innerHTML = rows.join("");
    document.querySelectorAll("#rows tr[data-id]").forEach((tr) => tr.onclick = () => select(tr.dataset.id));
  }
  $("updated").textContent = `Updated ${new Date().toLocaleTimeString()} · refreshes every 3 s`;
  if (selected) showCase(selected);
}

async function select(id) { selected = id; $("order-result").textContent = ""; await showCase(id); refreshQueue(); }

async function showCase(id) {
  const r = await api("GET", `/api/clinician/cases/${id}`);
  if (!r.ok) return;
  const c = r.data.case;
  const p = await patient(c.patientId);
  $("detail").hidden = false;
  $("d-avatar").textContent = initials(p.name);
  $("d-title").textContent = p.name;
  $("d-id").textContent = `${p.ageBand} · ${p.sex} · ${c.channel} · consultation ${c.consultationId.slice(0, 8)}`;
  $("d-status").innerHTML = `<span class="st ${c.status}">${statusLabel(c.status)}</span>`;
  $("d-rules").innerHTML = urg(c.rulesUrgency);
  $("d-flags").innerHTML = c.redFlags.length ? `Red flags: <b>${c.redFlags.map(esc).join(", ")}</b>` : "No red flags";
  $("d-ruleset").textContent = `Rule set ${c.rulesetVersion}`;
  $("d-model").innerHTML = c.modelUrgency
    ? `${urg(c.modelUrgency)} <span class="muted small">confidence ${Number(c.modelConfidence).toFixed(2)}</span>`
    : '<span class="muted">No suggestion: rules only</span>';
  $("d-factors").innerHTML = c.contributingFactors.length ? `Contributing factors: ${c.contributingFactors.map((f) => esc(f.replace(/_/g, " "))).join(", ")}` : "";
  $("d-modelver").textContent = c.modelVersion ? `Model ${c.modelVersion} (${c.modelMode})` : "";
  $("d-merged").innerHTML = urg(c.finalUrgency || c.mergedUrgency);

  const final = c.status === "FINAL";
  $("actions").hidden = final;
  $("b-open").disabled = c.status === "UNDER_REVIEW";
  $("signed").hidden = !final;
  if (final) {
    $("signed").innerHTML = `${I("check")} <b>${c.reviewDecision === "OVERRIDE" ? "Overridden" : "Confirmed"} as ${esc(LABEL[c.finalUrgency])}</b>
      by ${esc(c.reviewedBy)}, ${new Date(c.reviewedAt).toLocaleString()}${c.reviewReason ? `<br>Reason: ${esc(c.reviewReason)}` : ""}`;
  }
  $("d-history").innerHTML = c.history.map((h) =>
    `<li><b>${esc(statusLabel(h.to))}</b> ${esc(h.reason)} <span class="muted small">(${esc(h.actor)}, ${new Date(h.at).toLocaleTimeString()})</span></li>`).join("");
}

async function review(body) {
  const r = await api("POST", `/triage/v1/cases/${selected}/review`, body);
  if (!r.ok) alert(r.data?.issue?.[0]?.diagnostics || `Review failed (${r.status})`);
  await showCase(selected);
  refreshQueue();
}

$("b-open").onclick = async () => { await api("POST", `/api/clinician/cases/${selected}/open`); await showCase(selected); refreshQueue(); };
$("b-confirm").onclick = () => review({ decision: "CONFIRM" });
$("b-override").onclick = () => {
  const reason = $("o-reason").value.trim();
  if (!reason) { alert("An override needs a reason."); return; }
  review({ decision: "OVERRIDE", finalUrgency: $("o-urgency").value, reason });
};
$("b-order").onclick = async () => {
  const r = await api("POST", `/api/clinician/consultations/${selected}/orders`, { kind: "PRESCRIPTION", detail: "illustrative order" });
  $("order-result").className = r.ok ? "ok" : "refused";
  $("order-result").innerHTML = r.ok ? `${I("check")} Order placed.` : `${I("lock")} Refused: ${esc(r.data?.issue?.[0]?.diagnostics || r.status)}`;
};

(async function start() {
  $("logout-icon").innerHTML = I("logout");
  $("ico-rules").innerHTML = I("shield");
  $("ico-ai").innerHTML = I("spark");
  $("ico-rx").innerHTML = I("clipboard");
  const me = await api("GET", "/api/me");
  $("who").textContent = `${me.data.name}`;
  $("csrf").value = xsrf();
  await refreshQueue();
  setInterval(refreshQueue, 3000);
})();
