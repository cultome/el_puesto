/* El Puesto — Admin web (humanos). SPA vanilla sobre la API /admin/* (misma que usan
 * los agentes vía MCP): toda escritura pasa por la API auditada, nunca directo a la DB.
 * Login = X-Admin-Key (sessionStorage: se olvida al cerrar la pestaña, así no queda
 * guardada en el navegador); las secciones se filtran por los scopes de la clave. */

"use strict";

// ——— Estado ———
// Versiones anteriores la guardaban en localStorage (sobrevivía al navegador): se borra.
try { localStorage.removeItem("adminKey"); } catch (_) { /* sin almacenamiento */ }
let KEY = sessionStorage.getItem("adminKey") || "";
let WHO = null; // {name, scopes}
let currentSection = null;
let viewStack = []; // [{title, render}]
// Limpieza de la vista activa (streams SSE, timers): la registra la vista, la corre renderMain.
let activeViewCleanup = null;

const AREAS = ["INTERVENCION", "COMUNICACION", "RECOVERY", "ESCRUTINIO", "MEDICO"];

// ——— Chips con tooltip (el valor solo no siempre se explica) ———
const chip = (text, cls = "", tip = "") => h("span", { class: ("chip " + cls).trim(), title: tip || null }, text);
const ACCOUNT_STATUS_TIPS = {
  INVITED: "Invitada: el oficial aún no completa su registro",
  PENDING_APPROVAL: "Esperando aprobación manual del administrador",
  ACTIVE: "Cuenta activa: puede entrar a la app",
  SUSPENDED: "Acceso suspendido por el administrador",
};
const EVENT_STATUS_TIPS = {
  UPCOMING: "Por venir — derivado de las fechas del evento (no se captura)",
  LIVE: "En fechas del evento — derivado de las fechas (no se captura)",
  FINISHED: "Terminado — derivado de las fechas del evento (no se captura)",
};
const ACTIVE_TIP_ON = "La app lo muestra como el evento 'en curso' (Home y Modo evento); independiente del estatus por fechas";
const ACTIVE_TIP_OFF = "La app NO lo muestra como evento en curso";
// Roles operativos del puesto en un evento (catálogo operativo; validado por el backend).
const ASSIGNMENT_ROLES = [
  "Chief Post Marshal", "Comunicador", "Bandera Azul", "Bandera Amarilla",
  "Intervención 1", "Intervención 2", "Intervención 3", "Intervención 4", "Intervención 5",
  "Bombero 1", "Bombero 2", "Bombero 3", "Jefe Telehandler", "Operador Telehandler",
  "Jefe IFRT", "Operador IFRT", "Operador HIAB",
  "Panel de luz", "Driver Rider", "Coordinador de zona",
];
// Roles que hacen JEFE de su posición (guard "máx. uno por posición" del backend).
const CHIEF_ROLES = ["Chief Post Marshal", "Jefe Telehandler", "Jefe IFRT"];
// Tipos de activo en pista: presentación (etiqueta, abreviatura del marcador) y su CAPA,
// que da el color — las mismas de la app (decisión 2026-09-27): puestos naranja, rescate
// amarillo, soporte verde y médicos rojo; la abreviatura sigue diciendo el tipo.
// Un tipo nuevo = agregarlo al enum AssetType de shared (AL FINAL) + una línea aquí (y en
// AssetType.layer() de la app).
const ASSET_TYPES = {
  HIAB: { label: "HIAB", abbr: "H", layer: "rescate" },
  AMBULANCIA: { label: "Ambulancia", abbr: "A", layer: "medicos" },
  IFRT: { label: "IFRT", abbr: "IF", layer: "rescate" },
  TELEHANDLER: { label: "Telehandler", abbr: "TH", layer: "rescate" },
  TRACK_SWEEPER: { label: "Track Sweeper", abbr: "TS", layer: "soporte" },
  SAFETY_CAR: { label: "Safety Car", abbr: "SC", layer: "soporte" },
  DRIVER_RIDER: { label: "Driver Rider", abbr: "DR", layer: "rescate" },
};
const assetTypeOptions = Object.entries(ASSET_TYPES).map(([v, t]) => ({ v, l: t.label }));
const ACCOUNT_STATUS = ["INVITED", "PENDING_APPROVAL", "ACTIVE", "SUSPENDED"];
const SCOPES = ["accounts", "officers", "events", "circuits", "championships", "convocatorias", "agenda", "images", "keys", "moderation"];

// ——— Utilidades DOM ———
const $ = (sel) => document.querySelector(sel);

function h(tag, attrs = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined) continue;
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (k === "value") e.value = v;
    else if (k === "checked") e.checked = !!v;
    else if (k === "disabled") e.disabled = !!v;
    else e.setAttribute(k, v);
  }
  for (const kid of kids.flat(9)) {
    if (kid === null || kid === undefined || kid === false) continue;
    e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return e;
}

const svgNS = "http://www.w3.org/2000/svg";
function s(tag, attrs = {}) {
  const e = document.createElementNS(svgNS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}

function toast(text, isErr = false, hint = null) {
  const t = h("div", { class: "toast" + (isErr ? " err" : "") }, text,
    hint ? h("div", { class: "hint" }, hint) : null);
  $("#toasts").append(t);
  setTimeout(() => t.remove(), isErr ? 8000 : 4000);
}

/** Confirmación propia (en lugar del confirm() del navegador). */
function confirmModal(text) {
  return new Promise((resolve) => {
    const back = h("div", { class: "modal-back" });
    const close = (v) => { back.remove(); resolve(v); };
    back.append(h("div", { class: "modal" },
      h("div", { style: "margin-bottom:14px" }, text),
      h("div", { class: "form-actions" },
        h("button", { class: "btn btn-primary", onclick: () => close(true) }, "Confirmar"),
        h("button", { class: "btn", onclick: () => close(false) }, "Cancelar"),
      ),
    ));
    document.body.append(back);
  });
}

// ——— API ———
async function api(method, path, body = null, raw = false) {
  const headers = { "X-Admin-Key": KEY };
  let payload = null;
  if (body !== null) {
    if (raw) { payload = body; headers["Content-Type"] = "application/octet-stream"; }
    else { payload = JSON.stringify(body); headers["Content-Type"] = "application/json"; }
  }
  let r;
  try {
    r = await fetch("/admin" + path, { method, headers, body: payload });
  } catch (e) {
    return { ok: false, status: 0, data: { error: "sin conexión con el backend" } };
  }
  const txt = await r.text();
  let data;
  try { data = JSON.parse(txt); } catch { data = txt; }
  return { ok: r.ok, status: r.status, data };
}

/** Caja de error persistente para vistas que no pudieron cargar. */
function errorBox(res) {
  const d = typeof res.data === "object" && res.data ? res.data : { error: String(res.data || `error ${res.status}`) };
  return h("div", { class: "panel error-panel" },
    h("div", { style: "color:var(--red);font-weight:600" }, `✗ ${d.error ?? "error " + res.status}` + (res.status ? ` (HTTP ${res.status})` : "")),
    d.field ? h("div", { class: "muted", style: "margin-top:4px" }, `campo: ${d.field}`) : null,
    d.hint ? h("div", { class: "muted", style: "margin-top:4px" }, d.hint) : null,
  );
}

/** Muestra el resultado de una mutación (ChangeSummary o AdminError). */
function reportResult(res) {
  if (res.ok) {
    const d = res.data;
    // Toda mutación invalida la caché de referencias: los combos que se armen
    // después ven los datos recién escritos (p. ej. Pilotos → combo de Posiciones).
    if (d.action && d.action !== "validated") {
      for (const k of Object.keys(refCache)) delete refCache[k];
    }
    const labels = { created: "creado", updated: "actualizado", deleted: "borrado", replaced: "reemplazado", validated: "✔ validado (sin escribir)" };
    // El id (UUID) no se muestra: el detalle ya dice qué pasó.
    toast(`✓ ${d.entity ?? ""} ${labels[d.action] ?? d.action ?? "ok"}` + (d.detail ? ` — ${d.detail}` : ""));
  } else {
    const d = typeof res.data === "object" ? res.data : { error: String(res.data) };
    toast(`✗ ${d.error ?? "error " + res.status}` + (d.field ? ` (campo: ${d.field})` : ""), true, d.hint);
  }
  return res.ok;
}

// ——— Datos de referencia (para selects) ———
const refCache = {};
async function refList(path) {
  if (!(path in refCache)) {
    const r = await api("GET", path);
    refCache[path] = r.ok ? r.data : [];
  }
  return refCache[path];
}
const officerOptions = async () =>
  (await refList("/officers")).map((o) => ({ v: o.id, l: `${o.displayName} · ${o.omdaiId}`, s: String(o.omdaiId) }));
const circuitOptions = async () => (await refList("/circuits")).map((c) => ({ v: c.id, l: c.name }));
const eventOptions = async () => (await refList("/events")).map((e) => ({ v: e.id, l: e.name }));
const trazadoOptions = async (circuitId) =>
  circuitId ? (await refList(`/circuits/${circuitId}/trazados`)).map((t) => ({ v: t.id, l: t.name })) : [];
// Temporadas (API "championships") etiquetadas con su campeonato: "Fórmula E · 2025-26".
const championshipOptions = async () => (await refList("/championships")).map((c) => ({ v: c.id, l: `${c.name} · ${c.seasonLabel}` }));
const convocatoriaOptions = async () => (await refList("/convocatorias")).map((c) => ({ v: c.id, l: c.eventName }));

/** Nombre mostrable de un registro (los IDs son UUIDs internos y no se enseñan). */
const labelOf = (it) => it?.displayName ?? it?.name ?? it?.eventName ?? it?.title ?? it?.email ?? "";

/** Celda que resuelve un id de referencia a su nombre (async; "—" si no existe). */
function refNameCell(path, id, pick) {
  const span = h("span", {}, "…");
  refList(path).then((items) => {
    const it = items.find((x) => x.id === id);
    span.textContent = it ? pick(it) : "—";
  });
  return span;
}
const officerNameCell = (id) => refNameCell("/officers", id, (o) => o.displayName);
const eventNameCell = (id) => refNameCell("/events", id, (e) => e.name);
const circuitNameCell = (id) => refNameCell("/circuits", id, (c) => c.name);
const convocatoriaNameCell = (id) => refNameCell("/convocatorias", id, (c) => c.eventName);

// ——— Imágenes autenticadas (thumbnails / mapa) ———
const blobCache = {};
async function authBlobUrl(path) {
  if (path in blobCache) return blobCache[path];
  const r = await fetch("/admin" + path, { headers: { "X-Admin-Key": KEY } });
  blobCache[path] = r.ok ? URL.createObjectURL(await r.blob()) : null;
  return blobCache[path];
}
function invalidateImage(kind, ownerId) {
  for (const v of ["thumb", "full"]) delete blobCache[`/images/${kind}/${ownerId}/${v}`];
}
/** Celda con miniatura (avatar/logo) cargada con la clave admin. */
function thumbCell(kind, ownerId) {
  const span = h("span", { class: "muted" }, "·");
  authBlobUrl(`/images/${kind}/${ownerId}/thumb`).then((u) => {
    span.textContent = "";
    span.append(u ? h("img", { src: u, class: "thumb" }) : "—");
  });
  return span;
}

function uploadImage(kind, ownerId, reload) {
  const inp = h("input", { type: "file", accept: "image/jpeg,image/png" });
  inp.addEventListener("change", async () => {
    const f = inp.files[0];
    if (!f) return;
    const bytes = await f.arrayBuffer();
    const r = await api("POST", `/images/${kind}/${encodeURIComponent(ownerId)}`, bytes, true);
    if (reportResult(r)) { invalidateImage(kind, ownerId); if (reload) reload(); }
  });
  inp.click();
}

// ——— Objetos anidados (claves con punto: stats.events, point.x) ———
function unflatten(flat) {
  const out = {};
  for (const [k, v] of Object.entries(flat)) {
    const parts = k.split(".");
    let o = out;
    for (const p of parts.slice(0, -1)) o = (o[p] ??= {});
    o[parts[parts.length - 1]] = v;
  }
  return out;
}
function getPath(obj, path) {
  return path.split(".").reduce((o, p) => (o == null ? undefined : o[p]), obj);
}

// ——— Markdown mínimo (preview de convocatorias) ———
function mdRender(src) {
  // Escapa TODO lo que podría romper un atributo (comillas incluidas): el texto puede
  // venir de un agente o de una fuente externa.
  const esc = (t) => t.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  const inline = (t) => t
    .replace(/\*\*(.+?)\*\*/g, "<b>$1</b>")
    .replace(/\*(.+?)\*/g, "<i>$1</i>")
    .replace(/`(.+?)`/g, "<code>$1</code>")
    .replace(/\[(.+?)\]\((https?:\/\/[^)\s<>"']+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
  const out = [];
  let inList = false;
  for (const ln of esc(src).split("\n")) {
    const head = ln.match(/^(#{1,3})\s+(.*)/);
    if (head) {
      if (inList) { out.push("</ul>"); inList = false; }
      const lvl = head[1].length + 3;
      out.push(`<h${lvl}>${inline(head[2])}</h${lvl}>`);
      continue;
    }
    if (/^\s*[-*]\s+/.test(ln)) {
      if (!inList) { out.push("<ul>"); inList = true; }
      out.push("<li>" + inline(ln.replace(/^\s*[-*]\s+/, "")) + "</li>");
      continue;
    }
    if (inList) { out.push("</ul>"); inList = false; }
    if (ln.trim() !== "") out.push("<p>" + inline(ln) + "</p>");
  }
  if (inList) out.push("</ul>");
  return out.join("\n");
}

// ——— Formularios por esquema ———
// field: {key, label, type: text|number|date|datetime|checkbox|select|refselect|refmulti|textarea,
//         options?, load?: async(get)=>opts, dependsOn?, optional?, hint?, preview?}
function coerce(field, rawValue) {
  if (field.type === "checkbox") return rawValue; // ya es boolean
  const v = String(rawValue ?? "").trim();
  if (v === "") return field.optional ? undefined : (field.type === "number" ? 0 : "");
  if (field.type === "number") return Number(v);
  if (field.numeric) return Number(v); // refselect cuyo valor es numérico (p. ej. Nº de piloto)
  if (field.type === "datetime") return new Date(v).toISOString();
  return v;
}
function toInputValue(field, value) {
  if (value === null || value === undefined) return field.type === "checkbox" ? false : "";
  if (field.type === "datetime") {
    const d = new Date(value);
    const p = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
  }
  return value;
}
const normOpt = (o) => (typeof o === "object" ? o : { v: o, l: o });

function populateSelect(sel, options, current, optional) {
  sel.innerHTML = "";
  const opts = options.map(normOpt);
  if (optional || current === "" || current === undefined || current === null) {
    sel.append(h("option", { value: "" }, "—"));
  }
  let found = false;
  let group = null; // opciones con `g` se agrupan en optgroups (orden de aparición)
  for (const o of opts) {
    const selected = String(o.v) === String(current ?? "");
    if (selected) found = true;
    const opt = h("option", { value: o.v, selected: selected ? "" : null }, o.l);
    if (o.g) {
      if (!group || group.label !== o.g) { group = h("optgroup", { label: o.g }); sel.append(group); }
      group.append(opt);
    } else {
      group = null;
      sel.append(opt);
    }
  }
  if (current && !found) sel.append(h("option", { value: current, selected: "" }, `${current} (fuera de catálogo)`));
}

// refmulti: lista de checkboxes; el valor es un array ordenado según el catálogo.
function populateMulti(box, options, current) {
  box.innerHTML = "";
  const chosen = (current ?? []).map(String);
  const opts = options.map(normOpt);
  if (!opts.length) { box.append(h("div", { class: "muted mono" }, "— sin opciones —")); return; }
  for (const o of opts) {
    const cb = h("input", { type: "checkbox", value: o.v, checked: chosen.includes(String(o.v)) });
    box.append(h("label", { class: "check-row" }, cb, h("span", {}, o.l)));
  }
  for (const v of chosen) {
    if (!opts.some((o) => String(o.v) === v)) {
      box.append(h("label", { class: "check-row" },
        h("input", { type: "checkbox", value: v, checked: true }), h("span", {}, `${v} (fuera de catálogo)`)));
    }
  }
}

// combosearch: combo donde se escribe para buscar (catálogos grandes, p. ej. miles de
// oficiales). options: [{v, l, s?}] — s = texto extra de búsqueda (OMDAI ID, etc.).
// El elemento expone .value (el id elegido) y dispara "change" al seleccionar.
function comboSearch(field, value) {
  const wrap = h("div", { class: "combo-wrap" });
  const inp = h("input", { type: "text", placeholder: "escribe para buscar…", autocomplete: "off" });
  const list = h("div", { class: "combo-list", style: "display:none" });
  const labelOf = (v) =>
    (field.options ?? []).map(normOpt).find((o) => String(o.v) === String(v))?.l ?? (v ? `${v} (fuera de catálogo)` : "");
  wrap.value = value ?? "";
  inp.value = labelOf(wrap.value);
  const commit = (v, label) => {
    wrap.value = v; inp.value = label;
    list.style.display = "none";
    wrap.dispatchEvent(new Event("change"));
  };
  const refresh = () => {
    const q = inp.value.trim().toLowerCase();
    const opts = (field.options ?? []).map(normOpt);
    const hits = (q ? opts.filter((o) => (o.l + " " + (o.s ?? "")).toLowerCase().includes(q)) : opts).slice(0, 50);
    list.innerHTML = "";
    if (!hits.length) list.append(h("div", { class: "combo-empty muted" }, "sin coincidencias"));
    for (const o of hits) {
      list.append(h("div", { class: "combo-opt", onmousedown: (e) => { e.preventDefault(); commit(o.v, o.l); } }, o.l));
    }
    // position:fixed para que el dropdown no lo recorte el scroll de la tabla
    const r = inp.getBoundingClientRect();
    list.style.left = `${r.left}px`; list.style.top = `${r.bottom + 2}px`;
    list.style.minWidth = `${r.width}px`;
    list.style.display = "block";
  };
  inp.addEventListener("focus", () => { inp.select(); refresh(); });
  inp.addEventListener("input", () => { wrap.value = ""; refresh(); });
  inp.addEventListener("blur", () => setTimeout(() => {
    list.style.display = "none";
    inp.value = labelOf(wrap.value); // sin selección válida → restaura o limpia
  }, 150));
  wrap.append(inp, list);
  return wrap;
}

// geosearch: input de texto libre con búsqueda en Nominatim (el mismo servicio libre de
// OpenStreetMap que usa el mapa del trazado): escribe, 🔍 (o Enter) lista lugares y elegir
// uno rellena el campo con la dirección exacta (editable después). Expone .value.
function geoSearch(field, value) {
  const wrap = h("div", { class: "combo-wrap", style: "display:flex; gap:6px" });
  const inp = h("input", { type: "text", value: value ?? "", placeholder: "escribe y busca…", autocomplete: "off", style: "flex:1" });
  const list = h("div", { class: "combo-list wrap", style: "display:none" });
  Object.defineProperty(wrap, "value", { get: () => inp.value, set: (v) => { inp.value = v ?? ""; } });
  async function lookup() {
    const q = inp.value.trim();
    if (!q) return;
    btn.disabled = true;
    try {
      const r = await fetch(`https://nominatim.openstreetmap.org/search?format=json&limit=5&accept-language=es&q=${encodeURIComponent(q)}`);
      const hits = await r.json();
      list.innerHTML = "";
      if (!hits.length) list.append(h("div", { class: "combo-empty muted" }, "sin resultados"));
      for (const p of hits) {
        list.append(h("div", {
          class: "combo-opt",
          onmousedown: (e) => { e.preventDefault(); inp.value = p.display_name; list.style.display = "none"; },
        }, p.display_name));
      }
      const rect = inp.getBoundingClientRect();
      list.style.left = `${rect.left}px`; list.style.top = `${rect.bottom + 2}px`;
      // Con wrap, el ancho define dónde rompe la línea: el del input, sin salirse de la ventana.
      list.style.width = `${Math.min(Math.max(rect.width, 320), window.innerWidth - rect.left - 16)}px`;
      list.style.display = "block";
    } catch {
      toast("búsqueda no disponible (¿sin internet?)", true);
    }
    btn.disabled = false;
  }
  const btn = h("button", { class: "btn", title: "buscar el lugar con OpenStreetMap (Nominatim)", onclick: lookup }, "🔍");
  inp.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); lookup(); } });
  inp.addEventListener("blur", () => setTimeout(() => { list.style.display = "none"; }, 150));
  wrap.append(inp, btn, list);
  return wrap;
}

function fieldInput(field, value) {
  if (field.type === "combosearch") return comboSearch(field, value);
  if (field.type === "geosearch") return geoSearch(field, value);
  if (field.type === "refmulti") {
    const box = h("div", { class: "check-list" });
    populateMulti(box, field.options ?? [], value);
    return box;
  }
  if (field.type === "select" || field.type === "refselect") {
    const sel = h("select");
    populateSelect(sel, field.options ?? [], value ?? field.default, field.optional);
    return sel;
  }
  if (field.type === "checkbox") return h("input", { type: "checkbox", checked: !!(value ?? field.default) });
  if (field.type === "textarea") return h("textarea", { value: toInputValue(field, value) });
  const types = { number: "number", date: "date", datetime: "datetime-local", time: "time" };
  const inp = h("input", { type: types[field.type] ?? "text", value: toInputValue(field, value) });
  if (field.type === "number") inp.step = "any";
  return inp;
}

async function buildForm(fields, initial = {}, lockedKey = null) {
  for (const f of fields) {
    if ((f.type === "refselect" || f.type === "refmulti" || f.type === "combosearch") && f.load) {
      f.options = await f.load((k) => getPath(initial, k));
    }
  }
  const inputs = {};
  const el = h("div", {}, fields.map((f) => {
    const inp = fieldInput(f, getPath(initial, f.key));
    // El ID es la identidad del registro: al editar queda bloqueado (cambiarlo en un
    // upsert crearía un registro NUEVO dejando el original intacto).
    if (f.key === lockedKey) {
      inp.disabled = true;
      inp.title = "el ID no se cambia al editar; para otro ID crea un registro nuevo";
    }
    inputs[f.key] = { field: f, inp };
    if (f.type === "checkbox") {
      return [h("div", { class: "check-row" }, inp, h("label", {}, f.label)),
        f.hint ? h("div", { class: "muted mono", style: "margin:2px 0 6px" }, f.hint) : null];
    }
    const parts = [h("label", {}, f.label + (f.optional ? "" : " *")), inp,
      f.hint ? h("div", { class: "muted mono", style: "margin-top:2px" }, f.hint) : null];
    if (f.preview === "markdown") {
      const prev = h("div", { class: "md-preview", style: "display:none" });
      const btn = h("button", {
        class: "btn-mini", style: "margin-top:6px",
        onclick: () => {
          const show = prev.style.display === "none";
          prev.style.display = show ? "block" : "none";
          if (show) prev.innerHTML = mdRender(inp.value);
        },
      }, "vista previa");
      parts.push(btn, prev);
    }
    return parts;
  }));
  const currentValue = (k) => {
    const entry = inputs[k];
    return entry ? (entry.field.type === "checkbox" ? entry.inp.checked : entry.inp.value) : undefined;
  };
  for (const f of fields) {
    if ((f.type === "refselect" || f.type === "refmulti") && f.dependsOn && f.load) {
      inputs[f.dependsOn].inp.addEventListener("change", async () => {
        const opts = await f.load(currentValue);
        if (f.type === "refmulti") populateMulti(inputs[f.key].inp, opts, []);
        else populateSelect(inputs[f.key].inp, opts, "", f.optional);
      });
    }
  }
  return {
    el,
    read() {
      const flat = {};
      for (const { field, inp } of Object.values(inputs)) {
        if (field.type === "refmulti") {
          flat[field.key] = [...inp.querySelectorAll("input:checked")].map((c) => c.value);
          continue;
        }
        const v = coerce(field, field.type === "checkbox" ? inp.checked : inp.value);
        if (v !== undefined) flat[field.key] = v;
      }
      return unflatten(flat);
    },
  };
}

// ——— Vistas + routing por hash (#/seccion/…): refrescar restaura la vista y el
// botón atrás/adelante del navegador funciona ———
function syncHash() {
  const route = viewStack[viewStack.length - 1]?.route ?? "";
  if (location.hash !== "#/" + route) location.hash = "/" + route;
}
function setSection(section) {
  currentSection = section;
  viewStack = [{ title: section.label, render: section.render, route: section.id }];
  for (const k of Object.keys(refCache)) delete refCache[k];
  syncHash();
  renderShell();
}
function pushView(title, render, route) {
  viewStack.push({ title, render, route });
  syncHash();
  renderMain();
}
function popTo(i) {
  viewStack = viewStack.slice(0, i + 1);
  syncHash();
  renderMain();
}

/** Reconstruye la pila de vistas desde el hash (refresh / atrás / adelante). */
async function restoreFromHash() {
  const parts = location.hash.replace(/^#\/?/, "").split("/").filter(Boolean).map(decodeURIComponent);
  const section = SECTIONS.find((sec) => sec.id === parts[0] && can(sec.scope));
  if (!section) {
    setSection(SECTIONS.find((sec) => can(sec.scope)) ?? secClaves);
    return;
  }
  currentSection = section;
  viewStack = [{ title: section.label, render: section.render, route: section.id }];
  for (const k of Object.keys(refCache)) delete refCache[k];
  try {
    // Los títulos usan el NOMBRE de la entidad (los ids del hash no se muestran).
    if (section.id === "eventos" && parts[1]) {
      const evId = parts[1];
      const ev = (await refList("/events")).find((e) => e.id === evId) ?? { id: evId, name: "evento" };
      viewStack.push({ title: `Evento · ${ev.name}`, render: eventDetailView({ id: evId }), route: `eventos/${evId}` });
      const subs = { mbm: sessionsCfg, checklist: checklistCfg, asignaciones: assignmentsCfg };
      if (parts[2] && subs[parts[2]]) {
        viewStack.push({ title: `${parts[2]} · ${ev.name}`, render: bulkView(subs[parts[2]](ev)), route: `eventos/${evId}/${parts[2]}` });
      }
    } else if (section.id === "circuitos" && parts[1]) {
      const cid = parts[1];
      const cir = (await refList("/circuits")).find((c) => c.id === cid) ?? { id: cid, name: "circuito" };
      viewStack.push({ title: `Trazados · ${cir.name}`, render: trazadosView({ id: cid }), route: `circuitos/${cid}` });
      if (parts[2]) {
        const t = (await refList(`/circuits/${cid}/trazados`)).find((x) => x.id === parts[2]) ?? { id: parts[2], name: "trazado" };
        viewStack.push({ title: `Mapa · ${t.name}`, render: mapEditorView(t), route: `circuitos/${cid}/${t.id}` });
      }
    } else if (section.id === "campeonatos" && parts[1]) {
      // campeonatos/{campeonato}/{temporada}/{categoría}/{posiciones|calendario|pilotos}
      const sid = parts[1];
      const se = (await refList("/series")).find((s) => s.id === sid) ?? { id: sid, name: "campeonato" };
      viewStack.push({ title: `Temporadas · ${se.name}`, render: temporadasView(se), route: `campeonatos/${sid}` });
      if (parts[2]) {
        const chid = parts[2];
        const ch = (await refList("/championships")).find((c) => c.id === chid) ?? { id: chid, seriesId: sid, name: se.name, seasonLabel: "temporada" };
        viewStack.push({ title: `Categorías · ${ch.name} ${ch.seasonLabel}`, render: categoriasView(ch), route: `campeonatos/${sid}/${chid}` });
        const subs = { posiciones: standingsCfg, calendario: roundsCfg, pilotos: driversCfg };
        if (parts[3] && parts[4] && (subs[parts[4]] || parts[4] === "automaticas")) {
          const cat = (await refList(`/championships/${chid}/categories`)).find((c) => c.id === parts[3]) ?? { id: parts[3], name: "categoría" };
          const render = parts[4] === "automaticas" ? ingestView(cat) : bulkView(subs[parts[4]](cat));
          viewStack.push({ title: `${parts[4]} · ${cat.name}`, render, route: `campeonatos/${sid}/${chid}/${cat.id}/${parts[4]}` });
        }
      }
    }
  } catch { /* si algo falla, se queda en la vista más profunda que sí se armó */ }
  syncHash();
  renderShell();
}

window.addEventListener("hashchange", () => {
  if (!WHO) return;
  const current = "#/" + (viewStack[viewStack.length - 1]?.route ?? "");
  if (location.hash === current) return; // cambio hecho por la propia app
  restoreFromHash();
});

function renderMain() {
  if (activeViewCleanup) { try { activeViewCleanup(); } catch { /* nada */ } activeViewCleanup = null; }
  const main = $("#main");
  main.innerHTML = "";
  const crumbs = h("div", { class: "crumbs" },
    viewStack.length > 1
      ? h("button", { class: "btn btn-back", onclick: () => popTo(viewStack.length - 2) }, "← Volver")
      : null,
    h("span", {}, viewStack.map((v, i) => [
      i > 0 ? " › " : null,
      i < viewStack.length - 1 ? h("a", { onclick: () => popTo(i) }, v.title) : v.title,
    ])));
  main.append(crumbs);
  const container = h("div");
  main.append(container);
  container.append(h("div", { class: "loading" }, "cargando…"));
  viewStack[viewStack.length - 1].render(container);
}

// ——— Fábrica de secciones CRUD ———
function crudView(cfg) {
  return async function render(container) {
    const res = await api("GET", cfg.listPath);
    container.innerHTML = "";
    if (!res.ok) { container.append(errorBox(res)); return; }
    const items = res.data;
    const reload = () => render(container);

    let form = null;
    // El id de la entidad que se edita NO vive en el formulario (los ids los asigna el
    // servidor y no se muestran): se acarrea aquí. null = el form es de creación.
    let editingId = null;
    const formPanel = h("div", { class: "panel form-panel" });
    const renderForm = async (initial, isEdit) => {
      editingId = isEdit ? initial[cfg.itemKey] : null;
      form = await buildForm(cfg.fields, initial, isEdit ? cfg.itemKey : null);
      formPanel.innerHTML = "";
      // OJO: append() nativo pinta null como texto "null" (nuestro h() sí lo filtra).
      if (isEdit) {
        formPanel.append(
          h("h2", {}, `Editar ${cfg.entity}`),
          h("div", { class: "muted", style: "margin-bottom:8px" }, labelOf(initial)),
        );
      } else {
        formPanel.append(h("h2", {}, `Nuevo ${cfg.entity}`));
      }
      formPanel.append(
        form.el,
        h("div", { class: "form-actions" },
          h("button", { class: "btn btn-primary", onclick: () => save(false) }, "Guardar"),
          h("button", { class: "btn", title: "valida contra la API sin escribir", onclick: () => save(true) }, "Validar"),
          h("button", { class: "btn", onclick: () => renderForm({}, false) }, "Limpiar"),
        ),
      );
    };
    async function save(dryRun) {
      const vals = form.read();
      const dry = dryRun ? "?dryRun=true" : "";
      let r;
      if (cfg.keyed) {
        // Cuentas: la llave (email) sí la captura el usuario y va en el form (upsert).
        const id = vals[cfg.itemKey];
        if (!id) { toast(`falta ${cfg.itemKey}`, true); return; }
        const body = cfg.toBody ? cfg.toBody(vals) : vals;
        r = await api("PUT", (cfg.savePath ? cfg.savePath(id) : `${cfg.listPath}/${encodeURIComponent(id)}`) + dry, body);
      } else {
        const body = cfg.toBody ? cfg.toBody(vals) : vals;
        delete body[cfg.itemKey];
        if (editingId) {
          r = await api("PUT", (cfg.savePath ? cfg.savePath(editingId) : `${cfg.listPath}/${encodeURIComponent(editingId)}`) + dry, body);
        } else {
          // Crear: POST a la colección; el servidor asigna el id (UUID).
          r = await api("POST", (cfg.createPath ?? cfg.listPath) + dry, body);
        }
      }
      if (reportResult(r) && !dryRun) reload();
    }

    const makeRow = (item) => {
      const id = item[cfg.itemKey];
      const actions = [];
      actions.push(h("button", { class: "btn-mini", onclick: () => renderForm(item, true) }, "✎ editar"));
      for (const a of cfg.rowActions ?? []) {
        if (a.visible && !a.visible(item)) continue;
        const label = typeof a.label === "function" ? a.label(item) : a.label;
        actions.push(h("button", { class: "btn-mini" + (a.danger ? " danger" : ""), onclick: () => a.fn(item, reload) }, label));
      }
      if (cfg.deletable) {
        actions.push(h("button", {
          class: "btn-mini danger",
          onclick: async () => {
            if (!(await confirmModal(`¿Borrar ${cfg.entity} '${labelOf(item) || id}'?`))) return;
            const r = await api("DELETE", cfg.savePath ? cfg.savePath(id) : `${cfg.listPath}/${encodeURIComponent(id)}`);
            if (reportResult(r)) reload();
          },
        }, "borrar"));
      }
      return h("tr", {},
        cfg.columns.map((c) => h("td", {}, (c.fmt ? c.fmt(getPath(item, c.key), item) : getPath(item, c.key)) ?? h("span", { class: "muted" }, "—"))),
        h("td", {}, h("div", { class: "actions-cell" }, actions)),
      );
    };

    const tbody = h("tbody");
    const counter = h("h2", {});
    const applyFilter = (text) => {
      const q = text.trim().toLowerCase();
      const visible = q ? items.filter((it) => JSON.stringify(it).toLowerCase().includes(q)) : items;
      tbody.innerHTML = "";
      visible.forEach((it) => tbody.append(makeRow(it)));
      counter.textContent = q ? `${visible.length} de ${items.length}` : `${items.length} en total`;
    };
    const filterInp = h("input", { class: "filter-input", placeholder: "filtrar…", oninput: () => applyFilter(filterInp.value) });
    applyFilter("");

    const table = h("div", { class: "panel grow" },
      h("div", { class: "panel-head" }, counter, items.length > 5 ? filterInp : null),
      cfg.headerNote ? h("div", { class: "muted", style: "margin-bottom:10px" }, cfg.headerNote) : null,
      cfg.headerExtra ? cfg.headerExtra() : null,
      h("div", { class: "table-scroll" },
        items.length === 0 ? h("div", { class: "empty" }, "sin registros") :
          h("table", {},
            h("thead", {}, h("tr", {}, cfg.columns.map((c) => h("th", {}, c.label)), h("th", {}, ""))),
            tbody,
          )),
    );

    renderForm({}, false);
    container.append(h("div", { class: "layout" }, table, formPanel));
  };
}

// ——— Editor bulk (reemplazo total de una lista, con orden) ———
function bulkView(cfg) {
  return async function render(container) {
    for (const c of cfg.columns) {
      if ((c.type === "refselect" || c.type === "combosearch") && c.load) c.options = await c.load();
    }
    const res = await api("GET", cfg.getPath);
    container.innerHTML = "";
    if (!res.ok) { container.append(errorBox(res)); return; }
    let rows = res.data.map((item) => {
      const flat = {};
      for (const c of cfg.columns) flat[c.key] = getPath(item, c.key);
      for (const k of cfg.carry ?? []) flat[k] = item[k]; // claves que viajan sin editarse
      return flat;
    });

    const body = h("tbody");
    const renderRows = () => {
      body.innerHTML = "";
      rows.forEach((row, i) => {
        body.append(h("tr", {},
          h("td", { class: "ord-cell" },
            h("button", { class: "btn-mini", disabled: i === 0, onclick: () => { [rows[i - 1], rows[i]] = [rows[i], rows[i - 1]]; renderRows(); } }, "↑"),
            h("button", { class: "btn-mini", disabled: i === rows.length - 1, onclick: () => { [rows[i + 1], rows[i]] = [rows[i], rows[i + 1]]; renderRows(); } }, "↓"),
          ),
          cfg.columns.map((c) => {
            const inp = fieldInput(c, row[c.key]);
            const evName = (c.type === "checkbox" || c.type === "select" || c.type === "refselect" || c.type === "combosearch") ? "change" : "input";
            inp.addEventListener(evName, () => {
              row[c.key] = c.type === "checkbox" ? inp.checked : inp.value;
            });
            return h("td", { style: c.width ? `min-width:${c.width}px` : null }, inp);
          }),
          h("td", {}, h("button", { class: "btn-mini danger", title: "quitar fila", onclick: () => { rows.splice(i, 1); renderRows(); } }, "✕")),
        ));
      });
    };
    renderRows();
    // Barra superior opcional del editor (p. ej. selector de trazado en asignaciones);
    // puede mutar las options de una columna y pedir re-render de las filas.
    const topEl = cfg.top ? await cfg.top({ rerender: renderRows }) : null;

    const buildList = () => rows.map((row) => {
      const flat = {};
      for (const c of cfg.columns) {
        const v = coerce(c, row[c.key]);
        if (v !== undefined) flat[c.key] = v;
      }
      for (const k of cfg.carry ?? []) if (row[k] !== undefined && row[k] !== null) flat[k] = row[k];
      return unflatten(flat);
    });
    async function save(dryRun) {
      const r = await api("PUT", cfg.putPath + (dryRun ? "?dryRun=true" : ""), buildList());
      if (reportResult(r) && !dryRun) render(container);
    }

    container.append(h("div", { class: "panel" },
      h("h2", {}, cfg.title),
      cfg.note ? h("div", { class: "muted", style: "margin-bottom:10px" }, cfg.note) : null,
      topEl,
      h("div", { class: "table-scroll" },
        h("table", {},
          h("thead", {}, h("tr", {}, h("th", {}, "orden"), cfg.columns.map((c) => h("th", {}, c.label)), h("th", {}, ""))),
          body,
        )),
      h("div", { class: "form-actions" },
        h("button", { class: "btn", onclick: () => { rows.push(cfg.newRow ? cfg.newRow(rows) : {}); renderRows(); } }, "+ Fila"),
        h("button", { class: "btn", title: "valida contra la API sin escribir", onclick: () => save(true) }, "Validar"),
        h("button", { class: "btn btn-primary", onclick: () => save(false) }, "Guardar todo (reemplaza)"),
      ),
    ));
  };
}

// ——— Editor unificado del mapa: trazado + puestos + activos sobre OSM ———
// Los puestos/activos se guardan normalizados 0..1 (contrato con la app); aquí se
// editan en geo (lat/lon) y se convierten con la MISMA transformación que usa el
// backend para normalizar el trazado — por eso el trazado es la referencia y debe
// existir antes de guardar puestos.
function mapEditorView(trazado) {
  return async function render(container) {
    const [pathRes, pRes, aRes] = await Promise.all([
      api("GET", `/trazados/${trazado.id}/path`),
      api("GET", `/trazados/${trazado.id}/puestos`),
      api("GET", `/trazados/${trazado.id}/assets`),
    ]);
    container.innerHTML = "";
    for (const r of [pathRes, pRes, aRes]) if (!r.ok) { container.append(errorBox(r)); return; }

    // —— Estado ——
    let geo = pathRes.data.geo.map((g) => ({ lat: g.lat, lon: g.lon }));
    const rad = (d) => (d * Math.PI) / 180;
    const deg = (r) => (r * 180) / Math.PI;
    // Réplica exacta de la normalización del backend (Web Mercator, aspecto, margen 5%).
    function trackTransform() {
      if (geo.length < 2) return null;
      const xs = geo.map((g) => rad(g.lon));
      const ys = geo.map((g) => Math.asinh(Math.tan(rad(g.lat))));
      const minX = Math.min(...xs), maxX = Math.max(...xs);
      const minY = Math.min(...ys), maxY = Math.max(...ys);
      const span = Math.max(maxX - minX, maxY - minY);
      if (span <= 0) return null;
      const scale = 0.9 / span, cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
      return {
        toNorm: (it) => ({
          x: 0.5 + (rad(it.lon) - cx) * scale,
          y: 0.5 - (Math.asinh(Math.tan(rad(it.lat))) - cy) * scale,
        }),
        fromNorm: (x, y) => ({
          lon: deg(cx + (x - 0.5) / scale),
          lat: deg(Math.atan(Math.sinh(cy - (y - 0.5) / scale))),
        }),
      };
    }
    // Ítems guardados: lat/lon absolutas (fuente de verdad). Los datos legados sin geo
    // traen solo point normalizado y se reproyectan en cuanto hay trazado de referencia.
    let puestos = pRes.data.map((p) => ({
      id: p.id, number: p.number, label: p.label ?? "",
      lat: p.lat ?? null, lon: p.lon ?? null, normX: p.point.x, normY: p.point.y,
      onMap: p.onMap !== false, // false = posición sin lugar en el mapa (coordinación de zona)
    }));
    let activos = aRes.data.map((a) => ({
      id: a.id, type: a.type, label: a.label,
      lat: a.lat ?? null, lon: a.lon ?? null, normX: a.point.x, normY: a.point.y,
    }));
    function ensureGeo() {
      const tr = trackTransform();
      if (!tr) return;
      for (const it of [...puestos, ...activos]) {
        if (it.lat === null && it.normX !== undefined && it.onMap !== false) {
          const g = tr.fromNorm(it.normX, it.normY);
          it.lat = g.lat; it.lon = g.lon;
        }
      }
    }
    ensureGeo();

    let mode = geo.length === 0 ? "draw" : null; // draw | puesto | GRUA | AMBULANCIA | null
    let selected = null; // {kind: 'puesto'|'activo', i} — la selección de vértices es efímera
    let selVertex = -1;
    let mousePos = null;

    let z = 16;
    let center = { lat: 23.6, lon: -102.5 }; // México
    if (geo.length) {
      center = {
        lat: geo.reduce((a, g) => a + g.lat, 0) / geo.length,
        lon: geo.reduce((a, g) => a + g.lon, 0) / geo.length,
      };
    } else z = 5;

    const tiles = h("div", { class: "osm-tiles" });
    const svg = s("svg", { class: "osm-overlay" });
    // Aviso cuando los tiles no cargan (sin internet el fondo queda vacío pero el
    // dibujo/los marcadores siguen siendo editables): aparece al fallar y se quita solo
    // en cuanto un tile vuelve a cargar.
    const offlineNote = h("div", { class: "osm-offline", style: "display:none" },
      "Sin mapa de fondo (los tiles de OpenStreetMap no cargan — ¿sin internet?). El trazado y los marcadores siguen siendo editables.");
    const mapEl = h("div", { class: "osm-map" }, tiles, svg, offlineNote,
      h("div", { class: "osm-attrib" }, "© OpenStreetMap contributors"));
    const listPanel = h("div", { class: "panel form-panel map-list" });

    // —— Proyección de pantalla (z FRACCIONARIO: el zoom es continuo) ——
    const n = () => 2 ** z;
    const lon2tx = (lon) => ((lon + 180) / 360) * n();
    const lat2ty = (lat) => {
      const r = (lat * Math.PI) / 180;
      return ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * n();
    };
    const tx2lon = (tx) => (tx / n()) * 360 - 180;
    const ty2lat = (ty) => (Math.atan(Math.sinh(Math.PI * (1 - (2 * ty) / n()))) * 180) / Math.PI;
    const dims = () => ({ w: mapEl.clientWidth, hh: mapEl.clientHeight });
    const geo2px = (g) => {
      const { w, hh } = dims();
      return { x: (lon2tx(g.lon) - lon2tx(center.lon)) * 256 + w / 2, y: (lat2ty(g.lat) - lat2ty(center.lat)) * 256 + hh / 2 };
    };
    const px2geo = (x, y) => {
      const { w, hh } = dims();
      return { lon: tx2lon(lon2tx(center.lon) + (x - w / 2) / 256), lat: ty2lat(lat2ty(center.lat) + (y - hh / 2) / 256) };
    };

    // Tiles con diffing (se reutilizan los <img> y solo se reposicionan/escalan): los
    // tiles existen en niveles enteros; con z fraccionario se escalan a 256·2^(z−zi).
    const tileEls = new Map();
    function renderTiles() {
      const { w, hh } = dims();
      if (!w) return;
      const zi = Math.max(3, Math.min(19, Math.round(z)));
      const ts = 256 * 2 ** (z - zi); // tamaño en pantalla de un tile del nivel zi
      const nzi = 2 ** zi;
      const r = (center.lat * Math.PI) / 180;
      const wcx = ((center.lon + 180) / 360) * nzi * ts;
      const wcy = ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * nzi * ts;
      const x0 = Math.floor((wcx - w / 2) / ts), x1 = Math.floor((wcx + w / 2) / ts);
      const y0 = Math.max(0, Math.floor((wcy - hh / 2) / ts)), y1 = Math.min(nzi - 1, Math.floor((wcy + hh / 2) / ts));
      const keep = new Set();
      for (let tx = x0; tx <= x1; tx++) {
        for (let ty = y0; ty <= y1; ty++) {
          const wx = ((tx % nzi) + nzi) % nzi;
          const key = `${zi}/${wx}/${ty}/${tx}`;
          keep.add(key);
          let img = tileEls.get(key);
          if (!img) {
            img = h("img", {
              class: "osm-tile",
              src: `https://tile.openstreetmap.org/${zi}/${wx}/${ty}.png`,
              onerror: (e) => { e.target.classList.add("failed"); offlineNote.style.display = "block"; },
              onload: () => { offlineNote.style.display = "none"; },
            });
            tileEls.set(key, img);
            tiles.append(img);
          }
          img.style.left = (tx * ts - wcx + w / 2) + "px";
          img.style.top = (ty * ts - wcy + hh / 2) + "px";
          img.style.width = img.style.height = ts + "px";
        }
      }
      for (const [key, img] of tileEls) {
        if (!keep.has(key)) { img.remove(); tileEls.delete(key); }
      }
    }

    // —— Overlay: trazado + vértices + puntos medios + marcadores + preview ——
    function updatePreview() {
      let ln = svg.querySelector(".osm-preview");
      if (mode !== "draw" || !geo.length || !mousePos) { if (ln) ln.remove(); return; }
      const p = geo2px(geo[geo.length - 1]);
      if (!ln) { ln = s("line", { class: "osm-preview" }); svg.append(ln); }
      ln.setAttribute("x1", p.x); ln.setAttribute("y1", p.y);
      ln.setAttribute("x2", mousePos.x); ln.setAttribute("y2", mousePos.y);
    }

    function beginDragVertex(i, e) {
      e.stopPropagation(); e.preventDefault();
      selVertex = i; renderOverlay();
      const move = (ev) => {
        const r = mapEl.getBoundingClientRect();
        geo[i] = px2geo(ev.clientX - r.left, ev.clientY - r.top);
        renderOverlay();
      };
      const up = () => { window.removeEventListener("mousemove", move); window.removeEventListener("mouseup", up); };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    }

    function beginDragItem(it, kind, i, e) {
      e.stopPropagation(); e.preventDefault();
      selected = { kind, i }; updateSel(); renderOverlay();
      // La lista tiene scroll propio: traer a la vista la fila del marcador tocado.
      rowRefs.find((r) => r.kind === kind && r.i === i)?.el.scrollIntoView({ block: "nearest" });
      const move = (ev) => {
        const r = mapEl.getBoundingClientRect();
        const g = px2geo(ev.clientX - r.left, ev.clientY - r.top);
        it.lat = g.lat; it.lon = g.lon;
        renderOverlay();
      };
      const up = () => { window.removeEventListener("mousemove", move); window.removeEventListener("mouseup", up); };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    }

    // Aviso: ítems guardados que aún no tienen referencia geográfica (sin trazado).
    const pendingNote = h("div", { class: "muted mono", style: "margin-top:8px; color: var(--amber)" });
    function updatePendingNote() {
      const pend = [...puestos, ...activos].filter((it) => it.lat === null && it.onMap !== false).length;
      pendingNote.textContent = pend
        ? `⚠ ${pend} puestos/activos guardados sin referencia geográfica: dibuja el trazado y aparecerán a su alrededor para acomodarlos`
        : "";
    }

    function renderOverlay() {
      ensureGeo();
      updatePendingNote();
      const { w, hh } = dims();
      svg.setAttribute("viewBox", `0 0 ${w} ${hh}`);
      svg.innerHTML = "";
      const pts = geo.map(geo2px);
      if (pts.length >= 2) {
        svg.append(s("polyline", { points: pts.map((p) => `${p.x},${p.y}`).join(" "), class: "osm-line" }));
        if (pts.length > 2) {
          svg.append(s("line", { x1: pts[pts.length - 1].x, y1: pts[pts.length - 1].y, x2: pts[0].x, y2: pts[0].y, class: "osm-line" + (mode === "draw" ? " closing" : "") }));
        }
      }
      // Puntos medios: fuera del modo dibujo, arrastrar inserta un vértice en la recta.
      if (mode !== "draw" && pts.length >= 2) {
        const segs = pts.length > 2 ? pts.length : pts.length - 1;
        for (let i = 0; i < segs; i++) {
          const a = pts[i], b = pts[(i + 1) % pts.length];
          const m = s("circle", { cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2, r: 4.5, class: "osm-mid" });
          m.addEventListener("mousedown", (e) => {
            geo.splice(i + 1, 0, px2geo((a.x + b.x) / 2, (a.y + b.y) / 2));
            beginDragVertex(i + 1, e);
          });
          svg.append(m);
        }
      }
      pts.forEach((p, i) => {
        const c = s("circle", { cx: p.x, cy: p.y, r: 6, class: "osm-vertex" + (i === selVertex ? " selected" : "") });
        c.addEventListener("mousedown", (e) => beginDragVertex(i, e));
        svg.append(c);
      });
      // Marcadores de puestos y activos (los que ya tienen posición geo).
      const mk = (it, kind, i, layer, label) => {
        if (it.lat === null) return;
        const pt = geo2px(it);
        // Color de su capa en el borde y el texto; relleno solo el elegido (como en la app).
        const g = s("g", { class: "marker l-" + layer + (selected && selected.kind === kind && selected.i === i ? " selected" : "") });
        // Píldora adaptable al label ("MP 1", "IFRT12"…), no círculo fijo.
        const w = Math.max(20, String(label).length * 7 + 8);
        g.append(s("rect", { x: pt.x - w / 2, y: pt.y - 10, width: w, height: 20, rx: 10, class: "mk-pill" }));
        const t = s("text", { x: pt.x, y: pt.y, dy: 3.5, class: "mk-label" });
        t.textContent = label;
        g.append(t);
        g.addEventListener("mousedown", (e) => beginDragItem(it, kind, i, e));
        svg.append(g);
      };
      puestos.forEach((p, i) => mk(p, "puesto", i, "puesto", p.label || String(p.number)));
      activos.forEach((a, i) => {
        const t = ASSET_TYPES[a.type] ?? { abbr: "?", layer: "rescate" };
        mk(a, "activo", i, t.layer, a.label || t.abbr);
      });
      updatePreview();
    }
    const renderMap = () => { renderTiles(); renderOverlay(); };

    // —— Zoom suave: anima z hacia targetZ y ancla el punto bajo el cursor ——
    let targetZ = z;
    let zoomAnchor = null; // {x, y} en px del mapa; null = centro
    let zoomAnimating = false;
    function zoomBy(dz, anchorPx = null) {
      targetZ = Math.max(3, Math.min(19, targetZ + dz));
      zoomAnchor = anchorPx;
      if (!zoomAnimating) stepZoom();
    }
    function stepZoom() {
      const diff = targetZ - z;
      if (Math.abs(diff) < 0.01) { z = targetZ; zoomAnimating = false; renderMap(); return; }
      zoomAnimating = true;
      const g0 = zoomAnchor ? px2geo(zoomAnchor.x, zoomAnchor.y) : null;
      z += diff * 0.3;
      if (g0) {
        // Recentrar para que el punto bajo el cursor no se mueva al hacer zoom.
        const { w, hh } = dims();
        const tx = lon2tx(g0.lon) - (zoomAnchor.x - w / 2) / 256;
        const ty = lat2ty(g0.lat) - (zoomAnchor.y - hh / 2) / 256;
        center = { lon: tx2lon(tx), lat: ty2lat(ty) };
      }
      renderMap();
      requestAnimationFrame(stepZoom);
    }

    // —— Interacción del mapa: pan siempre; clic según el modo ——
    mapEl.addEventListener("mousedown", (e) => {
      if (e.target.tagName === "circle" || e.target.tagName === "text") return;
      e.preventDefault();
      const start = { x: e.clientX, y: e.clientY, lat: center.lat, lon: center.lon };
      let moved = false;
      const move = (ev) => {
        const dx = ev.clientX - start.x, dy = ev.clientY - start.y;
        if (Math.abs(dx) + Math.abs(dy) > 4) moved = true;
        if (moved) {
          center = { lon: tx2lon(lon2tx(start.lon) - dx / 256), lat: ty2lat(lat2ty(start.lat) - dy / 256) };
          renderMap();
        }
      };
      const up = (ev) => {
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
        if (moved) return;
        const r = mapEl.getBoundingClientRect();
        const g = px2geo(ev.clientX - r.left, ev.clientY - r.top);
        if (mode === "draw") {
          geo.push(g);
          selVertex = geo.length - 1;
          renderOverlay();
        } else if (mode === "puesto") {
          const next = puestos.reduce((m2, p) => Math.max(m2, Number(p.number) || 0), 0) + 1;
          // Prellenado "MP X" (solo al crear; no se sincroniza con el número después).
          puestos.push({ id: "", number: next, label: `MP ${next}`, lat: g.lat, lon: g.lon });
          selected = { kind: "puesto", i: puestos.length - 1 };
          renderList(); renderOverlay();
        } else if (ASSET_TYPES[mode]) {
          const count = activos.filter((a) => a.type === mode).length + 1;
          activos.push({ id: "", type: mode, label: `${ASSET_TYPES[mode].label} ${count}`, lat: g.lat, lon: g.lon });
          selected = { kind: "activo", i: activos.length - 1 };
          renderList(); renderOverlay();
        } else {
          selected = null; selVertex = -1;
          updateSel(); renderOverlay();
        }
      };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    });
    mapEl.addEventListener("mousemove", (e) => {
      const r = mapEl.getBoundingClientRect();
      mousePos = { x: e.clientX - r.left, y: e.clientY - r.top };
      updatePreview();
    });
    mapEl.addEventListener("mouseleave", () => { mousePos = null; updatePreview(); });
    mapEl.addEventListener("dblclick", (e) => {
      e.preventDefault();
      if (mode !== "draw") return;
      if (geo.length >= 2) {
        const a = geo2px(geo[geo.length - 1]), b = geo2px(geo[geo.length - 2]);
        if (Math.hypot(a.x - b.x, a.y - b.y) < 8) geo.pop();
      }
      closeTrack();
    });
    // Zoom continuo (fracciones de nivel por muesca) anclado al cursor.
    mapEl.addEventListener("wheel", (e) => {
      e.preventDefault();
      const r = mapEl.getBoundingClientRect();
      zoomBy(-e.deltaY * 0.0025, { x: e.clientX - r.left, y: e.clientY - r.top });
    }, { passive: false });

    // —— Modos ——
    const modeButtons = {};
    let closeBtn;
    function setMode(m) {
      mode = m;
      for (const [k, b] of Object.entries(modeButtons)) b.classList.toggle("btn-primary", k === mode);
      assetBtn.classList.toggle("btn-primary", !!ASSET_TYPES[mode]);
      closeBtn.disabled = mode !== "draw";
      renderOverlay();
    }
    const toggleMode = (m) => setMode(mode === m ? null : m);
    function closeTrack() {
      selVertex = -1;
      setMode(null);
      if (geo.length >= 3) toast("trazado cerrado (conectado al primer vértice)");
      else toast("modo dibujo terminado — se necesitan 3+ vértices para formar el circuito", true);
    }

    // —— Lista lateral (selección SIN re-render: no roba el foco de los inputs) ——
    const rowRefs = [];
    function updateSel() {
      for (const r of rowRefs) {
        r.el.classList.toggle("selected", !!selected && selected.kind === r.kind && selected.i === r.i);
      }
    }
    // Enfocar un campo de la lista resalta su marcador en el mapa (y lo trae a la
    // vista centrando el mapa si quedó fuera del viewport).
    function focusItem(kind, i, it) {
      selected = { kind, i };
      updateSel();
      if (it.lat !== null) {
        const r = mapEl.getBoundingClientRect();
        const pt = geo2px(it);
        if (pt.x < 20 || pt.y < 20 || pt.x > r.width - 20 || pt.y > r.height - 20) {
          center = { lat: it.lat, lon: it.lon };
          renderMap();
          return;
        }
      }
      renderOverlay();
    }
    function renderList() {
      listPanel.innerHTML = "";
      rowRefs.length = 0;
      const stop = (e) => e.stopPropagation();
      listPanel.append(h("h2", {}, `Puestos (${puestos.length})`));
      puestos.forEach((p, i) => {
        const numInp = h("input", { type: "number", value: p.number, style: "width:64px", onmousedown: stop, onfocus: () => focusItem("puesto", i, p), oninput: () => { p.number = numInp.value; renderOverlay(); } });
        const labInp = h("input", { type: "text", value: p.label, placeholder: "ID Puesto", onmousedown: stop, onfocus: () => focusItem("puesto", i, p), oninput: () => { p.label = labInp.value; renderOverlay(); } });
        const row = h("div", { class: "map-row", onmousedown: () => { selected = { kind: "puesto", i }; updateSel(); renderOverlay(); } },
          numInp, labInp,
          p.onMap === false ? chip("sin mapa", "", "Posición asignable sin lugar en el mapa (p. ej. coordinación de zona)")
            : p.lat === null ? chip("sin posición", "warn", "El puesto no tiene coordenadas: colócalo haciendo clic sobre el mapa") : null,
          h("button", { class: "btn-mini danger", onmousedown: stop, onclick: () => { puestos.splice(i, 1); selected = null; renderList(); renderOverlay(); } }, "✕"),
        );
        rowRefs.push({ kind: "puesto", i, el: row });
        listPanel.append(row);
      });
      listPanel.append(h("h2", { style: "margin-top:14px" }, `Grúas y ambulancias (${activos.length})`));
      activos.forEach((a, i) => {
        const typeSel = h("select", { style: "width:140px", onmousedown: stop, onfocus: () => focusItem("activo", i, a) });
        populateSelect(typeSel, assetTypeOptions, a.type, false);
        typeSel.addEventListener("change", () => { a.type = typeSel.value; renderOverlay(); });
        const labInp = h("input", { type: "text", value: a.label, onmousedown: stop, onfocus: () => focusItem("activo", i, a), oninput: () => { a.label = labInp.value; renderOverlay(); } });
        const row = h("div", { class: "map-row", onmousedown: () => { selected = { kind: "activo", i }; updateSel(); renderOverlay(); } },
          typeSel, labInp,
          a.lat === null ? chip("sin posición", "warn", "El activo no tiene coordenadas: colócalo haciendo clic sobre el mapa") : null,
          h("button", { class: "btn-mini danger", onmousedown: stop, onclick: () => { activos.splice(i, 1); selected = null; renderList(); renderOverlay(); } }, "✕"),
        );
        rowRefs.push({ kind: "activo", i, el: row });
        listPanel.append(row);
      });
      listPanel.append(h("div", { class: "form-actions" },
        h("button", { class: "btn btn-primary", onclick: saveAll }, "Guardar todo"),
      ));
      updateSel();
    }

    // —— Guardar: lat/lon absolutas (el backend deriva el point normalizado que
    // consume la app usando el trazado como marco; al redibujar el trazado los
    // re-deriva solo) ——
    async function saveAll() {
      if (geo.length > 0 && geo.length < 3) { toast("el trazado necesita 3+ vértices (o déjalo vacío para borrarlo)", true); return; }
      if ([...puestos, ...activos].some((it) => it.lat === null && it.onMap !== false)) {
        toast("hay puestos/activos legados sin posición (chip en la lista): dibuja el trazado para reproyectarlos, o quítalos", true);
        return;
      }
      const r1 = await api("PUT", `/trazados/${trazado.id}/path`, { geo: geo.map((g) => ({ lat: g.lat, lon: g.lon })) });
      if (!reportResult(r1)) return;
      const r2 = await api("PUT", `/trazados/${trazado.id}/puestos`,
        puestos.map((p) => ({ id: p.id || undefined, number: Number(p.number) || 0, label: p.label || undefined, lat: p.lat, lon: p.lon, onMap: p.onMap !== false })));
      reportResult(r2);
      const r3 = await api("PUT", `/trazados/${trazado.id}/assets`,
        activos.map((a) => ({ id: a.id || undefined, type: a.type, label: a.label, lat: a.lat, lon: a.lon })));
      reportResult(r3);
      if (r2.ok && r3.ok) render(container);
    }

    // —— Búsqueda (Nominatim, con debounce): sugiere mientras escribes ——
    // Debounce de 600ms (política de uso de Nominatim: ≤1 req/s) y solo la respuesta
    // de la última consulta gana (se descartan las que llegan tarde).
    const searchInp = h("input", { placeholder: "buscar lugar…", style: "flex:1; min-width:120px", autocomplete: "off" });
    const sugMenu = h("div", { class: "type-menu", style: "display:none; top:calc(100% + 4px); min-width:260px; max-width:420px" });
    const searchWrap = h("div", { class: "menu-wrap", style: "flex:1; display:flex" }, searchInp, sugMenu);
    const goTo = (lat, lon, label) => {
      center = { lat, lon };
      z = 15; targetZ = 15;
      sugMenu.style.display = "none";
      if (label) searchInp.value = label;
      renderMap();
    };
    async function queryNominatim(q, limit) {
      const r = await fetch(`https://nominatim.openstreetmap.org/search?format=json&limit=${limit}&accept-language=es&q=${encodeURIComponent(q)}`);
      return r.json();
    }
    let sugTimer = null;
    let sugSeq = 0;
    searchInp.addEventListener("input", () => {
      clearTimeout(sugTimer);
      const q = searchInp.value.trim();
      if (q.length < 3) { sugMenu.style.display = "none"; return; }
      sugTimer = setTimeout(async () => {
        const seq = ++sugSeq;
        let list = [];
        try { list = await queryNominatim(q, 5); } catch { return; }
        if (seq !== sugSeq) return; // llegó tarde: ya hay otra consulta en vuelo
        sugMenu.innerHTML = "";
        if (!list.length) { sugMenu.style.display = "none"; return; }
        list.forEach((it) => sugMenu.append(h("button", {
          class: "type-item",
          onclick: () => goTo(Number(it.lat), Number(it.lon), it.display_name.split(",")[0]),
        }, it.display_name)));
        sugMenu.style.display = "flex";
      }, 600);
    });
    searchInp.addEventListener("blur", () => setTimeout(() => { sugMenu.style.display = "none"; }, 200));
    async function search() {
      clearTimeout(sugTimer);
      const q = searchInp.value.trim();
      if (!q) return;
      try {
        const list = await queryNominatim(q, 1);
        if (!list.length) { toast("sin resultados", true); return; }
        goTo(Number(list[0].lat), Number(list[0].lon));
      } catch {
        toast("búsqueda no disponible (¿sin internet?)", true);
      }
    }
    searchInp.addEventListener("keydown", (e) => { if (e.key === "Enter") search(); });

    // —— Toolbar ——
    closeBtn = h("button", { class: "btn", disabled: mode !== "draw", onclick: closeTrack }, "✔ Cerrar trazado");
    // "+ <tipo>" coloca el último tipo usado; "▾" abre el menú de tipos (escala a N tipos).
    let lastAssetType = "HIAB";
    const assetBtn = h("button", { class: "btn", onclick: () => toggleMode(lastAssetType) }, "+ " + ASSET_TYPES[lastAssetType].label);
    const typeMenu = h("div", { class: "type-menu", style: "display:none" },
      Object.entries(ASSET_TYPES).map(([k, t]) => h("button", {
        class: "type-item",
        onclick: () => {
          lastAssetType = k;
          assetBtn.textContent = "+ " + t.label;
          typeMenu.style.display = "none";
          setMode(k);
        },
      }, t.label)));
    const caretBtn = h("button", {
      class: "btn btn-caret", title: "elegir tipo de activo",
      onclick: () => { typeMenu.style.display = typeMenu.style.display === "none" ? "block" : "none"; },
    }, "▾");
    mapEl.addEventListener("mousedown", () => { typeMenu.style.display = "none"; });
    const toolbar = h("div", { class: "map-toolbar" },
      modeButtons["draw"] = h("button", { class: "btn" + (mode === "draw" ? " btn-primary" : ""), onclick: () => toggleMode("draw") }, "✎ Dibujar trazado"),
      closeBtn,
      modeButtons["puesto"] = h("button", { class: "btn", onclick: () => toggleMode("puesto") }, "+ Puesto"),
      h("div", { class: "menu-wrap" }, assetBtn, caretBtn, typeMenu),
      h("button", { class: "btn", title: "quita el último vértice del trazado", onclick: () => { if (geo.length) { geo.pop(); selVertex = -1; renderOverlay(); } } }, "⌫ Vértice"),
      h("button", {
        class: "btn btn-danger", onclick: async () => { if (await confirmModal("¿Borrar todo el dibujo del trazado?")) { geo = []; selVertex = -1; renderOverlay(); } },
      }, "Limpiar trazado"),
      h("button", { class: "btn btn-primary", onclick: saveAll }, "Guardar todo"),
    );

    // —— Navegación del mapa (zoom + búsqueda): sección colapsable bajo el mapa ——
    const navBody = h("div", { class: "map-toolbar", style: "margin:8px 0 0; display:none" },
      h("button", { class: "btn", onclick: () => zoomBy(1) }, "＋ Zoom"),
      h("button", { class: "btn", onclick: () => zoomBy(-1) }, "－ Zoom"),
      searchWrap,
      h("button", { class: "btn", onclick: search }, "Buscar"),
    );
    let navOpen = false; // colapsada por default (rueda/arrastre cubren lo común)
    const navToggle = h("button", {
      class: "btn-mini", style: "margin-top:10px",
      onclick: () => {
        navOpen = !navOpen;
        navBody.style.display = navOpen ? "flex" : "none";
        navToggle.textContent = (navOpen ? "▾" : "▸") + " Zoom y búsqueda";
      },
    }, "▸ Zoom y búsqueda");
    const mapNav = h("div", {}, navToggle, navBody);

    const mapPanel = h("div", { class: "panel grow" },
      h("h2", {}, `Mapa de ${trazado.name}`),
      h("div", { class: "muted", style: "margin-bottom:10px" },
        "Todo en una vista: busca el autódromo, ✎ dibuja el trazado (clics siguiendo la pista, «✔ Cerrar trazado» al terminar) y con + Puesto / + Grúa / + Ambulancia coloca los marcadores haciendo clic sobre el mapa. Arrastra vértices o marcadores para ajustar; los puntos medios del trazado insertan vértices. Guardar convierte los marcadores a la referencia del trazado (por eso el trazado va primero)."),
      toolbar,
      mapEl,
      mapNav,
      pendingNote,
    );

    renderList();
    updatePendingNote();
    container.append(h("div", { class: "layout" }, mapPanel, listPanel));
    requestAnimationFrame(renderMap);
  };
}

// ——— Editores de las sub-listas de un evento ———
const sessionsCfg = (ev) => ({
  title: `Cronograma (MbM) de ${ev.name}`,
  note: "El orden de las filas es el orden mostrado en la app (usa ↑/↓). El estatus se deriva de fecha/hora al guardar. La categoría es opcional y sale de los campeonatos asociados al evento.",
  getPath: `/events/${ev.id}/sessions`, putPath: `/events/${ev.id}/sessions`,
  carry: ["id"], // la actividad conserva su identidad (el backend preserva ids que vienen)
  columns: [
    { key: "day", label: "Día", type: "date", width: 130 },
    { key: "time", label: "Hora", type: "time", width: 90 },
    {
      key: "category", label: "Categoría", type: "refselect", optional: true, width: 170,
      // Las categorías de los campeonatos asociados al evento (opcional: "—" = sin categoría).
      load: async () => {
        const lists = await Promise.all((ev.championshipIds ?? []).map((id) => refList(`/championships/${id}/categories`)));
        return lists.flat().map((c) => ({ v: c.name, l: c.name }));
      },
    },
    { key: "name", label: "Actividad", type: "text", width: 180 },
    { key: "endsInMin", label: "Termina en (min)", type: "number", optional: true, width: 70 },
  ],
  // La fila nueva arranca donde termina la anterior (día/hora + duración); si a la
  // anterior le falta fecha, hora o duración, la fila entra vacía.
  newRow: (rows) => {
    const prev = rows[rows.length - 1];
    const mins = Number(prev?.endsInMin);
    if (!prev?.day || !prev?.time || !mins || mins <= 0) return {};
    const d = new Date(`${prev.day}T${prev.time}:00`);
    if (isNaN(d)) return {};
    d.setMinutes(d.getMinutes() + mins);
    const pad = (n) => String(n).padStart(2, "0");
    return {
      day: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
      time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
    };
  },
});
const checklistCfg = (ev) => ({
  title: `Checklist del puesto — ${ev.name}`,
  note: "Aquí se define la PLANTILLA (los ítems). El estado 'hecho' lo marcan los oficiales en la app y se conserva al editar.",
  getPath: `/events/${ev.id}/checklist`, putPath: `/events/${ev.id}/checklist`,
  carry: ["id"], // conserva el id de cada ítem para que el backend preserve su estado
  columns: [
    { key: "text", label: "Ítem", type: "text", width: 320 },
  ],
});
const SHIFTS = ["Día completo", ...Array.from({ length: 8 }, (_, i) => `Turno ${i + 1}`)];
const assignmentsCfg = (ev) => {
  // Las asignaciones son por evento Y trazado: el selector de arriba define de qué
  // trazado salen las posiciones del combo (puestos Y activos tripulables; el Nº se
  // deriva del puesto al guardar — los activos no tienen).
  const puestoCol = { key: "puestoId", label: "Posición", type: "refselect", options: [], width: 170 };
  return {
    title: `Asignaciones — ${ev.name}`,
    note: "Elige el trazado: sus puestos y activos (TH/IFRT/HIAB…) alimentan el combo Posición; busca al oficial por nombre u OMDAI ID.",
    getPath: `/events/${ev.id}/assignments`, putPath: `/events/${ev.id}/assignments`,
    carry: ["id"], // la identidad de cada asignación sobrevive al guardado
    top: async ({ rerender }) => {
      if (!ev.circuitId) return null; // evento no encontrado: sin catálogo de puestos
      const trazadoIds = ev.trazadoIds ?? [ev.trazadoId];
      const all = await trazadoOptions(ev.circuitId);
      const opts = all.filter((o) => trazadoIds.includes(o.v));
      const sel = h("select", { style: "max-width:340px" });
      populateSelect(sel, opts, trazadoIds[0], false);
      const loadPuestos = async () => {
        const [puestos, assets] = await Promise.all([
          refList(`/trazados/${sel.value}/puestos`),
          refList(`/trazados/${sel.value}/assets`),
        ]);
        puestoCol.options = puestos.map((p) => ({ v: p.id, l: p.label || `MP ${p.number}`, g: "Puestos" }))
          .concat(assets.map((a) => ({ v: a.id, l: a.label, g: "Activos" })));
        rerender();
      };
      sel.addEventListener("change", loadPuestos);
      await loadPuestos();
      return h("div", { class: "top-bar" }, h("label", {}, "Trazado"), sel);
    },
    columns: [
      { key: "officerId", label: "Oficial", type: "combosearch", load: officerOptions, width: 230 },
      { key: "role", label: "Rol", type: "select", options: ASSIGNMENT_ROLES, width: 180 },
      puestoCol,
      { key: "shift", label: "Turno", type: "select", options: SHIFTS, default: "Día completo", width: 140 },
    ],
  };
};
// ——— Editores de las sub-listas de una categoría de campeonato ———
const standingsCfg = (c) => ({
  title: `Tabla de posiciones — ${c.name}`,
  note: "El piloto se elige del catálogo de Pilotos de la categoría (número, nombre y equipo viven ahí y se resuelven solos). Si falta un piloto, captúralo primero en Pilotos. Si la categoría tiene posiciones automáticas, la próxima corrida puede reemplazar lo que captures aquí.",
  getPath: `/categories/${c.id}/standings`, putPath: `/categories/${c.id}/standings`,
  columns: [
    { key: "pos", label: "Pos", type: "number", width: 55 },
    {
      key: "driverRef", label: "Piloto", type: "refselect", width: 260,
      // Sin caché: Pilotos se captura en la vista hermana y este combo debe ver
      // los cambios inmediatamente al volver. La llave es `ref` (el número se repite).
      load: async () => {
        const r = await api("GET", `/categories/${c.id}/drivers`);
        return (r.ok ? r.data : []).map((d) => ({ v: d.ref, l: `${d.numberText ?? "—"} · ${d.name}${d.team ? ` (${d.team})` : ""}` }));
      },
    },
    { key: "points", label: "Puntos", type: "number", width: 65 },
  ],
});
const roundsCfg = (c) => ({
  title: `Fechas — ${c.name}`,
  note: "Fecha = día de la carrera (rally: último día); Inicio = primer día del fin de semana (opcional). Sede = circuito del catálogo o, si no corre en circuito (rallies), Sede en texto \"Ciudad, País\". El estatus se deriva de las fechas. Los resultados viven en Posiciones.",
  getPath: `/categories/${c.id}/rounds`, putPath: `/categories/${c.id}/rounds`,
  carry: ["id"], // la planeación de los oficiales se liga a la carrera: su id sobrevive
  columns: [
    { key: "number", label: "Nº", type: "number", width: 55 },
    { key: "name", label: "Evento", type: "text", optional: true, width: 200 },
    { key: "startDate", label: "Inicio", type: "date", optional: true, width: 130 },
    { key: "date", label: "Fecha", type: "date", width: 130 },
    { key: "circuitId", label: "Circuito", type: "refselect", load: circuitOptions, optional: true, width: 220 },
    { key: "location", label: "Sede (sin circuito)", type: "text", optional: true, width: 160 },
  ],
});
const driversCfg = (c) => ({
  title: `Pilotos — ${c.name}`,
  note: "Nº como texto (\"007\", \"00\"; vacío = sin número). La llave del piloto la asigna el sistema (el id de la fuente automática, o el número/nombre): dos pilotos pueden compartir número (sustitutos).",
  getPath: `/categories/${c.id}/drivers`, putPath: `/categories/${c.id}/drivers`,
  carry: ["ref"], // la llave viaja sin editarse (Posiciones la referencia)
  columns: [
    { key: "numberText", label: "Nº", type: "text", optional: true, width: 60 },
    { key: "name", label: "Nombre", type: "text", width: 180 },
    { key: "team", label: "Equipo", type: "text", optional: true, width: 150 },
  ],
});

// ——— Posiciones automáticas (ingesta desde fuentes externas) ———
const fmtWhenShort = (iso) => iso ? new Date(iso).toLocaleString("es-MX", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—";

/** Chip de estado: al día (verde), esperando a la fuente (ámbar) o con error (rojo). */
function ingestChip(st) {
  if (!st.enabled) return h("span", { class: "chip" }, "pausada");
  if (st.lastError) return h("span", { class: "chip bad", title: st.lastError }, "error");
  if (st.targetRound == null) return h("span", { class: "chip" }, "sin fechas terminadas");
  if ((st.throughRound ?? 0) >= st.targetRound) {
    return h("span", { class: "chip ok" }, (st.confirmedRound ?? 0) >= st.targetRound ? "al día · confirmada" : "al día");
  }
  return h("span", { class: "chip warn", title: st.lastNote ?? "" }, "esperando a la fuente");
}

/** Tabla de posiciones devuelta por una vista previa (dryRun). */
function ingestPreview(run) {
  return h("div", { class: "panel", style: "margin-top:12px" },
    h("h2", {}, `Vista previa · ${run.detail ?? ""}`),
    h("div", { class: "table-scroll" }, h("table", {},
      h("thead", {}, h("tr", {}, ["Pos", "Nº", "Piloto", "Equipo", "Pts"].map((x) => h("th", {}, x)))),
      h("tbody", {}, (run.rows ?? []).map((r) => h("tr", {},
        h("td", { class: "mono" }, r.pos), h("td", { class: "mono" }, r.numberText ?? "—"),
        h("td", {}, r.driverName), h("td", { class: "muted" }, r.team), h("td", { class: "mono" }, r.points),
      ))),
    )),
  );
}

async function runIngest(categoryId, dryRun) {
  const res = await api("POST", `/categories/${categoryId}/standings-ingest/run${dryRun ? "?dryRun=true" : ""}`);
  if (!res.ok) { reportResult(res); return null; }
  const run = res.data;
  const msg = {
    written: `Posiciones actualizadas tras la fecha ${run.round}`, unchanged: `Sin cambios (fecha ${run.round})`,
    "not-ready": `La fuente aún no refleja la fecha ${run.round}`, idle: run.detail, preview: run.detail, error: run.detail,
  }[run.status] ?? run.status;
  toast(msg, run.status === "error", run.status === "not-ready" ? run.detail : null);
  return run;
}

/** Configuración + estado de la ingesta de UNA categoría. */
function ingestView(c) {
  return async function render(container) {
    const [srcRes, stRes] = await Promise.all([api("GET", "/standings-ingest/sources"), api("GET", "/standings-ingest")]);
    container.innerHTML = "";
    if (!srcRes.ok) { container.append(errorBox(srcRes)); return; }
    if (!stRes.ok) { container.append(errorBox(stRes)); return; }
    const st = stRes.data.find((x) => x.categoryId === c.id);
    const sel = h("select", {},
      h("option", { value: "" }, "— sin posiciones automáticas —"),
      srcRes.data.map((src) => h("option", { value: src.id }, `${src.id} · ${src.credit}`)),
    );
    sel.value = st?.source ?? "";
    const param = h("input", { type: "text", value: st?.param ?? "", placeholder: "solo si la fuente lo pide" });
    const enabled = h("input", { type: "checkbox", checked: st ? st.enabled : true });
    const desc = h("div", { class: "muted", style: "margin-top:6px" });
    const showDesc = () => {
      const src = srcRes.data.find((x) => x.id === sel.value);
      desc.textContent = src ? src.description + (src.paramHint ? ` · Parámetro: ${src.paramHint}` : "") : "";
    };
    sel.addEventListener("change", showDesc); showDesc();
    const out = h("div");
    const save = async () => {
      if (!sel.value) {
        if (!st) return;
        if (!(await confirmModal("¿Quitar las posiciones automáticas? Las posiciones guardadas se conservan."))) return;
        if (reportResult(await api("DELETE", `/categories/${c.id}/standings-ingest`))) render(container);
        return;
      }
      const body = { source: sel.value, param: param.value.trim() || null, enabled: enabled.checked };
      if (reportResult(await api("PUT", `/categories/${c.id}/standings-ingest`, body))) render(container);
    };
    container.append(h("div", { class: "panel" },
      h("h2", {}, `Posiciones automáticas — ${c.name}`),
      h("div", { class: "muted", style: "margin-bottom:12px" },
        "Al terminar cada fecha del calendario, el backend pide la tabla a la fuente (solo de esta categoría) hasta que la refleje, y la confirma ~3 días después (sanciones). Reemplaza pilotos y posiciones completos."),
      h("label", {}, "Fuente"), sel,
      h("label", {}, "Parámetro"), param,
      h("label", { class: "check-row" }, enabled, h("span", {}, "activa")),
      desc,
      h("div", { class: "form-actions", style: "margin-top:12px" },
        h("button", { class: "btn btn-primary", onclick: save }, "Guardar"),
        st ? h("button", { class: "btn", onclick: async () => { const r = await runIngest(c.id, false); if (r) render(container); } }, "Actualizar ahora") : null,
        st ? h("button", { class: "btn", title: "pide la tabla a la fuente sin escribir", onclick: async () => {
          const r = await runIngest(c.id, true);
          out.innerHTML = "";
          if (r?.rows) out.append(ingestPreview(r));
        } }, "Vista previa") : null,
      ),
      st ? h("div", { style: "margin-top:14px;display:grid;grid-template-columns:auto 1fr;gap:4px 14px" },
        h("span", { class: "muted" }, "Estado"), h("span", {}, ingestChip(st)),
        h("span", { class: "muted" }, "Última fecha terminada"), h("span", { class: "mono" }, st.targetRound ?? "—"),
        h("span", { class: "muted" }, "Posiciones tras la fecha"), h("span", { class: "mono" }, st.throughRound ?? "—"),
        h("span", { class: "muted" }, "Último cambio"), h("span", { class: "mono" }, fmtWhenShort(st.syncedAt)),
        h("span", { class: "muted" }, "Último intento"), h("span", { class: "mono" }, fmtWhenShort(st.lastAttemptAt)),
        h("span", { class: "muted" }, "Nota"), h("span", {}, st.lastError ? h("span", { style: "color:var(--red)" }, st.lastError) : (st.lastNote ?? "—")),
      ) : null,
    ), out);
  };
}

/** Sección: estado de TODAS las categorías con posiciones automáticas. */
const secIngesta = {
  id: "ingesta", label: "Posiciones auto", scope: "championships",
  render: async function render(container) {
    const res = await api("GET", "/standings-ingest");
    container.innerHTML = "";
    if (!res.ok) { container.append(errorBox(res)); return; }
    const rows = res.data;
    const out = h("div");
    container.append(h("div", { class: "panel" },
      h("h2", {}, "Posiciones automáticas"),
      h("div", { class: "muted", style: "margin-bottom:10px" },
        "Categorías cuya tabla de posiciones llega sola de una fuente externa al terminar cada fecha. Se configuran desde Campeonatos → Temporadas → Categorías → \"automáticas\"."),
      rows.length === 0
        ? h("div", { class: "muted" }, "Ninguna categoría tiene posiciones automáticas.")
        : h("div", { class: "table-scroll" }, h("table", {},
            h("thead", {}, h("tr", {}, ["Campeonato", "Categoría", "Fuente", "Estado", "Tras la fecha", "Último cambio", "Nota", ""].map((x) => h("th", {}, x)))),
            h("tbody", {}, rows.map((st) => h("tr", {},
              h("td", {}, `${st.championship} ${st.season}`),
              h("td", {}, st.category),
              h("td", { class: "mono" }, st.source),
              h("td", {}, ingestChip(st)),
              h("td", { class: "mono" }, `${st.throughRound ?? "—"} / ${st.targetRound ?? "—"}`),
              h("td", { class: "mono" }, fmtWhenShort(st.syncedAt)),
              h("td", { class: "muted" }, st.lastError ?? st.lastNote ?? ""),
              h("td", {},
                h("button", { class: "btn-mini", onclick: async () => { if (await runIngest(st.categoryId, false)) render(container); } }, "actualizar"),
                " ",
                h("button", { class: "btn-mini", onclick: async () => {
                  const r = await runIngest(st.categoryId, true);
                  out.innerHTML = "";
                  if (r?.rows) out.append(ingestPreview(r));
                } }, "vista previa"),
              ),
            ))),
          )),
    ), out);
  },
};

/** Detalle de un evento: datos + acceso a todos sus objetos relacionados. */
/** Detalle de evento "todo en una vista" (diseño Admin - Detalle de evento.dc.html). */
function eventDetailView(evLike) {
  return async function render(container) {
    const res = await api("GET", "/events");
    container.innerHTML = "";
    if (!res.ok) { container.append(errorBox(res)); return; }
    const ev = res.data.find((e) => e.id === evLike.id) ?? evLike;
    const [circuits, officers, champs, asgRes, sesRes, chkRes, chkStateRes, attRes, partRes] = await Promise.all([
      refList("/circuits"), refList("/officers"), refList("/championships"),
      api("GET", `/events/${ev.id}/assignments`),
      api("GET", `/events/${ev.id}/sessions`),
      api("GET", `/events/${ev.id}/checklist`),
      api("GET", `/events/${ev.id}/checklist-state`),
      api("GET", `/events/${ev.id}/attendance`),
      api("GET", `/events/${ev.id}/participations`),
    ]);
    const circuitName = circuits.find((c) => c.id === ev.circuitId)?.name ?? "circuito";
    const trazados = ev.circuitId ? await refList(`/circuits/${ev.circuitId}/trazados`) : [];
    const evTrazados = (ev.trazadoIds?.length ? ev.trazadoIds : [ev.trazadoId])
      .map((tid) => trazados.find((t) => t.id === tid)).filter(Boolean);
    let assignments = asgRes.ok ? asgRes.data : [];
    let sessions = sesRes.ok ? sesRes.data : [];
    let checklist = chkRes.ok ? chkRes.data : [];
    const champInfos = await Promise.all((ev.championshipIds ?? []).map(async (cid) => ({
      ch: champs.find((c) => c.id === cid),
      cats: await refList(`/championships/${cid}/categories`),
    })));

    const offOf = (id) => officers.find((o) => o.id === id);
    const firstName = (id) => {
      const n = offOf(id)?.displayName ?? "";
      const p = n.split(/\s+/);
      return p.length > 1 ? `${p[0]} ${p[1][0]}.` : p[0];
    };
    const initials = (id) => (offOf(id)?.displayName ?? "?").split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
    const byPuesto = new Map();
    const rebuildAsg = () => {
      byPuesto.clear();
      for (const a of assignments) {
        if (!byPuesto.has(a.puestoId)) byPuesto.set(a.puestoId, []);
        byPuesto.get(a.puestoId).push(a);
      }
    };
    rebuildAsg();
    // Avance del checklist por puesto: puestoId → (itemId → {done, markedBy, markedAt}).
    const stateByPuesto = new Map();
    const rebuildChkState = (rows) => {
      stateByPuesto.clear();
      for (const r of rows) {
        if (!stateByPuesto.has(r.puestoId)) stateByPuesto.set(r.puestoId, new Map());
        stateByPuesto.get(r.puestoId).set(r.itemId, r);
      }
    };
    rebuildChkState(chkStateRes.ok ? chkStateRes.data : []);
    const chkDone = (pid) => {
      const st = stateByPuesto.get(pid);
      return st ? checklist.filter((c) => st.get(c.id)?.done).length : 0;
    };
    const fmtWhen = (iso) => iso ? new Date(iso).toLocaleString("es-MX", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "";

    // —— estado local de la vista: trazado activo + puesto seleccionado ——
    let curTz = evTrazados[0] ?? null;
    let puestos = [], assets = [];
    let selPuesto = null;

    const goAsignaciones = () => pushView(`Asignaciones · ${ev.name}`, bulkView(assignmentsCfg(ev)), `eventos/${ev.id}/asignaciones`);

    // —— header ——
    const evHead =
      h("div", { class: "ev-head" },
        h("div", { class: "grow" },
          h("h2", {}, ev.name),
          h("div", { class: "detail-row" },
            chip(`${ev.startsOn} → ${ev.endsOn}`, "", "Fechas del evento (inicio → fin)"),
            chip(ev.status, ev.status === "LIVE" ? "ok" : "", EVENT_STATUS_TIPS[ev.status]),
            chip(ev.active ? "● activo en la app" : "no activo", ev.active ? "ok" : "", ev.active ? ACTIVE_TIP_ON : ACTIVE_TIP_OFF),
            chip(circuitName, "", "Circuito del evento"),
            chip(ev.selfRegistration === false ? "autoregistro cerrado" : "autoregistro permitido",
              ev.selfRegistration === false ? "" : "ok",
              ev.selfRegistration === false
                ? "Los oficiales NO pueden registrarse por honor: la participación sale solo del roster (se cambia en ✎ editar datos)"
                : "Los oficiales pueden registrar por honor que trabajaron el evento, desde su primer día (se cambia en ✎ editar datos)"),
          ),
        ),
        h("button", {
          class: "btn-mini",
          onclick: async () => {
            if (reportResult(await api("POST", `/events/${ev.id}/active?value=${!ev.active}`))) render(container);
          },
        }, ev.active ? "desactivar" : "activar"),
        h("button", { class: "btn-mini", title: "los datos del evento se editan en la lista de Eventos", onclick: () => popTo(0) }, "✎ editar datos"),
      );
    // Imagen del evento junto al nombre (solo si existe; sin placeholder).
    authBlobUrl(`/images/event/${ev.id}/thumb`).then((u) => {
      if (u) evHead.prepend(h("img", { src: u, class: "thumb", style: "width:32px;height:32px;margin-top:2px" }));
    });
    container.append(h("div", { class: "panel" }, evHead));

    // —— mapa (izquierda) + panel de puesto/cobertura (derecha) ——
    const mapArea = h("div", { class: "ev-maparea" });
    const mapEl = h("div", { class: "ev-map" }, mapArea,
      h("div", { class: "ev-mapcap" }, "Clic en un puesto o activo para ver sus asignaciones"));
    const tabsEl = h("div", { class: "ev-tztabs" });
    const sideEl = h("div", { class: "panel" });

    function renderTabs() {
      tabsEl.innerHTML = "";
      for (const t of evTrazados) {
        tabsEl.append(h("span", {
          class: "ev-tzt" + (t.id === curTz?.id ? " on" : ""),
          onclick: () => loadTrazado(t),
        }, t.name, h("span", { class: "m" }, `${t.lengthM.toLocaleString("es-MX")} m`)));
      }
    }

    function renderMap() {
      mapArea.innerHTML = "";
      if (curTz?.path?.length) {
        const svg = s("svg", { class: "ev-path", viewBox: "0 0 1 1", preserveAspectRatio: "none" });
        const d = curTz.path.map((p, i) => `${i ? "L" : "M"}${p.x} ${p.y}`).join(" ") + " Z";
        svg.append(s("path", { d, fill: "none", stroke: "#5f6a80", "stroke-width": "0.012", "stroke-linejoin": "round" }));
        mapArea.append(svg);
      } else if (puestos.length === 0) {
        mapArea.append(h("div", { class: "ev-nopath muted" }, "Este trazado aún no tiene dibujo ni puestos — usa «abrir editor del mapa»"));
      }
      for (const p of puestos) {
        if (p.onMap === false) continue; // coordinación de zona: sin pin (sale en la cobertura)
        const asg = byPuesto.get(p.id) ?? [];
        // Verde = checklist COMPLETA del puesto; ámbar = con oficiales; gris = sin asignar.
        const complete = checklist.length > 0 && chkDone(p.id) === checklist.length;
        const name = p.label || "Puesto " + p.number;
        const tip = asg.length
          ? `${name}: ${asg.length} oficial(es) · checklist ${chkDone(p.id)}/${checklist.length}${complete ? " ✓ completa" : ""}`
          : `${name}: sin asignar`;
        const pin = h("div", {
          class: "ev-pin" + (complete ? " complete" : asg.length ? " staffed" : "") + (selPuesto?.id === p.id ? " sel" : ""),
          style: `left:${p.point.x * 100}%;top:${p.point.y * 100}%`,
          title: tip,
          onclick: () => { selPuesto = selPuesto?.id === p.id ? null : p; renderMap(); renderSide(); },
        }, p.label || String(p.number));
        if (asg.length) pin.setAttribute("data-n", asg.length);
        mapArea.append(pin);
      }
      for (const a of assets) {
        // Los activos también son posiciones asignables: tripulado = mismo lenguaje
        // visual que un puesto con oficiales (ámbar + contador) y clic para ver la crew.
        const asg = byPuesto.get(a.id) ?? [];
        const pin = h("div", {
          class: `ev-asset l-${ASSET_TYPES[a.type]?.layer ?? "rescate"}` + (asg.length ? " staffed" : "") + (selPuesto?.id === a.id ? " sel" : ""),
          style: `left:${a.point.x * 100}%;top:${a.point.y * 100}%`,
          title: asg.length ? `${a.label}: ${asg.length} oficial(es)` : a.label,
          onclick: () => { selPuesto = selPuesto?.id === a.id ? null : { ...a, isAsset: true }; renderMap(); renderSide(); },
        }, a.label || ASSET_TYPES[a.type]?.abbr || "?");
        if (asg.length) pin.setAttribute("data-n", asg.length);
        mapArea.append(pin);
      }
    }

    function renderSide() {
      sideEl.innerHTML = "";
      if (selPuesto) {
        const asg = byPuesto.get(selPuesto.id) ?? [];
        const kindLine = selPuesto.isAsset
          ? `${ASSET_TYPES[selPuesto.type]?.label ?? selPuesto.type} · ${curTz?.name ?? ""}`
          : `Puesto ${selPuesto.number} · ${curTz?.name ?? ""}`;
        sideEl.append(
          h("div", { class: "ev-ptit" }, h("b", {}, selPuesto.isAsset ? "Activo seleccionado" : "Puesto seleccionado"),
            h("button", { class: "ev-link", onclick: () => { selPuesto = null; renderMap(); renderSide(); } }, "ver cobertura ✕")),
          h("div", { class: "ev-pph" }, h("b", {}, selPuesto.label || `Puesto ${selPuesto.number}`),
            h("small", {}, kindLine)),
          // OJO: append() nativo no aplana arrays (h() sí) — esparcir siempre.
          ...(asg.length === 0
            ? [h("div", { class: "ev-empty" }, "Sin oficiales asignados a esta posición. Agrégalos en Asignaciones.")]
            : asg.map((a) => {
              const avt = h("div", { class: "ev-avt" }, initials(a.officerId));
              authBlobUrl(`/images/avatar/${a.officerId}/thumb`).then((u) => {
                if (u) { avt.textContent = ""; avt.style.backgroundImage = `url(${u})`; }
              });
              return h("div", { class: "ev-arow" + (CHIEF_ROLES.includes(a.role) ? " chief" : "") },
                avt,
                h("div", { class: "ev-ainfo" },
                  h("b", {}, offOf(a.officerId)?.displayName ?? "oficial"),
                  h("small", {}, a.shift ?? "Día completo")),
                h("span", { class: "ev-role" }, a.role),
              );
            })),
        );
        if (checklist.length) {
          const st = stateByPuesto.get(selPuesto.id) ?? new Map();
          sideEl.append(
            h("div", { class: "ev-ptit", style: "margin-top:14px;margin-bottom:6px" },
              // El avance se reinicia a diario (el backend purga lo que no es de HOY, corte CDMX).
              h("b", {}, `Checklist de HOY · ${chkDone(selPuesto.id)}/${checklist.length}`)),
            ...checklist.map((c) => {
              const r = st.get(c.id);
              const on = !!r?.done;
              const tip = r?.markedBy
                ? `${on ? "Marcada" : "Desmarcada"} por ${offOf(r.markedBy)?.displayName ?? "oficial"} · ${fmtWhen(r.markedAt)}`
                : "Sin marcar por este puesto";
              return h("div", { class: "ev-chk", title: tip },
                h("span", { class: "bx" + (on ? " on" : "") }, on ? "✓" : ""), c.text);
            }),
          );
        }
        sideEl.append(
          h("div", { style: "margin-top:12px" },
            h("button", { class: "btn btn-primary", onclick: goAsignaciones }, "✎ Editar asignaciones")),
        );
        return;
      }
      // Sin selección: cobertura del trazado activo (puestos + activos tripulados).
      const tzPositionIds = new Set([...puestos.map((p) => p.id), ...assets.map((a) => a.id)]);
      const staffed = puestos.filter((p) => (byPuesto.get(p.id) ?? []).length > 0);
      const crewedAssets = assets.filter((a) => (byPuesto.get(a.id) ?? []).length > 0);
      const officersCount = new Set(assignments.map((a) => a.officerId)).size;
      // "Sin jefe" solo aplica a puestos (un HIAB de un solo operador no tiene rol-jefe).
      const noChief = staffed.filter((p) => !(byPuesto.get(p.id) ?? []).some((a) => CHIEF_ROLES.includes(a.role))).length;
      sideEl.append(
        h("div", { class: "ev-ptit" }, h("b", {}, "Cobertura del evento"),
          h("button", { class: "ev-link", onclick: goAsignaciones }, "asignaciones ›")),
        h("div", { class: "ev-kpis" },
          h("div", { class: "ev-kpi", title: "Oficiales con asignación en el evento (todos los trazados)" }, h("b", {}, String(officersCount)), h("small", {}, "oficiales")),
          h("div", { class: "ev-kpi", title: `Puestos del trazado ${curTz?.name ?? ""} con al menos un oficial` }, h("b", {}, `${staffed.length}/${puestos.length}`), h("small", {}, "puestos cubiertos")),
          h("div", { class: "ev-kpi", title: `Activos del trazado ${curTz?.name ?? ""} con tripulación asignada` }, h("b", {}, `${crewedAssets.length}/${assets.length}`), h("small", {}, "activos tripulados")),
          h("div", { class: "ev-kpi" + (noChief ? " warn" : ""), title: `Puestos cubiertos sin rol-jefe (${CHIEF_ROLES.join(", ")})` }, h("b", {}, String(noChief)), h("small", {}, "sin jefe")),
        ),
        staffed.length === 0 && crewedAssets.length === 0
          ? h("div", { class: "ev-empty" }, "Aún no hay oficiales asignados a posiciones de este trazado.")
          // Scroll propio a la altura del mapa; orden = recorrido de pista (number) y
          // después los activos tripulados por label.
          : h("div", { class: "ev-covlist" }, ...staffed
            .sort((a, b) => a.number - b.number)
            .map((p) => ({ pos: p, sel: () => { selPuesto = p; } }))
            .concat(crewedAssets
              .sort((a, b) => a.label.localeCompare(b.label, "es", { numeric: true }))
              .map((a) => ({ pos: a, sel: () => { selPuesto = { ...a, isAsset: true }; } })))
            .map(({ pos, sel }) => {
              const asg = byPuesto.get(pos.id) ?? [];
              const jefe = asg.find((a) => CHIEF_ROLES.includes(a.role));
              const who = jefe
                ? `${firstName(jefe.officerId)} (jefe)` + (asg.length > 1 ? ` + ${asg.length - 1}` : "")
                : `${firstName(asg[0].officerId)}` + (asg.length > 1 ? ` + ${asg.length - 1}` : "")
                  + (pos.number !== undefined ? " · sin jefe" : "");
              return h("div", {
                class: "ev-covrow",
                onclick: () => { sel(); renderMap(); renderSide(); },
              }, h("span", { class: "n" }, pos.label || `P ${pos.number}`), h("span", {}, who), h("span", { class: "c" }, String(asg.length)));
            })),
      );
      // Asignaciones a posiciones que no son del trazado activo (otros trazados del evento).
      const elsewhere = assignments.filter((a) => !tzPositionIds.has(a.puestoId)).length;
      if (elsewhere) sideEl.append(h("div", { class: "ev-empty" }, `+ ${elsewhere} asignación(es) en otros trazados del evento`));
    }

    async function loadTrazado(t) {
      curTz = t; selPuesto = null;
      const [pRes, aRes] = await Promise.all([
        api("GET", `/trazados/${t.id}/puestos`), api("GET", `/trazados/${t.id}/assets`),
      ]);
      puestos = pRes.ok ? pRes.data : [];
      assets = aRes.ok ? aRes.data : [];
      renderTabs(); renderMap(); renderSide();
    }

    const mapPanel = h("div", { class: "panel" },
      h("div", { class: "ev-ptit" }, h("b", {}, "Trazados y puestos"),
        h("button", {
          class: "ev-link",
          onclick: () => { if (curTz) pushView(`Mapa · ${curTz.name}`, mapEditorView(curTz), `circuitos/${ev.circuitId}/${curTz.id}`); },
        }, "abrir editor del mapa ›")),
      tabsEl, mapEl,
      h("div", { class: "ev-legend" },
        h("span", { class: "it" }, h("span", { class: "ev-lsw l-puesto" }), "Puesto"),
        h("span", { class: "it" }, h("span", { class: "ev-lsw l-rescate" }), "Rescate (HIAB, IFRT, TH, DR)"),
        h("span", { class: "it" }, h("span", { class: "ev-lsw l-soporte" }), "Soporte (SC, Sweeper)"),
        h("span", { class: "it" }, h("span", { class: "ev-lsw l-medicos" }), "Médicos"),
        h("span", { class: "it" }, h("span", { class: "ev-badge st" }, "2"), "Con oficiales"),
        h("span", { class: "it", title: "Todos los ítems del checklist marcados HOY por ese puesto (el avance se reinicia a diario)" }, h("span", { class: "ev-badge ck" }, "2"), "Checklist completa hoy"),
      ),
    );
    container.append(h("div", { class: "ev-grid" }, mapPanel, sideEl));

    // —— abajo: MbM + campeonatos + checklist ——
    const dayLabel = (iso) => {
      const d = new Date(iso + "T12:00:00");
      const txt = d.toLocaleDateString("es-MX", { weekday: "long", day: "numeric", month: "short" });
      return txt.charAt(0).toUpperCase() + txt.slice(1);
    };
    const mbmCount = h("b", {}, `MbM (cronograma) · ${sessions.length}`);
    const mbmBody = h("div");
    const mbmDaysEl = h("div", { class: "ev-mdays" });
    // Día seleccionado del cronograma (null = todos); arranca en HOY si hay actividades.
    let mbmDay = (() => {
      const today = new Date().toLocaleDateString("sv");
      return sessions.some((s) => s.day === today) ? today : null;
    })();
    const mbmPanel = h("div", { class: "panel" },
      h("div", { class: "ev-ptit" }, mbmCount,
        h("button", { class: "ev-link", onclick: () => pushView(`MbM · ${ev.name}`, bulkView(sessionsCfg(ev)), `eventos/${ev.id}/mbm`) }, "editar ›")),
      mbmDaysEl,
      mbmBody,
    );
    function renderMbm() {
      const days = [...new Set(sessions.map((s) => s.day))];
      if (mbmDay && !days.includes(mbmDay)) mbmDay = null;
      mbmDaysEl.innerHTML = "";
      if (days.length > 1) {
        const chip = (label, val) => h("button", {
          class: "ev-mdchip" + (mbmDay === val ? " on" : ""),
          onclick: () => { mbmDay = val; renderMbm(); },
        }, label);
        mbmDaysEl.append(chip("Todos", null), ...days.map((d) =>
          chip(new Date(d + "T12:00:00").toLocaleDateString("es-MX", { weekday: "short", day: "numeric" }), d)));
      }
      const list = mbmDay ? sessions.filter((s) => s.day === mbmDay) : sessions;
      mbmCount.textContent = `MbM (cronograma) · ${list.length}` + (mbmDay ? ` de ${sessions.length}` : "");
      mbmBody.innerHTML = "";
      if (list.length === 0) mbmBody.append(h("div", { class: "ev-empty" }, "Sin actividades aún."));
      let lastDay = null;
      for (const ses of list) {
        if (ses.day !== lastDay) { lastDay = ses.day; mbmBody.append(h("div", { class: "ev-mday" }, dayLabel(ses.day))); }
        // La actividad EN CURSO (estatus derivado del reloj por el backend) va resaltada,
        // con los minutos que le RESTAN (endsInMin es la duración capturada).
        const isLive = ses.status === "LIVE";
        let left = null;
        if (isLive && ses.endsInMin) {
          const end = new Date(`${ses.day}T${ses.time}:00`).getTime() + ses.endsInMin * 60000;
          left = Math.max(0, Math.round((end - Date.now()) / 60000));
        }
        mbmBody.append(h("div", { class: "ev-mrow" + (isLive ? " live" : "") },
          isLive ? h("span", { class: "ev-live", title: "en curso ahora (según su horario y el reloj)" }) : null,
          h("span", { class: "ev-mtime" }, ses.time),
          h("span", { class: "ev-mcat" + (ses.category ? "" : " none") }, ses.category || "—"),
          h("span", { class: "ev-mname" }, ses.name + (isLive ? " · EN CURSO" : "")),
          isLive
            ? h("span", { class: "ev-mdur grn" }, left != null ? `quedan ${left} min` : "ahora")
            : (ses.endsInMin ? h("span", { class: "ev-mdur" }, `${ses.endsInMin} min`) : null),
        ));
      }
    }
    renderMbm();

    const champPanel = h("div", { class: "panel" },
      h("div", { class: "ev-ptit" }, h("b", {}, "Campeonatos del evento"),
        h("button", { class: "ev-link", title: "se editan en los datos del evento", onclick: () => popTo(0) }, "editar evento ›")),
    );
    if (champInfos.length === 0) champPanel.append(h("div", { class: "ev-empty" }, "Sin campeonatos asociados; asócialos al editar el evento."));
    for (const { ch, cats } of champInfos) {
      if (!ch) continue;
      champPanel.append(h("div", { class: "ev-champ" },
        thumbCell("series", ch.seriesId),
        h("div", { class: "ci" },
          h("b", {}, `${ch.name} · ${ch.seasonLabel}`),
          h("div", { class: "cats" }, cats.map((c) => h("span", { class: "ev-catc" }, c.name))),
        ),
      ));
    }

    // El avance por puesto se consulta en el MAPA: pin verde = checklist completa;
    // clic en un puesto muestra su checklist en el panel.
    const chkCount = h("b", {}, `Checklist del puesto (plantilla) · ${checklist.length}`);
    const chkBody = h("div");
    const chkPanel = h("div", { class: "panel" },
      h("div", { class: "ev-ptit" }, chkCount,
        h("button", { class: "ev-link", onclick: () => pushView(`Checklist · ${ev.name}`, bulkView(checklistCfg(ev)), `eventos/${ev.id}/checklist`) }, "editar ›")),
      chkBody,
    );
    function renderChk() {
      chkCount.textContent = `Checklist del puesto (plantilla) · ${checklist.length}`;
      chkBody.innerHTML = "";
      if (checklist.length === 0) chkBody.append(h("div", { class: "ev-empty" }, "Sin ítems aún."));
      for (const c of checklist) chkBody.append(h("div", { class: "ev-chk" }, h("span", { class: "bx" }), c.text));
    }
    renderChk();

    // —— Asistencia (pase de lista de los jefes): REGISTRO por día, nunca se purga ——
    let attendance = attRes.ok ? attRes.data : [];
    let attDay = null; // día visible (default HOY si tiene filas; si no, el último con filas)
    const attCount = h("b", {}, "Asistencia (pase de lista)");
    const attDaysEl = h("div", { class: "ev-mdays" });
    const attBody = h("div");
    const attPanel = h("div", { class: "panel" },
      h("div", { class: "ev-ptit" }, attCount,
        h("span", { class: "muted", style: "font-size:11px" }, "la marcan los jefes de posición en la app")),
      attDaysEl, attBody);
    // Labels de TODAS las posiciones de los trazados del evento (la fila de asistencia
    // trae el snapshot puesto_id de cuando se marcó).
    let posLabels = new Map();
    Promise.all(evTrazados.map(async (t) => {
      const [ps, as] = await Promise.all([refList(`/trazados/${t.id}/puestos`), refList(`/trazados/${t.id}/assets`)]);
      return [...ps.map((x) => [x.id, x.label || `Puesto ${x.number}`]), ...as.map((x) => [x.id, x.label])];
    })).then((maps) => { posLabels = new Map(maps.flat()); renderAtt(); });
    function renderAtt() {
      const days = [...new Set(attendance.map((r) => r.day))].sort();
      const today = new Date().toLocaleDateString("sv");
      if (!attDay || !days.includes(attDay)) attDay = days.includes(today) ? today : (days[days.length - 1] ?? null);
      attDaysEl.innerHTML = "";
      if (days.length > 1) {
        const chipEl = (label, val) => h("button", {
          class: "ev-mdchip" + (attDay === val ? " on" : ""),
          onclick: () => { attDay = val; renderAtt(); },
        }, label);
        attDaysEl.append(...days.map((d) =>
          chipEl(new Date(d + "T12:00:00").toLocaleDateString("es-MX", { weekday: "short", day: "numeric" }), d)));
      }
      const list = attendance.filter((r) => r.day === attDay);
      const pres = list.filter((r) => r.present).length;
      attCount.textContent = attDay
        ? `Asistencia · ${dayLabel(attDay)} — ${pres} presente(s) · ${list.length - pres} ausente(s)`
        : "Asistencia (pase de lista)";
      attBody.innerHTML = "";
      if (!list.length) {
        attBody.append(h("div", { class: "ev-empty" }, "Sin pase de lista aún. El jefe de cada posición lo marca en la app (tab Puesto)."));
        return;
      }
      const byPos = new Map();
      for (const r of list) {
        if (!byPos.has(r.puestoId)) byPos.set(r.puestoId, []);
        byPos.get(r.puestoId).push(r);
      }
      const label = (pid) => posLabels.get(pid) ?? "posición";
      for (const [pid, rows] of [...byPos.entries()].sort((a, b) => label(a[0]).localeCompare(label(b[0]), "es", { numeric: true }))) {
        attBody.append(h("div", { class: "ev-mday" }, label(pid)));
        for (const r of rows.sort((a, b) => (offOf(a.officerId)?.displayName ?? "").localeCompare(offOf(b.officerId)?.displayName ?? "", "es"))) {
          const tip = `${r.present ? "Presente" : "Ausente"} · marcado por ${offOf(r.markedBy)?.displayName ?? "jefe"} · ${fmtWhen(r.markedAt)}`;
          attBody.append(h("div", { class: "ev-chk", title: tip },
            h("span", { class: "bx" + (r.present ? " on" : " no") }, r.present ? "✓" : "✕"),
            offOf(r.officerId)?.displayName ?? "oficial"));
        }
      }
    }
    renderAtt();

    // —— Registro por honor: lo que declararon los oficiales (aparte del roster; nunca da
    // permisos). El admin lo revisa y puede quitar un registro. ——
    let participations = partRes.ok ? partRes.data : [];
    const partCount = h("b", {}, "Registros por honor");
    const partBody = h("div");
    const partPanel = h("div", { class: "panel" },
      h("div", { class: "ev-ptit" }, partCount,
        h("span", { class: "muted", style: "font-size:11px" }, "los declaran los oficiales en la app · no dan permisos")),
      partBody);
    const fmtDays = (days) => days.length
      ? days.map((d) => new Date(d + "T12:00:00").toLocaleDateString("es-MX", { weekday: "short", day: "numeric" })).join(", ")
      : "todos los días";
    function renderPart() {
      partCount.textContent = `Registros por honor (${participations.length})`;
      partBody.innerHTML = "";
      if (!participations.length) {
        partBody.append(h("div", { class: "ev-empty" }, ev.selfRegistration === false
          ? "Autoregistro cerrado: la participación de este evento sale solo del roster."
          : "Nadie se ha registrado aún. Los oficiales lo hacen desde la agenda de la app a partir del primer día del evento."));
        return;
      }
      let lastPos = null;
      for (const p of participations) {
        const pos = p.positionLabel || "Sin puesto";
        if (pos !== lastPos) { lastPos = pos; partBody.append(h("div", { class: "ev-mday" }, pos)); }
        const tip = `Registrado el ${fmtWhen(p.createdAt)}` + (p.updatedAt !== p.createdAt ? ` · editado el ${fmtWhen(p.updatedAt)}` : "");
        partBody.append(h("div", { class: "ev-chk", title: tip },
          h("span", { class: "grow" },
            h("b", {}, p.officerName), ` · OMDAI ${p.omdaiId} · ${p.role}`,
            h("span", { class: "muted", style: "margin-left:6px" }, fmtDays(p.days)),
            p.rostered ? chip("también en roster", "", "El roster de la organización incluye a este oficial: su asignación manda y este registro no cuenta") : null),
          h("button", {
            class: "btn-mini danger",
            onclick: async () => {
              if (!(await confirmModal(`¿Quitar el registro por honor de ${p.officerName}? Deja de contar en su historial y sus logros.`))) return;
              if (reportResult(await api("DELETE", `/participations/${p.id}`))) refetch.participation();
            },
          }, "quitar")));
      }
    }
    renderPart();

    // —— Chat del evento: el admin lee y escribe como "Control" (tiempo real vía SSE) ——
    const chatMsgs = h("div", { class: "ev-chatmsgs" });
    const chatInput = h("input", { placeholder: "Mensaje como Control…" });
    async function renderChat() {
      const r = await api("GET", `/events/${ev.id}/chat`);
      if (!r.ok) return;
      chatMsgs.innerHTML = "";
      const msgs = r.data.messages ?? [];
      if (!msgs.length) chatMsgs.append(h("div", { class: "ev-empty" }, "Sin mensajes aún. Lo que escribas aquí lo ven los oficiales del evento en su app."));
      const hhmm = (iso) => new Date(iso).toLocaleTimeString("es-MX", { hour: "2-digit", minute: "2-digit", hour12: false });
      const dayLbl = (d) => {
        const hoy = new Date(); const ayer = new Date(); ayer.setDate(hoy.getDate() - 1);
        const mismo = (a, b) => a.toDateString() === b.toDateString();
        if (mismo(d, hoy)) return "Hoy";
        if (mismo(d, ayer)) return "Ayer";
        return d.toLocaleDateString("es-MX", { day: "numeric", month: "long", year: d.getFullYear() !== hoy.getFullYear() ? "numeric" : undefined });
      };
      let lastDay = null;
      for (const m of msgs) {
        // Separador de día (estilo WhatsApp): la fecha vive aquí, cada mensaje solo trae hora.
        const d = new Date(m.at);
        if (d.toDateString() !== lastDay) {
          lastDay = d.toDateString();
          chatMsgs.append(h("div", { class: "ev-daysep" }, h("span", {}, dayLbl(d))));
        }
        const ctrl = !m.senderId && !m.system;
        const avt = h("div", { class: "ev-avt sm" }, m.system ? "•" : (ctrl ? "C" : (offOf(m.senderId)?.displayName ?? m.senderName).split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase()));
        if (m.senderId) authBlobUrl(`/images/avatar/${m.senderId}/thumb`).then((u) => {
          if (u) { avt.textContent = ""; avt.style.backgroundImage = `url(${u})`; }
        });
        const ctx = [m.senderPuesto, m.senderRole].filter(Boolean).join(" · ");
        chatMsgs.append(h("div", { class: "ev-msg" + (ctrl ? " ctrl" : "") + (m.system ? " sys" : "") },
          avt,
          h("div", { class: "body" },
            h("div", { class: "hd" }, h("b", {}, m.senderName),
              ctx ? h("span", { class: "ctx" }, ctx) : null),
            h("div", { class: "txt" }, m.mediaType === "IMAGE" ? `📷 ${m.text || "Foto"}` : m.text),
            h("div", { class: "when" }, hhmm(m.at)),
          ),
        ));
      }
      chatMsgs.scrollTop = chatMsgs.scrollHeight;
    }
    async function sendControl() {
      const t = chatInput.value.trim();
      if (!t) return;
      chatInput.value = "";
      const r = await api("POST", `/events/${ev.id}/chat/messages`, { text: t });
      if (!r.ok) { reportResult(r); chatInput.value = t; return; }
      renderChat(); // el SSE también refetchea; esto evita esperar el round-trip
    }
    chatInput.addEventListener("keydown", (e) => { if (e.key === "Enter") sendControl(); });
    const chatPanel = h("div", { class: "panel" },
      h("div", { class: "ev-ptit" }, h("b", {}, "Chat del evento"),
        h("span", { class: "muted", style: "font-size:11px" }, "escribes como Control")),
      chatMsgs,
      h("div", { style: "display:flex; gap:8px; margin-top:10px" }, chatInput,
        h("button", { class: "btn btn-primary", onclick: sendControl }, "Enviar")),
    );
    renderChat();

    // MbM y chat lado a lado (el chat es el canal operativo del MbM); campeonatos y
    // checklist debajo del MbM.
    container.append(h("div", { class: "ev-below" },
      h("div", { class: "ev-col" }, mbmPanel, champPanel, chkPanel, attPanel, partPanel),
      h("div", { class: "ev-col" }, chatPanel)));

    if (curTz) await loadTrazado(curTz); else { renderTabs(); renderSide(); }

    // —— Tiempo real (SSE): el backend avisa QUÉ cambió y aquí se refetchea esa sección ——
    let es = null, esClosed = false;
    const refetch = {
      checklist: async () => {
        const [c, st] = await Promise.all([
          api("GET", `/events/${ev.id}/checklist`), api("GET", `/events/${ev.id}/checklist-state`),
        ]);
        if (c.ok) checklist = c.data;
        rebuildChkState(st.ok ? st.data : []);
        renderChk(); renderMap(); renderSide();
      },
      assignments: async () => {
        const r = await api("GET", `/events/${ev.id}/assignments`);
        if (r.ok) { assignments = r.data; rebuildAsg(); renderMap(); renderSide(); }
      },
      sessions: async () => {
        const r = await api("GET", `/events/${ev.id}/sessions`);
        if (r.ok) { sessions = r.data; renderMbm(); }
      },
      map: async () => { if (curTz) await loadTrazado(curTz); },
      attendance: async () => {
        const r = await api("GET", `/events/${ev.id}/attendance`);
        if (r.ok) { attendance = r.data; renderAtt(); }
      },
      participation: async () => {
        const r = await api("GET", `/events/${ev.id}/participations`);
        if (r.ok) { participations = r.data; renderPart(); }
      },
      chat: async () => { renderChat(); },
      event: async () => {
        if (activeViewCleanup) { activeViewCleanup(); activeViewCleanup = null; }
        render(container);
      },
    };
    async function connectStream() {
      if (esClosed) return;
      const tk = await api("POST", "/events/stream-token", {});
      if (esClosed) return;
      // Sin token (p. ej. backend reiniciándose): reintentar — antes se rendía para
      // siempre y la página dejaba de recibir cambios hasta recargarla.
      if (!tk.ok) { setTimeout(connectStream, 3000); return; }
      es = new EventSource(`/admin/events/${ev.id}/stream?t=${tk.data.token}`);
      es.addEventListener("change", (m) => { (refetch[JSON.parse(m.data).kind] ?? (() => {}))(); });
      // El token es de un solo uso: al caerse la conexión se pide otro y se reconecta.
      es.onerror = () => { es.close(); if (!esClosed) setTimeout(connectStream, 2000); };
    }
    connectStream();
    // El "quedan N min" y el resaltado EN CURSO avanzan aunque nadie escriba (tick por minuto).
    const mbmTick = setInterval(() => refetch.sessions(), 60_000);
    activeViewCleanup = () => { esClosed = true; if (es) es.close(); clearInterval(mbmTick); };
  };
}

/**
 * Prueba de los correos (POST /admin/email-test): una muestra de cada plantilla a la
 * dirección que se escriba. Son correos reales: máximo 3 pruebas por hora.
 */
function emailTestBox() {
  const inp = h("input", { type: "email", placeholder: "correo que recibe las muestras", style: "min-width:240px" });
  const out = h("div", { class: "muted", style: "margin-top:6px" });
  const btn = h("button", { class: "btn" }, "Probar correos");
  btn.onclick = async () => {
    const to = inp.value.trim();
    if (!to) { toast("escribe la dirección que recibirá las muestras", true); return; }
    btn.disabled = true;
    out.textContent = "Enviando…";
    const r = await api("POST", "/email-test", { to });
    btn.disabled = false;
    if (!r.ok) { out.textContent = ""; reportResult(r); return; }
    const fallidos = r.data.results.filter((x) => !x.sent).map((x) => x.kind);
    out.textContent = fallidos.length
      ? `✗ El servidor de correo no aceptó: ${fallidos.join(", ")}. Revisa el log del backend.`
      : `✓ El servidor de correo aceptó los ${r.data.results.length}. Revisa el buzón de ${r.data.to} (y su carpeta de spam).`;
  };
  return h("details", { style: "margin-bottom:10px" },
    h("summary", {}, "Probar los correos del sistema"),
    h("div", { class: "muted", style: "margin:6px 0" },
      "Manda a esa dirección una muestra de cada correo (acceso, invitación, cuenta aprobada, descarga de datos, sesiones cerradas y alerta) con «[Prueba]» en el asunto. Son correos reales: máximo 3 pruebas por hora."),
    h("div", { style: "display:flex; gap:8px; align-items:center; flex-wrap:wrap" }, inp, btn),
    out);
}

// ——— Secciones ———

const secCuentas = {
  id: "cuentas", label: "Cuentas", scope: "accounts",
  render: crudView({
    entity: "cuenta", listPath: "/accounts", itemKey: "email", keyed: true,
    headerNote: "Alta por invitación: liga la cuenta a un oficial (editar) y apruébala; al aprobarla le llega un correo para entrar.",
    headerExtra: emailTestBox,
    columns: [
      { key: "email", label: "Email" },
      { key: "officerId", label: "Oficial", fmt: (v) => (v ? officerNameCell(v) : null) },
      { key: "invitedBy", label: "Invitada por", fmt: (v) => v ?? h("span", { class: "muted" }, "—") },
      { key: "status", label: "Estatus", fmt: (v) => chip(v, v === "ACTIVE" ? "ok" : v === "SUSPENDED" ? "bad" : "warn", ACCOUNT_STATUS_TIPS[v]) },
      { key: "activeSessions", label: "Sesiones", fmt: (v) => (v ? h("span", { class: "mono", title: "Teléfonos con la sesión abierta" }, String(v)) : h("span", { class: "muted" }, "—")) },
    ],
    fields: [
      { key: "email", label: "Email", type: "text" },
      { key: "officerId", label: "Oficial", type: "refselect", load: officerOptions, optional: true },
      { key: "status", label: "Estatus", type: "select", options: ACCOUNT_STATUS },
    ],
    toBody: (v) => ({ officerId: v.officerId, status: v.status }),
    rowActions: [
      {
        // Sin oficial ligado no se aprueba (el backend lo rechaza): primero "editar" y elegir
        // el oficial (o crearlo en Oficiales).
        label: "aprobar", visible: (it) => it.status !== "ACTIVE" && !!it.officerId,
        fn: async (it, reload) => { if (reportResult(await api("POST", `/accounts/${encodeURIComponent(it.email)}/approve`))) reload(); },
      },
      {
        // Teléfono perdido o sospecha de robo: la cuenta sigue activa, pero debe volver a entrar.
        label: "cerrar sesiones", visible: (it) => (it.activeSessions ?? 0) > 0,
        fn: async (it, reload) => {
          if (!(await confirmModal(`¿Cerrar las ${it.activeSessions} sesiones de ${it.email}? Tendrá que volver a entrar con su correo.`))) return;
          if (reportResult(await api("POST", `/accounts/${encodeURIComponent(it.email)}/revoke-sessions`))) reload();
        },
      },
    ],
  }),
};

const secOficiales = {
  id: "oficiales", label: "Oficiales", scope: "officers",
  render: crudView({
    entity: "oficial", listPath: "/officers", itemKey: "id",
    columns: [
      { key: "id", label: "", fmt: (v) => thumbCell("avatar", v) },
      { key: "omdaiId", label: "OMDAI" },
      { key: "displayName", label: "Nombre" },
      { key: "assignedArea", label: "Área" },
      { key: "status", label: "Estatus", fmt: (v) => chip(v, v === "ACTIVE" ? "ok" : "bad", ACCOUNT_STATUS_TIPS[v]) },
    ],
    fields: [
      { key: "omdaiId", label: "OMDAI ID", type: "number" },
      { key: "displayName", label: "Nombre", type: "text" },
      { key: "assignedArea", label: "Área asignada", type: "select", options: AREAS, optional: true },
      { key: "systemRole", label: "Rol de sistema", type: "select", options: ["OFICIAL", "COORDINADOR", "ADMIN"] },
      { key: "status", label: "Estatus", type: "select", options: ACCOUNT_STATUS },
      { key: "stats.events", label: "Eventos (total)", type: "number" },
      { key: "stats.thisSeason", label: "Eventos (temporada)", type: "number" },
      { key: "stats.activeSince", label: "Activo desde (año)", type: "number", optional: true },
    ],
    rowActions: [
      { label: "avatar", fn: (it, reload) => uploadImage("avatar", it.id, reload) },
    ],
  }),
};

const secEventos = {
  id: "eventos", label: "Eventos", scope: "events",
  render: crudView({
    entity: "evento", listPath: "/events", itemKey: "id", deletable: true,
    headerNote: "Abre un evento para administrar su MbM, checklist, asignaciones y compañeros. El estatus se deriva de las fechas (antes = UPCOMING, durante = LIVE, después = FINISHED).",
    columns: [
      { key: "id", label: "", fmt: (v) => thumbCell("event", v) },
      { key: "name", label: "Nombre" },
      { key: "startsOn", label: "Inicio" },
      { key: "endsOn", label: "Fin" },
      { key: "status", label: "Estatus", fmt: (v) => chip(v, v === "LIVE" ? "ok" : "", EVENT_STATUS_TIPS[v]) },
      { key: "active", label: "Activo", fmt: (v) => chip(v ? "● activo" : "—", v ? "ok" : "", v ? ACTIVE_TIP_ON : ACTIVE_TIP_OFF) },
      {
        key: "selfRegistration", label: "Autoregistro",
        fmt: (v, it) => v === false
          ? chip("cerrado", "", "La participación sale solo del roster de la organización")
          : chip(it.declaredCount ? `permitido · ${it.declaredCount}` : "permitido", "ok",
            it.declaredCount ? `${it.declaredCount} oficial(es) registraron por honor que trabajaron este evento` : "Los oficiales pueden registrar por honor que trabajaron este evento"),
      },
    ],
    fields: [
      { key: "name", label: "Nombre", type: "text" },
      { key: "startsOn", label: "Inicia", type: "date" },
      { key: "endsOn", label: "Termina", type: "date" },
      { key: "circuitId", label: "Circuito", type: "refselect", load: circuitOptions },
      {
        key: "trazadoIds", label: "Trazados", type: "refmulti", dependsOn: "circuitId",
        load: (get) => trazadoOptions(get("circuitId")),
        hint: "el evento puede usar varios trazados del circuito; el primero marcado (orden del catálogo) queda como principal",
      },
      {
        key: "championshipIds", label: "Campeonatos", type: "refmulti", load: championshipOptions, optional: true,
        hint: "los campeonatos que corren en el evento; sus categorías alimentan el combo de Categoría del MbM",
      },
      {
        key: "selfRegistration", label: "Permitir autoregistro por honor", type: "checkbox", default: true,
        hint: "los oficiales registran desde la app que trabajaron el evento (a partir de su primer día); quítalo en eventos con roster completo, como el GP",
      },
    ],
    rowActions: [
      { label: "abrir ▸", fn: (it) => pushView(`Evento · ${it.name}`, eventDetailView(it), `eventos/${it.id}`) },
      { label: "imagen", fn: (it, reload) => uploadImage("event", it.id, reload) },
      {
        label: (it) => (it.active ? "desactivar" : "activar"),
        fn: async (it, reload) => {
          if (reportResult(await api("POST", `/events/${it.id}/active?value=${!it.active}`))) reload();
        },
      },
    ],
  }),
};

function trazadosView(circuit) {
  return crudView({
    entity: "trazado", listPath: `/circuits/${circuit.id}/trazados`, itemKey: "id",
    savePath: (id) => `/trazados/${encodeURIComponent(id)}`, createPath: "/trazados",
    headerNote: "En el mapa de cada trazado se dibuja la pista (sobre OSM) y se colocan puestos y activos.",
    columns: [
      { key: "name", label: "Nombre" },
      { key: "lengthM", label: "Metros" },
      { key: "curves", label: "Curvas" },
      { key: "direction", label: "Sentido" },
      { key: "path", label: "Dibujo", fmt: (v) => (v && v.length) ? chip("sí", "ok", "El trazado ya tiene su silueta dibujada sobre el mapa (OSM)") : chip("no", "", "Aún sin dibujo: entra al mapa y traza la pista sobre OSM") },
    ],
    // El circuito viene dado por la vista (se entró desde SUS trazados): no se
    // pregunta ni se puede cambiar; se inyecta al guardar.
    fields: [
      { key: "name", label: "Nombre", type: "text" },
      { key: "lengthM", label: "Longitud (m)", type: "number", hint: "metros, entero (p. ej. 4304)" },
      { key: "curves", label: "Curvas", type: "number" },
      { key: "direction", label: "Sentido", type: "select", options: ["Horario", "Antihorario"], default: "Horario" },
    ],
    toBody: (v) => ({ ...v, circuitId: circuit.id }),
    rowActions: [
      { label: "mapa ▸", fn: (t) => pushView(`Mapa · ${t.name}`, mapEditorView(t), `circuitos/${circuit.id}/${t.id}`) },
      {
        label: "borrar", danger: true,
        fn: async (t, reload) => {
          if (!(await confirmModal(`¿Borrar trazado '${t.name}' con sus puestos/activos?`))) return;
          if (reportResult(await api("DELETE", `/trazados/${t.id}`))) reload();
        },
      },
    ],
  });
}

const secCircuitos = {
  id: "circuitos", label: "Circuitos", scope: "circuits",
  render: crudView({
    entity: "circuito", listPath: "/circuits", itemKey: "id", deletable: true,
    columns: [
      { key: "id", label: "", fmt: (v) => thumbCell("circuit", v) },
      { key: "name", label: "Nombre" },
      { key: "location", label: "Ubicación" },
      { key: "country", label: "País" },
    ],
    fields: [
      { key: "name", label: "Nombre", type: "text" },
      { key: "location", label: "Ubicación", type: "geosearch", hint: "escribe y busca con 🔍 (OpenStreetMap); elegir un resultado pone la dirección exacta, y puedes editarla" },
      { key: "country", label: "País", type: "text", optional: true, hint: "en español; la app agrupa México primero" },
    ],
    rowActions: [
      { label: "trazados ▸", fn: (it) => pushView(`Trazados · ${it.name}`, trazadosView(it), `circuitos/${it.id}`) },
      { label: "logo", fn: (it, reload) => uploadImage("circuit", it.id, reload) },
    ],
  }),
};

/** Temporadas de un campeonato (API: championships con seriesId). */
function temporadasView(series) {
  return crudView({
    entity: "temporada", listPath: `/championships?seriesId=${encodeURIComponent(series.id)}`, itemKey: "id", deletable: true,
    savePath: (id) => `/championships/${encodeURIComponent(id)}`, createPath: "/championships",
    headerNote: "Una fila por temporada. Si la temporada cruza el año usa la etiqueta \"2025-26\"; el año para ordenar se deriva (2025-26 → 2026). Borrar una temporada borra sus categorías, calendario, posiciones y pilotos.",
    columns: [
      { key: "seasonLabel", label: "Temporada" },
      { key: "startsOn", label: "Del", fmt: (v) => v ?? null },
      { key: "endsOn", label: "Al", fmt: (v) => v ?? null },
    ],
    fields: [
      { key: "seasonLabel", label: "Temporada (\"2026\" o \"2025-26\")", type: "text" },
    ],
    // El campeonato viene dado por la vista: no se pregunta; se inyecta al guardar.
    toBody: (v) => ({ ...v, seriesId: series.id }),
    rowActions: [
      { label: "categorías ▸", fn: (c) => pushView(`Categorías · ${series.name} ${c.seasonLabel}`, categoriasView(c), `campeonatos/${series.id}/${c.id}`) },
    ],
  });
}

function categoriasView(champ) {
  const base = `campeonatos/${champ.seriesId}/${champ.id}`;
  return crudView({
    entity: "categoría", listPath: `/championships/${champ.id}/categories`, itemKey: "id",
    savePath: (id) => `/categories/${encodeURIComponent(id)}`, createPath: "/categories",
    columns: [{ key: "name", label: "Nombre" }],
    // El campeonato viene dado por la vista: no se pregunta; se inyecta al guardar.
    fields: [
      { key: "name", label: "Nombre", type: "text" },
    ],
    toBody: (v) => ({ ...v, championshipId: champ.id }),
    rowActions: [
      { label: "posiciones", fn: (c) => pushView(`Posiciones · ${c.name}`, bulkView(standingsCfg(c)), `${base}/${c.id}/posiciones`) },
      { label: "calendario", fn: (c) => pushView(`Calendario · ${c.name}`, bulkView(roundsCfg(c)), `${base}/${c.id}/calendario`) },
      { label: "pilotos", fn: (c) => pushView(`Pilotos · ${c.name}`, bulkView(driversCfg(c)), `${base}/${c.id}/pilotos`) },
      { label: "automáticas", fn: (c) => pushView(`Posiciones automáticas · ${c.name}`, ingestView(c), `${base}/${c.id}/automaticas`) },
      {
        label: "borrar", danger: true,
        fn: async (c, reload) => {
          if (!(await confirmModal(`¿Borrar categoría '${c.name}' con posiciones/fechas/pilotos?`))) return;
          if (reportResult(await api("DELETE", `/categories/${c.id}`))) reload();
        },
      },
    ],
  });
}

// Campeonato (API "series": Fórmula 1, NASCAR…) → sus temporadas → categorías.
const secCampeonatos = {
  id: "campeonatos", label: "Campeonatos", scope: "championships",
  render: crudView({
    entity: "campeonato", listPath: "/series", itemKey: "id", deletable: true,
    headerNote: "El campeonato es la serie (Fórmula E, NASCAR…); cada año/temporada va dentro. El logo es del campeonato. Para borrar un campeonato, borra antes sus temporadas.",
    columns: [
      { key: "id", label: "", fmt: (v) => thumbCell("series", v) },
      { key: "name", label: "Nombre" },
    ],
    fields: [
      { key: "name", label: "Nombre", type: "text" },
    ],
    rowActions: [
      { label: "temporadas ▸", fn: (it) => pushView(`Temporadas · ${it.name}`, temporadasView(it), `campeonatos/${it.id}`) },
      { label: "logo", fn: (it, reload) => uploadImage("series", it.id, reload) },
    ],
  }),
};

const secConvocatorias = {
  id: "convocatorias", label: "Convocatorias", scope: "convocatorias",
  render: crudView({
    entity: "convocatoria", listPath: "/convocatorias", itemKey: "id", deletable: true,
    columns: [
      { key: "eventName", label: "Evento" },
      { key: "eventDate", label: "Fecha" },
      { key: "circuitId", label: "Circuito", fmt: (v) => (v ? circuitNameCell(v) : null) },
      { key: "cupo", label: "Cupo" },
      { key: "status", label: "Estatus", fmt: (v) => chip(v, v === "OPEN" ? "ok" : "", v === "OPEN" ? "Inscripciones abiertas: la app la muestra en Convocatorias" : "Cerrada: la app la muestra solo en el historial") },
    ],
    fields: [
      { key: "eventName", label: "Evento", type: "text" },
      { key: "eventDate", label: "Fecha (texto mostrado)", type: "text", hint: "p. ej. 14–16 ago 2026" },
      { key: "location", label: "Lugar", type: "text" },
      {
        key: "circuitId", label: "Circuito", type: "refselect", load: circuitOptions, optional: true,
        hint: "opcional: liga la sede al catálogo (en la app el lugar navega al circuito)",
      },
      { key: "registrationCloseAt", label: "Cierre de inscripción", type: "datetime" },
      { key: "cupo", label: "Cupo", type: "number" },
      { key: "status", label: "Estatus", type: "select", options: ["OPEN", "CLOSED"] },
      { key: "externalApplyUrl", label: "URL de postulación", type: "text", optional: true },
      { key: "indicacionesMarkdown", label: "Indicaciones (Markdown)", type: "textarea", preview: "markdown" },
      { key: "participated", label: "Participé (histórico)", type: "checkbox" },
    ],
  }),
};

const secAgenda = {
  id: "agenda", label: "Agenda global", scope: "agenda",
  render: crudView({
    entity: "entrada", listPath: "/agenda", itemKey: "id", deletable: true,
    headerNote: "Entradas visibles para TODOS los oficiales (las personales viven en la app).",
    columns: [
      { key: "kind", label: "Tipo" },
      { key: "title", label: "Título" },
      { key: "at", label: "Cuándo", fmt: (v) => v ? new Date(v).toLocaleString("es-MX") : null },
      { key: "eventId", label: "Evento", fmt: (v) => (v ? eventNameCell(v) : null) },
      { key: "convocatoriaId", label: "Convocatoria", fmt: (v) => (v ? convocatoriaNameCell(v) : null) },
    ],
    fields: [
      { key: "kind", label: "Tipo", type: "select", options: ["EVENT", "CONVOCATORIA", "TRIP", "REMINDER"] },
      { key: "title", label: "Título", type: "text" },
      { key: "at", label: "Cuándo", type: "datetime", optional: true },
      { key: "allDay", label: "Todo el día", type: "checkbox" },
      { key: "location", label: "Lugar", type: "text", optional: true },
      { key: "eventId", label: "Evento ligado", type: "refselect", load: eventOptions, optional: true },
      {
        key: "convocatoriaId", label: "Convocatoria ligada", type: "refselect", load: convocatoriaOptions, optional: true,
        hint: "para entradas tipo CONVOCATORIA: en la app la entrada navega a su detalle",
      },
    ],
  }),
};

const secClaves = {
  id: "claves", label: "Claves y auditoría", scope: "keys",
  render: async function render(container) {
    const [keysRes, auditRes] = await Promise.all([api("GET", "/keys"), api("GET", "/audit?limit=50")]);
    container.innerHTML = "";
    if (!keysRes.ok) { container.append(errorBox(keysRes)); return; }

    const scopeChecks = SCOPES.concat("*").map((sc) => {
      const cb = h("input", { type: "checkbox" });
      return { scope: sc, cb, el: h("div", { class: "check-row" }, cb, h("label", {}, sc)) };
    });
    const nameInp = h("input", { type: "text" });
    async function createKey() {
      const scopes = scopeChecks.filter((x) => x.cb.checked).map((x) => x.scope);
      if (!nameInp.value.trim() || scopes.length === 0) { toast("nombre y al menos un scope", true); return; }
      const r = await api("POST", "/keys", { name: nameInp.value.trim(), scopes });
      if (!r.ok) { reportResult(r); return; }
      const back = h("div", { class: "modal-back" });
      back.append(h("div", { class: "modal" },
        h("h2", {}, `Clave '${r.data.name}' creada`),
        h("div", {}, "Guárdala ahora — no se volverá a mostrar:"),
        h("div", { class: "secret" }, r.data.key),
        h("div", { class: "form-actions" },
          h("button", { class: "btn btn-primary", onclick: () => navigator.clipboard.writeText(r.data.key).then(() => toast("copiada")) }, "Copiar"),
          h("button", { class: "btn", onclick: () => { back.remove(); render(container); } }, "Cerrar"),
        ),
      ));
      document.body.append(back);
    }

    const keysTable = h("div", { class: "panel grow" },
      h("h2", {}, "Claves de API (agentes y admins)"),
      h("div", { class: "table-scroll" }, h("table", {},
        h("thead", {}, h("tr", {}, ["Nombre", "Scopes", "Estado", "Último uso", ""].map((x) => h("th", {}, x)))),
        h("tbody", {}, keysRes.data.map((k) => h("tr", {},
          h("td", {}, k.name),
          h("td", { class: "mono" }, k.scopes.join(", ")),
          h("td", {}, chip(k.active ? "activa" : "revocada", k.active ? "ok" : "bad", k.active ? "La clave funciona: sus peticiones se aceptan" : "Clave revocada: sus peticiones se rechazan")),
          h("td", { class: "mono" }, k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString("es-MX") : "—"),
          h("td", {}, k.active ? h("button", {
            class: "btn-mini danger",
            onclick: async () => {
              if (!(await confirmModal(`¿Revocar la clave '${k.name}'?`))) return;
              if (reportResult(await api("DELETE", `/keys/${k.id}`))) render(container);
            },
          }, "revocar") : null),
        ))),
      )),
    );

    const createPanel = h("div", { class: "panel form-panel" },
      h("h2", {}, "Nueva clave"),
      h("label", {}, "Nombre (identifica al agente/uso) *"), nameInp,
      h("label", {}, "Scopes *"), scopeChecks.map((x) => x.el),
      h("div", { class: "form-actions" }, h("button", { class: "btn btn-primary", onclick: createKey }, "Crear")),
    );

    const auditRows = auditRes.ok ? auditRes.data : [];
    const auditTable = h("div", { class: "panel", style: "margin-top:18px" },
      h("h2", {}, "Auditoría (últimos 50 cambios)"),
      h("div", { class: "table-scroll" }, h("table", {},
        h("thead", {}, h("tr", {}, ["Cuándo", "Actor", "Acción", "Entidad", "ID", "Detalle"].map((x) => h("th", {}, x)))),
        h("tbody", {}, auditRows.map((a) => h("tr", {},
          h("td", { class: "mono" }, new Date(a.at).toLocaleString("es-MX")),
          h("td", {}, chip(a.actor, a.actor === "master" ? "" : "warn", a.actor === "master" ? "Cambio hecho con la clave maestra (env ADMIN_API_KEY)" : "Cambio hecho con la clave nombrada de un agente/admin")),
          h("td", { class: "mono" }, a.action),
          h("td", { class: "mono" }, a.entity),
          h("td", { class: "mono" }, a.entityId ?? "—"),
          h("td", { class: "muted" }, a.detail ?? "—"),
        ))),
      )),
    );

    // Botón de emergencia: solo ante una fuga (p. ej. se filtró JWT_SECRET).
    const emergencia = h("div", { class: "panel", style: "margin-top:18px" },
      h("h2", {}, "Emergencia: cerrar todas las sesiones"),
      h("div", { class: "muted" }, "Corta al momento las sesiones de TODAS las cuentas: nadie usa la app hasta volver a entrar con su correo. Úsalo solo ante una fuga."),
      h("div", { class: "form-actions" }, h("button", {
        class: "btn btn-danger",
        onclick: async () => {
          const prueba = await api("POST", "/sessions/revoke-all?dryRun=true");
          if (!prueba.ok) { reportResult(prueba); return; }
          if (!(await confirmModal(`¿Cerrar todas las sesiones? ${prueba.data.detail ?? ""}`))) return;
          if (reportResult(await api("POST", "/sessions/revoke-all"))) render(container);
        },
      }, "Cerrar todas las sesiones")),
    );

    container.append(h("div", { class: "layout" }, keysTable, createPanel), auditTable, emergencia);
  },
};

// ——— Seguridad: pausas por función, uso y congelados (scope keys) ———
// Para un abuso o un incidente sin redesplegar: pausar una función completa (la app recibe
// "pausado" y conserva lo que tenga en cola), ver quién hace más peticiones y quién choca
// con los límites, y descongelar a quien el sistema frenó solo.
const secSeguridad = {
  id: "seguridad", label: "Seguridad", scope: "keys",
  render: async function render(container) {
    const [sw, usage, frozen] = await Promise.all([
      api("GET", "/security/switches"), api("GET", "/security/usage?hours=24"), api("GET", "/security/frozen"),
    ]);
    container.innerHTML = "";
    for (const r of [sw, usage, frozen]) if (!r.ok) { container.append(errorBox(r)); return; }
    const who = (r) => r.name ? `${r.name} (${r.who})` : r.who;

    const switches = h("div", { class: "panel" },
      h("h2", {}, "Pausar funciones"),
      h("div", { class: "muted", style: "margin-bottom:10px" },
        "Apaga una función para TODOS mientras dura un abuso. La app recibe un aviso de pausa y reintenta más tarde (no pierde lo que tenga en cola)."),
      h("div", { class: "table-scroll" }, h("table", {},
        h("thead", {}, h("tr", {}, ["Función", "Estado", "Motivo", "Desde", ""].map((x) => h("th", {}, x)))),
        h("tbody", {}, sw.data.map((s) => h("tr", {},
          h("td", {}, s.description),
          h("td", {}, chip(s.paused ? "PAUSADA" : "activa", s.paused ? "bad" : "ok")),
          h("td", { class: "muted" }, s.reason ?? "—"),
          h("td", { class: "mono" }, s.since ? new Date(s.since).toLocaleString("es-MX") + ` · ${s.by}` : "—"),
          h("td", {}, h("button", {
            class: s.paused ? "btn-mini" : "btn-mini danger",
            onclick: async () => {
              let reason = null;
              if (!s.paused) {
                reason = window.prompt(`Motivo de la pausa (lo verá el oficial en la app):\n${s.description}`, "");
                if (reason === null) return;
              } else if (!(await confirmModal(`¿Reanudar "${s.description}"?`))) return;
              if (reportResult(await api("PUT", `/security/switches/${s.key}`, { paused: !s.paused, reason }))) render(container);
            },
          }, s.paused ? "reanudar" : "pausar")),
        ))),
      )),
    );

    const frozenPanel = h("div", { class: "panel", style: "margin-top:18px" },
      h("h2", {}, "Congelados"),
      h("div", { class: "muted", style: "margin-bottom:10px" },
        "Quien choca 60 veces con los límites en 10 minutos queda congelado 30 min (llega un correo de alerta)."),
      frozen.data.length === 0 ? h("div", { class: "muted" }, "Nadie congelado.")
        : h("table", {}, h("tbody", {}, frozen.data.map((f) => h("tr", {},
            h("td", {}, who(f)),
            h("td", { class: "mono" }, "hasta " + new Date(f.until).toLocaleTimeString("es-MX")),
            h("td", {}, h("button", {
              class: "btn-mini",
              onclick: async () => {
                if (!(await confirmModal(`¿Descongelar a ${who(f)}?`))) return;
                if (reportResult(await api("DELETE", `/security/frozen/${encodeURIComponent(f.who)}`))) render(container);
              },
            }, "descongelar")),
          )))),
    );

    const usagePanel = h("div", { class: "panel", style: "margin-top:18px" },
      h("h2", {}, "Uso (últimas 24 h)"),
      h("div", { class: "muted", style: "margin-bottom:10px" },
        "Quién hace más peticiones y quién choca con los límites (429). Solo en memoria: se reinicia con el backend."),
      usage.data.length === 0 ? h("div", { class: "muted" }, "Sin datos todavía.")
        : h("div", { class: "table-scroll" }, h("table", {},
            h("thead", {}, h("tr", {}, ["Quién", "Peticiones", "Rechazadas (429)", "Congelado"].map((x) => h("th", {}, x)))),
            h("tbody", {}, usage.data.map((u) => h("tr", {},
              h("td", {}, who(u)),
              h("td", { class: "mono" }, String(u.requests)),
              h("td", { class: "mono" }, u.rejected ? chip(String(u.rejected), "warn") : "0"),
              h("td", { class: "mono" }, u.frozenUntil ? new Date(u.frozenUntil).toLocaleTimeString("es-MX") : "—"),
            ))),
          )),
    );

    container.append(switches, frozenPanel, usagePanel);
  },
};

const secModeracion = {
  id: "moderacion", label: "Moderación", scope: "moderation",
  render: async function render(container) {
    const res = await api("GET", "/reports");
    container.innerHTML = "";
    if (!res.ok) { container.append(errorBox(res)); return; }
    const rows = res.data;
    // Qué se reporta: un mensaje, un chat (nombre/imagen/descripción) o un perfil (nombre/foto).
    const KIND = { message: "Mensaje", chat: "Chat", officer: "Perfil" };
    const about = (r) => (r.kind === "officer" ? `el perfil de ${r.targetName || "un oficial"}`
      : r.kind === "chat" ? `el chat ${r.targetName || r.chatName || ""}`
      : `el mensaje de ${r.messageSender || "sistema"}`);
    const content = (r) => (r.kind === "officer" ? "Nombre y foto del perfil"
      : r.kind === "chat" ? (r.messageText || "Nombre e imagen del chat")
      : (r.mediaType ? "📷 " : "") + (r.messageText || "—"));
    container.append(h("div", { class: "panel" },
      h("h2", {}, "Reportes"),
      h("div", { class: "muted", style: "margin-bottom:10px" },
        "Levantados por los oficiales desde la app: mensajes (mantener presionado), chats (nombre, imagen o descripción) y perfiles (nombre o foto). Descartar un reporte no toca lo reportado."),
      rows.length === 0
        ? h("div", { class: "muted" }, "Sin reportes pendientes.")
        : h("div", { class: "table-scroll" }, h("table", {},
            h("thead", {}, h("tr", {}, ["Cuándo", "Qué", "Chat / oficial", "Contenido", "Autor", "Reportó", "Motivo", ""].map((x) => h("th", {}, x)))),
            h("tbody", {}, rows.map((r) => h("tr", {},
              h("td", { class: "mono" }, new Date(r.createdAt).toLocaleString("es-MX")),
              h("td", { style: "white-space:nowrap" }, KIND[r.kind] ?? "Mensaje"),
              h("td", {}, r.kind === "officer" ? (r.targetName || "—") : (r.chatName || "—")),
              h("td", {}, content(r)),
              h("td", {}, r.kind === "officer" ? (r.targetName || "—") : (r.messageSender || "—")),
              h("td", {}, r.reporterName),
              h("td", { class: "muted" }, r.reason ?? "—"),
              h("td", {}, h("button", {
                class: "btn-mini danger",
                onclick: async () => {
                  if (!(await confirmModal(`¿Descartar el reporte sobre ${about(r)}?`))) return;
                  if (reportResult(await api("DELETE", `/reports/${r.id}`))) render(container);
                },
              }, "descartar")),
            ))),
          )),
    ));
  },
};

// ——— Puestos PROPUESTOS por los oficiales (registro por honor): cola de revisión ———
// Un grupo = mismo trazado + misma etiqueta normalizada ("7" = "MP 7"): que varios
// oficiales coincidan es la mejor verificación. Aprobar crea el puesto (lo ven todos),
// fusionar lo junta con uno existente, rechazar deja los registros sin puesto.
const PROPOSAL_STATUS = [
  ["PENDING", "Pendientes"], ["APPROVED", "Aprobados"], ["MERGED", "Fusionados"], ["REJECTED", "Rechazados"],
];
let proposalStatus = "PENDING";

/** Mini mapa del trazado: silueta, puestos existentes (gris) y las propuestas (ámbar). */
function proposalMiniMap(path, puestos, group) {
  const svg = s("svg", { viewBox: "0 0 100 100", width: "220", height: "220", class: "prop-map" });
  svg.append(s("rect", { x: 0, y: 0, width: 100, height: 100, rx: 4, fill: "var(--bg)", stroke: "var(--border)", "stroke-width": 0.5 }));
  if (path && path.length >= 3) {
    const d = path.map((p, i) => `${i ? "L" : "M"}${(p.x * 100).toFixed(2)},${(p.y * 100).toFixed(2)}`).join(" ") + " Z";
    svg.append(s("path", { d, fill: "none", stroke: "#3A4152", "stroke-width": 3.2, "stroke-linejoin": "round" }));
    svg.append(s("path", { d, fill: "none", stroke: "#20242E", "stroke-width": 1.8, "stroke-linejoin": "round" }));
  }
  for (const p of puestos.filter((x) => x.onMap !== false && x.point)) {
    svg.append(s("circle", { cx: p.point.x * 100, cy: p.point.y * 100, r: 1.3, fill: "var(--muted)" }));
  }
  for (const pr of group.proposals.filter((x) => x.point)) {
    svg.append(s("circle", { cx: pr.point.x * 100, cy: pr.point.y * 100, r: 1.8, fill: "var(--amber)", opacity: 0.8 }));
  }
  if (group.point) {
    svg.append(s("circle", { cx: group.point.x * 100, cy: group.point.y * 100, r: 4, fill: "none", stroke: "var(--amber)", "stroke-width": 0.8 }));
  }
  return svg;
}

const secPropuestas = {
  id: "propuestas", label: "Puestos propuestos", scope: "circuits",
  render: async function render(container) {
    const res = await api("GET", `/puesto-proposals?status=${proposalStatus}`);
    container.innerHTML = "";
    const tabs = h("div", { class: "ev-mdays", style: "margin:6px 0 4px" }, PROPOSAL_STATUS.map(([v, l]) =>
      h("button", { class: "ev-mdchip" + (proposalStatus === v ? " on" : ""), onclick: () => { proposalStatus = v; render(container); } }, l)));
    container.append(h("div", { class: "panel" },
      h("h2", {}, "Puestos propuestos por los oficiales"),
      h("div", { class: "muted", style: "margin-bottom:6px" },
        "Al registrarse por honor, si su puesto no aparecía en el trazado lo colocaron en el mapa con su número. Mientras está pendiente solo lo ve quien lo propuso. ",
        "Al resolverlo, sus registros pasan solos al puesto (o quedan sin puesto si se rechaza)."),
      h("div", { class: "muted mono", style: "font-size:11px" },
        "Si el trazado se carga desde data/circuitos/posiciones/, tras aprobar corre el cargador con --exportar-posiciones y haz commit."),
      tabs));
    if (!res.ok) { container.append(errorBox(res)); return; }
    const groups = res.data;
    if (!groups.length) {
      container.append(h("div", { class: "panel" }, h("div", { class: "ev-empty" },
        proposalStatus === "PENDING" ? "No hay puestos por revisar." : "Nada por aquí.")));
      return;
    }
    // Silueta y puestos de cada trazado involucrado (una lectura por trazado).
    const trazadoInfo = new Map();
    await Promise.all([...new Set(groups.map((g) => g.trazadoId))].map(async (tz) => {
      const g = groups.find((x) => x.trazadoId === tz);
      const [tzs, ps] = await Promise.all([
        api("GET", `/circuits/${g.circuitId}/trazados`), api("GET", `/trazados/${tz}/puestos`),
      ]);
      trazadoInfo.set(tz, {
        path: (tzs.ok ? tzs.data : []).find((t) => t.id === tz)?.path ?? [],
        puestos: ps.ok ? ps.data : [],
      });
    }));
    const when = (iso) => iso ? new Date(iso).toLocaleString("es-MX", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }) : "";
    for (const g of groups) {
      const info = trazadoInfo.get(g.trazadoId) ?? { path: [], puestos: [] };
      const ids = g.proposals.map((p) => p.id);
      const n = g.proposals.length;
      const rows = g.proposals.map((p) => h("div", { class: "ev-chk" },
        h("span", { class: "grow" },
          h("b", {}, p.officerName), ` · OMDAI ${p.omdaiId} · ${p.eventName}`,
          h("span", { class: "muted", style: "margin-left:6px" }, `"${p.label}" · ${p.point ? "marcado en el mapa" : "sin ubicación"} · ${when(p.createdAt)}`),
          p.reviewedBy ? h("div", { class: "muted mono", style: "font-size:11px" },
            `${p.status} por ${p.reviewedBy} · ${when(p.reviewedAt)}${p.reviewNote ? " · " + p.reviewNote : ""}`) : null)));
      const actions = [];
      if (proposalStatus === "PENDING") {
        const label = h("input", { value: g.label, style: "width:110px", title: "Etiqueta final del puesto (la que verán todos)" });
        const number = h("input", { type: "number", min: 1, placeholder: "número (opc.)", style: "width:120px" });
        actions.push(h("div", { class: "prop-act" },
          h("b", {}, "Aprobar como puesto nuevo"), label, number,
          h("button", {
            class: "btn btn-primary",
            onclick: async () => {
              const body = { ids, label: label.value.trim() };
              if (number.value) body.number = Number(number.value);
              if (!(await confirmModal(`¿Crear el puesto "${body.label}" en ${g.trazadoName} y publicarlo para todos?`))) return;
              if (reportResult(await api("POST", "/puesto-proposals/approve", body))) render(container);
            },
          }, "Aprobar")));
        const others = info.puestos.filter((p) => !g.nearby.some((x) => x.id === p.id))
          .map((p) => ({ v: p.id, l: p.label || `P ${p.number}` }));
        const merge = h("select", { style: "width:auto; min-width:200px; max-width:320px" },
          g.nearby.map((x) => h("option", { value: x.id }, `${x.label}${x.sameLabel ? " · misma etiqueta" : ""}${x.distanceM != null ? ` · ${x.distanceM} m` : ""}`)),
          others.length ? h("optgroup", { label: "Otros puestos del trazado" }, others.map((o) => h("option", { value: o.v }, o.l))) : null);
        actions.push(h("div", { class: "prop-act" },
          h("b", {}, "Ya existía: fusionar con"), merge,
          h("button", {
            class: "btn-mini", disabled: info.puestos.length ? null : "",
            onclick: async () => {
              if (!merge.value) return;
              if (!(await confirmModal(`¿Juntar ${n} propuesta(s) con el puesto "${merge.selectedOptions[0]?.textContent}"?`))) return;
              if (reportResult(await api("POST", "/puesto-proposals/merge", { ids, puestoId: merge.value }))) render(container);
            },
          }, "Fusionar")));
        const reason = h("input", { placeholder: "motivo (opcional)", style: "width:200px" });
        actions.push(h("div", { class: "prop-act" },
          h("b", {}, "No procede"), reason,
          h("button", {
            class: "btn-mini danger",
            onclick: async () => {
              if (!(await confirmModal(`¿Rechazar ${n} propuesta(s)? Sus registros se conservan, sin puesto.`))) return;
              const body = { ids };
              if (reason.value.trim()) body.reason = reason.value.trim();
              if (reportResult(await api("POST", "/puesto-proposals/reject", body))) render(container);
            },
          }, "Rechazar")));
      }
      container.append(h("div", { class: "panel prop-group" },
        proposalMiniMap(info.path, info.puestos, g),
        h("div", { class: "grow" },
          h("div", { class: "ev-ptit" },
            h("b", {}, `"${g.label}" · ${n} oficial${n === 1 ? "" : "es"}`),
            h("span", { class: "muted", style: "font-size:11px" }, `${g.circuitName} · ${g.trazadoName}`)),
          g.point ? null : h("div", { class: "muted", style: "font-size:12px; margin-bottom:6px" },
            "Nadie la marcó en el mapa (trazado sin dibujo o solo el número): al aprobarla queda sin lugar en el mapa; acomódala después en el editor del trazado."),
          rows, actions)));
    }
  },
};

const SECTIONS = [secCuentas, secOficiales, secEventos, secCircuitos, secPropuestas, secCampeonatos, secIngesta, secConvocatorias, secAgenda, secModeracion, secSeguridad, secClaves];

// ——— Shell ———
function can(scope) {
  return WHO && (WHO.scopes.includes("*") || WHO.scopes.includes(scope));
}

function renderShell() {
  const app = $("#app");
  app.innerHTML = "";
  const visible = SECTIONS.filter((sec) => can(sec.scope));
  const nav = h("div", { class: "sidebar" },
    h("div", { class: "brand" },
      h("div", { class: "brand-flag" }),
      h("div", {}, h("div", { class: "brand-name" }, "EL PUESTO"), h("div", { class: "brand-sub" }, "ADMIN")),
    ),
    visible.map((sec) => h("button", {
      class: "nav-btn" + (currentSection?.id === sec.id ? " active" : ""),
      onclick: () => setSection(sec),
    }, sec.label)),
    h("div", { class: "sidebar-foot" },
      h("div", {}, "clave: ", h("b", {}, WHO.name)),
      h("div", { style: "margin:4px 0" }, WHO.scopes.join(", ")),
      h("button", { class: "btn-mini", style: "margin-top:6px", onclick: logout }, "cerrar sesión"),
    ),
  );
  const main = h("div", { class: "main", id: "main" });
  app.append(nav, main);
  renderMain();
}

function logout() {
  sessionStorage.removeItem("adminKey");
  KEY = ""; WHO = null;
  renderLogin();
}

function renderLogin(errMsg = null) {
  const app = $("#app");
  app.innerHTML = "";
  const inp = h("input", { type: "password", placeholder: "X-Admin-Key" });
  const err = h("div", { class: "muted", style: errMsg ? "color:var(--red);margin-top:8px" : "display:none" }, errMsg ?? "");
  async function enter() {
    KEY = inp.value.trim();
    const r = await api("GET", "/whoami");
    if (!r.ok) { renderLogin(typeof r.data === "object" ? r.data.error : "clave inválida"); return; }
    WHO = r.data;
    sessionStorage.setItem("adminKey", KEY);
    if (location.hash.length > 2) restoreFromHash();
    else setSection(SECTIONS.find((sec) => can(sec.scope)) ?? secClaves);
  }
  inp.addEventListener("keydown", (e) => { if (e.key === "Enter") enter(); });
  app.append(h("div", { class: "login-wrap" }, h("div", { class: "login-card" },
    h("div", { class: "brand" },
      h("div", { class: "brand-flag" }),
      h("div", {}, h("div", { class: "brand-name" }, "EL PUESTO"), h("div", { class: "brand-sub" }, "ADMINISTRACIÓN")),
    ),
    h("label", {}, "Clave de administración"),
    inp, err,
    h("div", { class: "form-actions" }, h("button", { class: "btn btn-primary", style: "width:100%", onclick: enter }, "Entrar")),
  )));
  inp.focus();
}

// ——— Init ———
(async function init() {
  if (KEY) {
    const r = await api("GET", "/whoami");
    if (r.ok) {
      WHO = r.data;
      if (location.hash.length > 2) restoreFromHash();
      else setSection(SECTIONS.find((sec) => can(sec.scope)) ?? secClaves);
      return;
    }
  }
  renderLogin();
})();
