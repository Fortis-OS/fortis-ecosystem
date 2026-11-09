// Tiny DOM helpers. Text is always set with textContent — never innerHTML —
// so entry titles, notes etc. can't inject markup (docs/development §4).

type Attrs = {
  class?: string;
  text?: string;
  type?: string;
  value?: string;
  placeholder?: string;
  title?: string;
  for?: string;
  id?: string;
  name?: string;
  autocomplete?: string;
  disabled?: boolean;
  checked?: boolean;
  required?: boolean;
  min?: string;
  max?: string;
  rows?: number;
  onclick?: (e: MouseEvent) => void;
  oninput?: (e: Event) => void;
  onchange?: (e: Event) => void;
  onsubmit?: (e: SubmitEvent) => void;
};

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: (Node | string | null | false)[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null) continue;
    if (k === "class") el.className = v as string;
    else if (k === "text") el.textContent = v as string;
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v as EventListener);
    else if (typeof v === "boolean") (el as unknown as Record<string, boolean>)[k] = v;
    else if (k === "for") el.setAttribute("for", String(v));
    else (el as unknown as Record<string, unknown>)[k] = v;
  }
  for (const c of children) {
    if (c === null || c === false) continue;
    el.append(typeof c === "string" ? document.createTextNode(c) : c);
  }
  return el;
}

// Bundled icons: static path data, built with createElementNS.
const PATHS: Record<string, string> = {
  shield: "M12 2 4 5v6c0 5 3.4 9.7 8 11 4.6-1.3 8-6 8-11V5l-8-3z",
  key: "M7 14a3 3 0 1 1 0-6 3 3 0 0 1 0 6zm5.6-4H22v4h-2v2h-3v-2h-4.4A5 5 0 1 1 12.6 10z",
  card: "M3 6h18v12H3zM3 9h18",
  note: "M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h7",
  id: "M4 5h16v14H4zM8 10a2 2 0 1 0 4 0 2 2 0 0 0-4 0M7 16c.5-2 2-3 3-3s2.5 1 3 3M14 9h4M14 13h4",
  clock: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 4v5l3 3",
  wand: "M4 20 15 9M14 4l1 2 2 1-2 1-1 2-1-2-2-1 2-1zM19 10l.5 1 1 .5-1 .5-.5 1-.5-1-1-.5 1-.5z",
  lock: "M6 11h12v10H6zM8 11V8a4 4 0 0 1 8 0v3",
  search: "M10 4a6 6 0 1 0 0 12 6 6 0 0 0 0-12zm9 15-4.5-4.5",
  plus: "M12 5v14M5 12h14",
  copy: "M9 9h11v11H9zM5 15H4V4h11v1",
  eye: "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zm10-3a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
  edit: "M4 20h4L19 9l-4-4L4 16zM14 6l4 4",
  trash: "M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13",
  close: "M6 6l12 12M18 6 6 18",
  gear: "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19 12l2-1-1-3-2 .5-1.5-1.5L17 5l-3-1-1 2h-2l-1-2-3 1 .5 2L6 8.5 4 8l-1 3 2 1v0l-2 1 1 3 2-.5L7.5 17 7 19l3 1 1-2h2l1 2 3-1-.5-2 1.5-1.5 2 .5 1-3z",
  layers: "M12 3 2 8l10 5 10-5-10-5zM2 13l10 5 10-5M2 17l10 5 10-5",
  link: "M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1",
};

export function icon(name: keyof typeof PATHS | string): SVGSVGElement {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  const p = document.createElementNS(ns, "path");
  p.setAttribute("d", PATHS[name] ?? PATHS.note);
  svg.append(p);
  return svg;
}

export function clear(el: Element) {
  while (el.firstChild) el.removeChild(el.firstChild);
}

let toastTimer: number | undefined;
export function toast(msg: string, kind: "ok" | "error" = "ok") {
  let t = document.getElementById("toast");
  if (!t) {
    t = h("div", { id: "toast" });
    document.body.append(t);
  }
  t.textContent = msg;
  t.className = `toast show ${kind}`;
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => t!.classList.remove("show"), 2600);
}
