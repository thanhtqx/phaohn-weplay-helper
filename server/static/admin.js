let currentUser = null;
let wordSourcesTargetUser = null;
let usersCache = null;
let reportsLoaded = false;
let notificationsLoaded = false;
let approvalsLoaded = false;
let wordsLoaded = false;
let adminAllPairs = [];
let adminEditingId = null;
let adminDialogMode = "single";
let adminWordFilter = "all";
let adminDidAutoPending = false;

const adminUsersEl = document.getElementById("adminUsers");
const adminApprovalsEl = document.getElementById("adminApprovals");
const adminApprovalsEmptyEl = document.getElementById("adminApprovalsEmpty");
const adminNotificationsListEl = document.getElementById("adminNotificationsList");
const adminNotificationsEmptyEl = document.getElementById("adminNotificationsEmpty");
const adminReportsEl = document.getElementById("adminReports");
const adminReportsEmptyEl = document.getElementById("adminReportsEmpty");
const createUserForm = document.getElementById("createUserForm");
const createUserErrorEl = document.getElementById("createUserError");
const wordSourcesDialogEl = document.getElementById("wordSourcesDialog");
const wordSourcesListEl = document.getElementById("wordSourcesList");
const wordSourcesTitleEl = document.getElementById("wordSourcesTitle");
const wordSourcesErrorEl = document.getElementById("wordSourcesError");

const TAB_META = {
  words: {
    title: "Từ khóa",
    sub: "Quản lý toàn bộ danh sách từ — thêm, sửa, nhập/xuất file.",
  },
  users: {
    title: "Tài khoản",
    sub: "Tạo tài khoản, đổi mật khẩu và cấp nguồn từ.",
  },
  notifications: {
    title: "Thông báo",
    sub: "Gửi thông báo tới một người hoặc toàn bộ — app chỉ tải khi user đồng bộ/mở app.",
  },
  approvals: {
    title: "Duyệt từ",
    sub: "Duyệt từ user thêm thủ công — sau khi duyệt mới dùng được tra cứu và đồng bộ.",
  },
  reports: {
    title: "Báo cáo",
    sub: "Duyệt báo cáo từ sai hoặc áp dụng đề xuất sửa.",
  },
};

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body && !(options.body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
  }
  const res = await fetch(path, { ...options, headers, credentials: "same-origin" });
  if (res.status === 401) {
    window.location.href = "/admin/login";
    throw new Error("unauthorized");
  }
  if (!res.ok) {
    let detail = "error";
    try {
      const data = await res.json();
      detail = data.detail || detail;
      if (detail && typeof detail === "object" && detail.code === "account_locked") {
        sessionStorage.setItem("phaohn_lock_message", detail.message || "");
        window.location.href = "/admin/login";
        throw new Error("account_locked");
      }
    } catch (e) {
      if (e.message === "account_locked") throw e;
    }
    throw new Error(typeof detail === "string" ? detail : "error");
  }
  if (res.status === 204) return null;
  const type = res.headers.get("content-type") || "";
  if (type.includes("application/json")) return res.json();
  return res;
}

function escapeHtml(text) {
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function highlightMatch(text, query) {
  const raw = String(text ?? "");
  if (!query) return escapeHtml(raw);
  const ql = query.toLowerCase();
  const lower = raw.toLowerCase();
  const idx = lower.indexOf(ql);
  if (idx < 0) return escapeHtml(raw);
  const before = escapeHtml(raw.slice(0, idx));
  const hit = escapeHtml(raw.slice(idx, idx + query.length));
  const after = escapeHtml(raw.slice(idx + query.length));
  return `${before}<mark class="search-hit">${hit}</mark>${after}`;
}

async function copyWordText(text, btn) {
  try {
    await navigator.clipboard.writeText(text);
    const prev = btn.innerHTML;
    btn.innerHTML = '<i class="bi bi-check2"></i>';
    btn.classList.add("copy-ok");
    setTimeout(() => {
      btn.innerHTML = prev;
      btn.classList.remove("copy-ok");
    }, 1200);
  } catch (_) {
    window.prompt("Sao chép:", text);
  }
}

function wordCellHtml(kind, word, query) {
  const safe = highlightMatch(word, query);
  const wordClass = kind === "civilian" ? "tbl-word-civilian" : "tbl-word-spy";
  return `<div class="admin-word-cell">
    <span class="tbl-word ${wordClass}" title="${escapeHtml(word)}">${safe}</span>
    <button type="button" class="admin-table-btn word-copy-btn" title="Sao chép" aria-label="Sao chép"><i class="bi bi-clipboard"></i></button>
  </div>`;
}

function wordPairHtml(civilian, spy, sep = "·") {
  return `<span class="tbl-word tbl-word-civilian">${escapeHtml(civilian)}</span><span class="tbl-word-sep">${sep}</span><span class="tbl-word tbl-word-spy">${escapeHtml(spy)}</span>`;
}

function makeActionGroup() {
  const wrap = document.createElement("div");
  wrap.className = "admin-table-actions";
  return wrap;
}

function makeBsActionBtn(variant, html, title) {
  const btn = document.createElement("button");
  btn.type = "button";
  let tone = "neutral";
  if (variant === "success") tone = "success";
  else if (variant === "outline-danger" || variant === "danger") tone = "danger";
  else if (variant === "outline-primary") tone = "primary";
  else if (variant === "outline-success") tone = "success";
  else if (variant === "outline-info") tone = "primary";
  else if (variant === "outline-secondary") tone = "neutral";
  btn.className = `admin-table-btn admin-table-btn-${tone}`;
  if (title) btn.title = title;
  btn.innerHTML = html;
  return btn;
}

function roleLabel(role) {
  if (role === "superadmin") return "Super Admin";
  if (role === "admin") return "Admin";
  return "Người dùng";
}

function isStaffRole(role) {
  return role === "admin" || role === "superadmin";
}

function userInitial(name) {
  return (name || "?").charAt(0).toUpperCase();
}

function reportTypeLabel(type) {
  return type === "suggest_edit" ? "Đề xuất sửa" : "Từ sai";
}

function reportStatusLabel(status) {
  if (status === "resolved") return '<span class="tbl-status tbl-status-done">Đã xử lý</span>';
  if (status === "rejected") return '<span class="tbl-status tbl-status-muted">Từ chối</span>';
  return '<span class="tbl-status tbl-status-pending">Chờ duyệt</span>';
}

function closeAdminMobileMore() {
  document.getElementById("adminMobileMore")?.classList.add("hidden");
}

function setAdminTab(tab) {
  closeAdminMobileMore();
  const moreTabs = new Set(["notifications", "reports"]);
  document.querySelectorAll(".admin-menu-item, .mobile-bottom-nav-item[data-tab]").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.tab === tab);
  });
  document.getElementById("adminNavMore")?.classList.toggle("active", moreTabs.has(tab));
  document.getElementById("adminPanelWords").classList.toggle("hidden", tab !== "words");
  document.getElementById("adminPanelUsers").classList.toggle("hidden", tab !== "users");
  document.getElementById("adminPanelNotifications").classList.toggle("hidden", tab !== "notifications");
  document.getElementById("adminPanelApprovals").classList.toggle("hidden", tab !== "approvals");
  document.getElementById("adminPanelReports").classList.toggle("hidden", tab !== "reports");
  const meta = TAB_META[tab];
  document.getElementById("adminPageTitle").textContent = meta.title;
  document.getElementById("adminPageSub").textContent = meta.sub;
  if (tab === "words") loadAdminWords();
  if (tab === "users") {
    if (usersCache) renderAdminUsers(usersCache);
    else loadAdminUsers();
  }
  if (tab === "notifications") ensureNotificationsTab();
  if (tab === "approvals") loadAdminApprovals();
  if (tab === "reports" && !reportsLoaded) loadAdminReports();
  window.phaohnLayout?.();
}

async function loadAdminApprovals() {
  const items = await api("/api/admin/pending-pairs");
  approvalsLoaded = true;
  updatePendingBadges(items.length, items);
  adminApprovalsEmptyEl.classList.toggle("hidden", items.length > 0);
  const frag = document.createDocumentFragment();
  items.forEach((p, idx) => {
    const tr = document.createElement("tr");

    tr.innerHTML = `
      <td class="text-center tbl-stt">${idx + 1}</td>
      <td>${wordPairHtml(p.civilian_word, p.spy_word)}</td>
      <td><span class="tbl-meta">${escapeHtml(p.owner_username || "—")}</span></td>
      <td class="text-nowrap">${escapeHtml(formatNotifTime(p.saved_at))}</td>
      <td class="text-center"></td>`;
    const actions = makeActionGroup();
    const okBtn = makeBsActionBtn("success", '<i class="bi bi-check-lg"></i>', "Duyệt");
    okBtn.onclick = async () => {
      await api(`/api/admin/pending-pairs/${p.id}/approve`, { method: "PUT" });
      wordsLoaded = false;
      await Promise.all([loadAdminApprovals(), loadAdminWords()]);
    };
    const noBtn = makeBsActionBtn("outline-danger", '<i class="bi bi-x-lg"></i>', "Từ chối");
    noBtn.onclick = async () => {
      if (!confirm("Từ chối và xóa cặp từ này?")) return;
      await api(`/api/admin/pending-pairs/${p.id}`, { method: "DELETE" });
      await loadAdminApprovals();
    };
    actions.append(okBtn, noBtn);
    tr.lastElementChild.append(actions);
    frag.append(tr);
  });
  adminApprovalsEl.replaceChildren(frag);
  window.phaohnLayout?.();
}

function formatNotifTime(ms) {
  try {
    return new Date(ms).toLocaleString("vi-VN", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch (_) {
    return "—";
  }
}

function fillNotificationTargets(users) {
  const sel = document.getElementById("notifTarget");
  const current = sel.value;
  sel.replaceChildren();
  const allOpt = document.createElement("option");
  allOpt.value = "";
  allOpt.textContent = "Tất cả người dùng";
  sel.append(allOpt);
  for (const u of users) {
    if (u.role !== "user") continue;
    const opt = document.createElement("option");
    opt.value = String(u.id);
    opt.textContent = u.username;
    sel.append(opt);
  }
  if ([...sel.options].some((o) => o.value === current)) sel.value = current;
}

async function ensureNotificationsTab() {
  if (!usersCache) {
    usersCache = await api("/api/admin/users");
  }
  fillNotificationTargets(usersCache);
  if (!notificationsLoaded) await loadAdminNotifications();
}

async function loadAdminNotifications() {
  const items = await api("/api/admin/notifications");
  notificationsLoaded = true;
  const countEl = document.getElementById("adminNotifCount");
  if (countEl) countEl.textContent = String(items.length);
  adminNotificationsEmptyEl.classList.toggle("hidden", items.length > 0);
  if (!adminNotificationsListEl) return;
  const frag = document.createDocumentFragment();
  items.forEach((n) => {
    const target = n.target_username ? n.target_username : "Tất cả";
    const card = document.createElement("article");
    card.className = "admin-notif-card";
    card.innerHTML = `
      <div class="admin-notif-card-head">
        <h4 class="admin-notif-card-title">${escapeHtml(n.title)}</h4>
        <time class="admin-notif-card-time">${escapeHtml(formatNotifTime(n.created_at))}</time>
      </div>
      <p class="admin-notif-card-body">${escapeHtml(n.body)}</p>
      <div class="admin-notif-card-foot">
        <span class="admin-notif-card-target"><i class="bi bi-people"></i> ${escapeHtml(target)}</span>
      </div>`;
    frag.append(card);
  });
  adminNotificationsListEl.replaceChildren(frag);
  window.phaohnLayout?.();
}

document.getElementById("adminNav").addEventListener("click", (e) => {
  const tab = e.target.closest(".admin-menu-item")?.dataset?.tab;
  if (tab) setAdminTab(tab);
});

document.getElementById("adminMobileBottomNav")?.addEventListener("click", (e) => {
  const moreBtn = e.target.closest("#adminNavMore");
  if (moreBtn) {
    document.getElementById("adminMobileMore")?.classList.toggle("hidden");
    return;
  }
  const tab = e.target.closest(".mobile-bottom-nav-item[data-tab]")?.dataset?.tab;
  if (tab) setAdminTab(tab);
});

document.getElementById("adminMobileMore")?.addEventListener("click", (e) => {
  const tab = e.target.closest("[data-tab]")?.dataset?.tab;
  if (tab) setAdminTab(tab);
});

document.addEventListener("click", (e) => {
  if (!e.target.closest("#adminMobileMore") && !e.target.closest("#adminNavMore")) {
    closeAdminMobileMore();
  }
});

function userRoleBadge(role) {
  if (role === "superadmin") return '<span class="tbl-role tbl-role-super">Super Admin</span>';
  if (role === "admin") return '<span class="tbl-role tbl-role-admin">Admin</span>';
  return '<span class="tbl-text tbl-muted">Người dùng</span>';
}

function userStatusBadge(u) {
  if (u.is_locked) return '<span class="tbl-status tbl-status-locked">Đã khóa</span>';
  return '<span class="tbl-status tbl-status-active">Hoạt động</span>';
}

function updateUsersStats(users) {
  const total = users.length;
  const admins = users.filter((u) => isStaffRole(u.role)).length;
  const locked = users.filter((u) => u.is_locked).length;
  const active = total - locked;
  document.getElementById("statUsersTotal").textContent = String(total);
  document.getElementById("statUsersAdmin").textContent = String(admins);
  document.getElementById("statUsersActive").textContent = String(active);
  document.getElementById("statUsersLocked").textContent = String(locked);
  document.getElementById("statUsersLockedWrap")?.classList.toggle("hidden", locked === 0);
}

function renderAdminUsers(users) {
  const q = document.getElementById("adminUserSearch")?.value?.trim().toLowerCase() || "";
  const filtered = q
    ? users.filter((u) => u.username.toLowerCase().includes(q) || (u.created_by_name || "").toLowerCase().includes(q))
    : users;
  const desc = document.getElementById("usersListDesc");
  if (desc) {
    desc.textContent = q ? `${filtered.length}/${users.length} tài khoản` : `${users.length} tài khoản`;
  }
  const emptyEl = document.getElementById("adminUsersEmpty");
  const tableEl = adminUsersEl?.closest("table");
  if (!filtered.length) {
    emptyEl?.classList.remove("hidden");
    tableEl?.classList.add("hidden");
    adminUsersEl?.replaceChildren();
    return;
  }
  emptyEl?.classList.add("hidden");
  tableEl?.classList.remove("hidden");

  const frag = document.createDocumentFragment();
  filtered.forEach((u, idx) => {
    const isSelf = currentUser && u.id === currentUser.id;
    const tr = document.createElement("tr");

    tr.innerHTML = `
      <td class="text-center tbl-stt">${idx + 1}</td>
      <td>
        <span class="tbl-user-name">${escapeHtml(u.username)}</span>${isSelf ? '<span class="tbl-note">bạn</span>' : ""}
      </td>
      <td>${userRoleBadge(u.role)}</td>
      <td>${userStatusBadge(u)}</td>
      <td><span class="tbl-meta">${escapeHtml(u.created_by_name || "—")}</span></td>
      <td class="text-center"></td>`;
    const actions = makeActionGroup();
    if (!isSelf) {
      if (currentUser?.role === "superadmin" && u.role === "user") {
        const promoteBtn = makeBsActionBtn("outline-primary", '<i class="bi bi-arrow-up-circle"></i>', "Nâng lên Admin");
        promoteBtn.onclick = async () => {
          if (!confirm(`Nâng ${u.username} lên Admin?`)) return;
          try {
            await api(`/api/admin/users/${u.id}/role`, {
              method: "PUT",
              body: JSON.stringify({ role: "admin" }),
            });
            usersCache = null;
            await loadAdminUsers();
          } catch (err) {
            alert(err.message === "forbidden" ? "Chỉ Super Admin mới nâng quyền được." : "Không nâng quyền được.");
          }
        };
        actions.append(promoteBtn);
      }
      if (currentUser?.role === "superadmin" && u.role === "admin") {
        const demoteBtn = makeBsActionBtn("outline-secondary", '<i class="bi bi-arrow-down-circle"></i>', "Hạ về User");
        demoteBtn.onclick = async () => {
          if (!confirm(`Hạ ${u.username} về Người dùng?`)) return;
          try {
            await api(`/api/admin/users/${u.id}/role`, {
              method: "PUT",
              body: JSON.stringify({ role: "user" }),
            });
            usersCache = null;
            await loadAdminUsers();
          } catch (err) {
            alert("Không hạ quyền được.");
          }
        };
        actions.append(demoteBtn);
      }
      if (u.role === "user") {
        const sourcesBtn = makeBsActionBtn("outline-info", '<i class="bi bi-journal-text"></i>', "Nguồn từ");
        sourcesBtn.onclick = () => openWordSourcesDialog(u);
        actions.append(sourcesBtn);
      }
      if (u.is_locked) {
        const unlockBtn = makeBsActionBtn("outline-success", '<i class="bi bi-unlock"></i>', "Mở khóa");
        unlockBtn.onclick = async () => {
          if (!confirm(`Mở khóa tài khoản ${u.username}?`)) return;
          try {
            await api(`/api/admin/users/${u.id}/unlock`, { method: "PUT" });
            usersCache = null;
            await loadAdminUsers();
          } catch (err) {
            alert(err.message === "cannot_unlock_self" ? "Không thể thao tác trên chính bạn." : "Không mở khóa được.");
          }
        };
        actions.append(unlockBtn);
      } else {
        const lockBtn = makeBsActionBtn("outline-danger", '<i class="bi bi-lock"></i>', "Khóa");
        lockBtn.onclick = async () => {
          const reason = prompt(`Lý do khóa ${u.username} (user sẽ thấy khi đăng nhập):`);
          if (!reason || !reason.trim()) return;
          try {
            await api(`/api/admin/users/${u.id}/lock`, {
              method: "PUT",
              body: JSON.stringify({ reason: reason.trim() }),
            });
            usersCache = null;
            await loadAdminUsers();
            alert(`Đã khóa ${u.username}. Phiên đăng nhập của họ đã bị đăng xuất.`);
          } catch (err) {
            const map = {
              reason_required: "Cần nhập lý do khóa.",
              cannot_lock_self: "Không thể khóa chính bạn.",
            };
            alert(map[err.message] || "Không khóa được.");
          }
        };
        actions.append(lockBtn);
      }
      const resetBtn = makeBsActionBtn("outline-secondary", '<i class="bi bi-key"></i>', "Đổi mật khẩu");
      resetBtn.onclick = async () => {
        const pw = prompt(`Mật khẩu mới cho ${u.username}:`);
        if (!pw) return;
        try {
          await api(`/api/admin/users/${u.id}/password`, {
            method: "PUT",
            body: JSON.stringify({ password: pw }),
          });
          alert("Đã đổi mật khẩu.");
        } catch (err) {
          alert(err.message === "password_short" ? "Mật khẩu tối thiểu 6 ký tự." : "Không đổi được.");
        }
      };
      const delBtn = makeBsActionBtn("outline-danger", '<i class="bi bi-trash"></i>', "Xóa");
      delBtn.onclick = async () => {
        if (!confirm(`Xóa tài khoản ${u.username}?`)) return;
        await api(`/api/admin/users/${u.id}`, { method: "DELETE" });
        usersCache = null;
        await loadAdminUsers();
      };
      actions.append(resetBtn, delBtn);
    } else {
      actions.innerHTML = '<span class="tbl-note">Đang đăng nhập</span>';
    }
    tr.lastElementChild.append(actions);
    frag.append(tr);
  });
  adminUsersEl.replaceChildren(frag);
  window.phaohnLayout?.();
}

async function loadAdminUsers() {
  const users = await api("/api/admin/users");
  usersCache = users;
  updateUsersStats(users);
  renderAdminUsers(users);
}

document.getElementById("adminUserSearch")?.addEventListener("input", () => {
  if (usersCache) renderAdminUsers(usersCache);
});

async function loadAdminReports() {
  const reports = await api("/api/reports");
  reportsLoaded = true;
  const frag = document.createDocumentFragment();
  adminReportsEmptyEl.classList.toggle("hidden", reports.length > 0);
  const pending = reports.filter((r) => r.status === "pending").length;
  const badge = document.getElementById("reportsPendingBadge");
  badge.textContent = String(pending);
  badge.classList.toggle("hidden", pending === 0);

  reports.forEach((r, idx) => {
    const tr = document.createElement("tr");
    const detail = r.report_type === "suggest_edit"
      ? ` → ${r.suggested_civilian || "—"} / ${r.suggested_spy || "—"}`
      : "";
    const note = r.message ? ` (${r.message})` : "";

    tr.innerHTML = `
      <td class="text-center tbl-stt">${idx + 1}</td>
      <td>${wordPairHtml(r.civilian_word, r.spy_word)}${detail || note ? `<span class="tbl-note">${escapeHtml(detail + note)}</span>` : ""}</td>
      <td><span class="tbl-meta">${reportTypeLabel(r.report_type)}</span></td>
      <td><span class="tbl-meta">${escapeHtml(r.reporter_name || "—")}</span></td>
      <td>${reportStatusLabel(r.status)}</td>
      <td class="text-center"></td>`;
    const actions = makeActionGroup();
    if (r.status === "pending") {
      const okBtn = r.report_type === "suggest_edit"
        ? makeBsActionBtn("success", '<i class="bi bi-check-lg"></i>', "Áp dụng")
        : makeBsActionBtn("success", '<i class="bi bi-check-lg"></i>', "Đã xử lý");
      okBtn.onclick = async () => {
        if (r.report_type === "suggest_edit") {
          await api(`/api/admin/reports/${r.id}/approve`, { method: "PUT" });
        } else {
          await api(`/api/admin/reports/${r.id}/resolve?status=resolved`, { method: "PUT" });
        }
        reportsLoaded = false;
        await loadAdminReports();
      };
      const noBtn = makeBsActionBtn("outline-danger", '<i class="bi bi-x-lg"></i>', "Từ chối");
      noBtn.onclick = async () => {
        await api(`/api/admin/reports/${r.id}/resolve?status=rejected`, { method: "PUT" });
        reportsLoaded = false;
        await loadAdminReports();
      };
      actions.append(okBtn, noBtn);
      tr.lastElementChild.append(actions);
    } else {
      tr.lastElementChild.textContent = "—";
    }
    frag.append(tr);
  });
  adminReportsEl.replaceChildren(frag);
  window.phaohnLayout?.();
}

async function openWordSourcesDialog(user) {
  wordSourcesTargetUser = user;
  wordSourcesTitleEl.textContent = `Nguồn từ cho ${user.username}`;
  wordSourcesErrorEl.classList.add("hidden");
  const [allUsers, granted] = await Promise.all([
    usersCache ? Promise.resolve(usersCache) : api("/api/admin/users"),
    api(`/api/admin/users/${user.id}/word-sources`),
  ]);
  if (!usersCache) usersCache = allUsers;
  const grantedIds = new Set(granted.map((g) => g.id));
  const frag = document.createDocumentFragment();
  for (const src of allUsers) {
    if (src.id === user.id) continue;
    const label = document.createElement("label");
    label.className = "check-list-item";
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.value = String(src.id);
    cb.checked = grantedIds.has(src.id);
    label.append(cb, ` ${src.username} (${roleLabel(src.role)})`);
    frag.append(label);
  }
  wordSourcesListEl.replaceChildren(frag);
  wordSourcesDialogEl.showModal();
}

document.getElementById("btnWordSourcesCancel").addEventListener("click", () => wordSourcesDialogEl.close());
document.getElementById("btnWordSourcesSave").addEventListener("click", async () => {
  if (!wordSourcesTargetUser) return;
  wordSourcesErrorEl.classList.add("hidden");
  const ids = [...wordSourcesListEl.querySelectorAll("input:checked")].map((el) => Number(el.value));
  try {
    await api(`/api/admin/users/${wordSourcesTargetUser.id}/word-sources`, {
      method: "PUT",
      body: JSON.stringify({ source_user_ids: ids }),
    });
    wordSourcesDialogEl.close();
    alert(`Đã cập nhật nguồn từ cho ${wordSourcesTargetUser.username}.`);
  } catch (_) {
    wordSourcesErrorEl.textContent = "Không lưu được.";
    wordSourcesErrorEl.classList.remove("hidden");
  }
});

document.getElementById("sendNotificationForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const errEl = document.getElementById("sendNotificationError");
  const okEl = document.getElementById("sendNotificationOk");
  errEl.classList.add("hidden");
  okEl.classList.add("hidden");
  const targetRaw = document.getElementById("notifTarget").value;
  const body = {
    title: document.getElementById("notifTitle").value,
    body: document.getElementById("notifBody").value,
    target_user_id: targetRaw ? Number(targetRaw) : null,
  };
  try {
    await api("/api/admin/notifications", {
      method: "POST",
      body: JSON.stringify(body),
    });
    document.getElementById("sendNotificationForm").reset();
    okEl.textContent = "Đã gửi. Người dùng sẽ thấy khi họ mở app hoặc đồng bộ.";
    okEl.classList.remove("hidden");
    notificationsLoaded = false;
    await loadAdminNotifications();
  } catch (err) {
    const map = {
      empty: "Tiêu đề và nội dung không được trống.",
      user_not_found: "Không tìm thấy người dùng.",
    };
    errEl.textContent = map[err.message] || "Không gửi được.";
    errEl.classList.remove("hidden");
  }
});

createUserForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  createUserErrorEl.classList.add("hidden");
  try {
    await api("/api/admin/users", {
      method: "POST",
      body: JSON.stringify({
        username: document.getElementById("newUsername").value,
        password: document.getElementById("newPassword").value,
        role: document.getElementById("newRole").value,
      }),
    });
    createUserForm.reset();
    usersCache = null;
    await loadAdminUsers();
  } catch (err) {
    const map = {
      duplicate: "Tên đăng nhập đã tồn tại.",
      username_short: "Tên đăng nhập tối thiểu 3 ký tự.",
      password_short: "Mật khẩu tối thiểu 6 ký tự.",
    };
    createUserErrorEl.textContent = map[err.message] || "Không tạo được.";
    createUserErrorEl.classList.remove("hidden");
  }
});

async function adminDoLogout() {
  await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
  window.location.href = "/admin/login";
}

document.getElementById("btnLogout").addEventListener("click", adminDoLogout);

/* ── Admin words ── */
const adminWordsEl = document.getElementById("adminWords");
const adminWordsEmptyEl = document.getElementById("adminWordsEmpty");
const adminWordSearchEl = document.getElementById("adminWordSearch");
const adminSearchClearEl = document.getElementById("adminSearchClear");
const adminWordDialogEl = document.getElementById("adminWordDialog");
const adminWordFormEl = document.getElementById("adminWordForm");
const adminResultDialogEl = document.getElementById("adminResultDialog");

function approvalLabel(status) {
  if (status === "pending") return '<span class="tbl-status tbl-status-pending">Chờ duyệt</span>';
  return '<span class="tbl-status tbl-status-done">Đã duyệt</span>';
}

function getPendingPairs(list) {
  return list.filter((p) => p.approval_status === "pending");
}

function renderAdminBellDropdown(pendingList) {
  const listEl = document.getElementById("adminBellList");
  if (!listEl) return;
  if (!pendingList.length) {
    listEl.innerHTML = '<p class="bell-empty">Không có từ chờ duyệt</p>';
    return;
  }
  listEl.innerHTML = pendingList.slice(0, 8).map((p) => `
    <div class="bell-item bell-item-static">
      <span class="bell-item-title">${escapeHtml(p.civilian_word)} · ${escapeHtml(p.spy_word)}</span>
      <span class="bell-item-body">User: ${escapeHtml(p.owner_username || "—")}</span>
      <div class="bell-item-actions">
        <button type="button" class="btn btn-secondary btn-sm" data-admin-approve="${p.id}"><i class="bi bi-check-lg"></i> Duyệt</button>
        <button type="button" class="btn btn-secondary btn-sm btn-danger-text" data-admin-reject="${p.id}"><i class="bi bi-x-lg"></i></button>
      </div>
    </div>`).join("");
}

function updatePendingBadges(pendingCount, pendingList = []) {
  const apprBadge = document.getElementById("approvalsPendingBadge");
  apprBadge.textContent = String(pendingCount);
  apprBadge.classList.toggle("hidden", pendingCount === 0);
  const filterCount = document.getElementById("pendingFilterCount");
  filterCount.textContent = String(pendingCount);
  filterCount.classList.toggle("hidden", pendingCount === 0);
  const badgeLabel = pendingCount > 9 ? "9+" : String(pendingCount);
  const bellBadge = document.getElementById("adminBellBadge");
  if (bellBadge) {
    bellBadge.textContent = badgeLabel;
    bellBadge.classList.toggle("hidden", pendingCount === 0);
  }
  const mobilePending = document.getElementById("mobileAdminPendingBadge");
  if (mobilePending) {
    mobilePending.textContent = badgeLabel;
    mobilePending.classList.toggle("hidden", pendingCount === 0);
  }
  document.getElementById("adminBtnBell")?.classList.toggle("bell-btn-active", pendingCount > 0);
  const banner = document.getElementById("adminPendingBanner");
  const bannerText = document.getElementById("adminPendingBannerText");
  if (pendingCount > 0) {
    banner?.classList.remove("hidden");
    if (bannerText) bannerText.textContent = `${pendingCount} cặp từ user chờ duyệt — bấm chuông hoặc lọc "Chờ duyệt"`;
  } else {
    banner?.classList.add("hidden");
  }
  if (!document.getElementById("adminBellDropdown")?.classList.contains("hidden")) {
    renderAdminBellDropdown(pendingList);
  }
}

function updateAdminSearchClearBtn() {
  if (!adminSearchClearEl || !adminWordSearchEl) return;
  adminSearchClearEl.classList.toggle("hidden", !adminWordSearchEl.value.trim());
}

function renderAdminWords(list) {
  updateAdminSearchClearBtn();
  const q = adminWordSearchEl?.value?.trim() || "";
  let filtered = list;
  if (adminWordFilter === "pending") {
    filtered = filtered.filter((p) => p.approval_status === "pending");
  } else if (adminWordFilter === "approved") {
    filtered = filtered.filter((p) => p.approval_status !== "pending");
  }
  if (q) {
    const ql = q.toLowerCase();
    filtered = filtered.filter(
      (p) =>
        p.civilian_word.toLowerCase().includes(ql) ||
        p.spy_word.toLowerCase().includes(ql) ||
        (p.owner_username || "").toLowerCase().includes(ql)
    );
  }
  const pendingList = getPendingPairs(list);
  const pending = pendingList.length;
  const approved = list.length - pending;
  const showingLabel = document.getElementById("adminStatShowingLabel");
  document.getElementById("adminStatShowing").textContent = String(filtered.length);
  document.getElementById("adminStatTotal").textContent = String(list.length);
  document.getElementById("adminStatPending").textContent = String(pending);
  document.getElementById("adminStatApproved").textContent = String(approved);
  if (showingLabel) {
    const filteredLabel = q || adminWordFilter !== "all";
    showingLabel.textContent = filteredLabel ? "Khớp lọc" : "Đang hiển thị";
  }
  document.getElementById("adminStatPendingWrap")?.classList.toggle("hidden", pending === 0);
  const badge = document.getElementById("wordsCountBadge");
  badge.textContent = String(list.length);
  badge.classList.toggle("hidden", list.length === 0);
  updatePendingBadges(pending, pendingList);
  adminWordsEmptyEl.classList.toggle("hidden", filtered.length > 0);
  const frag = document.createDocumentFragment();
  filtered.forEach((pair, idx) => {
    const isPending = pair.approval_status === "pending";
    const tr = document.createElement("tr");

    const hiddenTag = pair.user_hidden ? '<span class="tbl-note">đã xóa</span>' : "";
    tr.innerHTML = `
      <td class="text-center tbl-stt">${idx + 1}</td>
      <td>${wordCellHtml("civilian", pair.civilian_word, q)}</td>
      <td>${wordCellHtml("spy", pair.spy_word, q)}</td>
      <td><span class="tbl-meta" title="${escapeHtml(pair.owner_username || "")}">${highlightMatch(pair.owner_username || "—", q)}</span></td>
      <td>${approvalLabel(pair.approval_status)}${hiddenTag}</td>
      <td class="text-center"></td>`;
    tr.querySelectorAll(".word-copy-btn").forEach((btn, i) => {
      const word = i === 0 ? pair.civilian_word : pair.spy_word;
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        copyWordText(word, btn);
      });
    });
    const actions = makeActionGroup();
    if (isPending) {
      const okBtn = makeBsActionBtn("success", '<i class="bi bi-check-lg"></i>', "Duyệt");
      okBtn.onclick = async () => {
        await api(`/api/admin/pending-pairs/${pair.id}/approve`, { method: "PUT" });
        wordsLoaded = false;
        approvalsLoaded = false;
        await Promise.all([loadAdminWords(), loadAdminApprovals()]);
      };
      const noBtn = makeBsActionBtn("outline-danger", '<i class="bi bi-x-lg"></i>', "Từ chối");
      noBtn.onclick = async () => {
        if (!confirm("Từ chối và xóa cặp từ này?")) return;
        await api(`/api/admin/pending-pairs/${pair.id}`, { method: "DELETE" });
        wordsLoaded = false;
        approvalsLoaded = false;
        await Promise.all([loadAdminWords(), loadAdminApprovals()]);
      };
      actions.append(okBtn, noBtn);
    } else {
      const editBtn = makeBsActionBtn("outline-primary", '<i class="bi bi-pencil"></i>', "Sửa");
      editBtn.onclick = () => openAdminWordDialog(pair);
      const delBtn = makeBsActionBtn("outline-danger", '<i class="bi bi-trash"></i>', "Xóa");
      delBtn.onclick = async () => {
        if (!confirm("Xóa vĩnh viễn cặp từ này? Hành động không thể hoàn tác.")) return;
        await api(`/api/pairs/${pair.id}`, { method: "DELETE" });
        wordsLoaded = false;
        await loadAdminWords();
      };
      actions.append(editBtn, delBtn);
    }
    tr.lastElementChild.append(actions);
    frag.append(tr);
  });
  adminWordsEl.replaceChildren(frag);
  window.phaohnLayout?.();
}

async function loadAdminWords() {
  const [pairs, pendingApi] = await Promise.all([
    api("/api/admin/all-pairs"),
    api("/api/admin/pending-pairs").catch(() => []),
  ]);
  adminAllPairs = pairs;
  wordsLoaded = true;
  const pendingCount = getPendingPairs(adminAllPairs).length;
  if (!adminDidAutoPending && pendingCount > 0) {
    adminDidAutoPending = true;
    adminWordFilter = "pending";
    document.querySelectorAll("#adminWordFilters .filter-chip").forEach((el) => {
      el.classList.toggle("active", el.dataset.filter === "pending");
    });
  }
  if (!usersCache) {
    try { usersCache = await api("/api/admin/users"); } catch (_) {}
  }
  renderAdminWords(adminAllPairs);
  if (pendingApi.length > pendingCount) {
    console.warn("pending API mismatch", pendingApi.length, pendingCount);
  }
}

function setAdminDialogMode(mode) {
  adminDialogMode = mode;
  document.getElementById("adminModeTabs").querySelectorAll(".seg-tab").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.mode === mode);
  });
  const isMulti = mode === "multi";
  document.getElementById("adminSingleFields").classList.toggle("hidden", isMulti);
  document.getElementById("adminMultiFields").classList.toggle("hidden", !isMulti);
}

function openAdminWordDialog(pair = null) {
  adminEditingId = pair ? pair.id : null;
  document.getElementById("adminWordDialogTitle").textContent = pair ? "Sửa cặp từ" : "Thêm từ khóa";
  document.getElementById("adminModeTabs").classList.toggle("hidden", !!pair);
  document.getElementById("adminWordError").classList.add("hidden");
  if (pair) {
    setAdminDialogMode("single");
    document.getElementById("adminCivilian").value = pair.civilian_word;
    document.getElementById("adminSpy").value = pair.spy_word;
  } else {
    setAdminDialogMode("single");
    document.getElementById("adminCivilian").value = "";
    document.getElementById("adminSpy").value = "";
    document.getElementById("adminMultiLines").value = "";
  }
  adminWordDialogEl.showModal();
}

function formatAdminImportResult(r) {
  const lines = [];
  if (r.added > 0) lines.push(`Thành công: ${r.added}`);
  if (r.pending > 0) lines.push(`Chờ duyệt: ${r.pending}`);
  if (r.duplicate > 0) lines.push(`Trùng: ${r.duplicate}`);
  if (r.empty > 0) lines.push(`Trống: ${r.empty}`);
  if (r.same_word > 0) lines.push(`Hai từ giống nhau: ${r.same_word}`);
  if (r.invalid_format > 0) lines.push(`Sai định dạng: ${r.invalid_format}`);
  if (!lines.length) lines.push("Không có gì để lưu");
  lines.push(`Tổng trên server: ${r.total}`);
  return lines.join("\n");
}

adminWordSearchEl?.addEventListener("input", () => {
  updateAdminSearchClearBtn();
  renderAdminWords(adminAllPairs);
});

adminSearchClearEl?.addEventListener("click", () => {
  if (!adminWordSearchEl) return;
  adminWordSearchEl.value = "";
  updateAdminSearchClearBtn();
  renderAdminWords(adminAllPairs);
  adminWordSearchEl.focus();
});

document.getElementById("adminWordFilters")?.addEventListener("click", (e) => {
  const chip = e.target.closest("[data-filter]");
  if (!chip) return;
  adminWordFilter = chip.dataset.filter;
  document.querySelectorAll("#adminWordFilters .filter-chip").forEach((el) => {
    el.classList.toggle("active", el.dataset.filter === adminWordFilter);
  });
  renderAdminWords(adminAllPairs);
});

document.getElementById("btnShowPending")?.addEventListener("click", () => {
  adminWordFilter = "pending";
  document.querySelectorAll("#adminWordFilters .filter-chip").forEach((el) => {
    el.classList.toggle("active", el.dataset.filter === "pending");
  });
  renderAdminWords(adminAllPairs);
  document.getElementById("adminWordSearch")?.focus();
});

document.getElementById("adminBtnAdd")?.addEventListener("click", () => openAdminWordDialog());
document.getElementById("adminWordCancel")?.addEventListener("click", () => adminWordDialogEl.close());
document.getElementById("adminResultOk")?.addEventListener("click", () => adminResultDialogEl.close());
document.getElementById("adminBtnExport")?.addEventListener("click", () => {
  window.location.href = "/api/pairs/export/csv";
});
document.getElementById("adminBtnImport")?.addEventListener("click", () => {
  document.getElementById("adminFileInput").click();
});

document.getElementById("adminModeTabs")?.addEventListener("click", (e) => {
  const mode = e.target.dataset?.mode;
  if (mode) setAdminDialogMode(mode);
});

document.getElementById("adminFileInput")?.addEventListener("change", async () => {
  const input = document.getElementById("adminFileInput");
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  const form = new FormData();
  form.append("file", file);
  try {
    const result = await api("/api/pairs/import", { method: "POST", body: form });
    document.getElementById("adminResultText").textContent = formatAdminImportResult(result);
    adminResultDialogEl.showModal();
    wordsLoaded = false;
    await loadAdminWords();
  } catch (err) {
    alert(err.message || "Nhập file thất bại.");
  }
});

adminWordFormEl?.addEventListener("submit", async (e) => {
  e.preventDefault();
  const errEl = document.getElementById("adminWordError");
  errEl.classList.add("hidden");
  try {
    if (adminEditingId) {
      await api(`/api/pairs/${adminEditingId}`, {
        method: "PUT",
        body: JSON.stringify({
          civilian_word: document.getElementById("adminCivilian").value,
          spy_word: document.getElementById("adminSpy").value,
        }),
      });
      adminWordDialogEl.close();
      wordsLoaded = false;
      await loadAdminWords();
      return;
    }
    if (adminDialogMode === "multi") {
      const text = document.getElementById("adminMultiLines").value;
      if (!text) {
        errEl.textContent = "Vui lòng nhập ít nhất một dòng.";
        errEl.classList.remove("hidden");
        return;
      }
      const result = await api("/api/pairs/bulk", {
        method: "POST",
        body: JSON.stringify({ text }),
      });
      adminWordDialogEl.close();
      document.getElementById("adminResultText").textContent = formatAdminImportResult(result);
      adminResultDialogEl.showModal();
      wordsLoaded = false;
      await loadAdminWords();
      return;
    }
    await api("/api/pairs", {
      method: "POST",
      body: JSON.stringify({
        civilian_word: document.getElementById("adminCivilian").value,
        spy_word: document.getElementById("adminSpy").value,
      }),
    });
    adminWordDialogEl.close();
    wordsLoaded = false;
    await loadAdminWords();
  } catch (err) {
    const map = {
      duplicate: "Cặp từ đã tồn tại.",
      same_word: "Hai từ không được giống nhau.",
      empty: "Vui lòng nhập đủ hai từ.",
    };
    errEl.textContent = map[err.message] || "Không lưu được.";
    errEl.classList.remove("hidden");
  }
});

function positionAdminBellDropdown(dd) {
  dd.classList.remove("bell-dropdown-mobile");
  if (dd.classList.contains("hidden")) return;
  if (window.innerWidth > 900) return;
  dd.classList.add("bell-dropdown-mobile");
}

document.getElementById("adminBtnBell")?.addEventListener("click", (e) => {
  e.stopPropagation();
  const dd = document.getElementById("adminBellDropdown");
  if (!dd) return;
  const open = dd.classList.contains("hidden");
  if (open) {
    dd.classList.remove("hidden");
    dd.classList.add("is-open");
    positionAdminBellDropdown(dd);
    renderAdminBellDropdown(getPendingPairs(adminAllPairs));
  } else {
    dd.classList.add("hidden");
    dd.classList.remove("is-open", "bell-dropdown-mobile");
  }
});

document.getElementById("adminBellViewAll")?.addEventListener("click", () => {
  document.getElementById("adminBellDropdown")?.classList.add("hidden");
  setAdminTab("words");
  adminWordFilter = "pending";
  document.querySelectorAll("#adminWordFilters .filter-chip").forEach((el) => {
    el.classList.toggle("active", el.dataset.filter === "pending");
  });
  renderAdminWords(adminAllPairs);
});

document.getElementById("adminBellList")?.addEventListener("click", async (e) => {
  const approveBtn = e.target.closest("[data-admin-approve]");
  const rejectBtn = e.target.closest("[data-admin-reject]");
  if (approveBtn) {
    await api(`/api/admin/pending-pairs/${approveBtn.dataset.adminApprove}/approve`, { method: "PUT" });
    wordsLoaded = false;
    approvalsLoaded = false;
    await Promise.all([loadAdminWords(), loadAdminApprovals()]);
  }
  if (rejectBtn) {
    if (!confirm("Từ chối và xóa cặp từ này?")) return;
    await api(`/api/admin/pending-pairs/${rejectBtn.dataset.adminReject}`, { method: "DELETE" });
    wordsLoaded = false;
    approvalsLoaded = false;
    await Promise.all([loadAdminWords(), loadAdminApprovals()]);
  }
});

let closeAdminTopbarProfileMenu = () => {};

document.addEventListener("click", (e) => {
  if (!e.target.closest("#adminBellWrap")) {
    const dd = document.getElementById("adminBellDropdown");
    dd?.classList.add("hidden");
    dd?.classList.remove("is-open", "bell-dropdown-mobile");
  }
  if (!e.target.closest("#adminTopbarProfileWrap")) closeAdminTopbarProfileMenu();
});

function setupAdminTopbarProfileMenu() {
  const wrap = document.getElementById("adminTopbarProfileWrap");
  const btn = document.getElementById("adminBtnTopbarProfile");
  const dropdown = document.getElementById("adminTopbarProfileDropdown");
  if (!wrap || !btn || !dropdown) return;

  const positionProfileDropdown = () => {
    dropdown.classList.remove("profile-dropdown-mobile");
    dropdown.style.position = "";
    dropdown.style.top = "";
    dropdown.style.right = "";
    dropdown.style.left = "";
    dropdown.style.zIndex = "";
    if (dropdown.classList.contains("hidden")) return;
    if (window.innerWidth > 900) return;
    const rect = btn.getBoundingClientRect();
    dropdown.classList.add("profile-dropdown-mobile");
    dropdown.style.position = "fixed";
    dropdown.style.zIndex = "220";
    dropdown.style.top = `${rect.bottom + 8}px`;
    dropdown.style.right = "10px";
    dropdown.style.left = "auto";
  };

  const close = () => {
    wrap.classList.remove("open");
    dropdown.classList.add("hidden");
    dropdown.classList.remove("profile-dropdown-mobile");
    dropdown.style.position = "";
    dropdown.style.top = "";
    dropdown.style.right = "";
    dropdown.style.left = "";
    dropdown.style.zIndex = "";
    btn.setAttribute("aria-expanded", "false");
  };
  const toggle = () => {
    const open = dropdown.classList.contains("hidden");
    const bellDd = document.getElementById("adminBellDropdown");
    bellDd?.classList.add("hidden");
    bellDd?.classList.remove("is-open", "bell-dropdown-mobile");
    if (open) {
      wrap.classList.add("open");
      dropdown.classList.remove("hidden");
      btn.setAttribute("aria-expanded", "true");
      positionProfileDropdown();
    } else {
      close();
    }
  };

  closeAdminTopbarProfileMenu = close;
  btn.addEventListener("click", (e) => {
    e.stopPropagation();
    toggle();
  });
  document.getElementById("adminTopbarBtnLogout")?.addEventListener("click", async () => {
    close();
    await adminDoLogout();
  });
}

function adminDisplayName(user) {
  const nick = (user?.nickname || "").trim();
  return nick || user?.username || "Admin";
}

function adminRoleLabel(role) {
  if (role === "superadmin") return "Super Admin";
  if (role === "admin") return "Quản trị viên";
  return "Người dùng";
}

async function initAdmin() {
  try {
    currentUser = await api("/api/auth/me");
    if (!isStaffRole(currentUser.role)) {
      window.location.href = "/";
      return;
    }
    const name = adminDisplayName(currentUser);
    const initial = name.charAt(0).toUpperCase();
    const role = adminRoleLabel(currentUser.role);
    document.getElementById("adminUserLabel").textContent = name;
    const topbarName = document.getElementById("adminTopbarUserName");
    if (topbarName) topbarName.textContent = name;
    const topbarAvatar = document.getElementById("adminTopbarAvatar");
    if (topbarAvatar) topbarAvatar.textContent = initial;
    const topbarAvatarLg = document.getElementById("adminTopbarAvatarLg");
    if (topbarAvatarLg) topbarAvatarLg.textContent = initial;
    const topbarUserNameLg = document.getElementById("adminTopbarUserNameLg");
    if (topbarUserNameLg) topbarUserNameLg.textContent = name;
    const topbarUserRoleLg = document.getElementById("adminTopbarUserRoleLg");
    if (topbarUserRoleLg) topbarUserRoleLg.textContent = role;
    const newRoleSelect = document.getElementById("newRole");
    if (newRoleSelect && currentUser.role !== "superadmin") {
      newRoleSelect.querySelector('option[value="admin"]')?.remove();
    }
    setAdminTab("words");
    await Promise.all([loadAdminApprovals(), loadAdminReports()]);
    if (!usersCache) await loadAdminUsers();
    window.phaohnLayout?.();
  } catch (err) {
    if (err.message !== "unauthorized") {
      alert(`Không tải được admin: ${err.message}`);
    }
  }
}

setupAdminTopbarProfileMenu();

document.querySelector(".users-create-panel")?.addEventListener("toggle", () => {
  window.phaohnLayout?.();
});

initAdmin();