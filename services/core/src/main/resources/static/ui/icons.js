// Line icons for TeleClinic (24px grid, 1.8px stroke, currentColor). Drawn for this project; no icon font.
"use strict";
(function () {
  const P = {
    bell: '<path d="M6 16v-5a6 6 0 1 1 12 0v5l2 2H4z"/><path d="M10 20a2 2 0 0 0 4 0"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3.2 3.4 3.2 14.6 0 18M12 3c-3.2 3.4-3.2 14.6 0 18"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M16.5 16.5L21 21"/>',
    login: '<path d="M10 17l5-5-5-5M15 12H3M14 3h5a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-5"/>',
    logout: '<path d="M15 17l5-5-5-5M20 12H9M10 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5"/>',
    help: '<circle cx="12" cy="12" r="9"/><path d="M9.6 9.4a2.5 2.5 0 1 1 3.6 2.3c-.7.4-1.2 1-1.2 1.8v.5"/><circle cx="12" cy="17.2" r=".7" fill="currentColor"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1.6-4 4.6-6 8-6s6.4 2 8 6"/>',
    child: '<circle cx="12" cy="6.5" r="3"/><path d="M8 21l1-6-2-3.5c1.6-1.2 3.2-1.8 5-1.8s3.4.6 5 1.8L15 15l1 6"/>',
    stethoscope: '<path d="M5 3v5a4 4 0 0 0 8 0V3"/><path d="M9 12v3a5 5 0 0 0 10 0v-2"/><circle cx="19" cy="11" r="2"/>',
    heart: '<path d="M20.8 8.6a5 5 0 0 0-8.8-3.2A5 5 0 0 0 3.2 8.6C3.2 14 12 20 12 20s8.8-6 8.8-11.4z"/><path d="M3.6 12h4l2-3 3 6 2-3h5.8"/>',
    thermo: '<path d="M14 14.8V4a2 2 0 0 0-4 0v10.8a4 4 0 1 0 4 0z"/><path d="M12 9v7"/>',
    phone: '<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/>',
    shield: '<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/><path d="M8.6 12l2.4 2.4 4.4-4.6"/>',
    card: '<rect x="3" y="5.5" width="18" height="13" rx="2"/><path d="M3 10h18M7 15h3"/>',
    check: '<circle cx="12" cy="12" r="9"/><path d="M8 12.5l2.7 2.7L16 10"/>',
    tick: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    back: '<path d="M19 12H5M11 6l-6 6 6 6"/>',
    out: '<path d="M7 17L17 7M8 7h9v9"/>',
    alert: '<path d="M12 3.5l9.5 17h-19z"/><path d="M12 10v4.5"/><circle cx="12" cy="17.6" r=".7" fill="currentColor"/>',
    megaphone: '<path d="M3 10.5v3l11 4.5V6z"/><path d="M14 9h1.5a3 3 0 0 1 0 6H14M6.5 15l1.5 4.5h2.6L9.4 15"/>',
    wallet: '<rect x="3" y="6" width="18" height="14" rx="2"/><path d="M3 10h18M16 15h2M6 6l9-3 1 3"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6"/><circle cx="12" cy="7.6" r=".7" fill="currentColor"/>',
    clipboard: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4h6v3H9zM9 12h6M9 16h4"/>',
    lock: '<rect x="5" y="10.5" width="14" height="10" rx="2"/><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5"/>',
    signal: '<path d="M4 20v-3M9 20v-6M14 20v-9M19 20V5"/>',
    chevron: '<path d="M6 9l6 6 6-6"/>',
    video: '<rect x="3" y="6" width="13" height="12" rx="2"/><path d="M16 10.5l5-3v9l-5-3z"/>',
    hospital: '<path d="M4 21V5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v16M2 21h20M12 7v6M9 10h6M10 21v-4h4v4"/>',
    eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    spark: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/><path d="M19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z"/>',
  };
  window.TC = window.TC || {};
  window.TC.icon = (name, cls = "") =>
    `<svg class="svgi ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${P[name] || ""}</svg>`;
})();
