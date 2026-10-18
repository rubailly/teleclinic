// Identity review: the queue with side-by-side evidence, link and dismiss with a reason, active links with unlink.
"use strict";
const $ = (id) => document.getElementById(id);
const I = TC.icon;
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const xsrf = () => decodeURIComponent((document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || "");

async function api(method, path, body) {
  const res = await fetch(path, { method, credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": xsrf() }, body: body === undefined ? undefined : JSON.stringify(body) });
  if (res.status === 401) { location.href = "/login"; throw new Error("signed out"); }
  const data = (res.headers.get("content-type") || "").includes("json") ? await res.json() : null;
  return { ok: res.ok, status: res.status, data };
}

const name = (p) => `${(p.name?.[0]?.given || []).join(" ")} ${p.name?.[0]?.family || ""}`.trim();
const ids = (p) => (p.identifier || []).map((i) => `${i.type.text}: ${i.value}`).join("<br>") || '<span class="muted">none</span>';
const phone = (p) => p.telecom?.[0]?.value || '<span class="muted">none</span>';
const sex = (p) => ({ female: "F", male: "M" }[p.gender] || "?");

function rowClass(field, item) {
  const has = (list) => list.some((f) => f.startsWith(field));
  return has(item.conflictingFields) ? "conflict" : has(item.agreeingFields) ? "agree" : "";
}

function pairCard(item) {
  const l = item.left, r = item.right;
  return `<article class="card raised pair" data-id="${item.id}">
    <div class="pair-head"><h3 style="margin:0">${esc(name(l))} <span class="muted">and</span> ${esc(name(r))}</h3>
      <span class="score">score ${item.score}</span></div>
    <table class="side-by-side">
      <thead><tr><th></th><th>TeleClinic</th><th>${esc(r.meta?.source || "Facility EMR")}</th></tr></thead>
      <tbody>
        <tr class="${rowClass("name", item)}"><td>Name</td><td>${esc(name(l))}</td><td>${esc(name(r))}</td></tr>
        <tr class="${rowClass("birth date", item)}"><td>Birth date</td><td>${esc(l.birthDate)}</td><td>${esc(r.birthDate)}</td></tr>
        <tr class="${rowClass("sex", item)}"><td>Sex</td><td>${sex(l)}</td><td>${sex(r)}</td></tr>
        <tr class="${rowClass("phone", item)}"><td>Phone</td><td>${phone(l)}</td><td>${phone(r)}</td></tr>
        <tr class="${rowClass("national ID", item) || rowClass("UPI", item)}"><td>Identifiers</td><td>${ids(l)}</td><td>${ids(r)}</td></tr>
      </tbody>
    </table>
    <div class="chips">${item.agreeingFields.map((f) => `<span class="chip-ok">${I("check")} ${esc(f)}</span>`).join("")}
      ${item.conflictingFields.map((f) => `<span class="chip-bad">${esc(f)}</span>`).join("")}</div>
    <div class="decide">
      <input placeholder="Reason (required): how you confirmed it" data-reason>
      <button class="btn btn-primary" data-link>${I("check")} Same person: link</button>
      <button class="btn btn-light" data-dismiss>Not the same person</button>
    </div>
  </article>`;
}

async function refresh() {
  const [q, links, blocked] = await Promise.all([api("GET", "/identity/review-queue"), api("GET", "/identity/links"), api("GET", "/identity/blocked")]);
  if (!q.ok) return;
  $("stats").innerHTML = [[q.data.length, "waiting"], [links.data.length, "linked"], [blocked.data.length, "never offered"]]
    .map(([b, s]) => `<div class="stat"><b>${b}</b><span>${s}</span></div>`).join("");
  $("queue").innerHTML = q.data.length ? q.data.map(pairCard).join("")
    : `<div class="card raised empty muted">${I("check")} Nothing waiting. New facility records are scored as they arrive.</div>`;
  $("links").innerHTML = links.data.length ? links.data.map((x) => `<div class="item"><div class="row"><div>
      <b>${esc(name(x.patient))}</b><span class="muted">${x.link.decidedBy === "auto" ? "automatic" : "by a clerk"}: ${esc(x.link.reason || "")}</span></div>
      <button class="btn btn-light" data-unlink="${x.link.id}">Unlink</button></div></div>`).join("") : '<p class="muted small">No links yet.</p>';
  $("blocked").innerHTML = blocked.data.length ? blocked.data.map((x) => `<div class="item"><b>${esc(name(x.left))}</b>
      <span class="muted">${esc(x.left.identifier.map((i) => i.value).join(", "))} here, ${esc(x.right.identifier.filter((i) => i.type.text.startsWith("National")).map((i) => i.value).join(", "))} at the facility</span></div>`).join("")
    : '<p class="muted small">None.</p>';

  document.querySelectorAll("[data-link]").forEach((b) => b.onclick = async () => {
    const card = b.closest(".pair"), reason = card.querySelector("[data-reason]").value.trim();
    if (!reason) { card.querySelector("[data-reason]").focus(); return; }
    const item = q.data.find((i) => i.id === card.dataset.id);
    const r = await api("POST", "/identity/links", { sourcePatient: `Patient/${item.left.id}`, targetPatient: `Patient/${item.right.id}`,
      linkType: "seealso", decidedBy: "clerk", reason });
    if (!r.ok) alert(r.data?.issue?.[0]?.diagnostics || r.status);
    refresh();
  });
  document.querySelectorAll("[data-dismiss]").forEach((b) => b.onclick = async () => {
    const card = b.closest(".pair"), reason = card.querySelector("[data-reason]").value.trim() || "not the same person";
    await api("POST", `/identity/review-queue/${card.dataset.id}/dismiss`, { reason });
    refresh();
  });
  document.querySelectorAll("[data-unlink]").forEach((b) => b.onclick = async () => {
    const reason = prompt("Why is this link wrong?");
    if (!reason) return;
    await api("DELETE", `/identity/links/${b.dataset.unlink}?reason=${encodeURIComponent(reason)}`);
    refresh();
  });
}

(async function start() {
  $("logout-icon").innerHTML = I("logout");
  const me = await api("GET", "/api/me");
  $("who").textContent = me.data ? me.data.name : "";
  $("csrf").value = xsrf();
  await refresh();
})();
