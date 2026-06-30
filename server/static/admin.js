let currentUser = null;
let wordSourcesTargetUser = null;
let usersCache = null;
let reportsLoaded = false;

const adminUsersEl = document.getElementById("adminUsers");
const adminReportsEl = document.getElementById("adminReports");
const adminReportsEmptyEl = document.getElementById("adminReportsEmpty");
const createUserForm = document.getElementById("createUserForm");
const createUserErrorEl = document.getElementById("createUserError");
const wordSourcesDialogEl = document.getElementById("wordSourcesDialog");
const wordSourcesListEl = document.getElementById("wordSourcesList");
const wordSourcesTitleEl = document.getElementById("wordSourcesTitle");
const wordSourcesErrorEl = document.getElementById("wordSourcesError");

const TAB_META = {
  users: {
    title: "Tài khoản",
    sub: "Tạo tài khoản, đổi mật khẩu và cấp nguồn từ.",
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
    window.location.href = "/login";
    throw new Error("unauthorized");
  }
  if (!res.ok) {
    let detail = "error";
    try {
      const data = await res.json();
      detail = data.detail || detail;
    } catch (_) {}
    throw new Error(detail);
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

function roleLabel(role) {
  return role === "admin" ? "Admin" : "Người dùng";
}

function userInitial(name) {
  return (name || "?").charAt(0).toUpperCase();
}

function reportTypeLabel(type) {
  return type === "suggest_edit" ? "Đề xuất sửa" : "Từ sai";
}

function reportStatusLabel(status) {
  if (status === "resolved") return "Đã xử lý";
  if (status === "rejected") return "Từ chối";
  return "Chờ duyệt";
}

function setAdminTab(tab) {
  document.querySelectorAll(".admin-menu-item").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.tab === tab);
  });
  document.getElementById("adminPanelUsers").classList.toggle("hidden", tab !== "users");
  document.getElementById("adminPanelReports").classList.toggle("hidden", tab !== "reports");
  const meta = TAB_META[tab];
  document.getElementById("adminPageTitle").textContent = meta.title;
  document.getElementById("adminPageSub").textContent = meta.sub;
  if (tab === "users" && !usersCache) loadAdminUsers();
  if (tab === "reports" && !reportsLoaded) loadAdminReports();
}

document.getElementById("adminNav").addEventListener("click", (e) => {
  const tab = e.target.closest(".admin-menu-item")?.dataset?.tab;
  if (tab) setAdminTab(tab);
});

async function loadAdminUsers() {
  const users = await api("/api/admin/users");
  usersCache = users;
  const frag = document.createDocumentFragment();
  for (const u of users) {
    const isSelf = currentUser && u.id === currentUser.id;
    const card = document.createElement("div");
    card.className = "user-card";
    card.innerHTML = `
      <div class="user-card-top">
        <div class="user-avatar">${escapeHtml(userInitial(u.username))}</div>
        <div class="user-meta">
          <div class="user-name">${escapeHtml(u.username)}${isSelf ? ' <span class="tag-self">bạn</span>' : ""}</div>
          <div class="user-sub">${roleLabel(u.role)} · Tạo bởi ${escapeHtml(u.created_by_name || "—")}</div>
        </div>
      </div>
      <div class="user-card-actions"></div>`;
    const actions = card.querySelector(".user-card-actions");
    if (!isSelf) {
      if (u.role === "user") {
        const sourcesBtn = document.createElement("button");
        sourcesBtn.className = "pill ghost";
        sourcesBtn.textContent = "Nguồn từ";
        sourcesBtn.onclick = () => openWordSourcesDialog(u);
        actions.append(sourcesBtn);
      }
      const resetBtn = document.createElement("button");
      resetBtn.className = "pill ghost";
      resetBtn.textContent = "Đổi MK";
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
      const delBtn = document.createElement("button");
      delBtn.className = "pill danger";
      delBtn.textContent = "Xóa";
      delBtn.onclick = async () => {
        if (!confirm(`Xóa tài khoản ${u.username}?`)) return;
        await api(`/api/admin/users/${u.id}`, { method: "DELETE" });
        usersCache = null;
        await loadAdminUsers();
      };
      actions.append(resetBtn, delBtn);
    } else {
      actions.innerHTML = '<span class="hint">Tài khoản đang đăng nhập</span>';
    }
    frag.append(card);
  }
  adminUsersEl.replaceChildren(frag);
}

async function loadAdminReports() {
  const reports = await api("/api/reports");
  reportsLoaded = true;
  const frag = document.createDocumentFragment();
  adminReportsEmptyEl.classList.toggle("hidden", reports.length > 0);
  const pending = reports.filter((r) => r.status === "pending").length;
  const badge = document.getElementById("reportsPendingBadge");
  badge.textContent = String(pending);
  badge.classList.toggle("hidden", pending === 0);

  for (const r of reports) {
    const tr = document.createElement("tr");
    const detail = r.report_type === "suggest_edit"
      ? ` → ${r.suggested_civilian || "—"} / ${r.suggested_spy || "—"}`
      : "";
    const note = r.message ? ` (${r.message})` : "";
    tr.innerHTML = `
      <td>${escapeHtml(r.civilian_word)} · ${escapeHtml(r.spy_word)}${escapeHtml(detail + note)}</td>
      <td>${reportTypeLabel(r.report_type)}</td>
      <td>${escapeHtml(r.reporter_name || "—")}</td>
      <td>${reportStatusLabel(r.status)}</td>
      <td class="td-actions"><div class="action-group"></div></td>`;
    const actions = tr.querySelector(".action-group");
    if (r.status === "pending") {
      const okBtn = document.createElement("button");
      okBtn.className = "ghost";
      if (r.report_type === "suggest_edit") {
        okBtn.textContent = "Áp dụng";
        okBtn.onclick = async () => {
          await api(`/api/admin/reports/${r.id}/approve`, { method: "PUT" });
          reportsLoaded = false;
          await loadAdminReports();
        };
      } else {
        okBtn.textContent = "Đã xử lý";
        okBtn.onclick = async () => {
          await api(`/api/admin/reports/${r.id}/resolve?status=resolved`, { method: "PUT" });
          reportsLoaded = false;
          await loadAdminReports();
        };
      }
      const noBtn = document.createElement("button");
      noBtn.className = "danger";
      noBtn.textContent = "Từ chối";
      noBtn.onclick = async () => {
        await api(`/api/admin/reports/${r.id}/resolve?status=rejected`, { method: "PUT" });
        reportsLoaded = false;
        await loadAdminReports();
      };
      actions.append(okBtn, noBtn);
    } else {
      actions.textContent = "—";
    }
    frag.append(tr);
  }
  adminReportsEl.replaceChildren(frag);
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

document.getElementById("btnLogout").addEventListener("click", async () => {
  await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
  window.location.href = "/login";
});

async function initAdmin() {
  currentUser = await api("/api/auth/me");
  if (currentUser.role !== "admin") {
    window.location.href = "/";
    return;
  }
  document.getElementById("adminUserLabel").textContent = currentUser.username;
  await Promise.all([loadAdminUsers(), loadAdminReports()]);
}

initAdmin();