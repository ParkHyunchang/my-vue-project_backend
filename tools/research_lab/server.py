"""Local-only research service. No production configuration, broker, or database."""
import argparse
import hmac
import json
import os
import secrets
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research_lab.core import Lab
from research_lab.feed import fetch_snapshot
from research_lab.deployment import signing_key, export_candidate, publish_market, invalidate_market

BASE = '/api/strategy-lab'
ROOT = Path(__file__).resolve().parents[2]


def utc_now():
    return datetime.now(timezone.utc).isoformat()


class Conflict(ValueError):
    pass


class InstanceLock:
    def __init__(self, path):
        self.file = Path(path).open('a+b')
        try:
            self.file.seek(0)
            if self.file.read(1) == b'':
                self.file.write(b'0')
                self.file.flush()
            self.file.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise RuntimeError('This research workspace is already running') from None

    def close(self):
        self.file.close()


class Controller:
    def __init__(self, runtime, fixture=False, lab=None, provider=None, interval=3600):
        self.runtime = Path(runtime)
        self.runtime.mkdir(parents=True, exist_ok=True)
        self.lab = lab or Lab(self.runtime / 'lab')
        self.fixture = fixture
        self.provider = provider or (lambda: ROOT / 'tools/team24_data' if fixture else fetch_snapshot(self.runtime / 'market'))
        self.interval = interval
        self.lock = threading.RLock()
        self.stopping = threading.Event()
        self.worker = None
        self.running = False
        self.last_run = None
        self.error = None
        self.due = time.monotonic()
        self.settings_path = self.runtime / 'scheduler.json'
        self.enabled = False
        if self.settings_path.exists():
            settings = json.loads(self.settings_path.read_text(encoding='utf-8'))
            if type(settings.get('enabled')) is not bool:
                raise ValueError('Invalid scheduler settings')
            self.enabled = settings['enabled']
            self.error = settings.get('error')
            self.last_run = settings.get('lastRunAt')
            if settings.get('inProgress'):
                self.error = '이전 연구 실행이 중단됐습니다. 다시 실행해 결과를 확인하세요.'
            if self.error:
                invalidate_market(self.runtime)

    def _save_settings(self):
        tmp = self.settings_path.with_suffix('.tmp')
        tmp.write_text(json.dumps({'enabled': self.enabled, 'error': self.error,
                                  'lastRunAt': self.last_run, 'inProgress': self.running}, ensure_ascii=False), encoding='utf-8')
        os.replace(tmp, self.settings_path)

    def status(self):
        status = self.lab.status()
        with self.lock:
            status.update(paperOnly=True, productionSwitchSupported=False, fixture=self.fixture,
                          running=self.running,
                          scheduler={'enabled': self.enabled, 'intervalSeconds': self.interval,
                                     'lastRunAt': self.last_run,
                                     'nextRunAt': datetime.fromtimestamp(time.time() + max(0, self.due-time.monotonic()), timezone.utc).isoformat() if self.enabled else None,
                                     'error': self.error})
        last = status.get('lastMarketDate')
        stale = not last or (datetime.now(timezone.utc).date() - datetime.fromisoformat(last).date()).days > 4
        status['market'] = {'lastDate': last,
                            'source': 'ARCHIVED_FIXTURE' if self.fixture else 'PUBLIC_COMPLETED_DAILY_BARS',
                            'stale': bool(self.error) or stale}
        if self.error:
            for candidate in status.get('candidates', []):
                candidate['eligible'] = False
                candidate['reasons'] = list(candidate.get('reasons', [])) + ['최근 연구 실행 오류를 해결한 뒤 다시 비교해야 합니다.']
        status['deploymentExportEnabled'] = False
        status['deploymentExportReason'] = '보관자료 모드에서는 실계좌 검토용 보고서를 내보낼 수 없습니다.'
        if not self.fixture:
            try:
                signing_key(self.runtime)
                status['deploymentExportEnabled'] = not self.running and not self.error
                status['deploymentExportReason'] = '실계좌 승인은 별도 화면에서 필요합니다.'
            except ValueError as exc:
                status['deploymentExportReason'] = str(exc)
        return status

    def start_cycle(self):
        with self.lock:
            if self.running:
                raise Conflict('연구 실행 중입니다. 완료 후 다시 확인하세요.')
            self.running = True
            self._save_settings()
            self.worker = threading.Thread(target=self._run, name='research-cycle', daemon=True)
            self.worker.start()

    def _run(self):
        try:
            dataset = self.provider()
            self.lab.run_cycle(dataset)
            if not self.fixture and (self.runtime / 'deployment-signing-key').exists():
                publish_market(self.lab, self.runtime)
            with self.lock:
                self.error = None
        except Exception as exc:
            # No provider credentials exist. Return a bounded, useful reason, not a traceback.
            with self.lock:
                self.error = str(exc)[:400] or type(exc).__name__
                invalidate_market(self.runtime)
        finally:
            with self.lock:
                self.last_run = utc_now()
                self.running = False
                self.due = time.monotonic() + self.interval
                self._save_settings()

    def set_scheduler(self, enabled):
        if type(enabled) is not bool:
            raise ValueError('enabled must be boolean')
        with self.lock:
            self.enabled = enabled
            self._save_settings()
            if enabled:
                self.due = time.monotonic()
        return self.status()

    def decision(self, operation, body):
        if set(body) != {'candidateId', 'reportId', 'expectedRevision'}:
            raise ValueError('Candidate, report and revision are required')
        if type(body['expectedRevision']) is not int or any(not isinstance(body[k], str) or not body[k] or len(body[k]) > 200 for k in ('candidateId', 'reportId')):
            raise ValueError('Invalid approval identity')
        with self.lock:
            if self.running or self.error:
                raise Conflict('연구 실행 또는 오류 확인이 끝난 뒤 다시 승인하세요.')
            if operation == 'export':
                if self.fixture:
                    raise Conflict('보관자료 모드의 실계좌 검토용 내보내기는 차단됩니다.')
                return export_candidate(self.lab, body['candidateId'], body['reportId'], body['expectedRevision'], signing_key(self.runtime))
            method = self.lab.approve if operation == 'approve' else self.lab.reject
            return method(body['candidateId'], body['reportId'], body['expectedRevision'])

    def schedule_loop(self):
        while not self.stopping.wait(.5):
            with self.lock:
                due = self.enabled and not self.running and time.monotonic() >= self.due
            if due:
                try:
                    self.start_cycle()
                except Conflict:
                    pass


def handler_class(controller, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, code, value):
            raw = json.dumps(value, ensure_ascii=False, allow_nan=False).encode('utf-8')
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            self.wfile.write(raw)

        def authorized(self):
            return (self.client_address[0] == '127.0.0.1'
                    and self.headers.get('Host') in ('127.0.0.1:13230', '127.0.0.1:13130')
                    and self.headers.get('Origin') in (None, 'http://127.0.0.1:13130', 'http://127.0.0.1:13230')
                    and self.headers.get('X-Strategy-Lab-Local') == '1'
                    and hmac.compare_digest(self.headers.get('X-Strategy-Lab-Token', ''), token))

        def do_GET(self):
            if not self.authorized():
                return self.reply(403, {'message': 'Local research access required'})
            if self.path != BASE + '/status':
                return self.reply(404, {'message': 'Unknown research route'})
            try:
                self.reply(200, controller.status())
            except Exception as exc:
                self.reply(503, {'message': str(exc)[:400]})

        def do_POST(self):
            if not self.authorized():
                return self.reply(403, {'message': 'Local research access required'})
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if length < 0 or length > 16384 or self.headers.get('Transfer-Encoding'):
                    raise ValueError('Invalid request size')
                body = json.loads(self.rfile.read(length) or b'{}')
                if not isinstance(body, dict):
                    raise ValueError('JSON object required')
                if self.path == BASE + '/cycle':
                    if body:
                        raise ValueError('Cycle has no user-supplied path or code')
                    controller.start_cycle()
                    return self.reply(202, {'accepted': True})
                if self.path == BASE + '/scheduler':
                    if set(body) != {'enabled'}:
                        raise ValueError('Only enabled may be changed')
                    return self.reply(200, controller.set_scheduler(body['enabled']))
                if self.path in (BASE + '/approve', BASE + '/reject', BASE + '/export'):
                    return self.reply(200, controller.decision(self.path.rsplit('/', 1)[1], body))
                self.reply(404, {'message': 'Unknown research route'})
            except Conflict as exc:
                self.reply(409, {'message': str(exc)})
            except (ValueError, KeyError, TypeError) as exc:
                self.reply(409, {'message': str(exc)[:400]})
            except Exception as exc:
                self.reply(503, {'message': str(exc)[:400]})

        def do_OPTIONS(self):
            self.reply(403, {'message': 'Cross-origin access disabled'})
    return Handler


def main():
    parser = argparse.ArgumentParser(description='Local paper strategy research, never production orders')
    parser.add_argument('--fixture', action='store_true')
    args = parser.parse_args()
    runtime = ROOT / '.research-runtime' / ('fixture' if args.fixture else 'local')
    runtime.mkdir(parents=True, exist_ok=True)
    instance = InstanceLock(runtime / 'instance.lock')
    token_path = runtime / 'access-token'
    if not token_path.exists():
        token_path.write_text(secrets.token_urlsafe(40), encoding='utf-8')
    token = token_path.read_text(encoding='utf-8').strip()
    if len(token) < 32:
        raise ValueError('Invalid local access token')
    controller = Controller(runtime, args.fixture)
    server = ThreadingHTTPServer(('127.0.0.1', 13230), handler_class(controller, token))
    server.daemon_threads = True
    thread = threading.Thread(target=controller.schedule_loop, daemon=True)
    thread.start()
    print('STRATEGY_LAB_READY http://127.0.0.1:13230 ' + ('FIXTURE' if args.fixture else 'PUBLIC DAILY DATA'), flush=True)
    try:
        server.serve_forever(poll_interval=.5)
    except KeyboardInterrupt:
        pass
    finally:
        controller.stopping.set()
        server.server_close()
        instance.close()


if __name__ == '__main__':
    main()
