"""Fixture-only HTTP/persistence checks. Never sends an order or contacts a broker."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / '.paper-runtime/paper-fixture'
BASE = 'http://127.0.0.1:13220'
FRONTEND = 'http://127.0.0.1:13120'
API = '/api/us-paper/sessions'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise RuntimeError('Fixture check refuses redirects')


def request(method, path, body=None, *, token=True, frontend=False, headers=None):
    values = {'Content-Type': 'application/json'}
    if token:
        values['X-Paper-Token'] = (RUNTIME / 'access-token').read_text().strip()
    values.update(headers or {})
    data = None if body is None else json.dumps(body).encode('utf-8')
    target = (FRONTEND if frontend else BASE) + path
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(urllib.request.Request(target, data=data, headers=values, method=method), timeout=30) as response:
            status, content = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, content = error.code, error.read()
    try:
        result = json.loads(content)
    except (json.JSONDecodeError, UnicodeDecodeError):
        result = {'non_json_response': True,
                  'webpack_host_rejection': content.strip() == b'Invalid Host header'}
    return status, result


def expect(method, path, expected=200, body=None, **kwargs):
    code, response = request(method, path, body, **kwargs)
    if code != expected:
        raise AssertionError(f'{method} {path}: expected HTTP {expected}, received {code}')
    return response


def scenario(value):
    if value not in ('normal', 'drop', 'stale', 'failure'):
        raise ValueError('Unknown fixture')
    (RUNTIME / 'scenario').write_text(value, encoding='utf-8')


def session_snapshot(status):
    return {key: status[key] for key in (
        'id', 'state', 'initialCashUsd', 'cashUsd', 'quantity', 'equityUsd',
        'peakEquityUsd', 'maxDrawdownPercent', 'hardLatched', 'startedAt',
    )} | {'fill_ids': [fill['id'] for fill in status['fills']]}


def frontend_checks():
    expect('GET', API, 403, token=False, frontend=True)
    expect('GET', API, 403, token=False, frontend=True,
           headers={'Origin': 'https://untrusted.invalid', 'X-Paper-Local': '1'})
    expect('GET', API, 403, token=False, frontend=True, headers={'Origin': FRONTEND})
    expect('OPTIONS', API, 403, token=False, frontend=True,
           headers={'Origin': 'https://untrusted.invalid', 'X-Paper-Local': '1'})
    expect('GET', '/api/not-paper', 403, token=False, frontend=True,
           headers={'Origin': FRONTEND, 'X-Paper-Local': '1'})
    host_code, host_body = request('GET', API, token=False, frontend=True,
                                  headers={'Host': 'untrusted.invalid', 'Origin': FRONTEND, 'X-Paper-Local': '1'})
    # webpack-dev-server rejects before custom middleware, using HTTP 200 text.
    assert host_code == 403 or (host_code == 200 and host_body.get('webpack_host_rejection') is True), \
        'Foreign Host must receive rejection, never the API payload'
    sessions = expect('GET', API, token=False, frontend=True,
                      headers={'Origin': FRONTEND, 'X-Paper-Local': '1', 'X-Paper-Token': 'client-must-not-control-token'})
    assert isinstance(sessions, list)
    return 'PASS: host/origin/header/path/preflight rejection and server token replacement'


def prepare(include_frontend):
    checks = []
    expect('GET', API, 401, token=False)
    expect('GET', API, 401, token=False, headers={'X-Paper-Token': 'invalid-local-token'})
    expect('GET', '/api/not-paper', 401)
    checks.append('Direct backend requires local token and paper route')
    expect('POST', API, 400, {'initialCashUsd': -1})
    expect('POST', API, 400, {'initialCashUsd': 100.001})
    checks.append('Invalid virtual capital is rejected')
    scenario('normal')
    first = expect('POST', API, body={'initialCashUsd': 10000})
    assert first['paperOnly'] and first['state'] == 'CREATED'
    first_id = first['id']
    active = expect('POST', f'{API}/{first_id}/start', body={})
    assert active['source'] == 'FIXTURE_NO_NETWORK', 'Must only run against the fixture host'
    assert active['state'] == 'RUNNING' and active['quantity'] > 0 and len(active['fills']) == 1
    buy = active['fills'][0]
    assert buy['side'] == 'BUY' and buy['priceUsd'] * buy['quantity'] + buy['feeUsd'] <= 9900.000001
    checks.append('Normal start creates virtual fee-inclusive cash-limited buy')
    with ThreadPoolExecutor(max_workers=6) as executor:
        results = list(executor.map(lambda _: expect('POST', f'{API}/{first_id}/poll', body={}), range(12)))
    assert all(len(result['fills']) == 1 for result in results)
    stable = expect('GET', f'{API}/{first_id}')
    assert stable['quantity'] == active['quantity'] and stable['cashUsd'] == active['cashUsd']
    checks.append('Twelve concurrent polls do not duplicate the daily fill')
    scenario('stale')
    stale = expect('POST', f'{API}/{first_id}/poll', body={})
    assert stale['reason'] == 'STALE_QUOTE' and not stale['quoteFresh'] and len(stale['fills']) == 1
    assert stale['cashUsd'] == stable['cashUsd'] and stale['peakEquityUsd'] == stable['peakEquityUsd']
    scenario('failure')
    failed = expect('POST', f'{API}/{first_id}/poll', body={})
    assert failed['reason'] == 'DATA_ERROR' and failed['cashUsd'] == stable['cashUsd'] and len(failed['fills']) == 1
    scenario('normal')
    recovered = expect('POST', f'{API}/{first_id}/poll', body={})
    assert recovered['quoteFresh'] and len(recovered['fills']) == 1
    checks.append('Stale and failed feeds retain ledger/peak; fresh recovery does not duplicate orders')
    stopped = expect('POST', f'{API}/{first_id}/stop', body={})
    assert stopped['state'] == 'STOPPED' and stopped['quantity'] == active['quantity']
    assert session_snapshot(expect('POST', f'{API}/{first_id}/poll', body={})) == session_snapshot(stopped)
    resumed = expect('POST', f'{API}/{first_id}/start', body={})
    assert resumed['state'] == 'RUNNING' and len(resumed['fills']) == 1 and resumed['peakEquityUsd'] == stable['peakEquityUsd']
    stopped = expect('POST', f'{API}/{first_id}/stop', body={})
    checks.append('Stop freezes virtual execution; resume retains holdings/peak/signal identity')
    second = expect('POST', API, body={'initialCashUsd': 10000})
    second_id = second['id']
    expect('POST', f'{API}/{second_id}/start', body={})
    scenario('drop')
    hard = expect('POST', f'{API}/{second_id}/poll', body={})
    assert hard['hardLatched'] and hard['state'] == 'HARD' and hard['quantity'] == 0
    assert hard['maxDrawdownPercent'] >= 10 and len(hard['fills']) == 2 and hard['fills'][0]['side'] == 'SELL'
    expect('POST', f'{API}/{second_id}/start', 409, {})
    scenario('normal')
    unchanged = expect('POST', f'{API}/{second_id}/poll', body={})
    assert session_snapshot(unchanged) == session_snapshot(hard)
    checks.append('Gap triggers persistent hard liquidation; rebound and restart command cannot unlock it')
    third = expect('POST', API, body={'initialCashUsd': 10000})
    running = expect('POST', f"{API}/{third['id']}/start", body={})
    if include_frontend:
        checks.append(frontend_checks())
    checkpoint = {
        'utc': datetime.now(timezone.utc).isoformat(), 'phase': 'before_process_restart',
        'status': 'PASS', 'checks': checks,
        'sessions': [session_snapshot(stopped), session_snapshot(hard), session_snapshot(running)],
    }
    path = RUNTIME / ('http-check-' + uuid.uuid4().hex + '.json')
    path.write_text(json.dumps(checkpoint, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (RUNTIME / 'http-check-latest').write_text(path.name, encoding='utf-8')
    print(json.dumps({'status': 'PASS', 'checks': checks, 'checkpoint': str(path)}, ensure_ascii=False))


def verify_restart():
    name = (RUNTIME / 'http-check-latest').read_text().strip()
    if Path(name).name != name or not name.startswith('http-check-') or not name.endswith('.json'):
        raise ValueError('Invalid fixture checkpoint name')
    source = RUNTIME / name
    checkpoint = json.loads(source.read_text(encoding='utf-8'))
    for before in checkpoint['sessions']:
        current = expect('GET', f"{API}/{before['id']}")
        assert session_snapshot(current) == before, 'Persistent state differs after restart'
        if before['hardLatched']:
            expect('POST', f"{API}/{before['id']}/start", 409, {})
    result = {'status': 'PASS', 'utc': datetime.now(timezone.utc).isoformat(),
              'checks': ['STOPPED, RUNNING and HARD cash/holdings/peak/ledger survive process restart',
                         'HARD session still rejects resume after restart'], 'checkpoint': source.name}
    source.with_name(source.stem + '-after-restart.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('prepare', 'verify-restart', 'frontend'))
    parser.add_argument('--include-frontend', action='store_true')
    arguments = parser.parse_args()
    if not RUNTIME.is_dir():
        raise SystemExit('Start run-paper-local.ps1 -Fixture first')
    try:
        if arguments.phase == 'prepare':
            prepare(arguments.include_frontend)
        elif arguments.phase == 'verify-restart':
            verify_restart()
        else:
            print(frontend_checks())
    finally:
        if arguments.phase == 'prepare':
            scenario('normal')
