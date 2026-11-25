// Fortis Lock — the error page (docs/security §7.8).
//
// Runs at document_start on a locked site, top frame only, after lock/pages/<browser>.js
// has set globalThis.__fortisLockPage. Stops the real page before any of its scripts run
// and draws the browser's own certificate error in its place. Triple-clicking the warning
// icon (Chrome/Brave: the red triangle, Firefox: the fox) opens the unlock form.
(() => {
  if (window.top !== window || globalThis.__fortisLockShown) return;
  const P = globalThis.__fortisLockPage;
  if (!P) return;
  globalThis.__fortisLockShown = true;
  const api = globalThis.browser ?? globalThis.chrome;
  try { window.stop(); } catch {}

  const host = location.hostname;
  const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const date = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", year: "numeric" });
  const today = new Date();
  const fill = (html) => html
    .replaceAll("{{HOST}}", esc(host))
    .replaceAll("{{TODAY}}", esc(date.format(today)))
    .replaceAll("{{EXPIRES}}", esc(date.format(new Date(today.getTime() + 89 * 864e5))));

  // Constructed stylesheets aren't subject to the site's Content-Security-Policy (style-src),
  // unlike <style> elements; fall back to <style> if adopting isn't possible.
  const sheets = new Map();
  function sheet(css) {
    if (!sheets.has(css)) {
      const s = new CSSStyleSheet();
      s.replaceSync(css);
      sheets.set(css, s);
    }
    return sheets.get(css);
  }
  function adopt(scope, cssList, where) {
    try {
      scope.adoptedStyleSheets = cssList.map(sheet);
    } catch {
      for (const css of cssList) {
        const st = document.createElement("style");
        st.textContent = css;
        where.prepend(st);
      }
    }
  }
  function adoptAll(scope) {
    const holders = [...scope.querySelectorAll("style[data-fortis-css]")];
    const list = holders.map((h) => P.css[h.dataset.fortisCss]).filter(Boolean);
    holders.forEach((h) => h.remove());
    if (scope === document && P.kind === "chromium") list.unshift(P.css.doc);
    adopt(scope, list, scope === document ? document.head : scope);
    for (const el of scope.querySelectorAll("*")) if (el.shadowRoot) adoptAll(el.shadowRoot);
  }

  // ---- images the site's CSP blocks --------------------------------------------------------
  // The page's images are data: URIs, which a strict `img-src` blocks. When the browser
  // reports that, draw them without loading anything: PNGs on a canvas (decoded from bytes
  // we already have), SVGs as inline <svg>. Pages on sites that allow data: stay untouched.
  const decode = (uri) => {
    const [head, body] = uri.split(",", 2);
    if (!/;base64/.test(head)) return { svg: /svg/.test(head), text: decodeURIComponent(body) };
    const bin = atob(body);
    const bytes = Uint8Array.from(bin, (ch) => ch.charCodeAt(0));
    return { svg: /svg/.test(head), bytes, text: /svg/.test(head) ? new TextDecoder().decode(bytes) : "" };
  };
  const inlineSvg = (text) => {
    const el = new DOMParser().parseFromString(text, "image/svg+xml").documentElement;
    if (el.localName !== "svg") return null;
    for (const s of el.querySelectorAll("script, foreignObject")) s.remove();
    return document.importNode(el, true);
  };
  function overlay(el, uri) {
    const { svg, bytes, text } = decode(uri);
    if (getComputedStyle(el).position === "static") el.style.position = "relative";
    const place = (child) => {
      Object.assign(child.style, { position: "absolute", inset: "0", width: "100%", height: "100%", pointerEvents: "none" });
      el.style.backgroundImage = "none";
      el.append(child);
    };
    if (svg) {
      const node = inlineSvg(text);
      if (node) { node.setAttribute("preserveAspectRatio", "xMinYMin meet"); place(node); }
      return;
    }
    createImageBitmap(new Blob([bytes], { type: "image/png" })).then((bmp) => {
      const r = el.getBoundingClientRect();
      const dpr = devicePixelRatio || 1;
      const cv = document.createElement("canvas");
      cv.width = Math.round(r.width * dpr);
      cv.height = Math.round(r.height * dpr);
      const k = Math.min(cv.width / bmp.width, cv.height / bmp.height);
      cv.getContext("2d").drawImage(bmp, 0, 0, bmp.width * k, bmp.height * k);
      place(cv);
    }, () => {});
  }
  function rescue(scope) {
    for (const el of scope.querySelectorAll("*")) {
      if (el.shadowRoot) rescue(el.shadowRoot);
      if (el.dataset.fortisPainted) continue;
      if (el.localName === "img" && el.getAttribute("src")?.startsWith("data:image/svg")) {
        const cs = getComputedStyle(el);
        const node = inlineSvg(decode(el.getAttribute("src")).text);
        if (!node) continue;
        node.setAttribute("class", el.getAttribute("class") ?? "");
        // The page's CSS targets `img`; carry over what it gave this one.
        Object.assign(node.style, { display: cs.display === "inline" ? "inline-block" : cs.display,
          width: cs.width, height: "auto", margin: cs.margin, verticalAlign: cs.verticalAlign });
        node.dataset.fortisPainted = "1";
        el.replaceWith(node);
        continue;
      }
      const bg = getComputedStyle(el).backgroundImage;
      const uris = [...bg.matchAll(/url\("?(data:image\/(?:png|svg\+xml)[^")]*)"?\)/g)].map((m) => m[1]);
      if (!uris.length || !el.getBoundingClientRect().width) continue;
      el.dataset.fortisPainted = "1";
      overlay(el, uris[uris.length - 1]); // image-set: the 2x one
    }
  }
  let rescueQueued = false;
  document.addEventListener("securitypolicyviolation", (e) => {
    if (!/^(img|default)-src/.test(e.effectiveDirective ?? e.violatedDirective ?? "") || rescueQueued) return;
    rescueQueued = true;
    setTimeout(() => { rescueQueued = false; rescue(document); }, 50);
  }, true);

  function render(html) {
    let root = document.documentElement;
    if (!root) {
      root = document.createElement("html");
      document.appendChild(root);
    }
    for (const a of [...root.attributes]) root.removeAttribute(a.name);
    for (const [k, v] of Object.entries(P.htmlAttrs ?? {})) root.setAttribute(k, v);
    if (root.setHTMLUnsafe) root.setHTMLUnsafe(fill(html));
    else root.innerHTML = fill(html);
    adoptAll(document);
    document.title = P.title;
    const icon = document.createElement("link");
    icon.rel = "icon";
    icon.href = P.favicon ?? "data:,";
    document.head.append(icon);
    wire();
  }

  // ---- behaving like the real page -------------------------------------------------------------

  let advanced = false;

  function back() {
    if (history.length > 1) history.back();
    else api.runtime.sendMessage({ op: "lock_back" }).catch?.(() => {});
  }

  function onTriple(el) {
    el?.addEventListener("click", (e) => { if (e.detail === 3) showUnlock(); });
  }

  function wire() {
    if (P.kind === "chromium") {
      const $ = (id) => document.getElementById(id);
      const details = $("details");
      const btn = $("details-button");
      btn?.addEventListener("click", () => {
        const open = details.classList.toggle("hidden") === false;
        btn.textContent = open ? P.closeDetails : P.openDetails;
        btn.setAttribute("aria-expanded", String(open));
      });
      $("primary-button")?.addEventListener("click", (e) => {
        if (/reload/i.test(e.currentTarget.textContent)) location.reload();
        else back();
      });
      const code = $("error-code");
      code?.addEventListener("click", () => {
        const open = $("error-debugging-info").classList.toggle("hidden") === false;
        code.setAttribute("aria-expanded", String(open));
      });
      $("learn-more-link")?.addEventListener("click", (e) => {
        e.preventDefault();
        location.href = "https://support.google.com/chrome/answer/6098869";
      });
      $("enhanced-protection-link")?.addEventListener("click", (e) => {
        e.preventDefault();
        api.runtime.sendMessage({ op: "lock_open_settings" }).catch?.(() => {});
      });
      for (const a of document.querySelectorAll('a[href="#"]')) a.addEventListener("click", (e) => e.preventDefault());
      onTriple($("icon"));
    } else {
      const card = document.querySelector("net-error-card")?.shadowRoot;
      if (!card) return;
      card.getElementById("advanced-button")?.addEventListener("click", () => {
        advanced = !advanced;
        render(advanced ? P.advanced : P.main);
      });
      card.getElementById("returnButton")?.addEventListener("click", back);
      onTriple(card.querySelector(".img-container"));
    }
  }

  // ---- hidden unlock form ------------------------------------------------------------------

  const FORM_CSS = `
    :host { all: initial; }
    .scrim { position: fixed; inset: 0; z-index: 2147483647; display: grid; place-items: center;
      background: rgba(0,0,0,.32); font: 14px/1.4 system-ui, sans-serif; color-scheme: light dark; }
    form { width: min(320px, calc(100vw - 32px)); padding: 20px; border-radius: 12px;
      background: Canvas; color: CanvasText; box-shadow: 0 8px 32px rgba(0,0,0,.28); }
    h2 { margin: 0 0 12px; font-size: 16px; font-weight: 600; }
    input { box-sizing: border-box; width: 100%; padding: 8px 10px; font: inherit; border-radius: 8px;
      border: 1px solid color-mix(in srgb, CanvasText 30%, transparent); background: Field; color: FieldText; }
    .err { min-height: 18px; margin: 6px 0; font-size: 13px; color: #d93025; }
    .row { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
    button { font: inherit; padding: 7px 14px; border-radius: 8px; cursor: pointer;
      border: 1px solid color-mix(in srgb, CanvasText 25%, transparent); background: Canvas; color: CanvasText; }
    button.primary { background: #1a73e8; border-color: #1a73e8; color: #fff; }
    button:disabled { opacity: .6; cursor: default; }`;
  let formHost = null;
  let formInput = null;

  function showUnlock() {
    if (formHost?.isConnected) return formInput?.focus();
    formHost = document.createElement("div");
    const root = formHost.attachShadow({ mode: "closed" });
    root.innerHTML = `<div class="scrim"><form autocomplete="off">
      <h2>Enter password</h2>
      <input type="password" aria-label="Password" maxlength="1024">
      <div class="err" role="alert"></div>
      <div class="row"><button type="button" data-a="phone">Use phone</button>
      <button type="button" data-a="cancel">Cancel</button><button class="primary">Unlock</button></div>
    </form></div>`;
    adopt(root, [FORM_CSS], root);
    const q = (s) => root.querySelector(s);
    const input = (formInput = q("input"));
    const err = q(".err");
    const buttons = [...root.querySelectorAll("button")];
    const busy = (on) => buttons.forEach((b) => { b.disabled = on; });
    const close = () => { formHost?.remove(); formHost = null; };
    const message = (e) => ({
      invalid_password: "Wrong password.",
      rate_limited: "Too many tries. Wait a moment.",
      conflict: "No password is set. Use your phone.",
      unavailable: e.message === "timeout" ? "No answer from the phone." : e.message === "no phone online" ? "Phone isn't connected." : "Fortis isn't running.",
      forbidden: "Denied on the phone.",
    })[e?.code] ?? (e?.message || "Couldn't unlock.");
    async function send(msg) {
      const r = await api.runtime.sendMessage(msg);
      if (!r?.ok) throw r?.error ?? {};
      return r.data;
    }
    q("form").addEventListener("submit", async (e) => {
      e.preventDefault();
      const password = input.value;
      if (!password) return;
      busy(true);
      err.textContent = "";
      try { await send({ op: "lock_unlock", password }); err.textContent = ""; }
      catch (x) { err.textContent = message(x); input.select(); }
      finally { busy(false); }
    });
    q('[data-a="phone"]').addEventListener("click", async () => {
      busy(true);
      err.textContent = "Confirm on your phone…";
      try { await send({ op: "lock_unlock_phone" }); }
      catch (x) { err.textContent = message(x); }
      finally { busy(false); }
    });
    q('[data-a="cancel"]').addEventListener("click", close);
    root.addEventListener("keydown", (e) => { if (e.key === "Escape") close(); });
    q(".scrim").addEventListener("click", (e) => { if (e.target === e.currentTarget) close(); });
    document.body.append(formHost);
    input.focus();
  }

  render(P.main);
})();
