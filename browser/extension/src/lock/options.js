// Fortis Lock settings: sites, unlock password, lock now (docs/security §7.8).
// Shows nothing while the lock is on (the agent refuses changes then anyway).
const api = globalThis.browser ?? globalThis.chrome;
const $ = (id) => document.getElementById(id);

async function send(msg) {
  const r = await api.runtime.sendMessage(msg);
  if (r?.ok) return r.data;
  throw r?.error ?? { message: "No reply" };
}
function message(text, err = false) {
  $("msg").textContent = text;
  $("msg").className = "msg" + (err ? " err" : "");
  $("msg").hidden = !text;
}
const errText = (e) => ({
  unavailable: "The Fortis agent isn't reachable. Is fortis-agent running?",
  invalid_password: "The current password is wrong.",
  rate_limited: "Too many tries. Wait a moment.",
  forbidden: "Not while the lock is on.",
})[e?.code] ?? e?.message ?? "Something went wrong.";

let state = null;

function render() {
  const locked = state.locked;
  $("unavailable").hidden = !locked;
  $("settings").hidden = locked;
  $("agent").textContent = state.agent ? "agent connected" : "agent not connected";
  $("agent").className = "pill " + (state.agent ? "ok" : "down");
  if (locked) return;

  const ul = $("sites");
  ul.replaceChildren();
  if (!state.sites.length) {
    const li = document.createElement("li");
    li.className = "empty";
    li.textContent = "No sites yet.";
    ul.append(li);
  }
  for (const site of state.sites) {
    const li = document.createElement("li");
    const name = document.createElement("span");
    name.textContent = site;
    const rm = document.createElement("button");
    rm.textContent = "Remove";
    rm.className = "link";
    rm.onclick = () => saveSites(state.sites.filter((s) => s !== site));
    li.append(name, rm);
    ul.append(li);
  }
  $("pwState").textContent = state.has_password
    ? "A password is set. Change it below."
    : "No password yet: only your phone's fingerprint can unlock. Set one to unlock on the laptop.";
  $("oldPw").hidden = !state.has_password;
}

async function refresh() {
  try { state = await send({ op: "lock_state" }); render(); }
  catch (e) { message(errText(e), true); }
}

async function saveSites(sites) {
  try { state = { ...state, ...(await send({ op: "lock_sites", sites })) }; render(); message(""); }
  catch (e) { message(errText(e), true); }
}

$("addForm").onsubmit = (e) => {
  e.preventDefault();
  const v = $("addSite").value.trim();
  if (!v) return;
  $("addSite").value = "";
  saveSites([...state.sites, v]);
};

$("pwForm").onsubmit = async (e) => {
  e.preventDefault();
  const pw = $("newPw").value;
  if (pw.length < 4) return message("Use at least 4 characters.", true);
  if (pw !== $("newPw2").value) return message("The two passwords don't match.", true);
  try {
    state = { ...state, ...(await send({ op: "lock_password", password: pw, old_password: $("oldPw").value || undefined })) };
    for (const id of ["oldPw", "newPw", "newPw2"]) $(id).value = "";
    render();
    message("Password saved.");
  } catch (err) { message(errText(err), true); }
};

$("lockNow").onclick = async () => {
  try { state = { ...state, ...(await send({ op: "lock_on" })) }; render(); }
  catch (e) { message(errText(e), true); }
};

api.commands?.getAll?.().then((cmds) => {
  const c = cmds.find((x) => x.name === "lock-sites");
  if (c?.shortcut) $("shortcut").textContent = c.shortcut;
});

api.storage.onChanged.addListener((ch) => { if (ch.lockState) refresh(); });
refresh();
