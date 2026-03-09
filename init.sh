#!/usr/bin/env bash
# =============================================================================
# init.sh — Rename this pickgen template to your project name
#
# Usage:
#   ./init.sh <project-name>
#   ./init.sh my-app
#
# This renames all namespaces, directories, configs, and references
# from "pickgen" to your chosen project name.
# =============================================================================
set -euo pipefail

if [ $# -lt 1 ] || [ -z "$1" ]; then
  echo "Usage: ./init.sh <project-name>"
  echo "Example: ./init.sh my-app"
  exit 1
fi

NEW_NAME="$1"

# Validate: lowercase letters, digits, hyphens; must start with a letter
if ! echo "$NEW_NAME" | grep -qE '^[a-z][a-z0-9-]*$'; then
  echo "Error: project name must start with a lowercase letter"
  echo "       and contain only lowercase letters, digits, or hyphens."
  echo "       Example: my-app, inventory, crm"
  exit 1
fi

OLD_NAME="pickgen"

# Clojure: hyphens in names become underscores on the filesystem
OLD_FS="pickgen"
NEW_FS=$(echo "$NEW_NAME" | tr '-' '_')

echo ""
echo "  Renaming project: $OLD_NAME → $NEW_NAME"
if [ "$NEW_NAME" != "$NEW_FS" ]; then
  echo "  Filesystem dirs:  $OLD_FS → $NEW_FS"
fi
echo ""

# ── 1. Update app-config.edn FIRST (before global replace changes paths) ─────
echo "[1/6] Updating app-config.edn..."
sed -i "s|db/${OLD_NAME}|db/${NEW_FS}|g" resources/config/app-config.edn
sed -i "s|uploads/${OLD_NAME}|uploads/${NEW_FS}|g" resources/config/app-config.edn

# ── 2. Replace namespace references in all Clojure/EDN source files ──────────
echo "[2/6] Replacing namespace references in .clj and .edn files..."
find . -type f \( -name "*.clj" -o -name "*.edn" \) \
  -not -path "./target/*" \
  -exec sed -i "s/${OLD_NAME}\./${NEW_NAME}./g" {} +

# ── 3. Update project.clj ────────────────────────────────────────────────────
echo "[3/6] Updating project.clj..."
sed -i "s/defproject ${OLD_NAME}/defproject ${NEW_NAME}/" project.clj
sed -i "s/\"${OLD_NAME}\"/\"${NEW_NAME}\"/" project.clj
sed -i "s/${OLD_NAME}\.jar/${NEW_NAME}.jar/" project.clj

# ── 4. Update README.md ─────────────────────────────────────────────────────
echo "[4/6] Updating README.md..."
# Capitalize first letter for the title
TITLE_NAME="$(echo "${NEW_NAME:0:1}" | tr '[:lower:]' '[:upper:]')${NEW_NAME:1}"
sed -i "s/# Pickgen/# ${TITLE_NAME}/" README.md
sed -i "s/pickgen/${NEW_NAME}/g" README.md

# ── 5. Rename source directories ────────────────────────────────────────────
echo "[5/6] Renaming directories..."
for dir in src dev test uploads; do
  if [ -d "${dir}/${OLD_FS}" ]; then
    mv "${dir}/${OLD_FS}" "${dir}/${NEW_FS}"
    echo "       ${dir}/${OLD_FS}/ → ${dir}/${NEW_FS}/"
  fi
done

# ── 6. Clean up ─────────────────────────────────────────────────────────────
echo "[6/6] Cleaning up..."
rm -f db/*.sqlite
rm -rf target/
echo "       Removed demo database and build artifacts"

echo ""
echo "  ✓ Project '${NEW_NAME}' is ready!"
echo ""
echo "  Next steps:"
echo "    lein migrate          # Create database tables and dictionaries"
echo "    lein database         # Seed default user accounts"
echo "    lein database -- --all  # Seed users + sample contactos, siblings, cars"
echo "    lein scaffold --all   # Generate entity scaffolding"
echo "    lein run              # Start the server (no hot reload)"
echo "    lein with-profile dev run  # Start with hot reloading (recommended for development)"
echo ""
echo "  To add your own entities, see README.md"
echo ""

# Self-remove — this script is a one-time operation
rm -f init.sh
