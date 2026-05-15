#!/usr/bin/env bash
# sync-env.sh — copy .env.synology to the Synology NAS.
#
# Usage:
#   scripts/sync-env.sh [user@host]
#
# If user@host is omitted, SYNOLOGY_HOST env var is used, or you're prompted.
# The file is placed at /volume1/docker/myassistant/.env (where docker-compose reads it).
#
# First-time setup:
#   cp .env.synology.example .env.synology
#   # fill in real values in .env.synology
#   scripts/sync-env.sh

set -euo pipefail

DEST_PATH="/volume1/docker/myassistant/.env"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$REPO_ROOT/.env.synology"

if [[ ! -f "$SRC" ]]; then
  echo "Error: $SRC not found."
  echo "Run: cp .env.synology.example .env.synology  then fill in real values."
  exit 1
fi

# Resolve target host
TARGET="${1:-${SYNOLOGY_HOST:-}}"
if [[ -z "$TARGET" ]]; then
  read -rp "Synology user@host (e.g. admin@192.168.1.10): " TARGET
fi

echo "Copying .env.synology → ${TARGET}:${DEST_PATH}"
scp "$SRC" "${TARGET}:${DEST_PATH}"
echo "Done. The .env is now in place on the Synology."
echo ""
echo "To apply changes without waiting for the next deploy, SSH in and run:"
echo "  cd /volume1/docker/myassistant && docker compose up -d"
