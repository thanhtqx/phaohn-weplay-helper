import time

from .database import get_conn


def create_notification(
    admin: dict,
    title: str,
    body: str,
    target_user_id: int | None,
) -> dict:
    title = title.strip()
    body = body.strip()
    if not title or not body:
        raise ValueError("empty")
    now = int(time.time() * 1000)
    with get_conn() as conn:
        if target_user_id is not None:
            row = conn.execute(
                "SELECT id FROM users WHERE id = ?",
                (target_user_id,),
            ).fetchone()
            if not row:
                raise ValueError("user_not_found")
        cur = conn.execute(
            """
            INSERT INTO admin_notifications (
                title, body, target_user_id, created_at, created_by
            ) VALUES (?, ?, ?, ?, ?)
            """,
            (title, body, target_user_id, now, admin["id"]),
        )
        conn.commit()
        notif_id = cur.lastrowid
    out = get_notification(notif_id)
    if out is None:
        raise ValueError("error")
    return out


def list_admin_notifications(limit: int = 100) -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT n.*,
                   t.username AS target_username,
                   a.username AS created_by_name
            FROM admin_notifications n
            LEFT JOIN users t ON t.id = n.target_user_id
            JOIN users a ON a.id = n.created_by
            ORDER BY n.created_at DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    return [_row_to_dict(r) for r in rows]


def get_notification(notif_id: int) -> dict | None:
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT n.*,
                   t.username AS target_username,
                   a.username AS created_by_name
            FROM admin_notifications n
            LEFT JOIN users t ON t.id = n.target_user_id
            JOIN users a ON a.id = n.created_by
            WHERE n.id = ?
            """,
            (notif_id,),
        ).fetchone()
    if not row:
        return None
    return _row_to_dict(row)


def list_inbox(user_id: int, limit: int = 50) -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT n.id, n.title, n.body, n.created_at, n.target_user_id,
                   r.read_at
            FROM admin_notifications n
            LEFT JOIN notification_reads r
              ON r.notification_id = n.id AND r.user_id = ?
            WHERE (n.target_user_id IS NULL OR n.target_user_id = ?)
            ORDER BY n.created_at DESC
            LIMIT ?
            """,
            (user_id, user_id, limit),
        ).fetchall()
    return [
        {
            "id": r["id"],
            "title": r["title"],
            "body": r["body"],
            "created_at": r["created_at"],
            "target_user_id": r["target_user_id"],
            "read": r["read_at"] is not None,
            "read_at": r["read_at"],
        }
        for r in rows
    ]


def ack_notifications(user_id: int, notification_ids: list[int]) -> int:
    if not notification_ids:
        return 0
    now = int(time.time() * 1000)
    unique_ids = sorted({int(i) for i in notification_ids if int(i) > 0})
    if not unique_ids:
        return 0
    with get_conn() as conn:
        placeholders = ",".join("?" * len(unique_ids))
        valid = conn.execute(
            f"""
            SELECT n.id
            FROM admin_notifications n
            WHERE n.id IN ({placeholders})
              AND (n.target_user_id IS NULL OR n.target_user_id = ?)
            """,
            (*unique_ids, user_id),
        ).fetchall()
        valid_ids = [r["id"] for r in valid]
        if not valid_ids:
            return 0
        for nid in valid_ids:
            conn.execute(
                """
                INSERT OR IGNORE INTO notification_reads (
                    notification_id, user_id, read_at
                ) VALUES (?, ?, ?)
                """,
                (nid, user_id, now),
            )
        conn.commit()
    return len(valid_ids)


def _row_to_dict(row) -> dict:
    return {
        "id": row["id"],
        "title": row["title"],
        "body": row["body"],
        "target_user_id": row["target_user_id"],
        "target_username": row["target_username"],
        "created_at": row["created_at"],
        "created_by": row["created_by"],
        "created_by_name": row["created_by_name"],
    }