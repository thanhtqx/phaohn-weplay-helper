import csv
import io
import json
import time
from pathlib import Path

from fastapi import Depends, FastAPI, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from . import auth_store, grants_store, history_store, reports_store, repository
from .auth_deps import (
    SESSION_COOKIE,
    SESSION_DAYS,
    require_admin,
    require_user,
    session_user,
)
from .database import init_db
from .import_parser import parse_comma_lines, parse_upload
from .models import (
    BulkTextIn,
    ChangePasswordIn,
    CreateUserIn,
    HistoryOut,
    ImportResult,
    LoginIn,
    LookupIn,
    LookupOut,
    ReportByWordsIn,
    ReportIn,
    ResetPasswordIn,
    SyncPushIn,
    SyncResult,
    UserOut,
    WordPairIn,
    WordPairOut,
    WordPairUpdate,
    WordSourcesIn,
)

STATIC_DIR = Path(__file__).resolve().parent.parent / "static"
RELEASES_DIR = Path(__file__).resolve().parent.parent / "releases"
APK_PATH = RELEASES_DIR / "PhaoHN-latest.apk"
VERSION_FILE = RELEASES_DIR / "version.json"

app = FastAPI(title="PhaoHN Words", version="2.3.7")


@app.on_event("startup")
def startup() -> None:
    init_db()
    auth_store.ensure_default_admin()
    auth_store.purge_expired_sessions()


def _session_cookie(token: str) -> dict:
    return {
        "key": SESSION_COOKIE,
        "value": token,
        "httponly": True,
        "samesite": "lax",
        "max_age": SESSION_DAYS * 86400,
        "path": "/",
    }


def _login_response(user: dict) -> JSONResponse:
    token = auth_store.create_session_token()
    expires = int(time.time()) + SESSION_DAYS * 86400
    auth_store.store_session(token, user["id"], expires)
    resp = JSONResponse({"ok": True, "user": user, "token": token})
    resp.set_cookie(**_session_cookie(token))
    return resp


@app.get("/login", response_class=HTMLResponse)
def login_page() -> FileResponse:
    return FileResponse(STATIC_DIR / "login.html")


@app.get("/", response_class=HTMLResponse, response_model=None)
def home(request: Request):
    if not session_user(request):
        return Response(status_code=302, headers={"Location": "/login"})
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/admin", response_class=HTMLResponse, response_model=None)
def admin_page(request: Request):
    user = session_user(request)
    if not user:
        return Response(status_code=302, headers={"Location": "/login"})
    if user.get("role") != "admin":
        return Response(status_code=302, headers={"Location": "/"})
    return FileResponse(STATIC_DIR / "admin.html")


@app.post("/api/auth/login")
def api_login(body: LoginIn) -> JSONResponse:
    user = auth_store.authenticate(body.username, body.password)
    if not user:
        raise HTTPException(status_code=401, detail="invalid_credentials")
    return _login_response(user)


@app.post("/api/auth/logout")
def api_logout(request: Request) -> JSONResponse:
    token = request.cookies.get(SESSION_COOKIE)
    if not token:
        auth = request.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            token = auth[7:].strip()
    if token:
        auth_store.delete_session(token)
    resp = JSONResponse({"ok": True})
    resp.delete_cookie(SESSION_COOKIE, path="/")
    return resp


@app.get("/api/auth/me")
def api_me(user: dict = Depends(require_user)) -> dict:
    return user


@app.post("/api/auth/change-password")
def api_change_password(
    body: ChangePasswordIn,
    user: dict = Depends(require_user),
) -> dict:
    ok, err = auth_store.change_own_password(
        user["id"], body.old_password, body.new_password
    )
    if err == "wrong_password":
        raise HTTPException(status_code=400, detail="wrong_password")
    if err == "password_short":
        raise HTTPException(status_code=400, detail="password_short")
    if not ok:
        raise HTTPException(status_code=400, detail="error")
    return {"ok": True}


@app.get("/api/admin/users", response_model=list[UserOut])
def api_list_users(_: dict = Depends(require_admin)) -> list[dict]:
    return auth_store.list_users()


@app.post("/api/admin/users", response_model=UserOut, status_code=201)
def api_create_user(
    body: CreateUserIn,
    admin: dict = Depends(require_admin),
) -> dict:
    user, err = auth_store.create_user(
        body.username, body.password, body.role, admin["id"]
    )
    if err == "duplicate":
        raise HTTPException(status_code=409, detail="duplicate")
    if err == "username_short":
        raise HTTPException(status_code=400, detail="username_short")
    if err == "password_short":
        raise HTTPException(status_code=400, detail="password_short")
    if err == "invalid_role":
        raise HTTPException(status_code=400, detail="invalid_role")
    if err == "empty" or user is None:
        raise HTTPException(status_code=400, detail="empty")
    rows = auth_store.list_users()
    return next((u for u in rows if u["id"] == user["id"]), user)


@app.delete("/api/admin/users/{user_id}")
def api_delete_user(user_id: int, admin: dict = Depends(require_admin)) -> dict:
    ok, err = auth_store.delete_user(user_id, admin["id"])
    if err == "self":
        raise HTTPException(status_code=400, detail="cannot_delete_self")
    if err == "not_found":
        raise HTTPException(status_code=404, detail="not_found")
    if not ok:
        raise HTTPException(status_code=400, detail="error")
    return {"ok": True}


@app.get("/api/admin/users/{user_id}/word-sources")
def api_get_word_sources(
    user_id: int,
    _: dict = Depends(require_admin),
) -> list[dict]:
    if not auth_store.get_user(user_id):
        raise HTTPException(status_code=404, detail="not_found")
    return grants_store.list_granted_sources(user_id)


@app.put("/api/admin/users/{user_id}/word-sources")
def api_set_word_sources(
    user_id: int,
    body: WordSourcesIn,
    _: dict = Depends(require_admin),
) -> list[dict]:
    if not auth_store.get_user(user_id):
        raise HTTPException(status_code=404, detail="not_found")
    return grants_store.set_granted_sources(user_id, body.source_user_ids)


@app.get("/api/word-sources")
def api_my_word_sources(user: dict = Depends(require_user)) -> list[dict]:
    if user["role"] == auth_store.ROLE_ADMIN:
        return auth_store.list_users()
    return grants_store.list_granted_sources(user["id"])


@app.put("/api/admin/users/{user_id}/password")
def api_reset_password(
    user_id: int,
    body: ResetPasswordIn,
    _: dict = Depends(require_admin),
) -> dict:
    ok, err = auth_store.reset_password(user_id, body.password)
    if err == "password_short":
        raise HTTPException(status_code=400, detail="password_short")
    if err == "not_found":
        raise HTTPException(status_code=404, detail="not_found")
    if not ok:
        raise HTTPException(status_code=400, detail="error")
    return {"ok": True}


@app.get("/api/pairs", response_model=list[WordPairOut])
def api_list_pairs(
    q: str = Query(default=""),
    user: dict = Depends(require_user),
) -> list[dict]:
    return repository.list_pairs(user, q)


@app.get("/api/pairs/export/csv")
def api_export_csv(user: dict = Depends(require_user)) -> Response:
    pairs = repository.list_pairs(user)
    buf = io.StringIO()
    buf.write("\ufeff")
    writer = csv.writer(buf)
    writer.writerow(["Dân thường", "Gián điệp"])
    for pair in pairs:
        writer.writerow([pair["civilian_word"], pair["spy_word"]])
    return Response(
        content=buf.getvalue(),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="phaohn_tukhoa.csv"'},
    )


@app.get("/api/pairs/{pair_id}", response_model=WordPairOut)
def api_get_pair(pair_id: int, user: dict = Depends(require_user)) -> dict:
    pair = repository.get_pair(pair_id, user)
    if not pair:
        raise HTTPException(status_code=404, detail="not_found")
    return pair


@app.post("/api/pairs", response_model=WordPairOut, status_code=201)
def api_create_pair(body: WordPairIn, user: dict = Depends(require_user)) -> dict:
    pair, err = repository.create_pair(body.civilian_word, body.spy_word, user)
    if err == "duplicate":
        raise HTTPException(status_code=409, detail="duplicate")
    if err == "same":
        raise HTTPException(status_code=400, detail="same_word")
    if err == "empty" or pair is None:
        raise HTTPException(status_code=400, detail="empty")
    return pair


@app.post("/api/pairs/bulk", response_model=ImportResult)
def api_bulk_text(body: BulkTextIn, user: dict = Depends(require_user)) -> dict:
    pairs, invalid = parse_comma_lines(body.text)
    if not pairs and invalid == 0:
        raise HTTPException(status_code=400, detail="empty")
    result = repository.import_many(pairs, user)
    result["invalid_format"] = invalid
    return result


@app.post("/api/pairs/import", response_model=ImportResult)
async def api_import_file(
    file: UploadFile = File(...),
    user: dict = Depends(require_user),
) -> dict:
    if not file.filename:
        raise HTTPException(status_code=400, detail="no_file")
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="empty_file")
    try:
        pairs, invalid = parse_upload(file.filename, data)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"parse_error:{exc}") from exc
    if not pairs and invalid == 0:
        raise HTTPException(status_code=400, detail="no_rows")
    result = repository.import_many(pairs, user)
    result["invalid_format"] = invalid
    return result


@app.put("/api/pairs/{pair_id}", response_model=WordPairOut)
def api_update_pair(
    pair_id: int,
    body: WordPairUpdate,
    user: dict = Depends(require_user),
) -> dict:
    pair, err = repository.update_pair(
        pair_id, body.civilian_word, body.spy_word, user
    )
    if err == "not_found":
        raise HTTPException(status_code=404, detail="not_found")
    if err == "duplicate":
        raise HTTPException(status_code=409, detail="duplicate")
    if err == "same":
        raise HTTPException(status_code=400, detail="same_word")
    if err == "empty" or pair is None:
        raise HTTPException(status_code=400, detail="empty")
    return pair


@app.post("/api/pairs/{pair_id}/adopt", response_model=WordPairOut)
def api_adopt_pair(pair_id: int, user: dict = Depends(require_user)) -> dict:
    pair, err = repository.adopt_pair(pair_id, user)
    if err == "not_found":
        raise HTTPException(status_code=404, detail="not_found")
    if err == "already_owned":
        return pair
    if err == "duplicate":
        raise HTTPException(status_code=409, detail="duplicate")
    if err == "same":
        raise HTTPException(status_code=400, detail="same_word")
    if err == "empty" or pair is None:
        raise HTTPException(status_code=400, detail="empty")
    return pair


@app.delete("/api/pairs/{pair_id}")
def api_delete_pair(pair_id: int, user: dict = Depends(require_user)) -> dict:
    if not repository.delete_pair(pair_id, user):
        raise HTTPException(status_code=404, detail="not_found")
    return {"ok": True}


@app.post("/api/pairs/report")
def api_report_by_words(
    body: ReportByWordsIn,
    user: dict = Depends(require_user),
) -> dict:
    pair_id = repository.find_pair_id_by_words(
        user, body.civilian_word, body.spy_word
    )
    if pair_id is None:
        raise HTTPException(status_code=404, detail="not_found")
    report, err = reports_store.create_report(
        pair_id,
        user,
        body.report_type,
        body.message,
        body.suggested_civilian,
        body.suggested_spy,
    )
    if err:
        raise HTTPException(status_code=400, detail=err)
    if report is None:
        raise HTTPException(status_code=400, detail="error")
    return report


@app.post("/api/pairs/{pair_id}/report")
def api_report_pair(
    pair_id: int,
    body: ReportIn,
    user: dict = Depends(require_user),
) -> dict:
    report, err = reports_store.create_report(
        pair_id,
        user,
        body.report_type,
        body.message,
        body.suggested_civilian,
        body.suggested_spy,
    )
    if err == "not_found":
        raise HTTPException(status_code=404, detail="not_found")
    if err == "invalid_type":
        raise HTTPException(status_code=400, detail="invalid_type")
    if report is None:
        raise HTTPException(status_code=400, detail="error")
    return report


@app.get("/api/reports")
def api_list_reports(user: dict = Depends(require_user)) -> list[dict]:
    return reports_store.list_reports(user)


@app.put("/api/admin/reports/{report_id}/resolve")
def api_resolve_report(
    report_id: int,
    status: str = Query(..., pattern="^(resolved|rejected)$"),
    _: dict = Depends(require_admin),
) -> dict:
    if not reports_store.resolve_report(report_id, status):
        raise HTTPException(status_code=404, detail="not_found")
    return {"ok": True}


@app.put("/api/admin/reports/{report_id}/approve")
def api_approve_report(
    report_id: int,
    admin: dict = Depends(require_admin),
) -> dict:
    report, err = reports_store.approve_report(report_id, admin)
    if err == "not_found":
        raise HTTPException(status_code=404, detail="not_found")
    if err == "empty_suggestion":
        raise HTTPException(status_code=400, detail="empty_suggestion")
    if err == "duplicate":
        raise HTTPException(status_code=409, detail="duplicate")
    if err == "same_word":
        raise HTTPException(status_code=400, detail="same_word")
    if err == "empty" or report is None:
        raise HTTPException(status_code=400, detail="error")
    return report


@app.post("/api/lookup", response_model=LookupOut)
def api_lookup(body: LookupIn, user: dict = Depends(require_user)) -> dict:
    return history_store.lookup_word(user, body.my_word)


@app.get("/api/history", response_model=list[HistoryOut])
def api_list_history(user: dict = Depends(require_user)) -> list[dict]:
    return history_store.list_history(user["id"])


@app.delete("/api/history")
def api_clear_history(user: dict = Depends(require_user)) -> dict:
    history_store.clear_history(user["id"])
    return {"ok": True}


@app.delete("/api/history/{item_id}")
def api_delete_history_item(
    item_id: int,
    user: dict = Depends(require_user),
) -> dict:
    if not history_store.delete_history_item(user["id"], item_id):
        raise HTTPException(status_code=404, detail="not_found")
    return {"ok": True}


@app.get("/api/history/export/csv")
def api_export_history_csv(user: dict = Depends(require_user)) -> Response:
    from datetime import datetime

    history = history_store.list_history(user["id"], limit=500)
    buf = io.StringIO()
    buf.write("\ufeff")
    writer = csv.writer(buf)
    writer.writerow(["Thời gian", "Từ của bạn", "Từ còn lại"])
    for entry in history:
        others = "; ".join(
            w for w in entry["other_words"].split("|") if w.strip()
        )
        ts = datetime.fromtimestamp(entry["played_at"] / 1000).strftime(
            "%d/%m/%Y %H:%M"
        )
        writer.writerow([ts, entry["my_word"], others])
    return Response(
        content=buf.getvalue(),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": 'attachment; filename="phaohn_lichsu.csv"'
        },
    )


@app.get("/api/sync/pull", response_model=list[WordPairOut])
def api_sync_pull(user: dict = Depends(require_user)) -> list[dict]:
    return repository.list_pairs(user)


@app.post("/api/sync/push", response_model=SyncResult)
def api_sync_push(body: SyncPushIn, user: dict = Depends(require_user)) -> dict:
    payload = [
        {"civilian_word": p.civilian_word, "spy_word": p.spy_word}
        for p in body.pairs
    ]
    return repository.push_pairs(payload, user)


@app.get("/api/app/version")
def api_app_version() -> dict:
    if VERSION_FILE.is_file():
        return json.loads(VERSION_FILE.read_text(encoding="utf-8"))
    return {"version": "unknown", "version_code": 0}


@app.get("/download/apk")
def download_apk() -> FileResponse:
    if not APK_PATH.is_file():
        raise HTTPException(status_code=404, detail="APK chưa có trên server")
    return FileResponse(
        APK_PATH,
        media_type="application/vnd.android.package-archive",
        filename="PhaoHN.apk",
    )


app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")