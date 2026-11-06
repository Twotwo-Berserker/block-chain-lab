#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${HARDHAT_PACKAGE_DIR:?Set HARDHAT_PACKAGE_DIR to the installed Hardhat package directory}"
export HARDHAT_EXPERIMENTAL_ALLOW_NON_LOCAL_INSTALLATION=true
exec node "$HARDHAT_PACKAGE_DIR/internal/cli/cli.js" node \
  --config scripts/hardhat.config.cjs --hostname 127.0.0.1 --port 18545
