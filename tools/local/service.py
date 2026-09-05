#!/usr/bin/env python3
"""Loopback-only Moto S3 fixture with deterministic request fault/latency controls."""
import argparse
import json
import threading
import time
from collections import Counter
from urllib.parse import parse_qs
import importlib.metadata
from moto.server import create_backend_app
from werkzeug.serving import make_server, WSGIRequestHandler

class QuietHandler(WSGIRequestHandler):
    def log(self, *args, **kwargs):
        pass  # No object paths, request signatures, or payloads in logs.

class Fixture:
    def __init__(self):
        self.app = create_backend_app('s3')
        self.lock = threading.Lock()
        self.reset({})

    def reset(self, config):
        self.counts = Counter()
        self.failures = dict(config.get('failures', {}))
        self.latency_ms = max(0, min(1000, config.get('latency_ms', 0)))
        self.bandwidth_mib_s = max(0, config.get('bandwidth_mib_s', 0))
        self.lose_complete_response = bool(config.get('lose_complete_response', False))

    def __call__(self, environ, start_response):
        path, method = environ['PATH_INFO'], environ['REQUEST_METHOD']
        if path == '/__s3os_health':
            return self.json_response(start_response, {'fixture': 's3os-local-only-v1', 'moto': importlib.metadata.version('moto')})
        if path == '/__s3os_control':
            with self.lock:
                if method == 'POST':
                    length = int(environ.get('CONTENT_LENGTH') or 0)
                    if length > 4096:
                        return self.json_response(start_response, {'error': 'control too large'}, '400 Bad Request')
                    self.reset(json.loads(environ['wsgi.input'].read(length) or '{}'))
                state = {'counts': dict(self.counts), 'remaining_failures': dict(self.failures)}
            return self.json_response(start_response, state)
        query = parse_qs(environ.get('QUERY_STRING', ''), keep_blank_values=True)
        if 'uploadId' in query:
            op = {'PUT': 'part', 'POST': 'complete', 'DELETE': 'abort', 'GET': 'list_parts'}.get(method, 'other')
        elif 'uploads' in query:
            op = 'create' if method == 'POST' else 'list_uploads'
        elif len(path.strip('/').split('/')) > 1:
            op = {'PUT': 'put', 'GET': 'get', 'HEAD': 'head', 'DELETE': 'delete'}.get(method, 'other')
        else:
            op = 'bucket'
        with self.lock:
            self.counts[op] += 1
            fail = self.failures.get(op, 0) > 0
            if fail:
                self.failures[op] -= 1
            delay = self.latency_ms / 1000
            if self.bandwidth_mib_s and op in ('part', 'put'):
                delay += int(environ.get('CONTENT_LENGTH') or 0) / (self.bandwidth_mib_s * 1024 * 1024)
            lose = self.lose_complete_response and op == 'complete'
            if lose:
                self.lose_complete_response = False
        if delay:
            time.sleep(delay)
        if fail:
            return self.failure(start_response)
        if lose:
            # Execute completion, discard its successful response, then simulate
            # loss of acknowledgement. This models ambiguity, not atomic rollback.
            result = self.app(environ, lambda *args: None)
            try:
                for _ in result:
                    pass
            finally:
                if hasattr(result, 'close'):
                    result.close()
            return self.failure(start_response)
        return self.app(environ, start_response)

    @staticmethod
    def failure(start_response):
        body = b'<Error><Code>SlowDown</Code><Message>Injected local fixture failure</Message></Error>'
        start_response('503 Service Unavailable', [('Content-Type', 'application/xml'), ('Content-Length', str(len(body)))])
        return [body]

    @staticmethod
    def json_response(start_response, data, status='200 OK'):
        body = json.dumps(data).encode()
        start_response(status, [('Content-Type', 'application/json'), ('Content-Length', str(len(body)))])
        return [body]

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--ready-file', required=True)
    args = parser.parse_args()
    server = make_server('127.0.0.1', args.port, Fixture(), threaded=True, request_handler=QuietHandler)
    from pathlib import Path
    Path(args.ready_file).write_text(json.dumps({'port': server.server_port}))
    server.serve_forever()
