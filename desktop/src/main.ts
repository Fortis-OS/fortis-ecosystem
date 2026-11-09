// Fortis desktop UI. Talks only to the Rust side (see api.ts).
import { api, Entry, errorText, isUiError, Kind, kindOf, Status, Summary } from "./api";
import { clear, h, icon, toast } from "./dom";
import { openEditor } from "./editor";

const root = document.getElementById("root")!;

type Filter = "all" | Kind | "generator" | "settings";
const NAV: { id: Filter; label: string; icon: string }[] = [
  { id: "all", label: "All items", icon: "layers" },
  { id: "Login", label: "Logins", icon: "key" },
  { id: "CreditCard", label: "Payment cards", icon: "card" },
  { id: "Identity", label: "Identities", icon: "id" },
  { id: "SecureNote", label: "Secure notes", icon: "note" },
  { id: "Totp", label: "Authenticator", icon: "clock" },
];
const KIND_ICON: Record<Kind, string> = { Login: "key", CreditCard: "card", Identity: "id", SecureNote: "note", Totp: "clock" };

const state = {
  vault: null as Status["state"] | null,
  filter: "all" as Filter,
  query: "",
  entries: [] as Summary[],
  selected: null as Entry | null,
};

let totpTimer: number | undefined;

// ---------------------------------------------------------------- screens

function centered(...children: Node[]) {
  clear(root);
  root.append(h("div", { class: "center" }, h("div", { class: "panel" }, h("div", { class: "brand" }, icon("shield"), h("span", { text: "Fortis" })), ...children)));
}

function showUnreachable(msg: string) {
  state.vault = null;
  centered(
    h("p", { class: "muted", text: msg }),
    h("button", { class: "btn primary", text: "Retry", onclick: () => refreshStatus() }),
  );
}

function showInit() {
  const p1 = h("input", { type: "password", placeholder: "Master password (12+ characters)", autocomplete: "new-password", required: true });
  const p2 = h("input", { type: "password", placeholder: "Repeat it", autocomplete: "new-password", required: true });
  const err = h("div", { class: "form-error" });
  centered(
    h("p", { class: "muted", text: "Create your vault. The master password can't be recovered — if you lose it, the vault is gone." }),
    h("form", {
      class: "stack",
      onsubmit: async (e) => {
        e.preventDefault();
        if (p1.value.length < 12) return void (err.textContent = "Use at least 12 characters.");
        if (p1.value !== p2.value) return void (err.textContent = "The passwords don't match.");
        try {
          await api.init(p1.value);
          p1.value = p2.value = "";
          await refreshStatus();
        } catch (x) {
          err.textContent = errorText(x);
        }
      },
    }, p1, p2, err, h("button", { class: "btn primary", type: "submit", text: "Create vault" })),
  );
  p1.focus();
}

function showLocked() {
  state.selected = null;
  state.entries = [];
  const pw = h("input", { type: "password", placeholder: "Master password", autocomplete: "current-password", required: true });
  const err = h("div", { class: "form-error" });
  const btn = h("button", { class: "btn primary", type: "submit", text: "Unlock" });
  centered(
    h("form", {
      class: "stack",
      onsubmit: async (e) => {
        e.preventDefault();
        btn.disabled = true;
        btn.textContent = "Unlocking…";
        try {
          await api.unlock(pw.value);
          pw.value = "";
          await refreshStatus();
        } catch (x) {
          err.textContent = errorText(x);
          pw.select();
        } finally {
          btn.disabled = false;
          btn.textContent = "Unlock";
        }
      },
    }, pw, err, btn),
  );
  pw.focus();
}

// ---------------------------------------------------------------- vault view

async function loadEntries() {
  const kind = state.filter !== "all" && state.filter !== "generator" && state.filter !== "settings" ? state.filter : undefined;
  try {
    state.entries = await api.list(state.query || undefined, kind);
  } catch (e) {
    return handleError(e);
  }
  renderList();
}

function handleError(e: unknown) {
  if (isUiError(e) && (e.code === "vault_locked" || e.code === "agent_unreachable" || e.code === "vault_uninitialised")) {
    void refreshStatus();
  } else {
    toast(errorText(e), "error");
  }
}

function showApp() {
  clear(root);
  const search = h("input", {
    type: "search",
    placeholder: "Search titles, usernames, sites, folders…",
    value: state.query,
    oninput: (e) => {
      state.query = (e.target as HTMLInputElement).value;
      void loadEntries();
    },
  });
  const nav = h("nav", { class: "nav" },
    ...NAV.map((n) => h("button", { class: `nav-item ${state.filter === n.id ? "active" : ""}`, onclick: () => setFilter(n.id) }, icon(n.icon), h("span", { text: n.label }))),
    h("div", { class: "nav-sep" }),
    h("button", { class: `nav-item ${state.filter === "generator" ? "active" : ""}`, onclick: () => setFilter("generator") }, icon("wand"), h("span", { text: "Generator" })),
    h("button", { class: `nav-item ${state.filter === "settings" ? "active" : ""}`, onclick: () => setFilter("settings") }, icon("gear"), h("span", { text: "Settings" })),
  );
  root.append(
    h("div", { class: "app" },
      h("aside", { class: "sidebar" },
        h("div", { class: "brand" }, icon("shield"), h("span", { text: "Fortis" })),
        nav,
        h("button", { class: "btn lock-btn", onclick: async () => { await api.lock().catch(handleError); void refreshStatus(); } }, icon("lock"), h("span", { text: "Lock" })),
      ),
      h("section", { class: "list-col" },
        h("div", { class: "toolbar" },
          h("div", { class: "search" }, icon("search"), search),
          h("button", { class: "btn primary", title: "New entry", onclick: () => addEntry() }, icon("plus"), h("span", { text: "New" })),
        ),
        h("div", { id: "list", class: "list" }),
      ),
      h("section", { id: "detail", class: "detail" }),
    ),
  );
  if (state.filter === "generator") return renderGenerator();
  if (state.filter === "settings") return renderSettings();
  renderDetail();
  void loadEntries();
}

function setFilter(f: Filter) {
  state.filter = f;
  state.selected = null;
  showApp();
}

function renderList() {
  const list = document.getElementById("list");
  if (!list) return;
  if (state.entries.length === 0) {
    list.replaceChildren(h("div", { class: "empty", text: state.query ? "Nothing matches your search." : "No entries yet. Click New to add one." }));
    return;
  }
  list.replaceChildren(
    ...state.entries.map((e) =>
      h("button", { class: `row ${state.selected?.id === e.id ? "selected" : ""}`, onclick: () => select(e.id) },
        h("div", { class: `badge ${e.type}` }, icon(KIND_ICON[e.type])),
        h("div", { class: "row-text" },
          h("div", { class: "row-title", text: e.title }),
          h("div", { class: "row-sub", text: [e.username, e.folder].filter(Boolean).join(" · ") || e.urls[0] || "" }),
        ),
        e.has_totp ? h("span", { class: "pill", text: "2FA" }) : null,
      ),
    ),
  );
}

async function select(id: string) {
  try {
    state.selected = await api.get(id);
  } catch (e) {
    return handleError(e);
  }
  renderList();
  renderDetail();
}

async function addEntry() {
  const kind = state.filter !== "all" && state.filter !== "generator" && state.filter !== "settings" ? state.filter : "Login";
  const id = await openEditor(null, kind);
  if (id) {
    toast("Saved");
    await loadEntries();
    await select(id);
  }
}

// --- detail panel

function copyBtn(id: string, field: string, label: string) {
  return h("button", {
    class: "icon-btn",
    title: `Copy ${label}`,
    onclick: async () => {
      try {
        await api.copySecret(id, field);
        toast(`Copied ${label} — clears in 30 s`);
      } catch (e) {
        handleError(e);
      }
    },
  }, icon("copy"));
}

function fieldRow(label: string, value: string, opts: { secret?: boolean; copy?: [string, string]; link?: boolean; multiline?: boolean } = {}) {
  const val = h("div", { class: `value ${opts.multiline ? "multiline" : ""} ${opts.secret ? "masked" : ""}`, text: opts.secret ? "••••••••••" : value });
  let shown = false;
  const actions: Node[] = [];
  if (opts.secret) {
    actions.push(h("button", {
      class: "icon-btn",
      title: "Show / hide",
      onclick: () => {
        shown = !shown;
        val.textContent = shown ? value : "••••••••••";
        val.classList.toggle("masked", !shown);
      },
    }, icon("eye")));
  }
  if (opts.copy) actions.push(copyBtn(opts.copy[0], opts.copy[1], label.toLowerCase()));
  return h("div", { class: "field-row" }, h("div", { class: "label", text: label }), h("div", { class: "value-line" }, val, ...actions));
}

function renderDetail() {
  window.clearInterval(totpTimer);
  const d = document.getElementById("detail");
  if (!d) return;
  const e = state.selected;
  if (!e) {
    d.replaceChildren(h("div", { class: "empty", text: "Select an entry" }));
    return;
  }
  const kind = kindOf(e.entry_type);
  const inner = (e.entry_type as Record<string, Record<string, unknown>>)[kind];
  const rows: Node[] = [];
  const str = (k: string) => (inner[k] === null || inner[k] === undefined ? "" : String(inner[k]));
  switch (kind) {
    case "Login":
      if (str("username")) rows.push(fieldRow("Username", str("username"), { copy: [e.id, "username"] }));
      rows.push(fieldRow("Password", str("password"), { secret: true, copy: [e.id, "password"] }));
      for (const u of inner.urls as string[]) rows.push(fieldRow("Website", u));
      break;
    case "CreditCard":
      rows.push(fieldRow("Name on card", str("cardholder_name")));
      rows.push(fieldRow("Number", str("number"), { secret: true, copy: [e.id, "number"] }));
      rows.push(fieldRow("Expires", `${String(inner.exp_month).padStart(2, "0")}/${str("exp_year")}`));
      rows.push(fieldRow("CVV", str("cvv"), { secret: true, copy: [e.id, "cvv"] }));
      break;
    case "Identity":
      rows.push(fieldRow("Name", `${str("first_name")} ${str("last_name")}`.trim()));
      if (str("email")) rows.push(fieldRow("Email", str("email"), { copy: [e.id, "email"] }));
      if (str("phone")) rows.push(fieldRow("Phone", str("phone"), { copy: [e.id, "phone"] }));
      if (str("address")) rows.push(fieldRow("Address", str("address"), { multiline: true }));
      break;
    case "SecureNote":
      rows.push(fieldRow("Note", str("content"), { secret: true, multiline: true, copy: [e.id, "content"] }));
      break;
    case "Totp":
      if (str("issuer")) rows.push(fieldRow("Issuer", str("issuer")));
      if (str("account")) rows.push(fieldRow("Account", str("account")));
      break;
  }
  const hasTotp = kind === "Totp" || (kind === "Login" && inner.totp);
  const totpBox = hasTotp ? h("div", { class: "totp" }) : null;
  if (e.notes) rows.push(fieldRow("Notes", e.notes, { secret: true, multiline: true, copy: [e.id, "notes"] }));

  d.replaceChildren(
    h("div", { class: "detail-head" },
      h("div", { class: `badge big ${kind}` }, icon(KIND_ICON[kind])),
      h("div", {}, h("h2", { text: e.title }), h("div", { class: "muted", text: e.folder ?? "" })),
      h("div", { class: "spacer" }),
      h("button", { class: "icon-btn", title: "Edit", onclick: () => editSelected() }, icon("edit")),
      h("button", { class: "icon-btn danger", title: "Delete", onclick: () => deleteSelected() }, icon("trash")),
    ),
    ...(totpBox ? [totpBox] : []),
    h("div", { class: "fields" }, ...rows),
    h("div", { class: "muted small", text: `Updated ${new Date(e.updated_at * 1000).toLocaleString()}` }),
  );
  if (totpBox) void startTotp(e.id, totpBox);
}

async function startTotp(id: string, box: HTMLElement) {
  let remaining = 0;
  const code = h("div", { class: "totp-code" });
  const bar = h("div", { class: "totp-bar" });
  box.replaceChildren(h("div", { class: "label", text: "One-time code" }), h("div", { class: "value-line" }, code, copyBtn(id, "totp", "code")), h("div", { class: "totp-track" }, bar));
  const refresh = async () => {
    try {
      const t = await api.totp(id);
      code.textContent = `${t.code.slice(0, Math.ceil(t.code.length / 2))} ${t.code.slice(Math.ceil(t.code.length / 2))}`;
      remaining = t.remaining_secs;
    } catch (e) {
      window.clearInterval(totpTimer);
      handleError(e);
    }
  };
  await refresh();
  totpTimer = window.setInterval(() => {
    remaining -= 1;
    bar.style.width = `${Math.max(0, (remaining / 30) * 100)}%`;
    if (remaining <= 0) void refresh();
  }, 1000);
  bar.style.width = `${(remaining / 30) * 100}%`;
}

async function editSelected() {
  if (!state.selected) return;
  const id = await openEditor(state.selected);
  if (id) {
    toast("Saved");
    await loadEntries();
    await select(id);
  }
}

async function deleteSelected() {
  const e = state.selected;
  if (!e || !confirm(`Delete "${e.title}"? It is removed from your phone on the next sync.`)) return;
  try {
    await api.remove(e.id);
    state.selected = null;
    toast("Deleted");
    renderDetail();
    await loadEntries();
  } catch (x) {
    handleError(x);
  }
}

// --- generator & settings

function renderGenerator() {
  const list = document.getElementById("list")!;
  list.replaceChildren(h("div", { class: "empty", text: "Generated passwords aren't saved anywhere." }));
  const out = h("div", { class: "gen-out" });
  const len = h("input", { type: "range", min: "8", max: "64", value: "20" });
  const lenLabel = h("span", { text: "20" });
  const upper = h("input", { type: "checkbox", checked: true });
  const nums = h("input", { type: "checkbox", checked: true });
  const syms = h("input", { type: "checkbox", checked: true });
  let current = "";
  const gen = async () => {
    lenLabel.textContent = len.value;
    try {
      current = (await api.generate(Number(len.value), upper.checked, nums.checked, syms.checked)).password;
      out.textContent = current;
    } catch (e) {
      handleError(e);
    }
  };
  for (const c of [len, upper, nums, syms]) c.addEventListener("input", gen);
  document.getElementById("detail")!.replaceChildren(
    h("h2", { text: "Password generator" }),
    out,
    h("div", { class: "gen-actions" },
      h("button", { class: "btn", text: "Regenerate", onclick: gen }),
      h("button", { class: "btn primary", text: "Copy", onclick: async () => { await api.copyText(current).catch(handleError); toast("Copied — clears in 30 s"); } }),
    ),
    h("label", { class: "field" }, h("span", {}, "Length ", lenLabel), len),
    h("label", { class: "check" }, upper, h("span", { text: "Uppercase A–Z" })),
    h("label", { class: "check" }, nums, h("span", { text: "Numbers 0–9" })),
    h("label", { class: "check" }, syms, h("span", { text: "Symbols !@#…" })),
  );
  void gen();
}

function renderSettings() {
  document.getElementById("list")!.replaceChildren();
  const cur = h("input", { type: "password", placeholder: "Current master password", autocomplete: "current-password" });
  const n1 = h("input", { type: "password", placeholder: "New master password (12+)", autocomplete: "new-password" });
  const n2 = h("input", { type: "password", placeholder: "Repeat new password", autocomplete: "new-password" });
  const msg = h("div", { class: "form-error" });
  const autoLock = h("div", { class: "muted" });
  api.status().then((s) => (autoLock.textContent = `The vault locks after ${s.auto_lock_minutes} minutes without use (set vault.auto_lock_minutes in ~/.config/fortis/agent.toml).`)).catch(() => {});
  document.getElementById("detail")!.replaceChildren(
    h("h2", { text: "Settings" }),
    autoLock,
    h("h3", { text: "Change master password" }),
    h("form", {
      class: "stack narrow",
      onsubmit: async (e) => {
        e.preventDefault();
        msg.textContent = "";
        if (n1.value.length < 12) return void (msg.textContent = "Use at least 12 characters.");
        if (n1.value !== n2.value) return void (msg.textContent = "The new passwords don't match.");
        try {
          await api.changePassword(cur.value, n1.value);
          cur.value = n1.value = n2.value = "";
          toast("Master password changed");
        } catch (x) {
          msg.textContent = errorText(x);
        }
      },
    }, cur, n1, n2, msg, h("button", { class: "btn primary", type: "submit", text: "Change password" })),
    h("p", { class: "muted small", text: "Your phone keeps syncing without re-entering anything; only the master password changes." }),
  );
}

// ---------------------------------------------------------------- status loop

async function refreshStatus() {
  let s: Status;
  try {
    s = await api.status();
  } catch (e) {
    return showUnreachable(errorText(e));
  }
  if (s.state === state.vault) return;
  state.vault = s.state;
  if (s.state === "uninitialised") showInit();
  else if (s.state === "locked") showLocked();
  else showApp();
}

// The agent auto-locks on its own; notice it within a few seconds.
window.setInterval(() => void refreshStatus(), 5000);
void refreshStatus();
