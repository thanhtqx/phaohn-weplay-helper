import time

from .database import get_conn
from .repository import APPROVAL_APPROVED, APPROVAL_PENDING, _pair_row


def list_pending_pairs() -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT p.id, p.civilian_word, p.spy_word, p.saved_at, p.user_id,
                   p.approval_status, u.username AS owner_username
            FROM word_pairs p
            JOIN users u ON u.id = p.user_id
            WHERE p.approval_status = ?
            ORDER BY p.saved_at ASC
            """,
            (APPROVAL_PENDING,),
        ).fetchall()
    return [dict(r) for r in rows]


def approve_pair(pair_id: int) -> dict | None:
    now = int(time.time() * 1000)
    with get_conn() as conn:
        row = conn.execute(
            "SELECT id, user_id FROM word_pairs WHERE id = ? AND approval_status = ?",
            (pair_id, APPROVAL_PENDING),
        ).fetchone()
        if not row:
            return None
        conn.execute(
            "UPDATE word_pairs SET approval_status = ?, saved_at = ? WHERE id = ?",
            (APPROVAL_APPROVED, now, pair_id),
        )
        conn.commit()
    return _pair_row(pair_id)


def reject_pair(pair_id: int) -> bool:
    with get_conn() as conn:
        cur = conn.execute(
            "DELETE FROM word_pairs WHERE id = ? AND approval_status = ?",
            (pair_id, APPROVAL_PENDING),
        )
        conn.commit()
        return cur.rowcount > 0