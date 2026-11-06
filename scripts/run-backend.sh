#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z ${WEB3J_PRIVATE_KEY:-} ]]; then
  read -r -s -p 'Backend testnet private key (hidden, not saved): ' WEB3J_PRIVATE_KEY
  printf '\n'
fi
if [[ ! $WEB3J_PRIVATE_KEY =~ ^(0x)?[[:xdigit:]]{64}$ ]]; then
  printf 'Invalid private key format.\n' >&2
  exit 1
fi
export WEB3J_PRIVATE_KEY
exec bash gradlew bootRun --console=plain
