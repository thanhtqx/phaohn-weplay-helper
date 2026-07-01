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
            "SELECT id FROM users WHERE role IN ('admin', 'superadmin') ORDER BY id LIMIT 1"
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
            CREATE TABLE IF NOT EXISTS admin_notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                target_user_id INTEGER,
                created_at INTEGER NOT NULL,
                created_by INTEGER NOT NULL,
                FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS notification_reads (
                notification_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                read_at INTEGER NOT NULL,
                PRIMARY KEY (notification_id, user_id),
                FOREIGN KEY (notification_id) REFERENCES admin_notifications(id) ON DELETE CASCADE,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """
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
        _migrate_user_lock(conn)
        _migrate_user_nickname(conn)
        _migrate_pair_approval(conn)
        _migrate_pair_user_hidden(conn)
        _migrate_dedupe_pairs(conn)
        _migrate_superadmin_role(conn)
        _migrate_pair_tombstones(conn)
        conn.commit()


def _migrate_pair_tombstones(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS pair_tombstones (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            civilian_word TEXT NOT NULL,
            spy_word TEXT NOT NULL,
            deleted_at INTEGER NOT NULL,
            deleted_by INTEGER,
            FOREIGN KEY (deleted_by) REFERENCES users(id)
        )
        """
    )
    conn.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_tombstone_pair
        ON pair_tombstones(civilian_word, spy_word)
        """
    )


def _migrate_superadmin_role(conn: sqlite3.Connection) -> None:
    conn.execute(
        "UPDATE users SET role = ? WHERE role = ?",
        ("superadmin", "admin"),
    )


def _migrate_dedupe_pairs(conn: sqlite3.Connection) -> None:
    from .repository import APPROVAL_APPROVED, APPROVAL_PENDING, dedupe_word_pairs

    conn.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_user_pair_exact
        ON word_pairs(user_id, civilian_word, spy_word)
        """
    )
    dedupe_word_pairs(conn)


def _migrate_pair_approval(conn: sqlite3.Connection) -> None:
    cols = {row[1] for row in conn.execute("PRAGMA table_info(word_pairs)").fetchall()}
    if "approval_status" not in cols:
        conn.execute(
            """
            ALTER TABLE word_pairs
            ADD COLUMN approval_status TEXT NOT NULL DEFAULT 'approved'
            """
        )


def _migrate_user_nickname(conn: sqlite3.Connection) -> None:
    cols = {row[1] for row in conn.execute("PRAGMA table_info(users)").fetchall()}
    if "nickname" not in cols:
        conn.execute("ALTER TABLE users ADD COLUMN nickname TEXT")


def _migrate_pair_user_hidden(conn: sqlite3.Connection) -> None:
    cols = {row[1] for row in conn.execute("PRAGMA table_info(word_pairs)").fetchall()}
    if "user_hidden_at" not in cols:
        conn.execute("ALTER TABLE word_pairs ADD COLUMN user_hidden_at INTEGER")


def _migrate_user_lock(conn: sqlite3.Connection) -> None:
    cols = {row[1] for row in conn.execute("PRAGMA table_info(users)").fetchall()}
    if "is_locked" not in cols:
        conn.execute(
            "ALTER TABLE users ADD COLUMN is_locked INTEGER NOT NULL DEFAULT 0"
        )
    if "lock_reason" not in cols:
        conn.execute("ALTER TABLE users ADD COLUMN lock_reason TEXT")
    if "locked_at" not in cols:
        conn.execute("ALTER TABLE users ADD COLUMN locked_at INTEGER")
    if "locked_by" not in cols:
        conn.execute("ALTER TABLE users ADD COLUMN locked_by INTEGER")