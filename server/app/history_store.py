import time

from .database import get_conn
from .repository import list_pairs

MAX_HISTORY = 80


def _find_pairs_by_word(user: dict, word: str) -> list[dict]:
    return [
        p
        for p in list_pairs(user)
        if p["civilian_word"] == word or p["spy_word"] == word
    ]


def _resolve_role(word: str, pairs: list[dict]) -> str:
    as_civilian = any(p["civilian_word"] == word for p in pairs)
    as_spy = any(p["spy_word"] == word for p in pairs)
    if as_civilian and not as_spy:
        return "civilian"
    if as_spy:
        return "spy"
    return "civilian"


def _collect_others(word: str, pairs: list[dict]) -> list[dict]:
    seen: set[tuple[str, str]] = set()
    others: list[dict] = []
    for pair in pairs:
        if pair["civilian_word"] == word:
            key = (pair["spy_word"], "spy")
            if key not in seen:
                seen.add(key)
                others.append({"word": pair["spy_word"], "role": "spy"})
        elif pair["spy_word"] == word:
            key = (pair["civilian_word"], "civilian")
            if key not in seen:
                seen.add(key)
                others.append({"word": pair["civilian_word"], "role": "civilian"})
    return others


def lookup_word(user: dict, my_word: str) -> dict:
    if not my_word:
        return {"status": "empty"}
    pairs = _find_pairs_by_word(user, my_word)
    others = _collect_others(my_word, pairs)
    if not others:
        return {"status": "not_found", "my_word": my_word}
    my_role = _resolve_role(my_word, pairs)
    other_words = [o["word"] for o in others]
    _record_lookup(user["id"], my_word, other_words)
    return {
        "status": "found",
        "my_word": my_word,
        "my_role": my_role,
        "others": others,
    }


def _record_lookup(user_id: int, my_word: str, other_words: list[str]) -> None:
    others_str = "|".join(other_words)
    now = int(time.time() * 1000)
    with get_conn() as conn:
        latest = conn.execute(
            """
            SELECT my_word, other_words FROM lookup_history
            WHERE user_id = ?
            ORDER BY played_at DESC LIMIT 1
            """,
            (user_id,),
        ).fetchone()
        if latest and latest["my_word"] == my_word and latest["other_words"] == others_str:
            return
        conn.execute(
            """
            INSERT INTO lookup_history (user_id, my_word, other_words, played_at)
            VALUES (?, ?, ?, ?)
            """,
            (user_id, my_word, others_str, now),
        )
        count = conn.execute(
            "SELECT COUNT(*) AS c FROM lookup_history WHERE user_id = ?",
            (user_id,),
        ).fetchone()["c"]
        if count > MAX_HISTORY:
            extra = count - MAX_HISTORY
            conn.execute(
                """
                DELETE FROM lookup_history
                WHERE id IN (
                    SELECT id FROM lookup_history
                    WHERE user_id = ?
                    ORDER BY played_at ASC LIMIT ?
                )
                """,
                (user_id, extra),
            )
        conn.commit()


def list_history(user_id: int, limit: int = MAX_HISTORY) -> list[dict]:
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT id, my_word, other_words, played_at
            FROM lookup_history
            WHERE user_id = ?
            ORDER BY played_at DESC
            LIMIT ?
            """,
            (user_id, limit),
        ).fetchall()
    return [dict(row) for row in rows]


def delete_history_item(user_id: int, item_id: int) -> bool:
    with get_conn() as conn:
        cur = conn.execute(
            "DELETE FROM lookup_history WHERE id = ? AND user_id = ?",
            (item_id, user_id),
        )
        conn.commit()
        return cur.rowcount > 0


def clear_history(user_id: int) -> None:
    with get_conn() as conn:
        conn.execute("DELETE FROM lookup_history WHERE user_id = ?", (user_id,))
        conn.commit()