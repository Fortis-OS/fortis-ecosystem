// Fortis browser extension — content script (top frame only; docs/security §7.7).
// Fills only on request from the background (user action), only visible
// fields, only if the page origin is still the one that was matched.
(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  if (window.top !== window) return;

  function visible(el) {
    if (!el || el.disabled || el.readOnly) return false;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const s = getComputedStyle(el);
    return s.visibility !== "hidden" && s.display !== "none" && parseFloat(s.opacity) > 0.1;
  }

  const TEXTISH = new Set(["text", "email", "tel", ""]);
  function inputs() { return [...document.querySelectorAll("input")].filter(visible); }

  function findFields(root = document) {
    const all = inputs();
    const pw = all.find((i) => i.type === "password");
    let user =
      all.find((i) => /\b(username|email)\b/.test(i.autocomplete || "") && i.type !== "password") ||
      (pw ? all.slice(0, all.indexOf(pw)).reverse().find((i) => TEXTISH.has(i.type)) : null) ||
      all.find((i) => i.type === "email");
    return { user: user || null, pw: pw || null };
  }

  function setValue(el, v) {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
    el.focus();
    setter.call(el, v);
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  api.runtime.onMessage.addListener((msg, sender, reply) => {
    if (msg?.op !== "fill") return;
    if (location.origin !== msg.origin) { reply({ filled: false, reason: "page changed" }); return; }
    const { user, pw } = findFields();
    if (!user && !pw) { reply({ filled: false, reason: "no login fields on this page" }); return; }
    if (user && msg.username) setValue(user, msg.username);
    if (pw) setValue(pw, msg.password);
    reply({ filled: true, username: !!user, password: !!pw });
  });

  // ---- offer to save after a sign-in ------------------------------------------
  let last = 0;
  function report(form) {
    const scope = form || document;
    const pw = [...scope.querySelectorAll("input[type=password]")].find((i) => i.value);
    if (!pw || Date.now() - last < 3000) return;
    last = Date.now();
    const { user } = findFields();
    api.runtime.sendMessage({ op: "submitted", username: user?.value ?? "", password: pw.value }).catch?.(() => {});
  }
  document.addEventListener("submit", (e) => report(e.target), true);
  document.addEventListener("click", (e) => {
    const b = e.target?.closest?.("button, input[type=submit], [role=button]");
    if (b && (b.form || b.closest("form") || document.querySelector("input[type=password]"))) report(b.form || b.closest("form"));
  }, true);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && e.target?.type === "password") report(e.target.form);
  }, true);
})();
