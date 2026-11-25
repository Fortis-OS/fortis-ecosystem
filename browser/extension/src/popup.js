const api = globalThis.browser ?? globalThis.chrome;
const $ = (id) => document.getElementById(id);

async function send(msg) {
  const r = await api.runtime.sendMessage(msg);
  if (r?.ok) return r.data;
  throw r?.error ?? { message: "No reply" };
}
function show(id, on) { $(id).hidden = !on; }
function message(text, err = false) { $("msg").textContent = text; $("msg").className = "msg" + (err ? " err" : ""); show("msg", !!text); }

async function refresh() {
  message("");
  let st;
  try { st = await send({ op: "status" }); }
  catch (e) {
    show("unlock", false); show("list", false); show("lock", false);
    return message(e.code === "unavailable"
      ? "The Fortis agent isn't reachable. Is fortis-agent running, and is fortis-browser-host installed?"
      : (e.message ?? "Error"), true);
  }
  const locked = st.state !== "unlocked";
  show("unlock", locked); show("lock", !locked);
  await showPending(locked);
  if (locked) { show("list", false); if (st.state === "uninitialised") message("No vault yet — run `fortis init`.", true); else $("pw").focus(); return; }
  await showMatches();
}

async function showPending(locked) {
  const p = await send({ op: "pending" }).catch(() => null);
  show("save", !!p);
  if (!p) return;
  $("saveQ").textContent = (p.status === "changed" ? "Update the password for " : "Save login for ") + (p.username ? p.username + " on " : "") + p.host + "?";
  $("saveBtn").textContent = locked ? "Unlock first" : p.status === "changed" ? "Update" : "Save";
  $("saveBtn").disabled = locked;
}

async function showMatches() {
  const m = await send({ op: "match" }).catch((e) => (message(e.message ?? "Error", true), null));
  show("list", !!m);
  if (!m) return;
  $("host").textContent = m.notFillable ? "Fortis fills only https pages." : m.host ?? "";
  const ul = $("entries"); ul.replaceChildren();
  if (!m.notFillable && m.entries.length === 0) {
    const li = document.createElement("li"); li.textContent = "No saved login for this site. Sign in and Fortis will offer to save it."; ul.append(li);
  }
  for (const e of m.entries) {
    const li = document.createElement("li");
    const who = document.createElement("div"); who.className = "who";
    const t = document.createElement("div"); t.className = "t"; t.textContent = e.title;
    const u = document.createElement("div"); u.className = "u"; u.textContent = e.username || "—";
    who.append(t, u);
    const fill = document.createElement("button"); fill.className = "primary"; fill.textContent = "Fill";
    fill.onclick = async () => {
      try {
        const r = await send({ op: "fill", id: e.id });
        if (r?.filled) window.close(); else message(r?.reason ? "Couldn't fill: " + r.reason : "Couldn't fill this page.", true);
      } catch (err) { message(err.message ?? "Error", true); }
    };
    const code = document.createElement("button"); code.textContent = "2FA";
    code.onclick = async () => {
      try { const r = await send({ op: "totp", id: e.id }); await navigator.clipboard.writeText(r.code); message(`2FA code copied (${r.remaining_secs}s left).`); }
      catch (err) { message(err.code === "not_found" ? "No 2FA for this login." : err.message ?? "Error", true); }
    };
    li.append(who, fill, code); ul.append(li);
  }
}

$("unlock").onsubmit = async (ev) => {
  ev.preventDefault();
  const pw = $("pw").value; $("pw").value = "";
  try { await send({ op: "unlock", password: pw }); await refresh(); }
  catch (e) { message(e.code === "invalid_password" ? "Wrong password." : e.code === "rate_limited" ? "Too many attempts — wait a moment." : e.message ?? "Couldn't unlock", true); }
};
$("lock").onclick = async () => { await send({ op: "lock" }).catch(() => {}); refresh(); };
$("saveBtn").onclick = async () => {
  try { const r = await send({ op: "save" }); message(r.action === "updated" ? "Password updated." : r.action === "created" ? "Saved to Fortis." : "Already saved."); show("save", false); showMatches(); }
  catch (e) { message(e.message ?? "Couldn't save", true); }
};
$("neverBtn").onclick = async () => { await send({ op: "never" }); show("save", false); };
$("dismissBtn").onclick = async () => { await send({ op: "dismiss" }); show("save", false); };
// Fortis Lock: hidden while on, so a guest doesn't see it.
async function showLock() {
  const st = await send({ op: "lock_state" }).catch(() => null);
  show("lockCard", !!st && !st.locked);
  if (!st || st.locked) return;
  $("lockSites").textContent = st.sites.length ? st.sites.join(", ") : "no sites yet";
  $("lockNow").disabled = !st.sites.length;
}
$("lockNow").onclick = async () => { await send({ op: "lock_on" }).catch(() => {}); window.close(); };
// Firefox drops the request if the popup closes first: wait for it.
$("lockSettings").onclick = async () => { await api.runtime.openOptionsPage().catch(() => {}); window.close(); };
showLock();
refresh();
