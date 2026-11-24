"""Manual integration check against the running localhost FIXTURE lab only."""
import json
import time
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError


def main():
    opener = build_opener(ProxyHandler({}))
    base = 'http://127.0.0.1:13130/api/strategy-lab'
    headers = {'X-Strategy-Lab-Local': '1', 'Origin': 'http://127.0.0.1:13130', 'Content-Type': 'application/json'}

    def call(path, body=None, h=None):
        request = Request(base+path, data=None if body is None else json.dumps(body).encode(), headers=headers if h is None else h)
        try:
            with opener.open(request, timeout=30) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    assert call('/status', h={})[0] == 403
    assert call('/status', h=dict(headers, Origin='https://untrusted.example'))[0] == 403
    code, state = call('/status')
    assert code == 200 and state['fixture'] and state['paperOnly'] and not state['productionSwitchSupported']
    assert call('/cycle', {})[0] == 202
    assert call('/cycle', {})[0] == 409
    started = time.monotonic()
    assert call('/status')[0] == 200
    print(json.dumps({'cycleStarted': True, 'statusSeconds': time.monotonic()-started}), flush=True)
    for _ in range(180):
        _, state = call('/status')
        if not state['running']:
            break
        time.sleep(1)
    assert not state['running'] and state['scheduler']['error'] is None, state
    assert len(state['candidates']) == 6 and all(not c['eligible'] for c in state['candidates'])
    assert call('/approve', {'candidateId': state['candidates'][0]['id'], 'reportId': state['report']['id'], 'expectedRevision': state['revision']})[0] == 409
    try:
        assert call('/scheduler', {'enabled': True})[1]['scheduler']['enabled']
    finally:
        assert not call('/scheduler', {'enabled': False})[1]['scheduler']['enabled']
    for _ in range(180):
        _, state = call('/status')
        if not state['running']:
            break
        time.sleep(1)
    assert not state['running']
    Path('target/team25-http-status.json').write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': True, 'revision': state['revision'], 'days': state['paperAccount']['days'], 'cash': state['paperAccount']['cashUsd'], 'schedulerEnabled': state['scheduler']['enabled']}))


if __name__ == '__main__':
    main()
