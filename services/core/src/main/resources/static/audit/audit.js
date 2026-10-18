// Audit explorer: the latest entries from both sources, chain status, and one entry in full.
"use strict";
const $ = (id) => document.getElementById(id);
const I = TC.icon;
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const xsrf = () => (document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || "";
const short = (h) => h ? h.slice(0, 10) + "…" : "";
let source = "", entries = [], selected = null, status = null, searches = false;

async function api(path) {
  const res = await fetch(path, { credentials: "same-origin", headers: { "X-XSRF-TOKEN": decodeURIComponent(xsrf()) } });
  if (res.status === 401) { location.href = "/login"; throw new Error("signed out"); }
  return { ok: res.ok, status: res.status, data: res.ok ? await res.json() : null };
}

function renderChain() {
  const el = $("chain");
  if (!status) { el.className = "chain"; el.innerHTML = ""; return; }
  const ok = status.status === "INTACT";
  el.className = "chain " + (ok ? "intact" : "broken");
  el.innerHTML = `<div class="wrap wide">${I(ok ? "shield" : "alert")}
    ${ok ? `Chain intact: ${status.checked.toLocaleString()} entries recomputed` : `Chain BROKEN at entry ${status.firstBrokenSeq}: ${esc(status.problem)}`}
    <span class="hash">head ${short(status.headHash)} · checked ${new Date(status.checkedAt).toLocaleTimeString()}</span>
    <button class="btn btn-light" id="verify">${I("check")} Verify now</button></div>`;
  $("verify").onclick = verify;
}

function renderStats() {
  const app = entries.filter((e) => e.source === "APPLICATION").length;
  const db = entries.filter((e) => e.source === "DATABASE").length;
  const denied = entries.filter((e) => e.outcome === "DENIED").length;
  const glass = entries.filter((e) => e.purpose === "BREAK_GLASS").length;
  $("stats").innerHTML = [[status ? status.headSeq.toLocaleString() : "…", "entries"], [app, "application (shown)"],
    [db, "database (shown)"], [denied, "refused"], [glass, "break-glass"]]
    .map(([b, s]) => `<div class="stat"><b>${b}</b><span>${s}</span></div>`).join("");
}

function who(e) {
  return e.source === "DATABASE" ? `<span class="mono">${esc(e.actor)}</span> <span class="muted">db role</span>`
    : `${esc(e.actor_name || e.actor)} <span class="muted">${esc(e.actor_role || "")}</span>`;
}

function renderRows() {
  const broken = status && status.status === "BROKEN" ? status.firstBrokenSeq : null;
  $("rows").innerHTML = entries.length ? entries.map((e) => `<tr data-seq="${e.seq}" class="${e.seq === selected ? "sel" : ""} ${e.seq === broken ? "broken" : ""}">
    <td class="mono">${e.seq}</td>
    <td>${new Date(e.recorded_at).toLocaleTimeString()}</td>
    <td><span class="src ${e.source}">${e.source === "APPLICATION" ? "APP" : "DB"}</span></td>
    <td>${who(e)}</td>
    <td>${esc(e.action)}</td>
    <td>${esc(e.resource_type || "")}${e.resource_id ? ` <span class="mono">${esc(String(e.resource_id).slice(0, 36))}</span>` : ""}</td>
    <td>${e.purpose ? `<span class="pur ${e.purpose}">${esc(e.purpose)}</span>` : ""}</td>
    <td class="out-${e.outcome}">${esc(e.outcome)}</td></tr>`).join("") : `<tr><td colspan="8" class="muted">No entries.</td></tr>`;
  document.querySelectorAll("#rows tr[data-seq]").forEach((tr) => tr.onclick = () => { selected = Number(tr.dataset.seq); renderRows(); renderEntry(); });
}

function renderEntry() {
  const e = entries.find((x) => x.seq === selected);
  if (!e) return;
  const fields = [["Sequence", e.seq], ["Recorded", new Date(e.recorded_at).toLocaleString()], ["Source", e.source],
    ["Actor", e.actor], ["Name", e.actor_name], ["Role", e.actor_role], ["Action", e.action], ["Resource", [e.resource_type, e.resource_id].filter(Boolean).join(" / ")],
    ["Patient", e.patient_id], ["Purpose", e.purpose], ["Basis", e.consent_basis], ["Outcome", e.outcome], ["Reason", e.reason], ["Correlation", e.correlation_id]]
    .filter(([, v]) => v !== null && v !== undefined && v !== "");
  $("entry").innerHTML = `<h3>Entry ${e.seq}</h3>
    <dl>${fields.map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join("")}</dl>
    ${Object.keys(e.detail || {}).length ? `<pre>${esc(JSON.stringify(e.detail, null, 2))}</pre>` : ""}
    <div class="link">${I("chevron")} previous <span class="mono">${esc(e.prev_hash)}</span></div>
    <div class="link">${I("lock")} this entry <span class="mono">${esc(e.hash)}</span></div>
    <p class="muted small">The hash covers every field above, the sequence number and the previous hash. Changing any of them breaks this entry and every entry after it.</p>`;
}

async function refresh() {
  const pid = $("patient").value.trim();
  const q = new URLSearchParams({ limit: "200" });
  if (source) q.set("source", source);
  if (!searches) q.set("hideSearches", "true");
  if (/^[0-9a-f-]{36}$/i.test(pid)) q.set("patientId", pid);
  const r = await api(`/api/audit/events?${q}`);
  if (!r.ok) return;
  entries = r.data.entries;
  renderRows();
  renderStats();
  $("updated").textContent = `Updated ${new Date().toLocaleTimeString()}`;
}

async function verify() {
  const r = await api("/api/audit/verify");
  if (r.ok) status = r.data;
  renderChain();
  renderRows();
  renderStats();
}

document.querySelectorAll("#source button").forEach((b) => b.onclick = () => {
  source = b.dataset.source;
  document.querySelectorAll("#source button").forEach((x) => x.classList.toggle("on", x === b));
  refresh();
});
$("patient").addEventListener("change", refresh);
$("searches").onchange = (ev) => { searches = ev.target.checked; refresh(); };
$("refresh").onclick = refresh;

(async function start() {
  $("logout-icon").innerHTML = I("logout");
  const me = await api("/api/me");
  $("who").textContent = me.data ? me.data.name : "";
  $("csrf").value = decodeURIComponent(xsrf());
  await verify();
  // No polling: every read of the trail is itself recorded, so the explorer reads when asked
  await refresh();
})();
