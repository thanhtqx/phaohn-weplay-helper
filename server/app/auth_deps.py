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


def session_user(request: Request) -> dict | None:
    token = _extract_token(request)
    if not token:
        return None
    return auth_store.get_session_user(token)


def require_user(request: Request) -> dict:
    user = session_user(request)
    if not user:
        raise HTTPException(status_code=401, detail="unauthorized")
    return user


def require_admin(request: Request) -> dict:
    user = require_user(request)
    if user["role"] != auth_store.ROLE_ADMIN:
        raise HTTPException(status_code=403, detail="forbidden")
    return user