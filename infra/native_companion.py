#!/usr/bin/env python3
"""Account-scoped native progress + authenticated job-event long polling.

Production library/session/job DB is READ ONLY. Own state contains no media,
passwords, session tokens, provider credentials or signing keys. Standard library.
"""
import hashlib
import hmac
import http.cookies
import json
import os
import re
import sqlite3
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PREFIX = '/api/v1/native/'

class ClosingConnection(sqlite3.Connection):
    def __exit__(self, *args):
        try: return super().__exit__(*args)
        finally: self.close()

class Denied(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message

class Companion:
    def __init__(self, library, state):
        self.library, self.state = library, state
        self.changed = threading.Condition()
        self.stop = threading.Event()
        with self.db() as db:
            db.executescript('''PRAGMA journal_mode=WAL;
              CREATE TABLE IF NOT EXISTS progress (
                owner INTEGER NOT NULL,title_id INTEGER NOT NULL,episode_id INTEGER NOT NULL,
                position INTEGER NOT NULL,duration INTEGER NOT NULL,completed INTEGER NOT NULL,
                changed_at INTEGER NOT NULL,event_id TEXT NOT NULL,
                PRIMARY KEY(owner,title_id,episode_id));
              CREATE TABLE IF NOT EXISTS observed_jobs (job_id TEXT PRIMARY KEY,signature TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT NOT NULL,stage TEXT NOT NULL,label TEXT NOT NULL,created INTEGER NOT NULL);
              CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL);''')

    def db(self):
        db = sqlite3.connect(self.state, timeout=5, factory=ClosingConnection)
        db.row_factory = sqlite3.Row
        return db

    def production(self):
        db = sqlite3.connect('file:' + urllib.parse.quote(self.library, safe='/') + '?mode=ro', uri=True, timeout=5, factory=ClosingConnection)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA query_only=ON')
        return db

    def authenticate(self, cookie, csrf='', unsafe=False):
        try:
            cookies = http.cookies.SimpleCookie(); cookies.load(cookie)
            token = cookies['pts_session'].value
        except (KeyError, http.cookies.CookieError):
            raise Denied(401, 'Sign in again')
        if not token or len(token) > 512:
            raise Denied(401, 'Invalid session')
        with self.production() as db:
            row = db.execute('''SELECT s.user_id,s.csrf_token,u.role,u.is_active,
              julianday(s.expires_at)>julianday('now') AS valid
              FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=?''',
              (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
        if not row or not row['valid'] or not row['is_active']:
            raise Denied(401, 'Session expired or disabled')
        if unsafe and (not csrf or not hmac.compare_digest(str(row['csrf_token']), csrf)):
            raise Denied(403, 'CSRF validation failed')
        return int(row['user_id']), row['role']

    def progress(self, owner):
        with self.db() as db:
            return [dict(row,completed=bool(row['completed'])) for row in db.execute('SELECT title_id,episode_id,position,duration,completed,changed_at,event_id FROM progress WHERE owner=? ORDER BY changed_at DESC LIMIT 20000', (owner,))]

    def put(self, owner, values):
        if not isinstance(values, list) or not 1 <= len(values) <= 100:
            raise Denied(400, 'Send 1–100 progress records')
        parsed = []
        now = int(time.time() * 1000)
        with self.production() as library:
            for value in values:
                try:
                    title, episode, position, duration, changed = [int(value[key]) for key in ['title_id', 'episode_id', 'position', 'duration', 'changed_at']]
                    event = value['event_id']; completed = value['completed']
                    assert isinstance(event, str) and re.fullmatch(r'[A-Za-z0-9-]{8,80}',event)
                    assert isinstance(completed, bool) or completed in (0, 1)
                    assert 0 < title < 2**63 and 0 <= episode < 2**63 and 0 <= position <= 7*86400 and 0 <= duration <= 7*86400
                    assert 0 < changed <= now + 300000
                except (KeyError, ValueError, TypeError, AssertionError):
                    raise Denied(400, 'Invalid progress record or device clock')
                if not library.execute('SELECT 1 FROM stream_titles WHERE id=?', (title,)).fetchone():
                    raise Denied(404, 'Title no longer exists')
                if episode and not library.execute('SELECT 1 FROM stream_episodes WHERE id=? AND title_id=?', (episode,title)).fetchone():
                    raise Denied(400, 'Episode does not belong to this title')
                parsed.append((owner,title,episode,position,duration,int(bool(completed)),changed,event))
        with self.db() as db:
            for value in parsed:
                db.execute('''INSERT INTO progress VALUES(?,?,?,?,?,?,?,?)
                  ON CONFLICT(owner,title_id,episode_id) DO UPDATE SET
                    position=excluded.position,duration=excluded.duration,completed=excluded.completed,
                    changed_at=excluded.changed_at,event_id=excluded.event_id
                  WHERE (excluded.changed_at,excluded.event_id)>(progress.changed_at,progress.event_id)''',value)
        return self.progress(owner)

    def scan_jobs(self):
        # A single shared scan, not one DB scan per connected phone. No retry,
        # delete, dispatch or pipeline mutation is possible through this service.
        with self.production() as library:
            jobs = library.execute('SELECT job_id,stage,attempt,finished_at FROM jobs').fetchall()
        with self.db() as db:
            initialized = db.execute("SELECT 1 FROM metadata WHERE key='jobs_initialized'").fetchone()
            added = False
            for job in jobs:
                signature = json.dumps([job['stage'],job['attempt'],job['finished_at']])
                prior = db.execute('SELECT signature FROM observed_jobs WHERE job_id=?',(job['job_id'],)).fetchone()
                if initialized and (not prior or prior[0] != signature) and job['stage'] in ('done','failed'):
                    # Never expose source URLs, failure details or private paths in notifications.
                    label = 'Library job ' + str(job['job_id'])[:12]
                    db.execute('INSERT INTO events(job_id,stage,label,created) VALUES(?,?,?,?)',(job['job_id'],job['stage'],label,int(time.time())))
                    added = True
                db.execute('INSERT INTO observed_jobs VALUES(?,?) ON CONFLICT(job_id) DO UPDATE SET signature=excluded.signature',(job['job_id'],signature))
            db.execute("INSERT OR IGNORE INTO metadata VALUES('jobs_initialized','1')")
            db.execute('DELETE FROM events WHERE created<?',(int(time.time())-7*86400,))
        if added:
            with self.changed: self.changed.notify_all()

    def events(self, cursor):
        with self.db() as db:
            latest = db.execute("SELECT COALESCE(MAX(seq),0) FROM sqlite_sequence WHERE name='events'").fetchone()[0]
            if cursor < 0 or cursor > latest: return {'cursor': latest, 'events': []}  # Establish baseline, no historical flood; recover from server restore.
            rows = [dict(r) for r in db.execute('SELECT id,job_id,stage,label,created FROM events WHERE id>? ORDER BY id LIMIT 100',(cursor,))]
            return {'cursor': rows[-1]['id'] if rows else max(cursor,latest), 'events': rows}

    def run(self):
        while not self.stop.is_set():
            try: self.scan_jobs()
            except sqlite3.Error: pass  # Fail closed; no production DB fallback write.
            self.stop.wait(5)

class Server(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, app):
        self.app = app
        self.slots = threading.BoundedSemaphore(32)
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request); return
        try: super().process_request(request, client_address)
        except Exception:
            self.slots.release(); raise

    def process_request_thread(self, request, client_address):
        try: super().process_request_thread(request, client_address)
        finally: self.slots.release()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass  # Never log cookies, queries or progress payloads.
    def setup(self):
        super().setup(); self.connection.settimeout(35)

    def reply(self, status, body):
        data = json.dumps(body,separators=(',',':')).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/json')
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers(); self.wfile.write(data)

    def do_GET(self): self.handle_api(False)
    def do_PUT(self): self.handle_api(True)
    def do_POST(self): self.reply(405,{'error':'Method not allowed'})

    def handle_api(self, unsafe):
        try:
            url = urllib.parse.urlsplit(self.path)
            if url.path == '/health' and not unsafe:
                self.reply(200,{'ok':True,'version':1}); return
            if url.path not in (PREFIX+'progress',PREFIX+'events'):
                raise Denied(404,'Not found')
            app = self.server.app
            cookie, csrf = self.headers.get('Cookie',''), self.headers.get('X-CSRF-Token','')
            owner, role = app.authenticate(cookie,csrf,unsafe)
            if url.path.endswith('/progress'):
                if unsafe:
                    length = int(self.headers.get('Content-Length','0'))
                    if not 0 < length <= 65536: raise Denied(413,'Progress payload too large')
                    if self.headers.get_content_type() != 'application/json': raise Denied(415,'JSON required')
                    data = json.loads(self.rfile.read(length))
                    if not isinstance(data,dict): raise Denied(400,'JSON object required')
                    records = app.put(owner,data.get('records'))
                else: records = app.progress(owner)
                self.reply(200,{'records':records,'server_time':int(time.time()*1000)})
            else:
                if unsafe: raise Denied(405,'Read-only events')
                if role != 'admin': raise Denied(403,'Administrator access required')
                cursor = int(urllib.parse.parse_qs(url.query).get('cursor',['-1'])[0])
                if not -1 <= cursor < 2**63: raise Denied(400,'Invalid cursor')
                result = app.events(cursor)
                if cursor >= 0 and not result['events']:
                    with app.changed: app.changed.wait(20)
                    # Revalidate after long polling: revocation/role changes are respected.
                    current, current_role = app.authenticate(cookie)
                    if current != owner or current_role != 'admin': raise Denied(403,'Access changed')
                    result = app.events(cursor)
                self.reply(200,result)
        except Denied as e: self.reply(e.status,{'error':e.message})
        except (ValueError,TypeError,KeyError,json.JSONDecodeError): self.reply(400,{'error':'Invalid request'})
        except (sqlite3.Error,OSError):
            try: self.reply(503,{'error':'Native sync temporarily unavailable'})
            except OSError: pass

if __name__ == '__main__':
    app = Companion(os.environ.get('PTS_LIBRARY_DB','/DATA/PTSlibrary/ptslibrary.db'),os.environ.get('PTS_NATIVE_DB','/var/lib/pts-native/state.db'))
    app.scan_jobs()
    threading.Thread(target=app.run,daemon=True).start()
    Server(('127.0.0.1',8791),app).serve_forever()
