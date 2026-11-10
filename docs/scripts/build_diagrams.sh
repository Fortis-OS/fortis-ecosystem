#!/bin/bash

# Exit on error
set -e

DOCS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH_DIR="$DOCS_DIR/architecture"
DIAGRAMS_DIR="$ARCH_DIR/diagrams"
BACKEND_DIR="$DOCS_DIR/../fortis-backend"

mkdir -p "$DIAGRAMS_DIR"

echo "Building High-Level Ecosystem Topology..."
if command -v dot >/dev/null 2>&1; then
    dot -Tsvg "$ARCH_DIR/fortis_ecosystem.dot" > "$DIAGRAMS_DIR/fortis_ecosystem.svg"
    echo "✅ Created fortis_ecosystem.svg"
else
    echo "❌ Graphviz (dot) is not installed. Skipping topology generation."
fi

echo "Building Rust Workspace Dependency Graph..."
if command -v cargo-depgraph >/dev/null 2>&1; then
    if [ -d "$BACKEND_DIR" ]; then
        cd "$BACKEND_DIR"
        # Generate dependency graph for workspace members only (excluding external crates)
        cargo depgraph --workspace-only | dot -Tsvg > "$DIAGRAMS_DIR/rust_dependencies.svg"
        echo "✅ Created rust_dependencies.svg"
    else
        echo "❌ Cannot find fortis-backend at $BACKEND_DIR"
    fi
else
    echo "❌ cargo-depgraph is not installed. Run 'cargo install cargo-depgraph' to enable."
fi

echo "Diagram generation complete! Check docs/architecture/diagrams/"
