from fastapi import HTTPException, Request

from . import auth_store

SESSION_COOKIE = "phaohn_session"
SESSION_DAYS = 14


def _extract_token(request: Request) -> str | None:
    token = request.cookies.get(SESSION_COOKIE)
    if token:
        return token
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth[7:].strip() or None
    return request.headers.get("X-Session-Token")


def _raise_if_locked(lock_detail: dict | None) -> None:
    if lock_detail:
        raise HTTPException(status_code=403, detail=lock_detail)


def session_user(request: Request) -> dict | None:
    token = _extract_token(request)
    if not token:
        return None
    user, lock_detail = auth_store.get_session_user(token)
    if lock_detail:
        return None
    return user


def require_user(request: Request) -> dict:
    token = _extract_token(request)
    if not token:
        raise HTTPException(status_code=401, detail="unauthorized")
    user, lock_detail = auth_store.get_session_user(token)
    _raise_if_locked(lock_detail)
    if not user:
        raise HTTPException(status_code=401, detail="unauthorized")
    return user


def require_admin(request: Request) -> dict:
    user = require_user(request)
    if not auth_store.is_staff(user):
        raise HTTPException(status_code=403, detail="forbidden")
    return user


def require_superadmin(request: Request) -> dict:
    user = require_user(request)
    if not auth_store.is_superadmin(user):
        raise HTTPException(status_code=403, detail="forbidden")
    return user