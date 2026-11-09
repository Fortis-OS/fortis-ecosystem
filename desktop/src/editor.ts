// Add / edit entry dialog. Builds an EntryInput exactly as the agent expects
// (docs/architecture §4.1, docs/api §1).
import { api, Entry, EntryInput, EntryType, errorText, Kind, kindOf, TotpConfig } from "./api";
import { h, icon } from "./dom";

const KINDS: [Kind, string][] = [
  ["Login", "Login"],
  ["CreditCard", "Payment card"],
  ["Identity", "Identity"],
  ["SecureNote", "Secure note"],
  ["Totp", "Authenticator (TOTP)"],
];

type Field = { key: string; label: string; secret?: boolean; textarea?: boolean; gen?: boolean; placeholder?: string; type?: string };

const FIELDS: Record<Kind, Field[]> = {
  Login: [
    { key: "username", label: "Username or email" },
    { key: "password", label: "Password", secret: true, gen: true },
    { key: "urls", label: "Websites (one per line)", textarea: true, placeholder: "https://example.com" },
    { key: "totp", label: "2FA secret (optional, base32)", secret: true, placeholder: "JBSWY3DPEHPK3PXP" },
  ],
  CreditCard: [
    { key: "cardholder_name", label: "Name on card" },
    { key: "number", label: "Card number", secret: true },
    { key: "exp_month", label: "Expiry month", type: "number", placeholder: "MM" },
    { key: "exp_year", label: "Expiry year", type: "number", placeholder: "YYYY" },
    { key: "cvv", label: "CVV", secret: true },
  ],
  Identity: [
    { key: "first_name", label: "First name" },
    { key: "last_name", label: "Last name" },
    { key: "email", label: "Email" },
    { key: "phone", label: "Phone" },
    { key: "address", label: "Address", textarea: true },
  ],
  SecureNote: [{ key: "content", label: "Note", textarea: true, secret: true }],
  Totp: [
    { key: "issuer", label: "Issuer (e.g. GitHub)" },
    { key: "account", label: "Account" },
    { key: "totp", label: "Secret (base32)", secret: true, placeholder: "JBSWY3DPEHPK3PXP" },
  ],
};

function initialValues(e: Entry | null): Record<string, string> {
  if (!e) return {};
  const t = e.entry_type as Record<string, Record<string, unknown>>;
  const inner = t[kindOf(e.entry_type)];
  const v: Record<string, string> = {};
  for (const [k, val] of Object.entries(inner)) {
    if (k === "urls") v.urls = (val as string[]).join("\n");
    else if (k === "totp") v.totp = val ? (val as TotpConfig).secret_base32 : "";
    else v[k] = val === null || val === undefined ? "" : String(val);
  }
  return v;
}

function totpFrom(secret: string, existing: TotpConfig | null): TotpConfig {
  return { digits: 6, period: 30, algorithm: "Sha1", ...(existing ?? {}), secret_base32: secret.replace(/\s+/g, "") };
}

export function build(kind: Kind, v: Record<string, string>, existing: Entry | null): EntryType {
  const s = (k: string) => (v[k] ?? "").trim();
  const opt = (k: string) => (s(k) === "" ? null : s(k));
  const oldTotp = existing ? ((existing.entry_type as Record<string, { totp?: TotpConfig | null }>)[kind]?.totp ?? null) : null;
  switch (kind) {
    case "Login":
      return {
        Login: {
          username: s("username"),
          password: v.password ?? "",
          urls: (v.urls ?? "").split("\n").map((x) => x.trim()).filter(Boolean),
          totp: s("totp") ? totpFrom(s("totp"), oldTotp) : null,
        },
      };
    case "CreditCard":
      return {
        CreditCard: {
          cardholder_name: s("cardholder_name"),
          number: s("number").replace(/\s+/g, ""),
          exp_month: Number(s("exp_month")) || 0,
          exp_year: Number(s("exp_year")) || 0,
          cvv: s("cvv"),
        },
      };
    case "Identity":
      return { Identity: { first_name: s("first_name"), last_name: s("last_name"), email: opt("email"), phone: opt("phone"), address: opt("address") } };
    case "SecureNote":
      return { SecureNote: { content: v.content ?? "" } };
    case "Totp":
      return { Totp: { issuer: opt("issuer"), account: opt("account"), totp: totpFrom(s("totp"), oldTotp) } };
  }
}

/** Opens the dialog; resolves with the saved entry id, or null if cancelled. */
export function openEditor(existing: Entry | null, initialKind: Kind = "Login"): Promise<string | null> {
  return new Promise((resolve) => {
    let kind: Kind = existing ? kindOf(existing.entry_type) : initialKind;
    const values: Record<string, string> = initialValues(existing);
    const title = h("input", { type: "text", value: existing?.title ?? "", placeholder: "e.g. GitHub", required: true });
    const folder = h("input", { type: "text", value: existing?.folder ?? "", placeholder: "optional" });
    const notes = h("textarea", { rows: 3, value: existing?.notes ?? "", placeholder: "optional" });
    const error = h("div", { class: "form-error" });
    const fieldsBox = h("div", { class: "fields" });

    const close = (result: string | null) => {
      overlay.remove();
      resolve(result);
    };

    function renderFields() {
      fieldsBox.replaceChildren(
        ...FIELDS[kind].map((f) => {
          const input = f.textarea
            ? h("textarea", { rows: 3, value: values[f.key] ?? "", placeholder: f.placeholder ?? "" })
            : h("input", { type: f.secret ? "password" : (f.type ?? "text"), value: values[f.key] ?? "", placeholder: f.placeholder ?? "", autocomplete: "off" });
          input.addEventListener("input", () => (values[f.key] = (input as HTMLInputElement).value));
          const extras: Node[] = [];
          if (f.secret && !f.textarea) {
            extras.push(
              h("button", {
                type: "button",
                class: "icon-btn",
                title: "Show / hide",
                onclick: () => ((input as HTMLInputElement).type = (input as HTMLInputElement).type === "password" ? "text" : "password"),
              }, icon("eye")),
            );
          }
          if (f.gen) {
            extras.push(
              h("button", {
                type: "button",
                class: "icon-btn",
                title: "Generate a strong password",
                onclick: async () => {
                  try {
                    const g = await api.generate(20, true, true, true);
                    (input as HTMLInputElement).value = g.password;
                    (input as HTMLInputElement).type = "text";
                    values[f.key] = g.password;
                  } catch (e) {
                    error.textContent = errorText(e);
                  }
                },
              }, icon("wand")),
            );
          }
          return h("label", { class: "field" }, h("span", { text: f.label }), h("div", { class: "input-row" }, input, ...extras));
        }),
      );
    }

    const kindSelect = h("select", {
      disabled: !!existing,
      onchange: (e) => {
        kind = (e.target as HTMLSelectElement).value as Kind;
        renderFields();
      },
    }, ...KINDS.map(([k, label]) => {
      const o = h("option", { value: k, text: label });
      o.selected = k === kind;
      return o;
    }));

    const form = h("form", {
      class: "modal",
      onsubmit: async (e) => {
        e.preventDefault();
        error.textContent = "";
        const input: EntryInput = {
          folder: folder.value.trim() || null,
          title: title.value.trim(),
          entry_type: build(kind, values, existing),
          custom_fields: existing?.custom_fields ?? [],
          notes: notes.value,
        };
        try {
          const r = await api.save(existing?.id ?? null, input);
          close(r.id);
        } catch (err) {
          error.textContent = errorText(err);
        }
      },
    },
      h("div", { class: "modal-head" },
        h("h2", { text: existing ? "Edit entry" : "New entry" }),
        h("button", { type: "button", class: "icon-btn", title: "Close", onclick: () => close(null) }, icon("close")),
      ),
      h("label", { class: "field" }, h("span", { text: "Type" }), kindSelect),
      h("label", { class: "field" }, h("span", { text: "Title" }), title),
      fieldsBox,
      h("label", { class: "field" }, h("span", { text: "Folder" }), folder),
      h("label", { class: "field" }, h("span", { text: "Notes" }), notes),
      error,
      h("div", { class: "modal-actions" },
        h("button", { type: "button", class: "btn", text: "Cancel", onclick: () => close(null) }),
        h("button", { type: "submit", class: "btn primary", text: "Save" }),
      ),
    );
    const overlay = h("div", { class: "overlay", onclick: (e) => e.target === overlay && close(null) }, form);
    document.addEventListener("keydown", function esc(e) {
      if (e.key === "Escape") {
        document.removeEventListener("keydown", esc);
        if (overlay.isConnected) close(null);
      }
    });
    renderFields();
    document.body.append(overlay);
    title.focus();
  });
}
