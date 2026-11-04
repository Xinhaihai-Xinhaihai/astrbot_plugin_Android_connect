"""Small, bounded SQLite store. Only accessed from the asyncio event-loop thread."""

import json
import sqlite3
import time
from pathlib import Path


class Store:
    def __init__(self, path: Path):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, session TEXT NOT NULL, prompt TEXT NOT NULL,
                control INTEGER NOT NULL, phone INTEGER NOT NULL, device TEXT NOT NULL,
                state TEXT NOT NULL, result TEXT NOT NULL DEFAULT '',
                error TEXT NOT NULL DEFAULT '', created REAL NOT NULL, updated REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS jobs_session ON jobs(session, created);
            CREATE TABLE IF NOT EXISTS audit (
                id INTEGER PRIMARY KEY, job_id TEXT, action TEXT NOT NULL,
                detail TEXT NOT NULL, created REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS job_runs (
                job_id TEXT NOT NULL, number INTEGER NOT NULL, state TEXT NOT NULL,
                result TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
                started REAL NOT NULL, finished REAL,
                PRIMARY KEY (job_id, number)
            );
        """)
        columns = {row["name"] for row in self.db.execute("PRAGMA table_info(jobs)")}
        for name, definition in (
            ("repeat_count", "INTEGER NOT NULL DEFAULT 1"),
            ("runs_started", "INTEGER NOT NULL DEFAULT 0"),
            ("runs_completed", "INTEGER NOT NULL DEFAULT 0"),
            ("next_run_at", "REAL NOT NULL DEFAULT 0"),
        ):
            if name not in columns:
                self.db.execute(f"ALTER TABLE jobs ADD COLUMN {name} {definition}")
        if "runs_completed" not in columns:
            self.db.execute(
                "UPDATE jobs SET runs_started=1,runs_completed=1 WHERE state='completed'"
            )
        self.db.execute(
            "UPDATE job_runs SET state='interrupted',error=?,finished=? WHERE state='running'",
            ("电脑端已重启，本轮未自动重试。", time.time()),
        )
        self.db.execute(
            "UPDATE jobs SET state='interrupted', error=?, updated=?, next_run_at=0 "
            "WHERE state IN ('queued','running','waiting')",
            ("电脑端已重启，任务未自动重试，请确认进度后重新提交。", time.time()),
        )
        self.db.commit()

    def get(self, job_id):
        row = self.db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return dict(row) if row else None

    def create(self, job_id, session, prompt, control, phone=False, device="", repeat_count=1):
        now = time.time()
        self.db.execute(
            "INSERT INTO jobs(id,session,prompt,control,phone,device,repeat_count,state,created,updated) "
            "VALUES (?,?,?,?,?,?,?,'queued',?,?)",
            (job_id, session, prompt, control, phone, device, repeat_count, now, now),
        )
        self.db.commit()
        return self.get(job_id)

    def update(self, job_id, state, result=None, error=None, next_run_at=0):
        self.db.execute(
            "UPDATE jobs SET state=?,result=COALESCE(?,result),error=COALESCE(?,error),"
            "next_run_at=?,updated=? WHERE id=?",
            (state, result[:100000] if result is not None else None,
             error[:2000] if error is not None else None, next_run_at, time.time(), job_id),
        )
        self.db.commit()

    def active(self):
        return [dict(row) for row in self.db.execute(
            "SELECT * FROM jobs WHERE state IN ('queued','running','waiting') ORDER BY created"
        )]

    def begin_run(self, job_id):
        self.db.execute("UPDATE jobs SET runs_started=runs_started+1 WHERE id=?", (job_id,))
        job = self.get(job_id)
        self.db.execute(
            "INSERT INTO job_runs(job_id,number,state,started) VALUES (?,?,'running',?)",
            (job_id, job["runs_started"], time.time()),
        )
        self.db.commit()
        return job

    def finish_run(self, job_id, number, state, result="", error=""):
        changed = self.db.execute(
            "UPDATE job_runs SET state=?,result=?,error=?,finished=? "
            "WHERE job_id=? AND number=? AND state='running'",
            (state, result[:100000], error[:2000], time.time(), job_id, number),
        ).rowcount
        if changed and state == "completed":
            self.db.execute(
                "UPDATE jobs SET runs_completed=runs_completed+1,result=?,error='',updated=? WHERE id=?",
                (result[:100000], time.time(), job_id),
            )
        self.db.execute(
            "DELETE FROM job_runs WHERE job_id=? AND number NOT IN "
            "(SELECT number FROM job_runs WHERE job_id=? ORDER BY number DESC LIMIT 100)",
            (job_id, job_id),
        )
        self.db.commit()

    def runs(self, job_id):
        return [dict(row) for row in self.db.execute(
            "SELECT job_id,number,state,SUBSTR(result,1,8000) AS result,error,started,finished "
            "FROM job_runs WHERE job_id=? ORDER BY number DESC LIMIT 20", (job_id,)
        )]

    def list(self, session=None, limit=60):
        if session:
            rows = self.db.execute(
                "SELECT * FROM jobs WHERE session=? ORDER BY created DESC LIMIT ?",
                (session, limit),
            )
        else:
            rows = self.db.execute("SELECT * FROM jobs ORDER BY created DESC LIMIT ?", (limit,))
        return [dict(row) for row in rows]

    def history(self, session, turns):
        rows = self.db.execute(
            "SELECT prompt,result FROM jobs WHERE session=? AND state='completed' "
            "ORDER BY created DESC LIMIT ?",
            (session, turns),
        ).fetchall()
        return [
            message
            for row in reversed(rows)
            for message in (
                {"role": "user", "content": row["prompt"]},
                {"role": "assistant", "content": row["result"]},
            )
        ]

    def audit(self, action, job_id=None, **detail):
        self.db.execute(
            "INSERT INTO audit(job_id,action,detail,created) VALUES (?,?,?,?)",
            (job_id, action, json.dumps(detail, ensure_ascii=False), time.time()),
        )
        self.db.commit()

    def events(self, job_id):
        return [
            dict(row)
            for row in self.db.execute(
                "SELECT * FROM audit WHERE job_id=? ORDER BY id DESC LIMIT 100", (job_id,)
            )
        ]

    def prune(self, days):
        cutoff = time.time() - days * 86400
        self.db.execute(
            "DELETE FROM jobs WHERE updated < ? AND state NOT IN ('queued','running','waiting')", (cutoff,)
        )
        self.db.execute("DELETE FROM job_runs WHERE job_id NOT IN (SELECT id FROM jobs)")
        self.db.execute("DELETE FROM audit WHERE created < ?", (cutoff,))
        self.db.commit()

    def close(self):
        self.db.close()
