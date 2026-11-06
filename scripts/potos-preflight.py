#!/usr/bin/env python3
"""Read-only checks of the group's previous lab contracts; never signs transactions."""
import datetime
import json
from pathlib import Path
import subprocess

RPC = 'https://rpc-testnet.potos.hk'
REFERENCE = json.loads((Path(__file__).resolve().parents[1] / 'docs/lab3-reference.json').read_text())
requests = []


def rpc(method, params):
    payload = {'jsonrpc': '2.0', 'id': len(requests) + 1, 'method': method, 'params': params}
    result = subprocess.run(['curl', '--fail-with-body', '--silent', '--show-error', '--max-time', '25',
                             RPC, '-H', 'Content-Type: application/json', '--data-binary', '@-'],
                            input=json.dumps(payload), text=True, capture_output=True, check=True)
    response = json.loads(result.stdout)
    requests.append({'request': payload, 'response': response})
    if 'error' in response:
        raise RuntimeError(response['error'])
    return response['result']


def call(address, selector):
    return rpc('eth_call', [{'to': address, 'data': selector}, 'latest'])


def main():
    chain_id = int(rpc('eth_chainId', []), 16)
    if chain_id != 60600:
        raise RuntimeError(f'Expected POTOS 60600, got {chain_id}')
    block = int(rpc('eth_blockNumber', []), 16)
    token = REFERENCE['contracts']['WQLToken']['address']
    escrow = REFERENCE['contracts']['Escrow']['address']
    owner = '0x' + call(token, '0x8da5cb5b')[-40:]
    arbiter = '0x' + call(escrow, '0xfe25e00a')[-40:]
    decimals = int(call(token, '0x313ce567'), 16)
    balance = int(call(token, '0x70a08231' + owner[2:].rjust(64, '0')), 16)
    output = {
        'classification': 'READ_ONLY_PREVIOUS_LAB3_NOT_ROUTE_A_ACCEPTANCE',
        'recordedAt': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
        'rpc': RPC, 'chainId': chain_id, 'observedBlock': block,
        'token': {'address': token, 'owner': owner, 'decimals': decimals, 'ownerBalanceUnits': str(balance)},
        'previousEscrow': {'address': escrow, 'arbiter': arbiter},
        'routeANewEscrow': None, 'rawRequests': requests,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
