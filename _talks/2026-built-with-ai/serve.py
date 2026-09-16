#!/usr/bin/env python3
"""Serve the deck for local editing.

    python3 serve.py [port]      # default port 8000

Sends every file with Cache-Control: no-store, so a reload always shows the
current CSS, scripts and images. The page itself watches the deck files and
reloads on change (see index.html); plain `python3 -m http.server` works for
that too, but may serve stale CSS and scripts from the browser cache.
"""
import http.server
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def log_message(self, format, *args):
        pass  # the page polls every second; keep the terminal quiet


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    print(f'Serving {ROOT} on http://localhost:{port}/?slides=slides-cinderella.md')
    http.server.ThreadingHTTPServer(('', port), Handler).serve_forever()
