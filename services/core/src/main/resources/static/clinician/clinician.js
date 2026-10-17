// Clinician queue. Talks only to the core's own API with the session cookie; no tokens in the browser.
"use strict";

const $ = (id) => document.getElementById(id);
const RANK = { EMERGENCY: 4, URGENT: 3, SEMI_URGENT: 2, ROUTINE: 1 };
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
  const data = res.headers.get("content-type")?.includes("json") ? await res.json() : null;
  return { ok: res.ok, status: res.status, data };
}

const urg = (u) => u ? `<span class="u ${u}">${u.replace("_", " ")}</span>` : `<span class="muted">pending</span>`;
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

function due(c) {
  if (!c.slaDueAt) return "";
  const s = Math.round((new Date(c.slaDueAt) - Date.now()) / 1000);
  const t = `${Math.floor(Math.abs(s) / 60)}:${String(Math.abs(s) % 60).padStart(2, "0")}`;
  return s < 0 ? `<span class="late">${t} late</span>` : t;
}

async function patient(id) {
  if (!patients[id]) {
    const r = await api("GET", `/api/clinician/patients/${id}`);
    patients[id] = r.ok ? r.data : { name: "Patient", ageBand: "?" };
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
    banner.textContent = q.model.mode === "UNREACHABLE"
      ? "Rules-only mode: the AI model service is not reachable. Cases show rules results only."
      : `Rules-only mode: the AI model is switched off (${q.model.reason || "no reason given"}). Cases show rules results only.`;
  } else banner.hidden = true;

  const cases = q.cases.sort((a, b) => RANK[b.mergedUrgency] - RANK[a.mergedUrgency] || new Date(a.queuedAt) - new Date(b.queuedAt));
  if (!cases.length) { $("rows").innerHTML = `<tr><td colspan="6" class="muted">No cases waiting.</td></tr>`; }
  else {
    const rows = await Promise.all(cases.map(async (c) => {
      const p = await patient(c.patientId);
      const status = c.status === "AI_PROPOSED" ? "AI-PROPOSED" : c.status.replace(/_/g, " ");
      return `<tr data-id="${c.consultationId}" class="${c.consultationId === selected ? "sel" : ""}">
        <td>${urg(c.mergedUrgency)}</td><td>${esc(p.name)} <span class="muted small">${esc(p.ageBand)} · ${esc(c.channel)}</span></td>
        <td><span class="badge ${c.status}">${status}</span></td><td>${urg(c.rulesUrgency)}</td>
        <td>${c.modelUrgency ? urg(c.modelUrgency) : '<span class="muted">none</span>'}</td><td>${due(c)}</td></tr>`;
    }));
    $("rows").innerHTML = rows.join("");
    document.querySelectorAll("#rows tr[data-id]").forEach((tr) => tr.onclick = () => select(tr.dataset.id));
  }
  $("updated").textContent = `Updated ${new Date().toLocaleTimeString()}`;
  if (selected) showCase(selected, false);
}

async function select(id) { selected = id; $("order-result").textContent = ""; await showCase(id, true); refreshQueue(); }

async function showCase(id) {
  const r = await api("GET", `/api/clinician/cases/${id}`);
  if (!r.ok) return;
  const c = r.data.case;
  const p = await patient(c.patientId);
  $("detail").hidden = false;
  $("d-title").textContent = `${p.name} (${p.ageBand}, ${p.sex})`;
  $("d-id").textContent = `Consultation ${c.consultationId} · ${c.channel}`;
  $("d-rules").innerHTML = urg(c.rulesUrgency);
  $("d-flags").innerHTML = c.redFlags.length ? `Red flags: <b>${c.redFlags.map(esc).join(", ")}</b>` : "No red flags";
  $("d-ruleset").textContent = `Rule set ${c.rulesetVersion}`;
  $("d-model").innerHTML = c.modelUrgency ? `${urg(c.modelUrgency)} <span class="muted small">confidence ${Number(c.modelConfidence).toFixed(2)}</span>` : '<span class="muted">No suggestion (rules only)</span>';
  $("d-factors").innerHTML = c.contributingFactors.length ? `Contributing factors: ${c.contributingFactors.map(esc).join(", ")}` : "";
  $("d-modelver").textContent = c.modelVersion ? `Model ${c.modelVersion} · ${c.modelMode}` : "";
  $("d-merged").innerHTML = urg(c.finalUrgency || c.mergedUrgency);
  $("d-status").innerHTML = `<span class="badge ${c.status}">${c.status === "AI_PROPOSED" ? "AI-PROPOSED" : c.status.replace(/_/g, " ")}</span>`;

  const final = c.status === "FINAL";
  $("actions").hidden = final;
  $("b-open").disabled = c.status === "UNDER_REVIEW";
  $("signed").hidden = !final;
  if (final) {
    $("signed").innerHTML = `<b>${esc(c.reviewDecision === "OVERRIDE" ? "Overridden" : "Confirmed")} as ${esc(c.finalUrgency)}</b>
      by ${esc(c.reviewedBy)} at ${new Date(c.reviewedAt).toLocaleString()}${c.reviewReason ? `<br>Reason: ${esc(c.reviewReason)}` : ""}`;
  }
  $("d-history").innerHTML = c.history.map((h) =>
    `<li><b>${esc(h.to)}</b> ${esc(h.reason)} <span class="muted small">(${esc(h.actor)}, ${new Date(h.at).toLocaleTimeString()})</span></li>`).join("");
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
  $("order-result").textContent = r.ok ? "Order placed." : `Refused: ${r.data?.issue?.[0]?.diagnostics || r.status}`;
};

(async function start() {
  const me = await api("GET", "/api/me");
  $("who").textContent = `${me.data.name} (${me.data.roles.join(", ")})`;
  $("csrf").value = xsrf();
  await refreshQueue();
  setInterval(refreshQueue, 3000);
})();
