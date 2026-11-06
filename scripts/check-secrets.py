#!/usr/bin/env python3
"""Conservative source scan. Print locations, never potential secret values."""
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
reference = root / 'docs/lab3-reference.json'
public_hashes = {v['transactionHash'] for v in json.loads(reference.read_text())['contracts'].values()}
evidence = root / 'evidence/member1'
local_records = evidence / 'records/06-local-integration.json'
local_transaction_hashes = ({tx['hash'] for tx in json.loads(local_records.read_text())['transactions']}
                            if local_records.exists() else set())
hex_value = re.compile(r'(?<![a-zA-Z0-9])(?:0x)?[0-9a-fA-F]{64}(?![a-zA-Z0-9])')
findings = []
checked = 0
for path in sorted(root.rglob('*')):
    if not path.is_file() or any(p in {'.git', '.gradle', 'build', 'node_modules'} for p in path.relative_to(root).parts):
        continue
    if path.name == '.env' or (path.name.startswith('.env.') and path.name != '.env.example'):
        findings.append(f'{path.relative_to(root)}: runtime environment file must not be submitted')
        continue
    try:
        text = path.read_text()
    except (UnicodeDecodeError, OSError):
        continue
    checked += 1
    for line_no, line in enumerate(text.splitlines(), 1):
        for match in hex_value.finditer(line):
            if path == reference and match.group() in public_hashes:
                continue  # Independently documented public transaction hashes, not credentials.
            if path.is_relative_to(evidence) and match.group() in local_transaction_hashes:
                continue  # Actual local-chain receipt hashes, also present in the test output.
            findings.append(f'{path.relative_to(root)}:{line_no}: potential key (value omitted)')
        if path.suffix == '.properties' and re.match(r'\s*web3j\.private-key\s*=', line):
            if line.split('=', 1)[1].strip() != '${WEB3J_PRIVATE_KEY}':
                findings.append(f'{path.relative_to(root)}:{line_no}: private key must be an environment placeholder')

print(f'Source files checked: {checked}')
print('Public historical transaction hashes: explicitly classified in docs/lab3-reference.json')
print('Local transaction hashes: explicitly classified in evidence/member1/records/06-local-integration.json')
if findings:
    print('\n'.join(findings))
    sys.exit(1)
print('PASS: no candidate plaintext private key or runtime .env file in submission sources')
print('Excluded build/cache directories are also excluded from the delivery archive.')
