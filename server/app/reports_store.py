import time

from .auth_store import is_staff
from .database import get_conn
from .repository import _can_access_pair


REPORT_WRONG = "wrong"
REPORT_SUGGEST = "suggest_edit"
STATUS_PENDING = "pending"
STATUS_RESOLVED = "resolved"
STATUS_REJECTED = "rejected"


def create_report(
    pair_id: int,
    reporter: dict,
    report_type: str,
    message: str = "",
    suggested_civilian: str = "",
    suggested_spy: str = "",
) -> tuple[dict | None, str | None]:
    if report_type not in (REPORT_WRONG, REPORT_SUGGEST):
        return None, "invalid_type"
    if not _can_access_pair(pair_id, reporter):
        return None, "not_found"
    now = int(time.time() * 1000)
    with get_conn() as conn:
        cur = conn.execute(
            """
            INSERT INTO word_reports (
                pair_id, reporter_id, report_type, message,
                suggested_civilian, suggested_spy, status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                pair_id,
                reporter["id"],
                report_type,
                message,
                suggested_civilian,
                suggested_spy,
                STATUS_PENDING,
                now,
            ),
        )
        conn.commit()
        report_id = cur.lastrowid
    return get_report(report_id, reporter), None


def get_report(report_id: int, user: dict) -> dict | None:
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT r.*, p.civilian_word, p.spy_word, u.username AS reporter_name,
                   o.username AS owner_name
            FROM word_reports r
            JOIN word_pairs p ON p.id = r.pair_id
            JOIN users u ON u.id = r.reporter_id
            LEFT JOIN users o ON o.id = p.user_id
            WHERE r.id = ?
            """,
            (report_id,),
        ).fetchone()
    if not row:
        return None
    item = dict(row)
    if not is_staff(user) and item["reporter_id"] != user["id"]:
        return None
    return item


def list_reports(user: dict) -> list[dict]:
    if is_staff(user):
        sql = """
            SELECT r.*, p.civilian_word, p.spy_word, u.username AS reporter_name,
                   o.username AS owner_name
            FROM word_reports r
            JOIN word_pairs p ON p.id = r.pair_id
            JOIN users u ON u.id = r.reporter_id
            LEFT JOIN users o ON o.id = p.user_id
            ORDER BY r.created_at DESC
        """
        params: list = []
    else:
        sql = """
            SELECT r.*, p.civilian_word, p.spy_word, u.username AS reporter_name,
                   o.username AS owner_name
            FROM word_reports r
            JOIN word_pairs p ON p.id = r.pair_id
            JOIN users u ON u.id = r.reporter_id
            LEFT JOIN users o ON o.id = p.user_id
            WHERE r.reporter_id = ?
            ORDER BY r.created_at DESC
        """
        params = [user["id"]]
    with get_conn() as conn:
        rows = conn.execute(sql, params).fetchall()
    return [dict(r) for r in rows]


def resolve_report(report_id: int, status: str) -> bool:
    if status not in (STATUS_RESOLVED, STATUS_REJECTED):
        return False
    with get_conn() as conn:
        cur = conn.execute(
            "UPDATE word_reports SET status = ? WHERE id = ?",
            (status, report_id),
        )
        conn.commit()
        return cur.rowcount > 0


def approve_report(report_id: int, admin: dict) -> tuple[dict | None, str | None]:
    from . import repository

    report = get_report(report_id, admin)
    if not report or report["status"] != STATUS_PENDING:
        return None, "not_found"
    if report["report_type"] == REPORT_SUGGEST:
        civilian = report["suggested_civilian"] or report["civilian_word"]
        spy = report["suggested_spy"] or report["spy_word"]
        if not civilian or not spy:
            return None, "empty_suggestion"
        pair, err = repository.update_pair(
            report["pair_id"], civilian, spy, admin
        )
        if err == "not_found":
            return None, "not_found"
        if err == "duplicate":
            return None, "duplicate"
        if err == "same":
            return None, "same_word"
        if err == "empty" or pair is None:
            return None, "empty"
    resolve_report(report_id, STATUS_RESOLVED)
    return get_report(report_id, admin), None