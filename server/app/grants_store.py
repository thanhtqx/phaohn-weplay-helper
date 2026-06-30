import time

from .database import get_conn


def list_granted_source_ids(user_id: int) -> list[int]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT source_user_id FROM user_word_grants
            WHERE user_id = ?
            ORDER BY source_user_id
            """,
            (user_id,),
        ).fetchall()
    return [row["source_user_id"] for row in rows]


def list_granted_sources(user_id: int) -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT g.source_user_id AS id, u.username
            FROM user_word_grants g
            JOIN users u ON u.id = g.source_user_id
            WHERE g.user_id = ?
            ORDER BY u.username
            """,
            (user_id,),
        ).fetchall()
    return [dict(row) for row in rows]


def set_granted_sources(user_id: int, source_user_ids: list[int]) -> list[dict]:
    cleaned = sorted({sid for sid in source_user_ids if sid != user_id})
    now = int(time.time() * 1000)
    with get_conn() as conn:
        conn.execute("DELETE FROM user_word_grants WHERE user_id = ?", (user_id,))
        for source_id in cleaned:
            exists = conn.execute(
                "SELECT id FROM users WHERE id = ?",
                (source_id,),
            ).fetchone()
            if not exists:
                continue
            conn.execute(
                """
                INSERT INTO user_word_grants (user_id, source_user_id, created_at)
                VALUES (?, ?, ?)
                """,
                (user_id, source_id, now),
            )
        conn.commit()
    return list_granted_sources(user_id)