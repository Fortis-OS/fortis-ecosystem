#!/usr/bin/env bash
# ==========================================
# FortisMobile Install Script
# Run this inside Termux after cloning the repo.
# ==========================================
set -euo pipefail

INSTALL_DIR="$HOME/.local/bin"
CONF_DIR="$HOME/.config/fortis-mobile"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "========================================"
echo "  FortisMobile Installer"
echo "========================================"
echo ""

# 1. Create directories
mkdir -p "$INSTALL_DIR"
mkdir -p "$CONF_DIR"
mkdir -p "$HOME/.local/share/fortis-mobile"
mkdir -p "$HOME/.termux/boot"
echo "✔ Directories created"

# 2. Install the CLI
cp "$SCRIPT_DIR/fortis-mobile" "$INSTALL_DIR/fortis-mobile"
chmod +x "$INSTALL_DIR/fortis-mobile"
echo "✔ fortis-mobile installed to $INSTALL_DIR"

# 3. Install config (don't overwrite existing)
if [[ ! -f "$CONF_DIR/fortis-mobile.conf" ]]; then
    cp "$SCRIPT_DIR/fortis-mobile.conf.example" "$CONF_DIR/fortis-mobile.conf"
    chmod 600 "$CONF_DIR/fortis-mobile.conf"
    echo "✔ Config installed to $CONF_DIR/fortis-mobile.conf"
else
    echo "  Config already exists at $CONF_DIR/fortis-mobile.conf — skipping"
fi

# 4. Install Termux:Boot script
cp "$SCRIPT_DIR/boot/start-backup-services.sh" "$HOME/.termux/boot/start-backup-services.sh"
chmod +x "$HOME/.termux/boot/start-backup-services.sh"
echo "✔ Termux:Boot script installed"

# 5. Ensure PATH includes ~/.local/bin
if ! echo "$PATH" | grep -q "$HOME/.local/bin"; then
    echo ""
    echo "⚠  Add this to your ~/.bashrc to make 'fortis-mobile' available in every Termux session:"
    echo "   export PATH=\"\$HOME/.local/bin:\$PATH\""
    echo ""
fi

echo ""
echo "✔ Installation complete."
echo ""
echo "Next steps:"
echo "  1. Edit your config:  nano $CONF_DIR/fortis-mobile.conf"
echo "  2. Verify it works:   fortis-mobile status"
echo "  3. Test readiness:    fortis-mobile health && echo READY"
