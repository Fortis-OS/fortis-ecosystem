#!/usr/bin/env bash
# Build the extension for Chromium (MV3) and Firefox (MV2) into dist/.
set -euo pipefail
cd "$(dirname "$0")"
KEY=$(cat chrome-public-key.txt)
rm -rf dist && mkdir -p dist/chrome dist/firefox
for d in chrome firefox; do cp -r src/* "dist/$d/"; done

python3 - "$KEY" <<'PY'
import json, sys
base = json.load(open("manifest.base.json"))
chrome = dict(base, manifest_version=3, key=sys.argv[1],
    background={"service_worker": "background.js"},
    action={"default_popup": "popup.html", "default_title": "Fortis"},
    permissions=["nativeMessaging", "storage", "activeTab", "tabs"],
    host_permissions=base["content_scripts"][0]["matches"])
firefox = dict(base, manifest_version=2,
    background={"scripts": ["background.js"], "persistent": True},
    browser_action={"default_popup": "popup.html", "default_title": "Fortis"},
    permissions=["nativeMessaging", "storage", "activeTab", "tabs"] + base["content_scripts"][0]["matches"],
    browser_specific_settings={"gecko": {"id": "fortis@fortis.local", "strict_min_version": "115.0"}})
firefox["commands"]["fill-login"]["suggested_key"] = {"default": "Ctrl+Shift+L"}
json.dump(chrome, open("dist/chrome/manifest.json", "w"), indent=2)
json.dump(firefox, open("dist/firefox/manifest.json", "w"), indent=2)
PY
echo "Built dist/chrome (load unpacked) and dist/firefox (about:debugging → Load Temporary Add-on)."
