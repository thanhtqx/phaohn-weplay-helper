from pydantic import BaseModel, Field


class WordPairIn(BaseModel):
    civilian_word: str = Field(min_length=1, max_length=200)
    spy_word: str = Field(min_length=1, max_length=200)


class WordPairOut(WordPairIn):
    id: int
    saved_at: int
    user_id: int | None = None
    owner_username: str | None = None
    is_shared: bool = False
    can_edit: bool = True
    approval_status: str = "approved"
    user_hidden: bool = False


class WordSourcesIn(BaseModel):
    source_user_ids: list[int] = Field(default_factory=list)


class WordPairUpdate(BaseModel):
    civilian_word: str = Field(min_length=1, max_length=200)
    spy_word: str = Field(min_length=1, max_length=200)


class SyncPairIn(BaseModel):
    civilian_word: str = Field(min_length=1, max_length=200)
    spy_word: str = Field(min_length=1, max_length=200)
    origin: str = Field(default="manual", pattern="^(manual|capture|import|sync)$")


class SyncPushIn(BaseModel):
    pairs: list[SyncPairIn]


class SyncResult(BaseModel):
    added: int = 0
    pending: int = 0
    skipped: int = 0
    total: int = 0


class BulkTextIn(BaseModel):
    text: str = Field(min_length=1)


class ImportResult(BaseModel):
    added: int = 0
    pending: int = 0
    duplicate: int = 0
    empty: int = 0
    same_word: int = 0
    invalid_format: int = 0
    total: int = 0


class PendingPairOut(WordPairIn):
    id: int
    saved_at: int
    user_id: int
    owner_username: str | None = None
    approval_status: str = "pending"


class LoginIn(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=128)


class AuthMeOut(BaseModel):
    id: int
    username: str
    nickname: str | None = None
    role: str


class ProfileUpdateIn(BaseModel):
    nickname: str = Field(min_length=1, max_length=64)


class UserOut(BaseModel):
    id: int
    username: str
    nickname: str | None = None
    role: str
    created_at: int
    created_by_name: str | None = None
    is_locked: bool = False
    lock_reason: str | None = None


class LockAccountIn(BaseModel):
    reason: str = Field(min_length=1, max_length=500)


class CreateUserIn(BaseModel):
    username: str = Field(min_length=3, max_length=64)
    password: str = Field(min_length=6, max_length=128)
    role: str = "user"


class RoleUpdateIn(BaseModel):
    role: str = Field(pattern="^(user|admin)$")


class ResetPasswordIn(BaseModel):
    password: str = Field(min_length=6, max_length=128)


class ChangePasswordIn(BaseModel):
    old_password: str = Field(min_length=1, max_length=128)
    new_password: str = Field(min_length=6, max_length=128)


class ReportIn(BaseModel):
    report_type: str = Field(pattern="^(wrong|suggest_edit)$")
    message: str = Field(default="", max_length=500)
    suggested_civilian: str = Field(default="", max_length=200)
    suggested_spy: str = Field(default="", max_length=200)


class ReportByWordsIn(ReportIn):
    civilian_word: str = Field(min_length=1, max_length=200)
    spy_word: str = Field(min_length=1, max_length=200)


class LookupIn(BaseModel):
    my_word: str = Field(max_length=200)


class LabeledWordOut(BaseModel):
    word: str
    role: str


class LookupOut(BaseModel):
    status: str
    my_word: str | None = None
    my_role: str | None = None
    others: list[LabeledWordOut] = Field(default_factory=list)


class HistoryOut(BaseModel):
    id: int
    my_word: str
    other_words: str
    played_at: int


class AdminNotificationIn(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    body: str = Field(min_length=1, max_length=2000)
    target_user_id: int | None = None


class AdminNotificationOut(BaseModel):
    id: int
    title: str
    body: str
    target_user_id: int | None = None
    target_username: str | None = None
    created_at: int
    created_by: int
    created_by_name: str | None = None


class InboxNotificationOut(BaseModel):
    id: int
    title: str
    body: str
    created_at: int
    target_user_id: int | None = None
    read: bool = False
    read_at: int | None = None


class NotificationAckIn(BaseModel):
    notification_ids: list[int] = Field(default_factory=list)