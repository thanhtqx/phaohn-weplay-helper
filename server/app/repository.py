import time

from .auth_store import ROLE_ADMIN
from .database import get_conn
from .grants_store import list_granted_source_ids


def is_admin(user: dict) -> bool:
    return user.get("role") == ROLE_ADMIN


def is_duplicate_pair(
    existing_civilian: str,
    existing_spy: str,
    civilian: str,
    spy: str,
) -> bool:
    same = existing_civilian == civilian and existing_spy == spy
    swapped = existing_civilian == spy and existing_spy == civilian
    return same or swapped


def _pair_select_sql() -> str:
    return """
        SELECT p.id, p.civilian_word, p.spy_word, p.saved_at, p.user_id,
               u.username AS owner_username,
               CASE WHEN p.user_id = ? THEN 0 ELSE 1 END AS is_shared,
               CASE WHEN ? = 1 OR p.user_id = ? THEN 1 ELSE 0 END AS can_edit
    """


def _list_pairs_sql(user: dict) -> tuple[str, list]:
    user_id = user["id"]
    admin_flag = 1 if is_admin(user) else 0
    select_args = [user_id, admin_flag, user_id]

    if is_admin(user):
        sql = f"""
            {_pair_select_sql()}
            FROM word_pairs p
            LEFT JOIN users u ON u.id = p.user_id
            ORDER BY p.saved_at DESC
        """
        return sql, select_args

    granted = list_granted_source_ids(user_id)
    if granted:
        placeholders = ",".join("?" * len(granted))
        sql = f"""
            {_pair_select_sql()}
            FROM word_pairs p
            LEFT JOIN users u ON u.id = p.user_id
            WHERE p.user_id = ?
               OR p.user_id IN ({placeholders})
            ORDER BY p.saved_at DESC
        """
        return sql, select_args + [user_id] + granted

    sql = f"""
        {_pair_select_sql()}
        FROM word_pairs p
        LEFT JOIN users u ON u.id = p.user_id
        WHERE p.user_id = ?
        ORDER BY p.saved_at DESC
    """
    return sql, select_args + [user_id]


def _row_to_pair(row) -> dict:
    item = dict(row)
    item["is_shared"] = bool(item.get("is_shared", 0))
    item["can_edit"] = bool(item.get("can_edit", 0))
    return item


def _can_access_pair(pair_id: int, user: dict) -> bool:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT user_id FROM word_pairs WHERE id = ?",
            (pair_id,),
        ).fetchone()
    if not row:
        return False
    if is_admin(user):
        return True
    owner_id = row["user_id"]
    if owner_id == user["id"]:
        return True
    return owner_id in list_granted_source_ids(user["id"])


def _can_edit_pair(pair_id: int, user: dict) -> bool:
    if is_admin(user):
        return True
    with get_conn() as conn:
        row = conn.execute(
            "SELECT user_id FROM word_pairs WHERE id = ?",
            (pair_id,),
        ).fetchone()
    if not row:
        return False
    return row["user_id"] == user["id"]


def _pair_row(pair_id: int, viewer: dict | None = None) -> dict | None:
    if viewer is None:
        with get_conn() as conn:
            row = conn.execute(
                """
                SELECT p.id, p.civilian_word, p.spy_word, p.saved_at, p.user_id,
                       u.username AS owner_username
                FROM word_pairs p
                LEFT JOIN users u ON u.id = p.user_id
                WHERE p.id = ?
                """,
                (pair_id,),
            ).fetchone()
        return dict(row) if row else None

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


def list_pairs(user: dict, search: str = "") -> list[dict]:
    sql, params = _list_pairs_sql(user)
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


def _has_duplicate(
    civilian: str,
    spy: str,
    user_id: int,
    exclude_id: int | None = None,
) -> bool:
    if civilian == spy:
        return True
    with get_conn() as conn:
        rows = conn.execute(
            "SELECT id, civilian_word, spy_word FROM word_pairs WHERE user_id = ?",
            (user_id,),
        ).fetchall()
    for row in rows:
        if exclude_id is not None and row["id"] == exclude_id:
            continue
        if is_duplicate_pair(row["civilian_word"], row["spy_word"], civilian, spy):
            return True
    return False


def create_pair(civilian: str, spy: str, user: dict) -> tuple[dict | None, str | None]:
    if not civilian or not spy:
        return None, "empty"
    if civilian == spy:
        return None, "same"
    user_id = user["id"]
    if _has_duplicate(civilian, spy, user_id):
        return None, "duplicate"
    now = int(time.time() * 1000)
    with get_conn() as conn:
        cur = conn.execute(
            """
            INSERT INTO word_pairs (civilian_word, spy_word, saved_at, user_id)
            VALUES (?, ?, ?, ?)
            """,
            (civilian, spy, now, user_id),
        )
        conn.commit()
        pair_id = cur.lastrowid
    return _pair_row(pair_id, user), None


def adopt_pair(pair_id: int, user: dict) -> tuple[dict | None, str | None]:
    if not _can_access_pair(pair_id, user):
        return None, "not_found"
    with get_conn() as conn:
        row = conn.execute(
            "SELECT civilian_word, spy_word, user_id FROM word_pairs WHERE id = ?",
            (pair_id,),
        ).fetchone()
    if not row:
        return None, "not_found"
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
    owner_id = owner["user_id"]
    if _has_duplicate(civilian, spy, owner_id, exclude_id=pair_id):
        return None, "duplicate"
    with get_conn() as conn:
        conn.execute(
            "UPDATE word_pairs SET civilian_word = ?, spy_word = ? WHERE id = ?",
            (civilian, spy, pair_id),
        )
        conn.commit()
    return _pair_row(pair_id, user), None


def delete_pair(pair_id: int, user: dict) -> bool:
    if not _can_edit_pair(pair_id, user):
        return False
    with get_conn() as conn:
        cur = conn.execute("DELETE FROM word_pairs WHERE id = ?", (pair_id,))
        conn.commit()
        return cur.rowcount > 0


def import_many(pairs: list[dict], user: dict) -> dict:
    added = 0
    duplicate = 0
    empty = 0
    same_word = 0
    user_id = user["id"]
    for item in pairs:
        civilian = str(item.get("civilian_word", ""))
        spy = str(item.get("spy_word", ""))
        if not civilian or not spy:
            empty += 1
            continue
        if civilian == spy:
            same_word += 1
            continue
        if _has_duplicate(civilian, spy, user_id):
            duplicate += 1
            continue
        now = int(time.time() * 1000)
        with get_conn() as conn:
            conn.execute(
                """
                INSERT INTO word_pairs (civilian_word, spy_word, saved_at, user_id)
                VALUES (?, ?, ?, ?)
                """,
                (civilian, spy, now, user_id),
            )
            conn.commit()
        added += 1
    return {
        "added": added,
        "duplicate": duplicate,
        "empty": empty,
        "same_word": same_word,
        "invalid_format": 0,
        "total": len(list_pairs(user)),
    }


def find_pair_id_by_words(user: dict, civilian: str, spy: str) -> int | None:
    for item in list_pairs(user):
        if is_duplicate_pair(item["civilian_word"], item["spy_word"], civilian, spy):
            return item["id"]
    return None


def push_pairs(pairs: list[dict], user: dict) -> dict:
    result = import_many(pairs, user)
    skipped = result["duplicate"] + result["empty"] + result["same_word"]
    return {
        "added": result["added"],
        "skipped": skipped,
        "total": result["total"],
    }