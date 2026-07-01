import time

from .auth_store import is_staff
from .database import get_conn
from .grants_store import list_granted_source_ids

APPROVAL_APPROVED = "approved"
APPROVAL_PENDING = "pending"
APPROVAL_REJECTED = "rejected"

ORIGIN_MANUAL = "manual"
ORIGIN_CAPTURE = "capture"
ORIGIN_IMPORT = "import"
ORIGIN_SYNC = "sync"


def is_admin(user: dict) -> bool:
    return is_staff(user)


def norm_word(value: str) -> str:
    return str(value or "").strip()


def is_blank_word(value: str) -> bool:
    return not norm_word(value)


def is_duplicate_pair(
    existing_civilian: str,
    existing_spy: str,
    civilian: str,
    spy: str,
) -> bool:
    """Trùng khi cùng thứ tự hoặc đảo vai — khớp từng ký tự, không chuẩn hóa."""
    same = existing_civilian == civilian and existing_spy == spy
    swapped = existing_civilian == spy and existing_spy == civilian
    return same or swapped


def record_pair_tombstone(
    civilian: str,
    spy: str,
    deleted_by: int | None = None,
) -> None:
    if is_blank_word(civilian) or is_blank_word(spy) or civilian == spy:
        return
    now = int(time.time() * 1000)
    with get_conn() as conn:
        for c, s in ((civilian, spy), (spy, civilian)):
            conn.execute(
                """
                INSERT OR IGNORE INTO pair_tombstones (
                    civilian_word, spy_word, deleted_at, deleted_by
                ) VALUES (?, ?, ?, ?)
                """,
                (c, s, now, deleted_by),
            )
        conn.commit()


def is_pair_tombstoned(civilian: str, spy: str) -> bool:
    if is_blank_word(civilian) or is_blank_word(spy):
        return False
    with get_conn() as conn:
        rows = conn.execute(
            "SELECT civilian_word, spy_word FROM pair_tombstones"
        ).fetchall()
    for row in rows:
        if is_duplicate_pair(row["civilian_word"], row["spy_word"], civilian, spy):
            return True
    return False


def _find_global_duplicate_row(
    civilian: str,
    spy: str,
    exclude_id: int | None = None,
) -> dict | None:
    """Trùng cặp từ trên toàn kho — không phân biệt user (kể cả đảo vai)."""
    if is_blank_word(civilian) or is_blank_word(spy) or civilian == spy:
        return None
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT id, user_id, civilian_word, spy_word, approval_status
            FROM word_pairs
            WHERE approval_status != ? AND user_hidden_at IS NULL
            """,
            (APPROVAL_REJECTED,),
        ).fetchall()
    for row in rows:
        if exclude_id is not None and row["id"] == exclude_id:
            continue
        if is_duplicate_pair(row["civilian_word"], row["spy_word"], civilian, spy):
            return dict(row)
    return None


def pair_exists_globally(civilian: str, spy: str) -> bool:
    return _find_global_duplicate_row(civilian, spy) is not None


def _approval_status_for_user(user: dict, origin: str = ORIGIN_MANUAL) -> str:
    if is_admin(user) or origin in (ORIGIN_CAPTURE, ORIGIN_SYNC):
        return APPROVAL_APPROVED
    return APPROVAL_PENDING


def _pair_select_sql() -> str:
    return """
        SELECT p.id, p.civilian_word, p.spy_word, p.saved_at, p.user_id,
               p.approval_status,
               u.username AS owner_username,
               CASE WHEN p.user_id = ? THEN 0 ELSE 1 END AS is_shared,
               CASE WHEN ? = 1 OR p.user_id = ? THEN 1 ELSE 0 END AS can_edit
    """


def _access_where_sql(user: dict, approved_only: bool) -> tuple[str, list]:
    user_id = user["id"]
    approval_clause = ""
    approval_args: list = []
    if is_admin(user) and not approved_only:
        return "WHERE 1=1", []

    if approved_only:
        approval_clause = "AND p.approval_status = ?"
        approval_args = [APPROVAL_APPROVED]
    else:
        approval_clause = """
            AND (
                p.approval_status = ?
                OR (p.user_id = ? AND p.approval_status = ?)
            )
        """
        approval_args = [APPROVAL_APPROVED, user_id, APPROVAL_PENDING]

    hidden_clause = ""
    hidden_args: list = []
    if not is_admin(user):
        hidden_clause = "AND NOT (p.user_id = ? AND p.user_hidden_at IS NOT NULL)"
        hidden_args = [user_id]

    if is_admin(user):
        sql = f"""
            WHERE 1=1 {approval_clause}
        """
        return sql, approval_args

    granted = list_granted_source_ids(user_id)
    if granted:
        placeholders = ",".join("?" * len(granted))
        sql = f"""
            WHERE (p.user_id = ? OR p.user_id IN ({placeholders}))
            {hidden_clause}
            {approval_clause}
        """
        return sql, [user_id] + granted + hidden_args + approval_args

    sql = f"""
        WHERE p.user_id = ?
        {hidden_clause}
        {approval_clause}
    """
    return sql, [user_id] + hidden_args + approval_args


def _list_pairs_sql(user: dict, approved_only: bool) -> tuple[str, list]:
    user_id = user["id"]
    admin_flag = 1 if is_admin(user) else 0
    select_args = [user_id, admin_flag, user_id]
    where_sql, where_args = _access_where_sql(user, approved_only)
    sql = f"""
        {_pair_select_sql()}
        FROM word_pairs p
        LEFT JOIN users u ON u.id = p.user_id
        {where_sql}
        ORDER BY p.saved_at DESC
    """
    return sql, select_args + where_args


def _row_to_pair(row) -> dict:
    item = dict(row)
    item["is_shared"] = bool(item.get("is_shared", 0))
    item["can_edit"] = bool(item.get("can_edit", 0))
    item["approval_status"] = item.get("approval_status") or APPROVAL_APPROVED
    item["user_hidden"] = bool(item.get("user_hidden_at"))
    return item


def _can_access_pair(pair_id: int, user: dict, include_pending: bool = True) -> bool:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT user_id, approval_status, user_hidden_at FROM word_pairs WHERE id = ?",
            (pair_id,),
        ).fetchone()
    if not row:
        return False
    if (
        row["user_hidden_at"]
        and row["user_id"] == user["id"]
        and not is_admin(user)
    ):
        return False
    if row["approval_status"] == APPROVAL_PENDING and not include_pending:
        return row["user_id"] == user["id"]
    if row["approval_status"] == APPROVAL_PENDING and row["user_id"] != user["id"]:
        return False
    if is_admin(user):
        return True
    owner_id = row["user_id"]
    if owner_id == user["id"]:
        return True
    if row["approval_status"] != APPROVAL_APPROVED:
        return False
    return owner_id in list_granted_source_ids(user["id"])


def _can_edit_pair(pair_id: int, user: dict) -> bool:
    if is_admin(user):
        return True
    with get_conn() as conn:
        row = conn.execute(
            "SELECT user_id, user_hidden_at FROM word_pairs WHERE id = ?",
            (pair_id,),
        ).fetchone()
    if not row:
        return False
    if row["user_id"] != user["id"]:
        return False
    return not row["user_hidden_at"]


def _pair_row(pair_id: int, viewer: dict | None = None) -> dict | None:
    if viewer is None:
        with get_conn() as conn:
            row = conn.execute(
                """
                SELECT p.id, p.civilian_word, p.spy_word, p.saved_at, p.user_id,
                       p.approval_status, u.username AS owner_username
                FROM word_pairs p
                LEFT JOIN users u ON u.id = p.user_id
                WHERE p.id = ?
                """,
                (pair_id,),
            ).fetchone()
        if not row:
            return None
        out = dict(row)
        out["approval_status"] = out.get("approval_status") or APPROVAL_APPROVED
        return out

    user_id = viewer["id"]
    admin_flag = 1 if is_admin(viewer) else 0
    with get_conn() as conn:
        row = conn.execute(
            f"""
            {_pair_select_sql()}
            FROM word_pairs p
            LEFT JOIN users u ON u.id = p.user_id
            WHERE p.id = ?
            """,
            [user_id, admin_flag, user_id, pair_id],
        ).fetchone()
    return _row_to_pair(row) if row else None


def list_all_pairs_admin() -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT p.id, p.civilian_word, p.spy_word, p.saved_at, p.user_id,
                   p.approval_status, p.user_hidden_at,
                   u.username AS owner_username
            FROM word_pairs p
            LEFT JOIN users u ON u.id = p.user_id
            ORDER BY
              CASE WHEN p.approval_status = ? THEN 0 ELSE 1 END,
              p.saved_at DESC
            """,
            (APPROVAL_PENDING,),
        ).fetchall()
    return [
        {
            "id": r["id"],
            "civilian_word": r["civilian_word"],
            "spy_word": r["spy_word"],
            "saved_at": r["saved_at"],
            "user_id": r["user_id"],
            "owner_username": r["owner_username"],
            "approval_status": r["approval_status"] or APPROVAL_APPROVED,
            "user_hidden": bool(r["user_hidden_at"]),
            "is_shared": False,
            "can_edit": True,
        }
        for r in rows
    ]


def list_pairs(user: dict, search: str = "") -> list[dict]:
    sql, params = _list_pairs_sql(user, approved_only=False)
    with get_conn() as conn:
        rows = conn.execute(sql, params).fetchall()
    items = [_row_to_pair(row) for row in rows]
    if not search:
        return items
    return [
        item
        for item in items
        if search == item["civilian_word"] or search == item["spy_word"]
    ]


def list_pairs_approved(user: dict, search: str = "") -> list[dict]:
    sql, params = _list_pairs_sql(user, approved_only=True)
    with get_conn() as conn:
        rows = conn.execute(sql, params).fetchall()
    items = [_row_to_pair(row) for row in rows]
    if not search:
        return items
    return [
        item
        for item in items
        if search == item["civilian_word"] or search == item["spy_word"]
    ]


def get_pair(pair_id: int, user: dict) -> dict | None:
    if not _can_access_pair(pair_id, user):
        return None
    return _pair_row(pair_id, user)


def _find_duplicate_row(
    civilian: str,
    spy: str,
    user_id: int,
    exclude_id: int | None = None,
) -> dict | None:
    if civilian == spy:
        return {"id": -1}
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT id, civilian_word, spy_word, approval_status FROM word_pairs
            WHERE user_id = ? AND approval_status != ?
            """,
            (user_id, APPROVAL_REJECTED),
        ).fetchall()
    for row in rows:
        if exclude_id is not None and row["id"] == exclude_id:
            continue
        if is_duplicate_pair(row["civilian_word"], row["spy_word"], civilian, spy):
            return dict(row)
    return None


def _has_duplicate(
    civilian: str,
    spy: str,
    user_id: int,
    exclude_id: int | None = None,
) -> bool:
    return _find_duplicate_row(civilian, spy, user_id, exclude_id) is not None


def _pick_keep_row(existing: dict, row: dict) -> tuple[dict, dict]:
    """Giữ bản đã duyệt; cùng trạng thái thì giữ id nhỏ hơn."""
    if (
        row["approval_status"] == APPROVAL_APPROVED
        and existing["approval_status"] != APPROVAL_APPROVED
    ):
        return dict(row), existing
    if (
        existing["approval_status"] == APPROVAL_APPROVED
        and row["approval_status"] != APPROVAL_APPROVED
    ):
        return existing, row
    if row["id"] < existing["id"]:
        return dict(row), existing
    return existing, row


def dedupe_word_pairs_global(conn=None) -> int:
    """Gộp trùng toàn kho — cùng cặp từ (kể cả đảo vai) chỉ giữ một bản."""
    own_conn = conn is None
    if own_conn:
        conn = get_conn()
    rows = conn.execute(
        """
        SELECT id, user_id, civilian_word, spy_word, approval_status
        FROM word_pairs
        WHERE approval_status != ? AND user_hidden_at IS NULL
        ORDER BY id
        """,
        (APPROVAL_REJECTED,),
    ).fetchall()
    kept: list[dict] = []
    removed = 0
    for row in rows:
        dup_idx = None
        for i, existing in enumerate(kept):
            if is_duplicate_pair(
                existing["civilian_word"],
                existing["spy_word"],
                row["civilian_word"],
                row["spy_word"],
            ):
                dup_idx = i
                break
        if dup_idx is None:
            kept.append(dict(row))
            continue
        existing = kept[dup_idx]
        keep_row, drop_row = _pick_keep_row(existing, dict(row))
        kept[dup_idx] = keep_row
        conn.execute("DELETE FROM word_pairs WHERE id = ?", (drop_row["id"],))
        removed += 1
    if own_conn:
        conn.commit()
    return removed


def dedupe_word_pairs(conn=None) -> int:
    """Alias — dọn trùng toàn kho (không theo user)."""
    return dedupe_word_pairs_global(conn)


def _insert_pair(
    civilian: str,
    spy: str,
    user_id: int,
    approval_status: str,
) -> int | None:
    if is_blank_word(civilian) or is_blank_word(spy) or civilian == spy:
        return None
    if is_pair_tombstoned(civilian, spy):
        return None
    existing = _find_duplicate_row(civilian, spy, user_id)
    if existing:
        if existing.get("id") == -1:
            return None
        if (
            approval_status == APPROVAL_APPROVED
            and existing.get("approval_status") == APPROVAL_PENDING
        ):
            now = int(time.time() * 1000)
            with get_conn() as conn:
                conn.execute(
                    """
                    UPDATE word_pairs
                    SET approval_status = ?, saved_at = ?
                    WHERE id = ?
                    """,
                    (APPROVAL_APPROVED, now, existing["id"]),
                )
                conn.commit()
            return existing["id"]
        return None
    if _find_global_duplicate_row(civilian, spy) is not None:
        return None
    now = int(time.time() * 1000)
    with get_conn() as conn:
        cur = conn.execute(
            """
            INSERT INTO word_pairs (
                civilian_word, spy_word, saved_at, user_id, approval_status
            ) VALUES (?, ?, ?, ?, ?)
            """,
            (civilian, spy, now, user_id, approval_status),
        )
        conn.commit()
        return cur.lastrowid


def create_pair(civilian: str, spy: str, user: dict) -> tuple[dict | None, str | None]:
    if not civilian or not spy:
        return None, "empty"
    if civilian == spy:
        return None, "same"
    user_id = user["id"]
    status = _approval_status_for_user(user, ORIGIN_MANUAL)
    pair_id = _insert_pair(civilian, spy, user_id, status)
    if pair_id is None:
        return None, "duplicate"
    return _pair_row(pair_id, user), None


def adopt_pair(pair_id: int, user: dict) -> tuple[dict | None, str | None]:
    if not _can_access_pair(pair_id, user):
        return None, "not_found"
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT civilian_word, spy_word, user_id, approval_status
            FROM word_pairs WHERE id = ?
            """,
            (pair_id,),
        ).fetchone()
    if not row:
        return None, "not_found"
    if row["approval_status"] != APPROVAL_APPROVED:
        return None, "not_approved"
    if row["user_id"] == user["id"]:
        return _pair_row(pair_id, user), "already_owned"
    return create_pair(row["civilian_word"], row["spy_word"], user)


def update_pair(
    pair_id: int,
    civilian: str,
    spy: str,
    user: dict,
) -> tuple[dict | None, str | None]:
    if not _can_edit_pair(pair_id, user):
        return None, "not_found"
    if not civilian or not spy:
        return None, "empty"
    if civilian == spy:
        return None, "same"
    with get_conn() as conn:
        owner = conn.execute(
            "SELECT user_id FROM word_pairs WHERE id = ?",
            (pair_id,),
        ).fetchone()
    if _find_global_duplicate_row(civilian, spy, exclude_id=pair_id) is not None:
        return None, "duplicate"
    with get_conn() as conn:
        conn.execute(
            "UPDATE word_pairs SET civilian_word = ?, spy_word = ? WHERE id = ?",
            (civilian, spy, pair_id),
        )
        conn.commit()
    return _pair_row(pair_id, user), None


def delete_pair(pair_id: int, user: dict) -> bool:
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT civilian_word, spy_word, user_id
            FROM word_pairs WHERE id = ?
            """,
            (pair_id,),
        ).fetchone()
    if not row:
        return False
    if is_admin(user):
        record_pair_tombstone(
            row["civilian_word"],
            row["spy_word"],
            user.get("id"),
        )
        with get_conn() as conn:
            cur = conn.execute("DELETE FROM word_pairs WHERE id = ?", (pair_id,))
            conn.commit()
            return cur.rowcount > 0
    if not _can_edit_pair(pair_id, user):
        return False
    now = int(time.time() * 1000)
    with get_conn() as conn:
        cur = conn.execute(
            """
            UPDATE word_pairs SET user_hidden_at = ?
            WHERE id = ? AND user_id = ? AND user_hidden_at IS NULL
            """,
            (now, pair_id, user["id"]),
        )
        conn.commit()
        return cur.rowcount > 0


def import_many(
    pairs: list[dict],
    user: dict,
    origin: str = ORIGIN_IMPORT,
) -> dict:
    added = 0
    pending = 0
    duplicate = 0
    empty = 0
    same_word = 0
    user_id = user["id"]
    status = _approval_status_for_user(user, origin)
    for item in pairs:
        civilian = str(item.get("civilian_word", ""))
        spy = str(item.get("spy_word", ""))
        if is_blank_word(civilian) or is_blank_word(spy):
            empty += 1
            continue
        if civilian == spy:
            same_word += 1
            continue
        pair_id = _insert_pair(civilian, spy, user_id, status)
        if pair_id is None:
            duplicate += 1
            continue
        added += 1
        if status == APPROVAL_PENDING:
            pending += 1
    dedupe_word_pairs()
    return {
        "added": added,
        "pending": pending,
        "duplicate": duplicate,
        "empty": empty,
        "same_word": same_word,
        "invalid_format": 0,
        "total": len(list_pairs(user)),
    }


def find_pair_id_by_words(user: dict, civilian: str, spy: str) -> int | None:
    for item in list_pairs_approved(user):
        if is_duplicate_pair(item["civilian_word"], item["spy_word"], civilian, spy):
            return item["id"]
    return None


def push_pairs(pairs: list[dict], user: dict) -> dict:
    added = 0
    pending = 0
    skipped = 0
    user_id = user["id"]
    for item in pairs:
        civilian = str(item.get("civilian_word", ""))
        spy = str(item.get("spy_word", ""))
        origin = str(item.get("origin", ORIGIN_MANUAL))
        if is_blank_word(civilian) or is_blank_word(spy):
            skipped += 1
            continue
        if civilian == spy:
            skipped += 1
            continue
        if is_pair_tombstoned(civilian, spy):
            skipped += 1
            continue
        status = _approval_status_for_user(user, origin)
        pair_id = _insert_pair(civilian, spy, user_id, status)
        if pair_id is None:
            skipped += 1
            continue
        added += 1
        if status == APPROVAL_PENDING:
            pending += 1
    dedupe_word_pairs_global()
    return {
        "added": added,
        "pending": pending,
        "skipped": skipped,
        "total": len(list_pairs_approved(user)),
    }