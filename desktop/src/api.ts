// Typed wrappers around the Rust commands. The webview never sees the agent
// token; every call goes through Rust (docs/security §5.2).
import { invoke } from "@tauri-apps/api/core";

export type VaultState = "uninitialised" | "locked" | "unlocked";
export interface Status {
  state: VaultState;
  auto_lock_minutes: number;
  locks_in_secs: number | null;
}
export interface UiError {
  code: string;
  message: string;
}
export type Kind = "Login" | "CreditCard" | "Identity" | "SecureNote" | "Totp";
export interface Summary {
  id: string;
  title: string;
  type: Kind;
  folder: string | null;
  username: string | null;
  urls: string[];
  has_totp: boolean;
  updated_at: number;
}
export interface TotpConfig {
  secret_base32: string;
  digits: number;
  period: number;
  algorithm: "Sha1" | "Sha256" | "Sha512";
}
export type EntryType =
  | { Login: { username: string; password: string; urls: string[]; totp: TotpConfig | null } }
  | { CreditCard: { cardholder_name: string; number: string; exp_month: number; exp_year: number; cvv: string } }
  | { Identity: { first_name: string; last_name: string; email: string | null; phone: string | null; address: string | null } }
  | { SecureNote: { content: string } }
  | { Totp: { issuer: string | null; account: string | null; totp: TotpConfig } };
export interface EntryInput {
  folder: string | null;
  title: string;
  entry_type: EntryType;
  custom_fields: unknown[];
  notes: string;
}
export interface Entry extends EntryInput {
  id: string;
  created_at: number;
  updated_at: number;
}

export function kindOf(t: EntryType): Kind {
  return Object.keys(t)[0] as Kind;
}

export function isUiError(e: unknown): e is UiError {
  return typeof e === "object" && e !== null && "code" in e && "message" in e;
}

export function errorText(e: unknown): string {
  return isUiError(e) ? e.message : String(e);
}

export const api = {
  status: () => invoke<Status>("vault_status"),
  init: (password: string) => invoke("init_vault", { password }),
  unlock: (password: string) => invoke("unlock", { password }),
  lock: () => invoke("lock"),
  changePassword: (current: string, next: string) => invoke("change_password", { current, new: next }),
  list: (query?: string, kind?: string) => invoke<Summary[]>("list_entries", { query: query ?? null, kind: kind ?? null }),
  get: (id: string) => invoke<Entry>("get_entry", { id }),
  save: (id: string | null, entry: EntryInput) => invoke<{ id: string }>("save_entry", { id, entry }),
  remove: (id: string) => invoke("delete_entry", { id }),
  totp: (id: string) => invoke<{ code: string; remaining_secs: number }>("totp", { id }),
  generate: (length: number, uppercase: boolean, numbers: boolean, symbols: boolean) =>
    invoke<{ password: string }>("generate_password", { length, uppercase, numbers, symbols }),
  copySecret: (id: string, field: string) => invoke("copy_secret", { id, field }),
  copyText: (text: string) => invoke("copy_text", { text }),
};
