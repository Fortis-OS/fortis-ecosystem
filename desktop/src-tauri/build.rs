fn main() {
    // Only these app commands exist for the webview, and each must be granted
    // explicitly in capabilities/default.json (docs/deployment §3).
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(&[
        "vault_status",
        "init_vault",
        "unlock",
        "lock",
        "change_password",
        "list_entries",
        "get_entry",
        "save_entry",
        "delete_entry",
        "totp",
        "generate_password",
        "copy_secret",
        "copy_text",
    ])))
    .expect("failed to run tauri-build");
}
