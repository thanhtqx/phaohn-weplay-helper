import os
import secrets
import time

import bcrypt

from .database import get_conn

ROLE_SUPERADMIN = "superadmin"
ROLE_ADMIN = "admin"
ROLE_USER = "user"
STAFF_ROLES = frozenset({ROLE_ADMIN, ROLE_SUPERADMIN})


def is_staff(user: dict) -> bool:
    return user.get("role") in STAFF_ROLES


def is_superadmin(user: dict) -> bool:
    return user.get("role") == ROLE_SUPERADMIN
DEFAULT_LOCK_MESSAGE = "Tài khoản đã bị Admin khóa."


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode("utf-8"), password_hash.encode("utf-8"))
    except ValueError:
        return False


def account_locked_detail(reason: str | None) -> dict:
    msg = (reason or "").strip() or DEFAULT_LOCK_MESSAGE
    return {"code": "account_locked", "message": msg}


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
            (admin_user, hash_password(admin_pass), ROLE_SUPERADMIN, now),
        )
        conn.commit()


def _user_public(row) -> dict:
    keys = row.keys()
    user_id = row["id"] if "id" in keys else row["user_id"]
    return {
        "id": user_id,
        "username": row["username"],
        "nickname": row["nickname"] if "nickname" in keys else None,
        "role": row["role"],
    }


def get_user_by_username(username: str) -> dict | None:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT id, username, nickname, role, is_locked FROM users WHERE username = ?",
            (username,),
        ).fetchone()
    if not row:
        return None
    if row["is_locked"]:
        return None
    return _user_public(row)


def authenticate(username: str, password: str) -> tuple[dict | None, dict | None]:
    """Trả về (user, lock_detail). lock_detail chỉ có khi đúng MK nhưng bị khóa."""
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT id, username, nickname, password_hash, role, is_locked, lock_reason
            FROM users WHERE username = ?
            """,
            (username,),
        ).fetchone()
    if not row:
        return None, None
    if not verify_password(password, row["password_hash"]):
        return None, None
    if row["is_locked"]:
        return None, account_locked_detail(row["lock_reason"])
    return _user_public(row), None


def get_user(user_id: int) -> dict | None:
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT id, username, nickname, role, created_at, created_by,
                   is_locked, lock_reason, locked_at, locked_by
            FROM users WHERE id = ?
            """,
            (user_id,),
        ).fetchone()
    return dict(row) if row else None


def list_users() -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT u.id, u.username, u.nickname, u.role, u.created_at,
                   u.is_locked, u.lock_reason,
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
    if role not in (ROLE_USER, ROLE_ADMIN):
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


def update_user_role(actor: dict, user_id: int, role: str) -> tuple[bool, str | None]:
    if not is_superadmin(actor):
        return False, "forbidden"
    if role not in (ROLE_USER, ROLE_ADMIN):
        return False, "invalid_role"
    if user_id == actor["id"]:
        return False, "self"
    with get_conn() as conn:
        row = conn.execute(
            "SELECT id, role FROM users WHERE id = ?",
            (user_id,),
        ).fetchone()
        if not row:
            return False, "not_found"
        if row["role"] == ROLE_SUPERADMIN:
            return False, "cannot_change_superadmin"
        conn.execute("UPDATE users SET role = ? WHERE id = ?", (role, user_id))
        conn.commit()
    return True, None


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


def lock_user(user_id: int, admin_id: int, reason: str) -> tuple[bool, str | None]:
    if user_id == admin_id:
        return False, "self"
    reason = reason.strip()
    if not reason:
        return False, "reason_required"
    now = int(time.time() * 1000)
    with get_conn() as conn:
        row = conn.execute("SELECT id FROM users WHERE id = ?", (user_id,)).fetchone()
        if not row:
            return False, "not_found"
        conn.execute(
            """
            UPDATE users
            SET is_locked = 1, lock_reason = ?, locked_at = ?, locked_by = ?
            WHERE id = ?
            """,
            (reason, now, admin_id, user_id),
        )
        conn.commit()
    return True, None


def unlock_user(user_id: int, admin_id: int) -> tuple[bool, str | None]:
    if user_id == admin_id:
        return False, "self"
    with get_conn() as conn:
        row = conn.execute("SELECT id FROM users WHERE id = ?", (user_id,)).fetchone()
        if not row:
            return False, "not_found"
        conn.execute(
            """
            UPDATE users
            SET is_locked = 0, lock_reason = NULL, locked_at = NULL, locked_by = NULL
            WHERE id = ?
            """,
            (user_id,),
        )
        conn.commit()
    return True, None


def update_nickname(user_id: int, nickname: str) -> tuple[bool, str | None]:
    nickname = nickname.strip()
    if not nickname:
        return False, "empty"
    if len(nickname) > 64:
        return False, "too_long"
    with get_conn() as conn:
        row = conn.execute("SELECT id FROM users WHERE id = ?", (user_id,)).fetchone()
        if not row:
            return False, "not_found"
        conn.execute(
            "UPDATE users SET nickname = ? WHERE id = ?",
            (nickname, user_id),
        )
        conn.commit()
    return True, None


def get_me(user_id: int) -> dict | None:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT id, username, nickname, role FROM users WHERE id = ?",
            (user_id,),
        ).fetchone()
    return _user_public(row) if row else None


def change_own_password(
    user_id: int,
    old_password: str,
    new_password: str,
) -> tuple[bool, str | None]:
    if len(new_password) < 6:
        return False, "password_short"
    with get_conn() as conn:
        row = conn.execute(
            "SELECT password_hash, is_locked FROM users WHERE id = ?",
            (user_id,),
        ).fetchone()
        if not row:
            return False, "not_found"
        if row["is_locked"]:
            return False, "account_locked"
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


def get_session_user(token: str) -> tuple[dict | None, dict | None]:
    """Trả về (user, lock_detail). lock_detail khi phiên hợp lệ nhưng tài khoản bị khóa."""
    now = int(time.time())
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT u.id, u.username, u.nickname, u.role, u.is_locked, u.lock_reason
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.token = ? AND s.expires_at > ?
            """,
            (token, now),
        ).fetchone()
        if not row:
            return None, None
        if row["is_locked"]:
            conn.execute("DELETE FROM sessions WHERE user_id = ?", (row["id"],))
            conn.commit()
            return None, account_locked_detail(row["lock_reason"])
    return _user_public(row), None


def delete_session(token: str) -> None:
    with get_conn() as conn:
        conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
        conn.commit()


def purge_expired_sessions() -> None:
    now = int(time.time())
    with get_conn() as conn:
        conn.execute("DELETE FROM sessions WHERE expires_at <= ?", (now,))
        conn.commit()