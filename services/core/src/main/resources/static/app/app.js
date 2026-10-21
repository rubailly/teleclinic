// TeleClinic patient app. Talks to the core with the session cookie only (backend for frontend): no tokens here.
"use strict";
(function () {
  const I = TC.icon;
  const root = document.getElementById("root");
  let lang = safeGet("tc.lang") || "en";
  let me = undefined; // undefined: not checked yet; null: signed out
  let pollTimer = null;

  // ---------- helpers ----------
  function safeGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function safeSet(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } }
  // crypto.randomUUID needs a secure origin (HTTPS or localhost); getRandomValues works on plain HTTP too
  function uuid() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
    const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
  }
  const t = (k, vars = {}) => String(TC.text[lang][k] ?? TC.text.en[k] ?? k).replace(/\{(\w+)\}/g, (_, v) => esc(vars[v] ?? ""));
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const xsrf = () => (document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || "";
  const money = (n) => Number(n).toLocaleString("en-US");
  const first = (name) => String(name || "").split(" ")[0];
  const initials = (name) => String(name || "?").split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase();
  const go = (hash) => { location.hash = hash; };

  async function api(method, path, body, headers = {}) {
    const res = await fetch(path, {
      method, credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": decodeURIComponent(xsrf()), ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    let data = null;
    if ((res.headers.get("content-type") || "").includes("json")) data = await res.json();
    return { ok: res.ok, status: res.status, data };
  }

  async function whoAmI() {
    if (me !== undefined) return me;
    const r = await api("GET", "/api/me");
    me = r.ok ? r.data : null;
    // Staff who signed in here go to their own page: the patient app has nothing to show a clinician
    const roles = me?.roles || [];
    const home = roles.includes("patient") ? null : roles.includes("clinician") ? "/clinician/"
      : roles.includes("clerk") ? "/clerk/" : roles.includes("admin") ? "/audit/" : null;
    if (home) { location.replace(home); return new Promise(() => {}); }
    return me;
  }

  function signIn() {
    safeSet("tc.after", location.hash || "#/start");
    location.href = "/login?next=/app/";
  }

  function toast(msg) {
    const el = document.createElement("div");
    el.className = "toast";
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 3200);
  }

  // ---------- layout ----------
  function layout(hero, body, { compactHero = true } = {}) {
    const signedIn = !!me;
    const narrow = window.matchMedia("(max-width: 720px)").matches;
    root.innerHTML = `
      <div class="notice">${I("bell")}${t("notice")}</div>
      <header class="topbar">
        <div class="wrap">
          <nav class="nav">
            <a class="wordmark" href="#/"><b>Tele</b><span>Clinic</span><small>IremboHub</small></a>
            <div class="nav-links">
              <a href="#/" class="opt-mobile">${I("help")}<span class="label">${t("help")}</span></a>
              ${signedIn ? `<a href="#/visits">${I("clipboard")}<span class="label">${t("myVisits")}</span></a>` : ""}
              ${signedIn
                ? `<button id="signout">${I("logout")}<span class="label">${t("signOut")}</span></button>`
                : `<button id="signin">${I("login")}<span class="label">${t("signIn")}</span></button>`}
              <label class="lang">${I("globe")}
                <select id="lang" aria-label="Language">
                  <option value="en" ${lang === "en" ? "selected" : ""}>${narrow ? "EN" : "English"}</option>
                  <option value="rw" ${lang === "rw" ? "selected" : ""}>${narrow ? "RW" : "Kinyarwanda"}</option>
                </select></label>
            </div>
          </nav>
          <div class="hero ${compactHero ? "compact" : ""}">${hero}</div>
        </div>
      </header>
      <main class="wrap">${body}</main>
      <footer class="footer"><div class="wrap"><span>${t("footer")}</span><span>${t("footer2")}</span></div></footer>
      <form id="logout-form" method="post" action="/logout" hidden><input type="hidden" name="_csrf" value="${esc(decodeURIComponent(xsrf()))}"></form>`;
    document.getElementById("lang").onchange = (e) => { lang = e.target.value; safeSet("tc.lang", lang); document.documentElement.lang = lang === "rw" ? "rw" : "en"; render(); };
    const si = document.getElementById("signin"); if (si) si.onclick = signIn;
    const so = document.getElementById("signout"); if (so) so.onclick = () => document.getElementById("logout-form").submit();
  }

  function needSignIn() {
    layout(`<h1>${t("signIn")}</h1>`, `
      <div class="narrow page"><div class="page-card signin-card">
        <div class="ring">${I("lock")}</div>
        <h2>${t("signInFirst")}</h2>
        <p class="muted">IremboAccount</p>
        <button class="btn btn-primary btn-lg" id="go-signin">${I("login")} ${t("continueIrembo")}</button>
      </div></div>`);
    document.getElementById("go-signin").onclick = signIn;
  }

  function pageHead(back, stepsOn) {
    const mini = stepsOn ? `<div class="steps-mini">${[1, 2, 3, 4].map((n) => `<i class="${n <= stepsOn ? "on" : ""}"></i>`).join("")}</div>` : "";
    return `<div class="page-head">${back ? `<a class="back" href="${back}">${I("back")} ${t("back")}</a>` : ""}${mini}</div>`;
  }

  // ---------- views ----------
  async function home() {
    await whoAmI();
    layout(`
      <h1>${t("heroTitle")}</h1>
      <p>${t("heroText")}</p>
      <div class="hero-actions">
        <a class="btn btn-white btn-lg" href="#/start">${I("stethoscope")} ${t("start")}</a>
        <a class="btn btn-outline btn-lg" href="#ussd">${I("phone")} USSD</a>
      </div>
      <div class="trust"><span>${I("shield")} IremboAccount</span><span>${I("wallet")} IremboPay</span><span>${I("heart")} Mutuelle de Santé / RSSB</span></div>`,
    `
      <section class="section">
        <div class="panel-info" id="ussd">
          <div class="ring">${I("phone")}</div>
          <div class="grow"><h3>${t("ussdTitle")}</h3><p>${t("ussdText")}</p></div>
          <a class="btn btn-primary" href="#/start">${t("start")} ${I("out")}</a>
        </div>
      </section>
      <section class="section how" style="padding-top:0">
        <h2 class="section-title">${t("howTitle")}</h2>
        <div class="grid grid-2" style="grid-template-columns:repeat(auto-fit,minmax(230px,1fr))">
          ${[1, 2, 3, 4].map((n) => `<div class="tile"><div class="num">${n}</div><div><h4>${t("how" + n)}</h4><p>${t("how" + n + "d")}</p></div></div>`).join("")}
        </div>
      </section>
      <section class="section" style="padding-top:0">
        <h2 class="section-title">${t("healthServices")}</h2>
        <div class="grid grid-3">
          <a class="tile" href="#/start"><div class="ico">${I("video")}</div><div><h4>${t("svcTele")}</h4><p>${t("svcTeleD")}</p></div></a>
          <a class="tile" href="#/visits"><div class="ico">${I("clipboard")}</div><div><h4>${t("svcVisits")}</h4><p>${t("svcVisitsD")}</p></div></a>
          <a class="tile" href="#/access"><div class="ico">${I("eye")}</div><div><h4>${t("svcAccess")}</h4><p>${t("svcAccessD")}</p></div></a>
        </div>
      </section>`, { compactHero: false });
  }

  async function start() {
    if (!(await whoAmI())) return needSignIn();
    layout(`<h1>${t("whoFor")}</h1><p>${t("whoForD")}</p>`, `<div class="narrow page"><div class="page-card">${pageHead("#/", 1)}<div id="people"><span class="spinner"></span></div></div></div>`);
    const r = await api("GET", "/api/me/patients");
    if (!r.ok) return toast(t("error"));
    document.getElementById("people").innerHTML = `<div class="grid">${r.data.map((p) => `
      <button class="tile" data-pid="${p.patientId}" data-name="${esc(p.name)}" data-rel="${p.relation}">
        <div class="avatar ${p.relation === "GUARDIAN" ? "child" : ""}">${initials(p.name)}</div>
        <div><h4>${esc(p.name)} ${p.relation === "SELF" ? `<span class="badge">${t("me")}</span>` : `<span class="badge ok">${t("guardianOf")}</span>`}</h4>
        <p>${t("ageBand")} ${esc(p.ageBand)}${p.relation === "GUARDIAN" ? " · " + t("rel" + p.relationship) : ""}</p></div>
        <span style="margin-left:auto;color:var(--muted)">${I("arrow")}</span>
      </button>`).join("")}</div>`;
    document.querySelectorAll("[data-pid]").forEach((b) => b.onclick = async () => {
      sessionStorage.setItem("tc.patient", JSON.stringify({ id: b.dataset.pid, name: b.dataset.name, rel: b.dataset.rel }));
      const c = await api("GET", `/api/patients/${b.dataset.pid}/consents/active`);
      go(c.ok ? `#/symptoms/${b.dataset.pid}` : `#/consent/${b.dataset.pid}`);
    });
  }

  const chosen = () => { try { return JSON.parse(sessionStorage.getItem("tc.patient")) || {}; } catch (e) { return {}; } };

  async function consent(pid) {
    if (!(await whoAmI())) return needSignIn();
    const p = chosen();
    const name = first(p.name);
    layout(`<h1>${t("consentTitle")}</h1><p>${t("consentIntro")}</p>`, `
      <div class="narrow page"><div class="page-card">${pageHead("#/start", 2)}
        ${p.rel === "GUARDIAN" ? `<div class="cover" style="margin-bottom:16px">${I("shield")} ${t("consentGuardian", { name: p.name })}</div>` : ""}
        <label class="check"><input type="checkbox" checked disabled><div><b>${t("cTreat")}</b><span>${t("cTreatD", { name })}</span></div></label>
        <label class="check"><input type="checkbox" checked disabled><div><b>${t("cPay")}</b><span>${t("cPayD")}</span></div></label>
        <label class="check"><input type="checkbox" id="ai"><div><b>${I("spark")} ${t("cAi")}</b><span>${t("cAiD")}</span></div></label>
        <button class="btn btn-primary btn-block btn-lg" id="agree" style="margin-top:8px">${I("check")} ${t("agree")}</button>
      </div></div>`);
    document.getElementById("agree").onclick = async () => {
      const purposes = ["TREAT", "PAYMENT"].concat(document.getElementById("ai").checked ? ["AI_TRIAGE_SUPPORT"] : []);
      const r = await api("POST", `/api/patients/${pid}/consents`, { purposes });
      if (!r.ok) return toast(r.data?.issue?.[0]?.diagnostics || t("error"));
      go(`#/symptoms/${pid}`);
    };
  }

  const SYMPTOMS = ["fever", "cough", "headache", "diarrhoea", "vomiting", "abdominal_pain", "rash", "fast_breathing", "bleeding"];
  const DANGER = ["difficulty_breathing", "chest_pain", "convulsions", "unconscious", "severe_bleeding"];

  async function symptoms(pid) {
    if (!(await whoAmI())) return needSignIn();
    const p = chosen();
    const picked = new Set();
    let days = 1;
    layout(`<h1>${t("symptomsTitle", { name: first(p.name) })}</h1><p>${t("symptomsD")}</p>`, `
      <div class="narrow page"><div class="page-card">${pageHead("#/start", 3)}
        <div class="chips" id="sym">${SYMPTOMS.map((s) => `<button class="chip" data-s="${s}">${t("sym_" + s)}</button>`).join("")}</div>
        <div class="danger-group"><h4>${I("alert")} ${t("dangerSigns")}</h4>
          <div class="chips">${DANGER.map((s) => `<button class="chip danger" data-s="${s}">${t("sym_" + s)}</button>`).join("")}</div></div>
        <div class="divider"></div>
        <div class="field"><label>${t("since")}</label>
          <div class="seg" id="days">${[[1, "1"], [2, "2"], [3, "3"], [5, "4-6"], [7, "7+"]].map(([v, l]) => `<button data-d="${v}" class="${v === 1 ? "on" : ""}">${l}</button>`).join("")}</div></div>
        <div class="field"><label>${I("thermo")} ${t("temperature")} <span class="muted small">(${t("optional")})</span></label>
          <input class="input" id="temp" type="number" step="0.1" min="34" max="43" placeholder="37.0" style="max-width:160px"></div>
        <button class="btn btn-primary btn-block btn-lg" id="advice" disabled>${t("getAdvice")} ${I("arrow")}</button>
      </div></div>`);
    const btn = document.getElementById("advice");
    document.querySelectorAll("[data-s]").forEach((c) => c.onclick = () => {
      c.classList.toggle("on");
      c.classList.contains("on") ? picked.add(c.dataset.s) : picked.delete(c.dataset.s);
      btn.disabled = picked.size === 0;
    });
    document.querySelectorAll("[data-d]").forEach((b) => b.onclick = () => {
      document.querySelectorAll("[data-d]").forEach((x) => x.classList.remove("on"));
      b.classList.add("on");
      days = Number(b.dataset.d);
    });
    btn.onclick = async () => {
      btn.disabled = true;
      btn.innerHTML = `<span class="spinner"></span>`;
      const temp = parseFloat(document.getElementById("temp").value);
      const body = { patientId: pid, channel: "WEB", symptoms: [...picked], durationDays: days,
        vitals: isNaN(temp) ? null : { temperatureC: temp } };
      const r = await api("POST", "/api/consultations", body, { "Idempotency-Key": uuid() });
      if (!r.ok) { btn.disabled = false; btn.textContent = t("getAdvice"); return toast(r.data?.issue?.[0]?.diagnostics || t("error")); }
      go(`#/advice/${r.data.id}`);
    };
  }

  function adviceCard(c) {
    const icon = c.urgency === "ROUTINE" ? "check" : c.urgency === "SEMI_URGENT" ? "clock" : "alert";
    return `<div class="advice ${c.urgency}"><div class="ico">${I(icon)}</div>
      <div><h3>${t("urgency_" + c.urgency)}</h3><p>${esc(c.patientAdvice)}</p></div></div>`;
  }

  async function advice(cid) {
    if (!(await whoAmI())) return needSignIn();
    const r = await api("GET", `/api/consultations/${cid}`);
    if (!r.ok) return toast(t("error"));
    const c = r.data;
    const p = chosen();
    layout(`<h1>${t("adviceTitle", { name: first(p.name) || "" })}</h1>`, `
      <div class="narrow page"><div class="page-card">${pageHead(null, 3)}
        ${adviceCard(c)}
        <p class="muted small" style="margin:14px 0 18px">${I("stethoscope")} ${t("adviceReview")}</p>
        <button class="btn btn-primary btn-block btn-lg" id="pay">${t("toPayment")} ${I("arrow")}</button>
      </div></div>`);
    document.getElementById("pay").onclick = () => go(`#/pay/${cid}`);
  }

  async function pay(cid) {
    if (!(await whoAmI())) return needSignIn();
    layout(`<h1>${t("payTitle")}</h1>`, `<div class="narrow page"><div class="page-card">${pageHead(`#/advice/${cid}`, 4)}<div id="pay-body"><span class="spinner"></span></div></div></div>`);
    const r = await api("POST", `/api/consultations/${cid}/payment`);
    if (!r.ok) {
      if (r.status === 409) return go(`#/visit/${cid}`);
      document.getElementById("pay-body").innerHTML = `<div class="status-line bad">${I("alert")} ${esc(r.data?.issue?.[0]?.diagnostics || t("error"))}</div>`;
      return;
    }
    const p = r.data;
    const due = p.payBy ? new Date(p.payBy).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "";
    document.getElementById("pay-body").innerHTML = `
      <div class="pay-summary">
        <div class="pay-row"><span>${t("fee")}</span><span class="${p.insured ? "strike" : ""}">${money(p.fee)} ${p.currency}</span></div>
        ${p.insured ? `<div class="cover">${I("shield")} ${t("covered", { scheme: p.coverageScheme === "CBHI" ? "Mutuelle de Santé (CBHI)" : p.coverageScheme })}</div>`
                    : `<div class="cover none">${I("info")} ${t("notCovered")}</div>`}
        <div class="pay-row total"><span>${t("youPay")}</span><span class="amount">${money(p.amount)} <small>${p.currency}</small></span></div>
      </div>
      <button class="btn btn-primary btn-block btn-lg" id="go-pay" style="margin-top:18px">${I("wallet")} ${t("payWith")}</button>
      <p class="muted small center" style="margin-top:10px">${I("clock")} ${t("payBy", { time: due })}</p>`;
    document.getElementById("go-pay").onclick = () => { location.href = p.checkoutUrl; };
  }

  const STEP_OF = { DRAFT: 0, TRIAGED: 1, PENDING_PAYMENT: 1, PAID: 2, QUEUED: 2, IN_CONSULTATION: 3, COMPLETED: 4, SYNC_PENDING: 4, SYNCED: 4 };

  async function visit(cid) {
    if (!(await whoAmI())) return needSignIn();
    const r = await api("GET", `/api/consultations/${cid}`);
    if (!r.ok) return toast(t("error"));
    const c = r.data;
    const at = STEP_OF[c.state] ?? 0;
    const labels = ["stepBooked", "stepPay", "stepQueue", "stepClinician", "stepDone"];
    const status = {
      PENDING_PAYMENT: `<div class="status-line"><span class="spinner"></span> ${t("waitingPay")}</div>`,
      QUEUED: `<div class="status-line">${I("check")} ${t("inQueue")}</div>`,
      PAID: `<div class="status-line">${I("check")} ${t("inQueue")}</div>`,
      EXPIRED: `<div class="status-line bad">${I("clock")} ${t("expired")}</div>`,
      REFUND_PENDING: `<div class="status-line bad">${I("wallet")} ${t("refund")}</div>`,
    }[c.state] || "";
    layout(`<h1>${t("visitTitle")}</h1><p>${new Date(c.history[0]?.at || Date.now()).toLocaleString()}</p>`, `
      <div class="narrow page"><div class="page-card">
        <div class="stepper">${labels.map((l, i) => `<div class="s ${i < at || (i === at && at === 4) ? "done" : i === at ? "now" : ""}">
          <div class="dot">${i < at || (i === at && at === 4) ? I("tick") : i + 1}</div><span>${t(l)}</span></div>`).join("")}</div>
        ${status}
        ${c.urgency ? adviceCard(c) : ""}
        <div class="visit-meta">
          ${c.amountDue != null ? `<span>${I("wallet")} ${money(c.amountDue)} ${c.currency}${c.coverageScheme ? " · " + esc(c.coverageScheme) : ""}</span>` : ""}
          <span>${I(c.channel === "USSD" ? "phone" : "globe")} ${esc(c.channel)}</span>
        </div>
        <h3>${t("history")}</h3>
        <ul class="timeline">${c.history.map((h) => `<li><span class="d"></span><span>${t("state_" + h.to)}</span>
          <time>${new Date(h.at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })}</time></li>`).join("")}</ul>
      </div></div>`);
    if (["PENDING_PAYMENT", "PAID", "QUEUED"].includes(c.state)) pollTimer = setTimeout(() => visit(cid), 2500);
  }

  async function visits() {
    if (!(await whoAmI())) return needSignIn();
    const r = await api("GET", "/api/consultations");
    const list = r.ok ? r.data : [];
    layout(`<h1>${t("visitsTitle")}</h1>`, `<div class="narrow page"><div class="page-card">
      ${list.length ? `<div class="grid">${list.map((c) => `<a class="tile visit-row" href="#/visit/${c.id}">
        <div class="ico">${I(c.channel === "USSD" ? "phone" : "video")}</div>
        <div class="grow"><h4>${new Date(c.history[0]?.at).toLocaleString()}</h4><p>${t("state_" + c.state)}</p></div>
        ${c.urgency ? `<span class="badge ${c.urgency}">${t("urgency_" + c.urgency)}</span>` : ""}</a>`).join("")}</div>`
        : `<p class="muted center">${t("none")}</p>`}
      <a class="btn btn-primary btn-block" href="#/start" style="margin-top:16px">${I("plus")} ${t("start")}</a>
    </div></div>`);
  }

  // Who accessed my record: every read and change of a person's health data, from the audit trail
  async function access(pid) {
    if (!(await whoAmI())) return needSignIn();
    if (!pid) {
      const r = await api("GET", "/api/me/patients");
      if (!r.ok) return toast(t("error"));
      if (r.data.length === 1) return go(`#/access/${r.data[0].patientId}`);
      layout(`<h1>${t("svcAccess")}</h1><p>${t("accessPick")}</p>`, `<div class="narrow page"><div class="page-card">
        <div class="grid">${r.data.map((p) => `<a class="tile" href="#/access/${p.patientId}">
          <div class="avatar ${p.relation === "GUARDIAN" ? "child" : ""}">${initials(p.name)}</div>
          <div><h4>${esc(p.name)} ${p.relation === "SELF" ? `<span class="badge">${t("me")}</span>` : `<span class="badge ok">${t("guardianOf")}</span>`}</h4></div>
          <span style="margin-left:auto;color:var(--muted)">${I("arrow")}</span></a>`).join("")}</div></div></div>`);
      return;
    }
    const [people, log] = await Promise.all([api("GET", "/api/me/patients"), api("GET", `/api/me/patients/${pid}/access-log`)]);
    if (!log.ok) return toast(t("error"));
    const who = (people.ok ? people.data : []).find((p) => p.patientId === pid);
    const showMine = safeGet("tc.access.mine") === "1";
    const entries = log.data.entries.filter((e) => showMine || !e.you);
    const icon = (e) => I(e.role === "clinician" ? "stethoscope" : e.you ? "user" : e.role === "admin" ? "shield" : "user");
    const purpose = (e) => e.purpose ? `<span class="badge purpose ${e.purpose}">${t("purpose_" + e.purpose)}</span>` : "";
    layout(`<h1>${t("svcAccess")}</h1><p>${who ? esc(who.name) + " · " : ""}${t("accessD")}</p>`, `
      <div class="narrow page"><div class="page-card">
        ${pageHead(people.ok && people.data.length > 1 ? "#/access" : "#/")}
        <label class="mine"><input type="checkbox" id="mine" ${showMine ? "checked" : ""}> ${t("showMine")}</label>
        ${entries.length ? `<ul class="access">${entries.map((e) => `<li class="${e.outcome === "DENIED" ? "denied" : ""}">
          <div class="who-ico ${e.role}">${icon(e)}</div>
          <div class="grow">
            <div class="line"><b>${e.you ? t("you") : esc(e.who || t("unknown"))}</b> <span class="muted">${esc(t("role_" + e.role) || e.role)}</span></div>
            <div>${t("action_" + e.action)} ${t("res_" + e.resource)}</div>
            <div class="chips">${purpose(e)}${e.outcome === "DENIED" ? `<span class="badge refused">${t("refused")}</span>` : ""}</div>
            ${e.breakGlassReason ? `<div class="reason">${I("alert")} ${t("reasonGiven")} “${esc(e.breakGlassReason)}”</div>` : ""}
          </div>
          <time>${new Date(e.at).toLocaleString([], { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })}</time>
        </li>`).join("")}</ul>` : `<p class="muted center">${t("accessNone")}</p>`}
        <p class="foot-note">${I("lock")} ${t("accessFoot")}</p>
      </div></div>`);
    document.getElementById("mine").onchange = (ev) => { safeSet("tc.access.mine", ev.target.checked ? "1" : "0"); access(pid); };
  }

  // ---------- router ----------
  async function render() {
    clearTimeout(pollTimer);
    const after = safeGet("tc.after");
    if (after && (await whoAmI())) { safeSet("tc.after", ""); if (after !== location.hash) return go(after); }
    const [, view, id] = (location.hash.replace(/^#\/?/, "") || "").match(/^([a-z]*)\/?(.*)$/) || [];
    const routes = { "": home, start, consent, symptoms, advice, pay, visit, visits, access };
    window.scrollTo(0, 0);
    try { await (routes[view] || home)(id); } catch (e) { console.error(e); toast(t("error")); }
  }
  window.addEventListener("hashchange", render);
  document.documentElement.lang = lang === "rw" ? "rw" : "en";
  render();
})();
