// Fortis Lock — background (docs/security §7.8).
//
// The agent owns the lock; this follows it over a long-lived native port ("watch") and
// applies it: a document_start content script that draws the browser's own certificate
// error on the locked sites, and a network rule that blocks everything else they load.
// The last known state is cached so the lock stays on if the agent is unreachable.
(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  const HOST = "fortis_browser_host";
  const SCRIPT_ID = "fortis-lock";
  const RULE_ID = 7001;
  const ALARM = "fortis-lock-reconnect";
  const isFirefox = api.runtime.getURL("").startsWith("moz-extension:");
  const flavor = isFirefox ? "firefox"
    : (navigator.userAgentData?.brands ?? []).some((b) => /brave/i.test(b.brand)) ? "brave" : "chrome";
  // Everything except the page itself, which the content script replaces.
  const BLOCKED_TYPES = ["sub_frame", "stylesheet", "script", "image", "font", "object", "xmlhttprequest",
    "ping", "csp_report", "media", "websocket", "other"];

  let state = { locked: false, sites: [], has_password: false };
  let pendingLock = false; // locked here while the agent was unreachable; tell it when it's back
  let agentUp = false;
  let port = null;
  let firefoxScript = null; // contentScripts.register handle (Firefox MV2 fallback)
  let seq = 1e6;

  async function host(cmd, args = {}) {
    const r = await api.runtime.sendNativeMessage(HOST, { id: ++seq, cmd, args });
    if (!r) throw { code: "unavailable", message: "No reply from fortis-browser-host" };
    if (!r.ok) throw r.error ?? { code: "error", message: "Unknown error" };
    return r.data;
  }

  const patterns = (sites) => sites.flatMap((s) => [`https://${s}/*`, `https://*.${s}/*`]);
  const same = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

  // ---- applying the lock --------------------------------------------------------------------

  async function setScript(locked, sites) {
    const js = [`lock/pages/${flavor}.js`, "lock/page.js"];
    const on = locked && sites.length > 0;
    if (api.scripting?.registerContentScripts) {
      try { await api.scripting.unregisterContentScripts({ ids: [SCRIPT_ID] }); } catch {}
      if (on) {
        await api.scripting.registerContentScripts([{
          id: SCRIPT_ID, matches: patterns(sites), js, runAt: "document_start", allFrames: false,
        }]);
      }
    } else {
      try { await firefoxScript?.unregister(); } catch {}
      firefoxScript = on ? await api.contentScripts.register({
        matches: patterns(sites), js: js.map((file) => ({ file })), runAt: "document_start", allFrames: false,
      }) : null;
    }
  }

  async function setRule(locked, sites) {
    const dnr = api.declarativeNetRequest;
    if (!dnr) return;
    const addRules = locked && sites.length ? [{
      id: RULE_ID, priority: 1, action: { type: "block" },
      condition: { requestDomains: sites, resourceTypes: BLOCKED_TYPES },
    }] : [];
    await dnr.updateDynamicRules({ removeRuleIds: [RULE_ID], addRules });
  }

  async function reloadTabs(sites) {
    if (!sites.length) return;
    const tabs = await api.tabs.query({ url: patterns(sites) }).catch(() => []);
    for (const t of tabs) api.tabs.reload(t.id).catch(() => {});
  }

  /** Forget service workers of open locked tabs so a cached page shell can't run offline. */
  async function dropServiceWorkers(sites) {
    if (!api.browsingData?.remove) return;
    const tabs = await api.tabs.query({ url: patterns(sites) }).catch(() => []);
    const origins = [...new Set([...tabs.map((t) => new URL(t.url).origin), ...sites.map((s) => `https://${s}`)])];
    const opts = isFirefox ? { hostnames: origins.map((o) => new URL(o).hostname) } : { origins };
    await api.browsingData.remove(opts, { serviceWorkers: true }).catch(() => {});
  }

  /** Make `next` the state: store it, then change the browser only if something changed. */
  async function setState(next, { force = false } = {}) {
    const prev = state;
    state = { locked: !!next.locked, sites: [...(next.sites ?? [])], has_password: !!next.has_password };
    await api.storage.local.set({ lockState: state, lockPending: pendingLock });
    const changed = prev.locked !== state.locked || !same(prev.sites, state.sites);
    if (!changed && !force) return;
    if (state.locked) await dropServiceWorkers(state.sites);
    await setScript(state.locked, state.sites);
    await setRule(state.locked, state.sites);
    // Reload the tabs that must switch between the real page and the error page.
    if (force) await reloadTabs(state.sites);
    else if (prev.locked !== state.locked) await reloadTabs([...new Set([...prev.sites, ...state.sites])]);
    else if (state.locked) {
      await reloadTabs([...state.sites.filter((s) => !prev.sites.includes(s)),
        ...prev.sites.filter((s) => !state.sites.includes(s))]);
    }
  }

  async function lockNow() {
    pendingLock = true;
    await setState({ ...state, locked: true });
    try {
      const s = await host("lock_on");
      pendingLock = false;
      await setState(s);
    } catch { /* stays locked here; sent again when the agent is back */ }
  }

  // ---- following the agent --------------------------------------------------------------------

  async function onWatch(m) {
    if (!m?.ok) return; // agent not running: the host exits and we reconnect later
    agentUp = true;
    if (pendingLock && !m.data.locked) {
      try { await host("lock_on"); pendingLock = false; } catch {}
      return; // the agent's change comes back on this port
    }
    pendingLock = false;
    await setState(m.data);
  }

  function connect() {
    if (port) return;
    try { port = api.runtime.connectNative(HOST); } catch { port = null; return; }
    port.onMessage.addListener((m) => { onWatch(m); });
    port.onDisconnect.addListener(() => { port = null; agentUp = false; });
    port.postMessage({ cmd: "watch" });
  }

  const ready = (async () => {
    const s = await api.storage.local.get(["lockState", "lockPending"]);
    pendingLock = !!s.lockPending;
    if (s.lockState) {
      // Re-apply from the cache before anything else: fail closed.
      state = { locked: false, sites: [], has_password: false };
      await setState(s.lockState, { force: !!s.lockState.locked });
    }
    connect();
  })();

  api.alarms?.create(ALARM, { periodInMinutes: 0.5 });
  api.alarms?.onAlarm.addListener((a) => { if (a.name === ALARM) ready.then(connect); });
  api.runtime.onStartup?.addListener(() => { ready.then(connect); });

  api.commands?.onCommand.addListener((command) => {
    if (command === "lock-sites") ready.then(lockNow);
  });

  // ---- messages ---------------------------------------------------------------------------------

  const extensionPage = (sender) => (sender.url ?? "").startsWith(api.runtime.getURL(""));
  const PAGE_OPS = new Set(["lock_unlock", "lock_unlock_phone", "lock_back", "lock_open_settings"]);

  async function onMessage(msg, sender) {
    await ready;
    const fromPage = !!sender.tab && !extensionPage(sender);
    if (fromPage && (!PAGE_OPS.has(msg.op) || sender.frameId !== 0 || !state.locked)) {
      throw { code: "forbidden", message: "not allowed" };
    }
    switch (msg.op) {
      case "lock_state": return { ...state, agent: agentUp, pending: pendingLock, flavor };
      case "lock_on": await lockNow(); return state;
      case "lock_unlock": {
        if (typeof msg.password !== "string" || !msg.password || msg.password.length > 1024) throw { code: "bad_request" };
        return setState(await host("lock_unlock", { password: msg.password })).then(() => state);
      }
      case "lock_unlock_phone": return setState(await host("lock_unlock_phone")).then(() => state);
      case "lock_sites": return setState(await host("lock_sites", { sites: msg.sites })).then(() => state);
      case "lock_password": {
        const args = { password: msg.password };
        if (msg.old_password) args.old_password = msg.old_password;
        return setState(await host("lock_password", args)).then(() => state);
      }
      case "lock_back": {
        // "Back to safety" / "Go back" on a tab with nothing to go back to: a new tab page.
        const url = isFirefox ? "about:home" : "chrome://newtab/";
        await api.tabs.update(sender.tab.id, { url }).catch(() => api.tabs.update(sender.tab.id, { url: "about:blank" }));
        return {};
      }
      case "lock_open_settings": {
        if (!isFirefox) await api.tabs.update(sender.tab.id, { url: "chrome://settings/security" }).catch(() => {});
        return {};
      }
      default: throw { code: "bad_request", message: "unknown op" };
    }
  }

  api.runtime.onMessage.addListener((msg, sender, reply) => {
    if (typeof msg?.op !== "string" || !msg.op.startsWith("lock_")) return; // background.js handles it
    onMessage(msg, sender).then((data) => reply({ ok: true, data }),
      (e) => reply({ ok: false, error: e?.code ? e : { code: "error", message: String(e?.message ?? e) } }));
    return true;
  });

  globalThis.__fortisLock = { setState, lockNow, get state() { return state; } }; // tests only
})();
