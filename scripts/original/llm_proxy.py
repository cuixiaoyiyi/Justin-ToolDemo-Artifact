"""Unified local transport and per-attempt accounting for the new benchmark.

No service is started on import. No prompts, completions, credentials, or upstream
response bodies are persisted. usage.jsonl contains one finished upstream
attempt per line. A separate lifecycle journal records starts and delivery, so a
cancelled client does not hide an already-running upstream request.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path('/home/clm/tooldemo72-20261023')
REPO = Path(__file__).resolve().parents[1]
SECRET = REPO/'workspace/tool-demo-20261017.properties'
PORT = 8772
MODEL = 'deepseek-flash'
MAX_TOKENS = 4096
MAX_CONCURRENT = 6
HTTP_TIMEOUT = 180
MAX_RETRIES = 2
RETRYABLE = {408, 409, 425, 429, 500, 502, 503, 504, 599}
TOOLS = {'justin-llm', 'chatunitest', 'chattester', 'hits'}
IDENTITY = 'tooldemo72-20261023'
PIDFILE = ROOT/'proxy.pid.json'
READY = ROOT/'proxy-ready.json'
HERE = Path(__file__).resolve()


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _write_json(path, value):
    path = Path(path).resolve()
    if not path.is_relative_to(ROOT.resolve()):
        raise ValueError('Proxy output outside the new benchmark root')
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name+'.tmp-'+str(os.getpid()))
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    temp.replace(path)


def _settings():
    values = {}
    for line in SECRET.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            values[k.strip()] = v.strip()
    key = values.get('apiKey')
    endpoint = values.get('openaiUrl')
    if not key or not endpoint:
        raise RuntimeError('Missing upstream credential or endpoint in local properties')
    endpoint = endpoint.rstrip('/')
    if endpoint.endswith('/v1'):
        endpoint += '/chat/completions'
    elif not endpoint.endswith('/chat/completions'):
        endpoint += '/v1/chat/completions'
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme not in {'https', 'http'} or not parsed.hostname:
        raise RuntimeError('Invalid configured upstream endpoint')
    return key, endpoint


def _text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list) and all(isinstance(x, dict) and x.get('type')=='text'
                                       and isinstance(x.get('text'), str) for x in value):
        return ''.join(x['text'] for x in value)
    raise ValueError('Only plain text message content is supported')


def chat_request(body, messages_api=False):
    if not isinstance(body, dict):
        raise ValueError('JSON object required')
    if body.get('stream'):
        raise ValueError('Streaming is disabled for the benchmark')
    if body.get('tools') or body.get('functions'):
        raise ValueError('Only plain text generation is supported')
    messages = []
    if messages_api:
        if body.get('system'):
            messages.append(dict(role='system', content=_text(body['system'])))
        for message in body.get('messages', []):
            if message.get('role') not in {'user', 'assistant'}:
                raise ValueError('Unsupported Messages role')
            messages.append(dict(role=message['role'], content=_text(message['content'])))
        request = dict(messages=messages)
        for parameter in ['temperature', 'top_p']:
            if parameter in body:
                request[parameter] = body[parameter]
        if body.get('stop_sequences'):
            request['stop'] = body['stop_sequences']
    else:
        for message in body.get('messages', []):
            if message.get('role') not in {'system', 'developer', 'user', 'assistant'}:
                raise ValueError('Unsupported chat role')
            if message.get('tool_calls') or message.get('function_call'):
                raise ValueError('Tool calls are unsupported')
            copied = dict(message)
            copied['content'] = _text(message.get('content'))
            messages.append(copied)
        request = dict(body, messages=messages)
        request.pop('max_completion_tokens', None)
    if not messages:
        raise ValueError('At least one message is required')
    request.update(model=MODEL, max_tokens=MAX_TOKENS,
                   thinking=dict(type='disabled'), stream=False)
    return request


def message_response(result):
    choice = result['choices'][0]
    text = choice['message'].get('content') or ''
    if not isinstance(text, str):
        raise ValueError('Upstream returned non-text content')
    usage = result.get('usage') or {}
    return dict(id=result.get('id') or 'msg_'+uuid.uuid4().hex,
                type='message', role='assistant', model=MODEL,
                content=[dict(type='text', text=text)],
                stop_reason={'length':'max_tokens', 'stop':'end_turn'}.get(choice.get('finish_reason'), 'end_turn'),
                stop_sequence=None, usage=dict(input_tokens=usage.get('prompt_tokens', usage.get('input_tokens', 0)),
                    output_tokens=usage.get('completion_tokens', usage.get('output_tokens', 0))))


class Accounting:
    def __init__(self, instance):
        self.instance = instance
        self.gate = threading.BoundedSemaphore(MAX_CONCURRENT)
        self.lock = threading.Lock()
        self.active = 0
        self.draining = False

    def log(self, record, filename='usage.jsonl'):
        record = dict(proxy_instance=self.instance, **record)
        with self.lock:
            with (ROOT/filename).open('a', encoding='utf-8') as stream:
                stream.write(json.dumps(record, ensure_ascii=False)+'\n')
                stream.flush()
                os.fsync(stream.fileno())

    def enter(self):
        with self.lock:
            self.active += 1

    def leave(self):
        with self.lock:
            self.active -= 1

    def snapshot(self):
        with self.lock:
            return self.active

    def drain(self):
        with self.lock:
            self.draining = True

    def is_draining(self):
        with self.lock:
            return self.draining


def _usage(result):
    raw = result.get('usage') if isinstance(result, dict) else None
    usage = raw if isinstance(raw, dict) else {}
    details = usage.get('prompt_tokens_details') or {}
    cached = usage.get('prompt_cache_hit_tokens')
    if cached is None and isinstance(details, dict):
        cached = details.get('cached_tokens')
    if cached is None:
        cached = usage.get('cache_read_input_tokens')
    return usage, usage.get('prompt_tokens', usage.get('input_tokens')), \
        usage.get('completion_tokens', usage.get('output_tokens')), cached


def serve(port=PORT, instance_id=None):
    ROOT.mkdir(parents=True, exist_ok=True)
    key, endpoint = _settings()
    instance = instance_id or uuid.uuid4().hex
    state = Accounting(instance)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass

        def respond(self, status, body):
            raw = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            self.wfile.write(raw)

        def error(self, status, kind, message):
            self.respond(status, dict(type='error', error=dict(type=kind, message=message)))

        def do_GET(self):
            if urllib.parse.urlsplit(self.path).path != '/health':
                self.error(404, 'not_found', 'Unknown local endpoint')
                return
            self.respond(200, dict(proxy=IDENTITY, instance_id=instance, pid=os.getpid(), port=port,
                active_requests=state.snapshot(), max_concurrent=MAX_CONCURRENT, model=MODEL,
                max_tokens=MAX_TOKENS, thinking='disabled', draining=state.is_draining()))

        def do_POST(self):
            path = urllib.parse.urlsplit(self.path).path
            if path=='/_control/drain/'+instance:
                length = int(self.headers.get('Content-Length', '0'))
                if length:
                    self.rfile.read(min(length, 1024))
                state.drain()
                self.respond(200, dict(status='draining', active_requests=state.snapshot(), instance_id=instance))
                return
            match = re.fullmatch(r'/([A-Za-z0-9_-]+)/([A-Za-z0-9_.-]+)/v1/(chat/completions|messages)', path)
            if not match or match.group(1) not in TOOLS:
                self.error(404, 'not_found', 'Expected /tool/method_id/v1/chat/completions or /messages')
                return
            tool, method_id, api = match.groups()
            if state.is_draining():
                self.error(503, 'api_error', 'Local benchmark proxy is draining; no upstream attempt was made')
                return
            received = time.time()
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 64*1024*1024:
                    raise ValueError('Invalid request length')
                incoming_raw = self.rfile.read(length)
                body = json.loads(incoming_raw)
                normalized = chat_request(body, messages_api=api=='messages')
                data = json.dumps(normalized, ensure_ascii=False, separators=(',', ':')).encode()
            except (ValueError, TypeError, KeyError, json.JSONDecodeError):
                self.error(400, 'invalid_request_error', 'Invalid or unsupported plain text request')
                return
            identifier = uuid.uuid4().hex
            common = dict(request_id=identifier, label=tool+'/'+method_id, tool=tool,
                method_id=method_id, api=api, model=MODEL, upstream_model=MODEL,
                asked_model=body.get('model'), max_tokens=MAX_TOKENS,
                asked_max_tokens=body.get('max_tokens', body.get('max_completion_tokens')),
                thinking='disabled', request_sha256=_sha(data), incoming_request_sha256=_sha(incoming_raw),
                upstream_endpoint_sha256=_sha(endpoint.encode()), received_unix=received)
            status, raw, result, failure = 599, b'', None, 'upstream_transport_error'
            attempts = []
            state.enter()
            try:
                for number in range(1, MAX_RETRIES+2):
                    if state.is_draining():
                        status, failure = 503, 'local_proxy_draining_no_further_upstream_attempt'
                        break
                    queued = time.monotonic()
                    attempt_id = uuid.uuid4().hex
                    with state.gate:
                        if state.is_draining():
                            status, failure = 503, 'local_proxy_draining_no_upstream_attempt'
                            break
                        started = time.time()
                        tick = time.monotonic()
                        queue_seconds = round(tick-queued, 6)
                        record = dict(**common, attempt=number, attempt_id=attempt_id,
                                      t=started, started_unix=started, queue_seconds=queue_seconds,
                                      http_timeout_s=HTTP_TIMEOUT)
                        state.log(dict(event='attempt_started', **record), 'proxy-lifecycle.jsonl')
                        raw, result, status, failure = b'', None, 599, None
                        try:
                            request = urllib.request.Request(endpoint, data=data,
                                headers={'Content-Type':'application/json', 'Authorization':'Bearer '+key})
                            with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
                                status = response.status
                                raw = response.read()
                        except urllib.error.HTTPError as exc:
                            status = exc.code
                            failure = 'HTTP_'+str(status)
                            try:
                                raw = exc.read()
                            except Exception as read_error:
                                failure += '_body_'+type(read_error).__name__
                        except Exception as exc:
                            status = 599
                            failure = type(exc).__name__  # Exception messages can contain URLs; never persist them.
                        try:
                            result = json.loads(raw) if raw else None
                        except (ValueError, TypeError):
                            failure = failure or 'invalid_upstream_json'
                        usage, input_tokens, output_tokens, cached = _usage(result)
                        finish = None
                        if isinstance(result, dict):
                            choices = result.get('choices')
                            if isinstance(choices, list) and choices and isinstance(choices[0], dict):
                                finish = choices[0].get('finish_reason')
                        elapsed = round(time.monotonic()-tick, 6)
                        record.update(event='attempt_finished', finished_unix=time.time(), seconds=elapsed,
                            upstream_seconds=elapsed, status=status, error=failure, usage=usage,
                            prompt_tokens=input_tokens, completion_tokens=output_tokens, cached_tokens=cached,
                            finish=finish, response_sha256=_sha(raw),
                            retry_planned=status in RETRYABLE and number <= MAX_RETRIES and not state.is_draining())
                        # Persist costs before writing anything back to a potentially cancelled client.
                        state.log(record)
                    attempts.append(attempt_id)
                    if status not in RETRYABLE or number > MAX_RETRIES or state.is_draining():
                        break
                    time.sleep(min(2**(number-1), 2))
                delivery_status = status if status < 599 else 502
                try:
                    if status==200:
                        if not isinstance(result, dict) or not isinstance(result.get('choices'), list) or not result['choices']:
                            raise ValueError('invalid_upstream_chat_response')
                        if api=='messages':
                            self.respond(200, message_response(result))
                        else:
                            self.respond(200, raw)
                    else:
                        self.error(delivery_status, 'api_error', failure or 'Upstream request failed')
                    delivery = 'sent'
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, socket.timeout):
                    delivery = 'client_cancelled_or_disconnected'
                except (ValueError, TypeError, KeyError, IndexError):
                    delivery_status = 502
                    try:
                        self.error(502, 'api_error', 'Upstream returned an invalid plain text response')
                        delivery = 'upstream_protocol_error_sent'
                    except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, socket.timeout):
                        delivery = 'client_cancelled_or_disconnected'
                state.log(dict(event='client_delivery', **common, attempt_ids=attempts,
                    finished_unix=time.time(), upstream_final_status=status,
                    client_status=delivery_status, delivery=delivery), 'proxy-lifecycle.jsonl')
            finally:
                state.leave()

    class Server(ThreadingHTTPServer):
        daemon_threads = True
        allow_reuse_address = True

        def handle_error(self, request, client_address):
            # Base implementation prints tracebacks and can expose response fragments.
            state.log(dict(event='handler_internal_error', t=time.time()), 'proxy-lifecycle.jsonl')

    server = Server(('127.0.0.1', port), Handler)
    metadata = _process_record(os.getpid(), port, instance)
    _write_json(PIDFILE, metadata)
    _write_json(READY, dict(**metadata, ready_unix=time.time()))

    def terminate(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()
        if READY.exists():
            try:
                if json.loads(READY.read_text()).get('instance_id')==instance:
                    READY.unlink()
            except (ValueError, OSError):
                pass


def _start_ticks(pid):
    content = (Path('/proc')/str(pid)/'stat').read_text()
    return content[content.rfind(')')+2:].split()[19]


def _process_record(pid, port, instance):
    return dict(proxy=IDENTITY, pid=pid, port=port, instance_id=instance,
                start_ticks=_start_ticks(pid), script=str(HERE), started_unix=time.time())


def _owned(record):
    try:
        pid = int(record['pid'])
        if record.get('proxy')!=IDENTITY or record.get('script')!=str(HERE):
            return False
        if _start_ticks(pid)!=record['start_ticks']:
            return False
        parts = (Path('/proc')/str(pid)/'cmdline').read_bytes().split(b'\0')
        instance = str(record['instance_id']).encode()
        basic = str(HERE).encode() in parts and b'serve' in parts
        return basic and (instance in parts if b'--instance-id' in parts else True)
    except (OSError, ValueError, KeyError, IndexError):
        return False


def _health(port):
    try:
        with urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/health', timeout=2) as response:
            result = json.load(response)
        return result if isinstance(result, dict) else None
    except Exception:
        return None


def start(port=PORT):
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT/'proxy-control.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if PIDFILE.exists():
            record = json.loads(PIDFILE.read_text())
            if _owned(record):
                health = _health(record['port'])
                return dict(status='already_running', **(health or record))
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', port))==0:
                raise RuntimeError('Requested localhost port is occupied by another process; it was left untouched')
        instance = uuid.uuid4().hex
        args = [sys.executable, str(HERE), 'serve', '--port', str(port), '--instance-id', instance]
        with (ROOT/'proxy.log').open('a') as log:
            process = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        record = _process_record(process.pid, port, instance)
        _write_json(PIDFILE, record)
        deadline = time.monotonic()+15
        while time.monotonic()<deadline:
            health = _health(port)
            if health and health.get('proxy')==IDENTITY and health.get('instance_id')==instance:
                return dict(status='started', **health)
            if process.poll() is not None:
                raise RuntimeError('Proxy startup failed; inspect the sanitized new-root proxy.log')
            time.sleep(0.1)
        raise RuntimeError('Proxy has not become ready; inspect its owned process and new-root proxy.log')


def stop(force=False):
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT/'proxy-control.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not PIDFILE.exists():
            return dict(status='not_running')
        record = json.loads(PIDFILE.read_text())
        if not _owned(record):
            return dict(status='not_owned_or_already_stopped', killed=False)
        health = _health(record['port'])
        if health and health.get('instance_id')==record['instance_id'] and not force:
            request = urllib.request.Request('http://127.0.0.1:'+str(record['port'])+'/_control/drain/'+record['instance_id'],
                data=b'{}', headers={'Content-Type':'application/json'})
            with urllib.request.urlopen(request, timeout=2) as response:
                response.read()
            # New work and retries are now refused. Already-started attempts finish
            # and persist usage before active reaches zero, including cancelled SDK clients.
            drain_deadline = time.monotonic()+HTTP_TIMEOUT+30
            while time.monotonic()<drain_deadline:
                health = _health(record['port'])
                if not _owned(record) or (health and health.get('active_requests',0)==0):
                    break
                time.sleep(0.5)
            if _owned(record) and (not health or health.get('active_requests',0)>0):
                return dict(status='still_draining', killed=False, active_requests=health.get('active_requests') if health else None,
                            message='An upstream operation remains active; preserved its process to avoid dropping usage. Call stop again after it finishes.')
        pid = int(record['pid'])
        if not _owned(record):
            return dict(status='ownership_changed', killed=False)
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic()+10
        while time.monotonic()<deadline and _owned(record):
            time.sleep(0.1)
        if _owned(record):
            os.kill(pid, signal.SIGKILL)
        return dict(status='stopped', pid=pid, force=force, killed=True)


def main():
    parser = argparse.ArgumentParser(description='Isolated benchmark plain-text proxy')
    sub = parser.add_subparsers(dest='command', required=True)
    begin = sub.add_parser('start')
    begin.add_argument('--port', type=int, default=PORT)
    running = sub.add_parser('serve')
    running.add_argument('--port', type=int, default=PORT)
    running.add_argument('--instance-id', default=None)
    end = sub.add_parser('stop')
    end.add_argument('--force', action='store_true')
    args = parser.parse_args()
    try:
        if args.command=='serve':
            serve(args.port, args.instance_id)
            return
        result = start(args.port) if args.command=='start' else stop(args.force)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as exc:
        # Never print exception text: upstream/client libraries may embed credentials or request snippets.
        print(json.dumps(dict(status='error', error_type=type(exc).__name__,
                              message='Proxy command failed; inspect sanitized new-root state/logs.')))
        raise SystemExit(1)


if __name__=='__main__':
    main()
