#!/usr/bin/env python3
"""SSE test server for SSE Parser Burp extension testing."""

import http.server
import json
import random
import time

PORT = 7788

HTML = """<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>SSE Test Server</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: system-ui, sans-serif; background: #f5f6fa; color: #1a1a2e; padding: 24px; }
    h1 { font-size: 22px; margin-bottom: 6px; }
    p  { color: #555; margin-bottom: 20px; font-size: 14px; }
    code { background: #e8e8f0; padding: 2px 6px; border-radius: 3px; font-family: monospace; }
    #log { background: white; border: 1px solid #dde; border-radius: 8px; padding: 0; max-height: 480px; overflow-y: auto; }
    .row { display: flex; align-items: baseline; gap: 10px; padding: 8px 14px; border-bottom: 1px solid #eef; font-size: 13px; }
    .row:last-child { border-bottom: none; }
    .ts  { color: #999; font-size: 11px; white-space: nowrap; font-family: monospace; }
    .tag { font-size: 11px; font-weight: 600; padding: 1px 7px; border-radius: 20px; white-space: nowrap; }
    .tag-message  { background: #e3f0ff; color: #1a5fc8; }
    .tag-heartbeat{ background: #e6ffe6; color: #1a7a1a; }
    .tag-alert    { background: #fff3e0; color: #c06000; }
    .tag-metrics  { background: #f0e8ff; color: #6a1ab8; }
    .tag-error    { background: #ffe8e8; color: #c01a1a; }
    .data { font-family: monospace; font-size: 12px; color: #333; word-break: break-all; }
    #status { margin-bottom: 12px; font-size: 13px; }
    #status.ok  { color: #1a7a1a; }
    #status.err { color: #c01a1a; }
  </style>
</head>
<body>
  <h1>SSE Test Server</h1>
  <p>Events stream from <code>/events</code> every 2 seconds. Point your Burp-proxied browser here to test the SSE Parser extension.</p>
  <div id="status" class="err">Connecting…</div>
  <div id="log"></div>
  <script>
    const log = document.getElementById('log');
    const status = document.getElementById('status');
    const es = new EventSource('/events');

    function addRow(type, data) {
      const d = document.createElement('div');
      d.className = 'row';
      const now = new Date().toLocaleTimeString('en-GB', {hour12:false});
      d.innerHTML =
        '<span class="ts">' + now + '</span>' +
        '<span class="tag tag-' + type + '">' + type + '</span>' +
        '<span class="data">' + data.replace(/</g,'&lt;') + '</span>';
      log.prepend(d);
      if (log.children.length > 200) log.removeChild(log.lastChild);
    }

    es.onopen = () => { status.textContent = 'Connected — events arriving live'; status.className = 'ok'; };
    es.onerror = () => { status.textContent = 'Connection lost — reconnecting…'; status.className = 'err'; };
    ['message','heartbeat','alert','metrics'].forEach(t =>
      es.addEventListener(t, e => addRow(t, e.data)));
  </script>
</body>
</html>"""


class SSEHandler(http.server.BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path == '/':
            body = HTML.encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        elif self.path == '/events':
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Connection', 'keep-alive')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()

            types   = ['message', 'heartbeat', 'alert', 'metrics']
            users   = ['alice', 'bob', 'charlie', 'diana', 'eve']
            actions = ['login', 'logout', 'purchase', 'view', 'click', 'search']
            levels  = ['info', 'warn', 'error']

            seq = 0
            try:
                while True:
                    seq += 1
                    t = types[seq % len(types)]

                    if t == 'message':
                        data = json.dumps({
                            'user': random.choice(users),
                            'action': random.choice(actions),
                            'value': random.randint(1, 9999),
                            'session': f's{random.randint(1000,9999)}'
                        })
                    elif t == 'heartbeat':
                        data = json.dumps({'ts': int(time.time()), 'seq': seq, 'ok': True})
                    elif t == 'alert':
                        data = json.dumps({
                            'level': random.choice(levels),
                            'msg': f'Event #{seq} triggered on node-{random.randint(1,8)}',
                            'code': random.randint(1000, 9999)
                        })
                    else:  # metrics
                        data = json.dumps({
                            'cpu': round(random.uniform(5, 95), 1),
                            'mem': round(random.uniform(20, 80), 1),
                            'rps': random.randint(50, 800),
                            'lat_ms': random.randint(1, 250)
                        })

                    payload = f'event: {t}\nid: {seq}\ndata: {data}\n\n'
                    self.wfile.write(payload.encode())
                    self.wfile.flush()
                    time.sleep(2)

            except (BrokenPipeError, ConnectionResetError, OSError):
                pass

        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass  # quiet


if __name__ == '__main__':
    server = http.server.HTTPServer(('0.0.0.0', PORT), SSEHandler)
    print(f'SSE test server → http://localhost:{PORT}')
    server.serve_forever()
