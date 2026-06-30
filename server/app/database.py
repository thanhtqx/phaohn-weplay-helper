import sqlite3
import time
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent.parent / "data" / "phaohn.db"


def get_conn() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def _migrate_word_pairs(conn: sqlite3.Connection) -> None:
    cols = {row[1] for row in conn.execute("PRAGMA table_info(word_pairs)").fetchall()}
    if "user_id" not in cols:
        conn.execute("ALTER TABLE word_pairs ADD COLUMN user_id INTEGER")
        admin = conn.execute(
            "SELECT id FROM users WHERE role = 'admin' ORDER BY id LIMIT 1"
        ).fetchone()
        admin_id = admin["id"] if admin else 1
        conn.execute(
            "UPDATE word_pairs SET user_id = ? WHERE user_id IS NULL",
            (admin_id,),
        )


def init_db() -> None:
    with get_conn() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS word_pairs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                civilian_word TEXT NOT NULL,
                spy_word TEXT NOT NULL,
                saved_at INTEGER NOT NULL,
                user_id INTEGER
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_civilian ON word_pairs(civilian_word)"
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_spy ON word_pairs(spy_word)"
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'user',
                created_at INTEGER NOT NULL,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS user_word_grants (
                user_id INTEGER NOT NULL,
                source_user_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, source_user_id),
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY (source_user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS lookup_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                my_word TEXT NOT NULL,
                other_words TEXT NOT NULL,
                played_at INTEGER NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """
        )
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_lookup_user ON lookup_history(user_id, played_at DESC)"
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS word_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pair_id INTEGER NOT NULL,
                reporter_id INTEGER NOT NULL,
                report_type TEXT NOT NULL,
                message TEXT,
                suggested_civilian TEXT,
                suggested_spy TEXT,
                status TEXT NOT NULL DEFAULT 'pending',
                created_at INTEGER NOT NULL,
                FOREIGN KEY (pair_id) REFERENCES word_pairs(id) ON DELETE CASCADE,
                FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """
        )
        _migrate_word_pairs(conn)
        conn.commit()