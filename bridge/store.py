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
        """)
        self.db.execute(
            "UPDATE jobs SET state='interrupted', error=?, updated=? "
            "WHERE state IN ('queued','running')",
            ("电脑端已重启，任务未自动重试，请确认进度后重新提交。", time.time()),
        )
        self.db.commit()

    def get(self, job_id):
        row = self.db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return dict(row) if row else None

    def create(self, job_id, session, prompt, control, phone=False, device=""):
        now = time.time()
        self.db.execute(
            "INSERT INTO jobs(id,session,prompt,control,phone,device,state,created,updated) "
            "VALUES (?,?,?,?,?,?,'queued',?,?)",
            (job_id, session, prompt, control, phone, device, now, now),
        )
        self.db.commit()
        return self.get(job_id)

    def update(self, job_id, state, result="", error=""):
        self.db.execute(
            "UPDATE jobs SET state=?,result=?,error=?,updated=? WHERE id=?",
            (state, result[:100000], error[:2000], time.time(), job_id),
        )
        self.db.commit()

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
            "DELETE FROM jobs WHERE updated < ? AND state NOT IN ('queued','running')", (cutoff,)
        )
        self.db.execute("DELETE FROM audit WHERE created < ?", (cutoff,))
        self.db.commit()

    def close(self):
        self.db.close()
