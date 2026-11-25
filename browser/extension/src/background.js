// Fortis browser extension — background (docs/security §7.7).
// Talks to fortis-browser-host (native messaging). Never stores secrets on
// disk; pending saves live in storage.session (memory) for at most 5 minutes.
// Chromium service worker: load Fortis Lock (Firefox lists it in manifest "scripts").
if (typeof importScripts === "function") importScripts("lock/background.js");
const api = globalThis.browser ?? globalThis.chrome;
const HOST = "fortis_browser_host";
const PENDING_TTL_MS = 5 * 60 * 1000;
let seq = 0;

async function host(cmd, args = {}) {
  const r = await api.runtime.sendNativeMessage(HOST, { id: ++seq, cmd, args });
  if (!r) throw { code: "unavailable", message: "No reply from fortis-browser-host" };
  if (!r.ok) throw r.error ?? { code: "error", message: "Unknown error" };
  return r.data;
}

function fillable(url) {
  try {
    const u = new URL(url);
    return u.protocol === "https:" || (u.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname));
  } catch { return false; }
}

// ---- pending saves (memory only) -------------------------------------------
const session = api.storage.session ?? null;
const memPending = {};
async function getPending(tabId) {
  const k = "p" + tabId;
  const p = session ? (await session.get(k))[k] : memPending[k];
  if (p && Date.now() - p.at > PENDING_TTL_MS) { await clearPending(tabId); return null; }
  return p ?? null;
}
async function setPending(tabId, p) {
  const k = "p" + tabId;
  if (session) await session.set({ [k]: p }); else memPending[k] = p;
  badge(tabId, p.status === "changed" ? "!" : "+");
}
async function clearPending(tabId) {
  const k = "p" + tabId;
  if (session) await session.remove(k); else delete memPending[k];
  badge(tabId, "");
}
function badge(tabId, text) {
  const a = api.action ?? api.browserAction;
  a.setBadgeText({ tabId, text }).catch?.(() => {});
  if (text) a.setBadgeBackgroundColor?.({ tabId, color: "#d4af37" });
}

async function neverList() { return (await api.storage.local.get("never")).never ?? []; }
function hostOf(url) { try { return new URL(url).hostname; } catch { return ""; } }

// ---- fill -------------------------------------------------------------------
async function fillTab(tab, entryId) {
  if (!fillable(tab.url)) throw { code: "forbidden", message: "Fortis fills only https pages." };
  const cred = await host("fill", { id: entryId, url: tab.url });
  // Top frame only, and only if the page is still on the same origin.
  try {
    return await api.tabs.sendMessage(tab.id, { op: "fill", origin: new URL(tab.url).origin, ...cred }, { frameId: 0 });
  } catch {
    throw { code: "unavailable", message: "Reload this page once (it was open before Fortis was installed), then try again." };
  }
}

async function activeTab() {
  const [t] = await api.tabs.query({ active: true, currentWindow: true });
  return t;
}

// Keyboard shortcut: fill if exactly one login matches, else open the popup.
api.commands?.onCommand.addListener(async (command) => {
  if (command !== "fill-login") return;
  const tab = await activeTab();
  if (!tab || !fillable(tab.url)) return;
  try {
    const m = await host("match", { url: tab.url });
    if (m.entries.length === 1) await fillTab(tab, m.entries[0].id);
    else (api.action ?? api.browserAction).openPopup?.();
  } catch { (api.action ?? api.browserAction).openPopup?.(); }
});

// ---- messages from popup and content script ----------------------------------

/** A content script (top frame only) reports a submitted login. */
async function onSubmitted(msg, sender) {
  if (msg.op !== "submitted" || sender.frameId !== 0 || !sender.tab) return {};
  const url = sender.url ?? sender.tab.url;
  if (!fillable(url) || (await neverList()).includes(hostOf(url))) return {};
  if (typeof msg.password !== "string" || !msg.password || msg.password.length > 4096) return {};
  const username = typeof msg.username === "string" ? msg.username.slice(0, 512) : "";
  let status, id;
  try { ({ status, id } = await host("save_check", { url, username, password: msg.password })); }
  catch (e) { if (e.code !== "vault_locked") return {}; status = "locked"; }
  if (status === "same") return {};
  await setPending(sender.tab.id, { url, username, password: msg.password, status, id, at: Date.now() });
  return {};
}

/** Popup requests, always about the active tab. */
async function onPopup(msg, tab) {
  switch (msg.op) {
    case "status": return host("status");
    case "unlock": return host("unlock", { password: msg.password });
    case "lock": return host("lock");
    case "match":
      if (!tab || !fillable(tab.url)) return { host: hostOf(tab?.url ?? ""), entries: [], notFillable: true };
      return host("match", { url: tab.url });
    case "fill": return fillTab(tab, msg.id);
    case "totp": return host("totp", { id: msg.id, url: tab.url });
    case "pending": {
      const p = tab && await getPending(tab.id);
      return p ? { url: p.url, host: hostOf(p.url), username: p.username, status: p.status } : null;
    }
    case "save": {
      const p = tab && await getPending(tab.id);
      if (!p) return { action: "none" };
      let status = p.status, id = p.id;
      if (status === "locked") ({ status, id } = await host("save_check", { url: p.url, username: p.username, password: p.password }));
      const r = status === "same" ? { action: "same" }
        : await host("save", { url: p.url, username: p.username, password: p.password, ...(status === "changed" ? { id } : {}) });
      await clearPending(tab.id);
      return r;
    }
    case "dismiss": if (tab) await clearPending(tab.id); return {};
    case "never": {
      const p = tab && await getPending(tab.id);
      if (p) {
        const list = await neverList();
        const h = hostOf(p.url);
        if (h && !list.includes(h)) await api.storage.local.set({ never: [...list, h] });
        await clearPending(tab.id);
      }
      return {};
    }
    default: throw { code: "bad_request", message: "unknown op" };
  }
}

api.runtime.onMessage.addListener((msg, sender, reply) => {
  if (typeof msg?.op === "string" && msg.op.startsWith("lock_")) return; // lock/background.js
  // Only our own pages and content scripts can message us (no externally_connectable).
  const work = sender.tab ? onSubmitted(msg, sender) : activeTab().then((t) => onPopup(msg, t));
  work.then((data) => reply({ ok: true, data }),
    (e) => reply({ ok: false, error: e?.code ? e : { code: "error", message: String(e?.message ?? e) } }));
  return true; // async reply
});

// For tests/e2e only (no effect in normal use).
globalThis.__fortis = { onPopup, getPending };

api.tabs.onRemoved.addListener((tabId) => clearPending(tabId));
