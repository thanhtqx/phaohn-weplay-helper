import os
import secrets
import time

import bcrypt

from .database import get_conn

ROLE_ADMIN = "admin"
ROLE_USER = "user"


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode("utf-8"), password_hash.encode("utf-8"))
    except ValueError:
        return False


def ensure_default_admin() -> None:
    admin_user = os.environ.get("PHAOHN_ADMIN_USER", "admin")
    admin_pass = os.environ.get("PHAOHN_ADMIN_PASSWORD", "PhaoHN@2026")
    with get_conn() as conn:
        row = conn.execute(
            "SELECT id FROM users WHERE username = ?",
            (admin_user,),
        ).fetchone()
        if row:
            return
        now = int(time.time() * 1000)
        conn.execute(
            """
            INSERT INTO users (username, password_hash, role, created_at, created_by)
            VALUES (?, ?, ?, ?, NULL)
            """,
            (admin_user, hash_password(admin_pass), ROLE_ADMIN, now),
        )
        conn.commit()


def authenticate(username: str, password: str) -> dict | None:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT id, username, password_hash, role FROM users WHERE username = ?",
            (username,),
        ).fetchone()
    if not row:
        return None
    if not verify_password(password, row["password_hash"]):
        return None
    return {
        "id": row["id"],
        "username": row["username"],
        "role": row["role"],
    }


def get_user(user_id: int) -> dict | None:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT id, username, role, created_at, created_by FROM users WHERE id = ?",
            (user_id,),
        ).fetchone()
    return dict(row) if row else None


def list_users() -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT u.id, u.username, u.role, u.created_at,
                   c.username AS created_by_name
            FROM users u
            LEFT JOIN users c ON c.id = u.created_by
            ORDER BY u.created_at DESC
            """
        ).fetchall()
    return [dict(r) for r in rows]


def create_user(username: str, password: str, role: str, created_by: int) -> tuple[dict | None, str | None]:
    if not username or not password:
        return None, "empty"
    if len(username) < 3:
        return None, "username_short"
    if len(password) < 6:
        return None, "password_short"
    if role not in (ROLE_ADMIN, ROLE_USER):
        return None, "invalid_role"
    with get_conn() as conn:
        exists = conn.execute(
            "SELECT id FROM users WHERE username = ?",
            (username,),
        ).fetchone()
        if exists:
            return None, "duplicate"
        now = int(time.time() * 1000)
        cur = conn.execute(
            """
            INSERT INTO users (username, password_hash, role, created_at, created_by)
            VALUES (?, ?, ?, ?, ?)
            """,
            (username, hash_password(password), role, now, created_by),
        )
        conn.commit()
        user_id = cur.lastrowid
    user = get_user(user_id)
    return user, None


def delete_user(user_id: int, actor_id: int) -> tuple[bool, str | None]:
    if user_id == actor_id:
        return False, "self"
    with get_conn() as conn:
        row = conn.execute("SELECT id FROM users WHERE id = ?", (user_id,)).fetchone()
        if not row:
            return False, "not_found"
        conn.execute("DELETE FROM users WHERE id = ?", (user_id,))
        conn.commit()
    return True, None


def change_own_password(
    user_id: int,
    old_password: str,
    new_password: str,
) -> tuple[bool, str | None]:
    if len(new_password) < 6:
        return False, "password_short"
    with get_conn() as conn:
        row = conn.execute(
            "SELECT password_hash FROM users WHERE id = ?",
            (user_id,),
        ).fetchone()
        if not row:
            return False, "not_found"
        if not verify_password(old_password, row["password_hash"]):
            return False, "wrong_password"
        conn.execute(
            "UPDATE users SET password_hash = ? WHERE id = ?",
            (hash_password(new_password), user_id),
        )
        conn.commit()
    return True, None


def reset_password(user_id: int, new_password: str) -> tuple[bool, str | None]:
    if len(new_password) < 6:
        return False, "password_short"
    with get_conn() as conn:
        row = conn.execute("SELECT id FROM users WHERE id = ?", (user_id,)).fetchone()
        if not row:
            return False, "not_found"
        conn.execute(
            "UPDATE users SET password_hash = ? WHERE id = ?",
            (hash_password(new_password), user_id),
        )
        conn.commit()
    return True, None


def create_session_token() -> str:
    return secrets.token_urlsafe(32)


def store_session(token: str, user_id: int, expires_at: int) -> None:
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)",
            (token, user_id, expires_at),
        )
        conn.commit()


def get_session_user(token: str) -> dict | None:
    now = int(time.time())
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT s.user_id, u.username, u.role
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.token = ? AND s.expires_at > ?
            """,
            (token, now),
        ).fetchone()
    if not row:
        return None
    return {
        "id": row["user_id"],
        "username": row["username"],
        "role": row["role"],
    }


def delete_session(token: str) -> None:
    with get_conn() as conn:
        conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
        conn.commit()


def purge_expired_sessions() -> None:
    now = int(time.time())
    with get_conn() as conn:
        conn.execute("DELETE FROM sessions WHERE expires_at <= ?", (now,))
        conn.commit()