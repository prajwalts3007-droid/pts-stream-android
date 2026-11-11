import hashlib
import http.client
import json
import os
import sqlite3
import tempfile
import threading
import time
import unittest
from native_companion import Companion, Denied, Server, ClosingConnection
from pathlib import Path

class CompanionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.production = os.path.join(self.temp.name,'library.db')
        with sqlite3.connect(self.production, factory=ClosingConnection) as db:
            db.executescript('''CREATE TABLE users(id INTEGER PRIMARY KEY,role TEXT,is_active INTEGER);
              CREATE TABLE sessions(user_id INTEGER,token_hash TEXT,csrf_token TEXT,expires_at TEXT);
              CREATE TABLE stream_titles(id INTEGER PRIMARY KEY);
              CREATE TABLE stream_episodes(id INTEGER PRIMARY KEY,title_id INTEGER);
              CREATE TABLE jobs(job_id TEXT PRIMARY KEY,stage TEXT,attempt TEXT,finished_at TEXT);
              INSERT INTO users VALUES(1,'admin',1),(2,'user',1),(3,'admin',0);
              INSERT INTO stream_titles VALUES(100),(200);
              INSERT INTO stream_episodes VALUES(101,100),(201,200);
              INSERT INTO jobs VALUES('old','done','a','2026-01-01');''')
            for owner in range(1,4):
                db.execute("INSERT INTO sessions VALUES(?,?,?,datetime('now','+1 day'))",(owner,hashlib.sha256(('fixture-'+str(owner)).encode()).hexdigest(),'fixture-csrf'))
        self.app = Companion(self.production,os.path.join(self.temp.name,'native.db'))

    def tearDown(self): self.temp.cleanup()

    def record(self, **extra):
        return dict(title_id=100,episode_id=101,position=30,duration=120,completed=False,changed_at=int(time.time()*1000),event_id='fixture-event-1',**extra)

    def test_authentication_is_owner_scoped_and_requires_session_csrf(self):
        self.assertEqual((1,'admin'),self.app.authenticate('pts_session=fixture-1','fixture-csrf',True))
        for cookie,csrf,unsafe in [('', '',False),('pts_session=fixture-3','',False),('pts_session=fixture-1','wrong',True)]:
            with self.assertRaises(Denied):self.app.authenticate(cookie,csrf,unsafe)
        with sqlite3.connect(self.production, factory=ClosingConnection) as db:db.execute("UPDATE sessions SET expires_at='2000-01-01' WHERE user_id=1")
        with self.assertRaises(Denied):self.app.authenticate('pts_session=fixture-1')

    def test_progress_isolation_and_production_is_never_modified(self):
        before = Path(self.production).read_bytes()
        self.app.put(1,[self.record()])
        self.assertEqual([],self.app.progress(2))
        self.assertEqual(30,self.app.progress(1)[0]['position'])
        self.assertEqual(before,Path(self.production).read_bytes())

    def test_episode_ownership_validation_is_atomic(self):
        bad=self.record();bad['episode_id']=201
        with self.assertRaises(Denied):self.app.put(1,[self.record(),bad])
        self.assertEqual([],self.app.progress(1))

    def test_older_offline_update_and_duplicate_cannot_clobber_newer_completion(self):
        first=self.record();self.app.put(1,[first])
        newer=dict(first,changed_at=first['changed_at']+1,event_id='fixture-event-2',completed=True,position=120)
        self.app.put(1,[newer]);self.app.put(1,[first]);self.app.put(1,[newer])
        self.assertEqual(1,len(self.app.progress(1)))
        self.assertTrue(self.app.progress(1)[0]['completed'])
        self.assertEqual(120,self.app.progress(1)[0]['position'])

    def test_invalid_or_future_records_are_rejected(self):
        for patch in [dict(position=-1),dict(changed_at=int(time.time()*1000)+900000),dict(event_id=''),dict(title_id=999),dict(episode_id=201)]:
            value=self.record();value.update(patch)
            with self.assertRaises(Denied):self.app.put(1,[value])

    def test_job_alerts_baseline_deduplication_and_new_attempts(self):
        self.app.scan_jobs();self.assertEqual([],self.app.events(0)['events'])
        with sqlite3.connect(self.production, factory=ClosingConnection) as db:db.execute("INSERT INTO jobs VALUES('new','queued','a',NULL)")
        self.app.scan_jobs()
        with sqlite3.connect(self.production, factory=ClosingConnection) as db:db.execute("UPDATE jobs SET stage='failed' WHERE job_id='new'")
        self.app.scan_jobs();self.app.scan_jobs()
        events=self.app.events(0)['events'];self.assertEqual(1,len(events));self.assertEqual('failed',events[0]['stage'])
        self.assertNotIn('download_url',events[0]);self.assertNotIn('error',events[0])
        self.assertEqual([],self.app.events(-1)['events'])
        with sqlite3.connect(self.production, factory=ClosingConnection) as db:db.execute("UPDATE jobs SET attempt='b',stage='done' WHERE job_id='new'")
        self.app.scan_jobs();self.assertEqual(2,len(self.app.events(0)['events']))

    def test_http_auth_roles_csrf_and_bounds(self):
        server=Server(('127.0.0.1',0),self.app)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        def request(method,path,cookie='',data=None,csrf=''):
            client=http.client.HTTPConnection(*server.server_address,timeout=5)
            body=json.dumps(data).encode() if data is not None else None
            client.request(method,path,body,{'Cookie':cookie,'X-CSRF-Token':csrf,'Content-Type':'application/json'})
            response=client.getresponse();result=(response.status,json.loads(response.read()));client.close();return result
        try:
            self.assertEqual(401,request('GET','/api/v1/native/progress')[0])
            self.assertEqual(403,request('GET','/api/v1/native/events','pts_session=fixture-2')[0])
            self.assertEqual(403,request('PUT','/api/v1/native/progress','pts_session=fixture-1',{'records':[self.record()]})[0])
            self.assertEqual(200,request('PUT','/api/v1/native/progress','pts_session=fixture-1',{'records':[self.record()]},'fixture-csrf')[0])
            self.assertEqual([],request('GET','/api/v1/native/progress','pts_session=fixture-2')[1]['records'])
        finally:server.shutdown();server.server_close();thread.join()

    def test_long_poll_revalidates_revoked_administrator_before_delivery(self):
        self.app.scan_jobs()
        server=Server(('127.0.0.1',0),self.app)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        result=[]
        def poll():
            client=http.client.HTTPConnection(*server.server_address,timeout=5)
            client.request('GET','/api/v1/native/events?cursor=0',headers={'Cookie':'pts_session=fixture-1'})
            response=client.getresponse();result.append(response.status);response.read();client.close()
        polling=threading.Thread(target=poll);polling.start()
        try:
            time.sleep(.15)
            with sqlite3.connect(self.production,factory=ClosingConnection) as db:db.execute("UPDATE users SET role='user' WHERE id=1")
            with self.app.changed:self.app.changed.notify_all()
            polling.join(3)
            self.assertEqual([403],result)
        finally:server.shutdown();server.server_close();thread.join();polling.join(5)

if __name__ == '__main__':unittest.main()
