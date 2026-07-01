const rowsEl = document.getElementById("rows");
const emptyEl = document.getElementById("empty");
function updateWordsStats(all, filtered, q) {
  const showing = document.getElementById("statWordsShowing");
  const total = document.getElementById("statWordsTotal");
  const sharedEl = document.getElementById("statWordsShared");
  const sharedWrap = document.getElementById("statWordsSharedWrap");
  const showingLabel = document.getElementById("statWordsShowingLabel");
  if (!showing || !total) return;

  const shared = all.filter((p) => p.is_shared).length;

  showing.textContent = String(filtered.length);
  total.textContent = String(all.length);
  if (sharedEl) sharedEl.textContent = String(shared);
  sharedWrap?.classList.toggle("stat-muted", shared === 0);
  if (showingLabel) {
    showingLabel.textContent = q.trim() ? "Khớp lọc" : "Đang hiển thị";
  }
}

function sttBadge(n) {
  return `<span class="tbl-stt">${n}</span>`;
}


const searchEl = document.getElementById("search");
const btnSearchClear = document.getElementById("btnSearchClear");
const dialogEl = document.getElementById("dialog");
const formEl = document.getElementById("form");
const dialogTitleEl = document.getElementById("dialogTitle");
const civilianEl = document.getElementById("civilian");
const spyEl = document.getElementById("spy");
const multiLinesEl = document.getElementById("multiLines");
const formErrorEl = document.getElementById("formError");
const modeTabsEl = document.getElementById("modeTabs");
const singleFieldsEl = document.getElementById("singleFields");
const multiFieldsEl = document.getElementById("multiFields");
const fileInputEl = document.getElementById("fileInput");
const resultDialogEl = document.getElementById("resultDialog");
const resultTextEl = document.getElementById("resultText");
const tableScroll = document.getElementById("tableScroll");
const tableEl = rowsEl.closest("table");
const ownerHeader = document.getElementById("ownerHeader");

let allPairs = [];
let pairsById = new Map();
let editingId = null;
let dialogMode = "single";
let currentUser = null;
let reportingPair = null;
let reportType = "wrong";
let searchTimer = null;
let currentTab = "words";

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
      if (detail && typeof detail === "object" && detail.code === "account_locked") {
        sessionStorage.setItem("phaohn_lock_message", detail.message || "");
        window.location.href = "/login";
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

function displayName(user) {
  if (!user) return "?";
  const nick = (user.nickname || "").trim();
  return nick || user.username || "?";
}

function roleLabel(role) {
  if (role === "superadmin") return "Super Admin";
  if (role === "admin") return "Quản trị viên";
  return "Người dùng";
}

function applyUserProfile(user) {
  const name = displayName(user);
  const initial = name.charAt(0).toUpperCase();
  const role = roleLabel(user.role);
  document.getElementById("userBadge").textContent = name;
  const roleBadge = document.getElementById("userRoleBadge");
  if (roleBadge) roleBadge.textContent = role;
  const topbarName = document.getElementById("topbarUserName");
  if (topbarName) topbarName.textContent = name;
  const topbarAvatar = document.getElementById("topbarAvatar");
  if (topbarAvatar) topbarAvatar.textContent = initial;
  const topbarAvatarLg = document.getElementById("topbarAvatarLg");
  if (topbarAvatarLg) topbarAvatarLg.textContent = initial;
  const topbarUserNameLg = document.getElementById("topbarUserNameLg");
  if (topbarUserNameLg) topbarUserNameLg.textContent = name;
  const topbarUserRoleLg = document.getElementById("topbarUserRoleLg");
  if (topbarUserRoleLg) topbarUserRoleLg.textContent = role;
  const avatarEl = document.getElementById("userAvatar");
  if (avatarEl) avatarEl.textContent = initial;
  if (user.role === "admin" || user.role === "superadmin") {
    document.getElementById("linkAdmin")?.classList.remove("hidden");
    document.getElementById("topbarLinkAdmin")?.classList.remove("hidden");
  }
}

async function loadApkVersion() {
  try {
    const info = await fetch("/api/app/version").then((r) => r.json());
    const appName = info.name || "Pháo™";
    const label = info.version && info.version !== "unknown"
      ? `${appName} v${info.version}`
      : appName;
    const verEl = document.getElementById("asideApkVersion");
    if (verEl) verEl.textContent = label;
    const mobileApk = document.getElementById("mobileApkLink");
    if (mobileApk) {
      mobileApk.title = info.version
        ? `Tải ${appName} v${info.version}`
        : `Tải ${appName}`;
    }
  } catch (_) {}
}

const nicknameDialogEl = document.getElementById("nicknameDialog");
const nicknameFormEl = document.getElementById("nicknameForm");
const nicknameInputEl = document.getElementById("nicknameInput");
const nicknameErrorEl = document.getElementById("nicknameError");

function promptNicknameIfNeeded(user) {
  if (!user || (user.nickname || "").trim()) return;
  nicknameErrorEl?.classList.add("hidden");
  if (nicknameInputEl) nicknameInputEl.value = "";
  nicknameDialogEl?.showModal();
}

nicknameFormEl?.addEventListener("submit", async (e) => {
  e.preventDefault();
  nicknameErrorEl?.classList.add("hidden");
  const nick = (nicknameInputEl?.value || "").trim();
  if (!nick) {
    nicknameErrorEl.textContent = "Vui lòng nhập biệt danh.";
    nicknameErrorEl.classList.remove("hidden");
    return;
  }
  try {
    currentUser = await api("/api/auth/profile", {
      method: "PUT",
      body: JSON.stringify({ nickname: nick }),
    });
    applyUserProfile(currentUser);
    nicknameDialogEl?.close();
  } catch (err) {
    nicknameErrorEl.textContent = err.message === "empty" ? "Biệt danh không được để trống." : err.message;
    nicknameErrorEl.classList.remove("hidden");
  }
});

function rebuildPairIndex(list) {
  pairsById = new Map(list.map((p) => [p.id, p]));
}

function updateSearchClearBtn() {
  if (!btnSearchClear) return;
  const hasValue = !!searchEl.value.trim();
  btnSearchClear.classList.toggle("hidden", !hasValue);
}

function render(list) {
  const q = searchEl.value;
  updateSearchClearBtn();
  const filtered = q
    ? list.filter((p) => p.civilian_word === q || p.spy_word === q)
    : list;

  updateWordsStats(list, filtered, q);

  if (!filtered.length) {
    const title = emptyEl.querySelector(".empty-title");
    const desc = emptyEl.querySelector(".empty-desc");
    if (title) title.textContent = q ? "Không tìm thấy" : "Chưa có từ khóa";
    if (desc) {
      desc.textContent = q
        ? `Không có cặp từ khớp chính xác "${q}" (phân biệt hoa/thường, dấu).`
        : "Thêm thủ công hoặc nhập file Excel/CSV để bắt đầu.";
    }
    emptyEl.classList.remove("hidden");
    if (tableEl) tableEl.classList.add("hidden");
    rowsEl.replaceChildren();
    return;
  }

  emptyEl.classList.add("hidden");
  if (tableEl) tableEl.classList.remove("hidden");

  const hasShared = list.some((p) => p.is_shared);
  const showOwner = currentUser?.role === "admin" || currentUser?.role === "superadmin" || hasShared;
  if (ownerHeader) ownerHeader.classList.toggle("hidden", !showOwner);

  const frag = document.createDocumentFragment();
  filtered.forEach((pair, idx) => {
    const tr = document.createElement("tr");
    const ownerLabel = pair.is_shared
      ? escapeHtml(pair.owner_username || "—")
      : "Của tôi";
    const canEdit = pair.can_edit !== false;
    const parts = [];
    parts.push(`<button type="button" class="action-menu-item" data-report="${pair.id}">Báo cáo</button>`);
    if (canEdit) parts.push(`<button type="button" class="action-menu-item" data-edit="${pair.id}">Sửa</button>`);
    if (pair.is_shared) parts.push(`<button type="button" class="action-menu-item" data-adopt="${pair.id}">Thêm vào của tôi</button>`);
    if (canEdit) {
      parts.push(`<div class="action-menu-sep"></div>`);
      parts.push(`<button type="button" class="action-menu-item action-menu-item-danger" data-del="${pair.id}">Xóa</button>`);
    }
    const menuItems = parts.join("");
    const actionMenu = menuItems
      ? `<div class="action-menu">
          <button type="button" class="action-menu-btn" data-menu-toggle aria-label="Thao tác"><i class="bi bi-three-dots-vertical"></i></button>
          <div class="action-menu-dropdown">${menuItems}</div>
        </div>`
      : "";
    const ownerCell = showOwner
      ? `<td class="td-owner tbl-meta">${ownerLabel}</td>`
      : "";
    const metaTags = pair.is_shared ? '<span class="tbl-note">share</span>' : "";
    const metaHtml = metaTags ? `<span class="td-word-meta">${metaTags}</span>` : "";
    tr.innerHTML = `
      <td class="td-stt">${sttBadge(idx + 1)}</td>
      <td class="td-civilian" title="${escapeHtml(pair.civilian_word)}">${escapeHtml(pair.civilian_word)}${metaHtml}</td>
      <td class="td-spy" title="${escapeHtml(pair.spy_word)}">${escapeHtml(pair.spy_word)}</td>
      ${ownerCell}
      <td class="td-actions">${actionMenu}</td>`;
    frag.append(tr);
  });
  rowsEl.replaceChildren(frag);
  window.phaohnLayout?.();
}

function formatImportResult(r) {
  const lines = [];
  if (r.added > 0) lines.push(`Thành công: ${r.added}`);
  if (r.pending > 0) lines.push(`Đã lưu: ${r.pending}`);
  if (r.duplicate > 0) lines.push(`Trùng: ${r.duplicate}`);
  if (r.empty > 0) lines.push(`Trống: ${r.empty}`);
  if (r.same_word > 0) lines.push(`Hai từ giống nhau: ${r.same_word}`);
  if (r.invalid_format > 0) lines.push(`Sai định dạng: ${r.invalid_format}`);
  if (!lines.length) lines.push("Không có gì để lưu");
  lines.push(`Tổng trên server: ${r.total}`);
  return lines.join("\n");
}

function showImportResult(r) {
  resultTextEl.textContent = formatImportResult(r);
  resultDialogEl.showModal();
}

async function loadPairs() {
  allPairs = await api("/api/pairs");
  rebuildPairIndex(allPairs);
  render(allPairs);
}

function setDialogMode(mode) {
  dialogMode = mode;
  modeTabsEl.querySelectorAll(".seg-tab").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.mode === mode);
  });
  const isMulti = mode === "multi";
  singleFieldsEl.classList.toggle("hidden", isMulti);
  multiFieldsEl.classList.toggle("hidden", !isMulti);
}

function openDialog(pair = null) {
  editingId = pair ? pair.id : null;
  dialogTitleEl.textContent = pair ? "Sửa cặp từ" : "Thêm từ khóa";
  modeTabsEl.classList.toggle("hidden", !!pair);
  if (pair) {
    setDialogMode("single");
    civilianEl.value = pair.civilian_word;
    spyEl.value = pair.spy_word;
  } else {
    setDialogMode("single");
    civilianEl.value = "";
    spyEl.value = "";
    multiLinesEl.value = "";
  }
  formErrorEl.classList.add("hidden");
  dialogEl.showModal();
  (pair || dialogMode === "single" ? civilianEl : multiLinesEl).focus();
}

function showFormError(msg) {
  formErrorEl.textContent = msg;
  formErrorEl.classList.remove("hidden");
}

const helpDialogEl = document.getElementById("helpDialog");

document.getElementById("btnHelp").addEventListener("click", () => helpDialogEl.showModal());
document.getElementById("btnHelpClose").addEventListener("click", () => helpDialogEl.close());
document.getElementById("btnAdd").addEventListener("click", () => openDialog());
document.getElementById("btnCancel").addEventListener("click", () => dialogEl.close());
document.getElementById("btnResultOk").addEventListener("click", () => resultDialogEl.close());
document.getElementById("btnExport").addEventListener("click", () => {
  window.location.href = "/api/pairs/export/csv";
});
document.getElementById("btnImportFile").addEventListener("click", () => fileInputEl.click());

searchEl.addEventListener("input", () => {
  updateSearchClearBtn();
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    render(allPairs);
    if (!searchEl.value) hideLookupCard();
  }, 120);
});

btnSearchClear?.addEventListener("click", () => {
  searchEl.value = "";
  updateSearchClearBtn();
  hideLookupCard();
  render(allPairs);
  searchEl.focus();
});

searchEl.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    doLookup();
  }
});

modeTabsEl.addEventListener("click", (e) => {
  const mode = e.target.dataset?.mode;
  if (mode) setDialogMode(mode);
});

fileInputEl.addEventListener("change", async () => {
  const file = fileInputEl.files?.[0];
  fileInputEl.value = "";
  if (!file) return;
  const form = new FormData();
  form.append("file", file);
  try {
    const result = await api("/api/pairs/import", { method: "POST", body: form });
    showImportResult(result);
    await loadPairs();
  } catch (err) {
    const map = {
      no_file: "Chưa chọn file.",
      empty_file: "File trống.",
      no_rows: "Không đọc được dòng nào.",
    };
    alert(map[err.message] || `Nhập file thất bại: ${err.message}`);
  }
});

const reportDialogEl = document.getElementById("reportDialog");
const reportFormEl = document.getElementById("reportForm");
const reportPairLabelEl = document.getElementById("reportPairLabel");
const reportTypeTabsEl = document.getElementById("reportTypeTabs");
const reportSuggestFieldsEl = document.getElementById("reportSuggestFields");
const reportErrorEl = document.getElementById("reportError");

function setReportType(type) {
  reportType = type;
  reportTypeTabsEl.querySelectorAll(".seg-tab").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.type === type);
  });
  reportSuggestFieldsEl.classList.toggle("hidden", type !== "suggest_edit");
}

function openReportDialog(pair) {
  reportingPair = pair;
  reportPairLabelEl.textContent = `${pair.civilian_word} · ${pair.spy_word}`;
  document.getElementById("reportMessage").value = "";
  document.getElementById("reportSuggestCivilian").value = "";
  document.getElementById("reportSuggestSpy").value = "";
  setReportType("wrong");
  reportErrorEl.classList.add("hidden");
  reportDialogEl.showModal();
}

reportTypeTabsEl.addEventListener("click", (e) => {
  const type = e.target.dataset?.type;
  if (type) setReportType(type);
});

document.getElementById("btnReportCancel").addEventListener("click", () => reportDialogEl.close());

reportFormEl.addEventListener("submit", async (e) => {
  e.preventDefault();
  reportErrorEl.classList.add("hidden");
  if (!reportingPair) return;
  const suggestedCivilian = document.getElementById("reportSuggestCivilian").value;
  const suggestedSpy = document.getElementById("reportSuggestSpy").value;
  if (reportType === "suggest_edit" && !suggestedCivilian && !suggestedSpy) {
    reportErrorEl.textContent = "Vui lòng nhập ít nhất một từ đề xuất.";
    reportErrorEl.classList.remove("hidden");
    return;
  }
  try {
    await api(`/api/pairs/${reportingPair.id}/report`, {
      method: "POST",
      body: JSON.stringify({
        report_type: reportType,
        message: document.getElementById("reportMessage").value,
        suggested_civilian: suggestedCivilian,
        suggested_spy: suggestedSpy,
      }),
    });
    reportDialogEl.close();
    alert("Đã gửi báo cáo.");
  } catch (_) {
    reportErrorEl.textContent = "Gửi báo cáo thất bại.";
    reportErrorEl.classList.remove("hidden");
  }
});

const Z_FLOATING_MENU = 200;

function closeAllActionMenus() {
  document.querySelectorAll(".action-menu.open").forEach((el) => {
    el.classList.remove("open");
    const dropdown = el.querySelector(".action-menu-dropdown");
    if (dropdown) {
      dropdown.style.position = "";
      dropdown.style.top = "";
      dropdown.style.left = "";
      dropdown.style.zIndex = "";
      dropdown.style.visibility = "";
      dropdown.style.display = "";
    }
  });
}

function openActionMenu(menu, toggle) {
  closeAllActionMenus();
  menu.classList.add("open");
  const dropdown = menu.querySelector(".action-menu-dropdown");
  if (!dropdown) return;
  dropdown.style.visibility = "hidden";
  dropdown.style.display = "block";
  const rect = toggle.getBoundingClientRect();
  const dw = dropdown.offsetWidth;
  const dh = dropdown.offsetHeight;
  const gap = 4;
  const margin = 8;
  let top = rect.bottom + gap;
  if (top + dh > window.innerHeight - margin) {
    top = Math.max(margin, rect.top - dh - gap);
  }
  dropdown.style.position = "fixed";
  dropdown.style.zIndex = String(Z_FLOATING_MENU);
  dropdown.style.top = `${top}px`;
  dropdown.style.left = `${Math.max(margin, Math.min(rect.right - dw, window.innerWidth - dw - margin))}px`;
  dropdown.style.visibility = "visible";
}

document.addEventListener("click", (e) => {
  if (e.target.closest(".action-menu")) return;
  closeAllActionMenus();
});

rowsEl.addEventListener("click", async (e) => {
  const toggle = e.target.closest("[data-menu-toggle]");
  if (toggle) {
    e.preventDefault();
    e.stopPropagation();
    const menu = toggle.closest(".action-menu");
    if (!menu) return;
    if (menu.classList.contains("open")) {
      closeAllActionMenus();
    } else {
      openActionMenu(menu, toggle);
    }
    return;
  }

  const btn = e.target.closest(".action-menu-item");
  if (!btn) return;
  e.stopPropagation();
  closeAllActionMenus();

  const adoptId = btn.dataset.adopt;
  const reportId = btn.dataset.report;
  const editId = btn.dataset.edit;
  const delId = btn.dataset.del;

  if (adoptId) {
    try {
      await api(`/api/pairs/${adoptId}/adopt`, { method: "POST" });
      alert("Đã thêm vào từ khóa của bạn.");
      await loadPairs();
    } catch (err) {
      const map = {
        duplicate: "Bạn đã có cặp từ này rồi.",
        not_found: "Không tìm thấy từ khóa.",
      };
      alert(map[err.message] || "Không thêm được.");
    }
    return;
  }
  if (reportId) {
    const pair = pairsById.get(Number(reportId));
    if (pair) openReportDialog(pair);
    return;
  }
  if (editId) {
    const pair = pairsById.get(Number(editId));
    if (pair) openDialog(pair);
    return;
  }
  if (delId) {
    if (!confirm("Ẩn cặp từ khóa này khỏi danh sách của bạn?")) return;
    await api(`/api/pairs/${delId}`, { method: "DELETE" });
    await loadPairs();
  }
});

formEl.addEventListener("submit", async (e) => {
  e.preventDefault();
  formErrorEl.classList.add("hidden");
  try {
    if (editingId) {
      await api(`/api/pairs/${editingId}`, {
        method: "PUT",
        body: JSON.stringify({
          civilian_word: civilianEl.value,
          spy_word: spyEl.value,
        }),
      });
      dialogEl.close();
      await loadPairs();
      return;
    }

    if (dialogMode === "multi") {
      const text = multiLinesEl.value;
      if (!text) {
        showFormError("Vui lòng nhập ít nhất một dòng.");
        return;
      }
      const result = await api("/api/pairs/bulk", {
        method: "POST",
        body: JSON.stringify({ text }),
      });
      dialogEl.close();
      showImportResult(result);
      await loadPairs();
      return;
    }

    const created = await api("/api/pairs", {
      method: "POST",
      body: JSON.stringify({
        civilian_word: civilianEl.value,
        spy_word: spyEl.value,
      }),
    });
    dialogEl.close();
    await loadPairs();

  } catch (err) {
    const map = {
      duplicate: "Cặp từ đã có (trùng y hệt hoặc đảo vai dân/gián).",
      same_word: "Hai từ không được giống nhau.",
      empty: "Vui lòng nhập đủ hai từ.",
    };
    showFormError(map[err.message] || "Không lưu được.");
  }
});

const changePasswordDialogEl = document.getElementById("changePasswordDialog");
const changePasswordFormEl = document.getElementById("changePasswordForm");
const changePasswordErrorEl = document.getElementById("changePasswordError");

function openChangePasswordDialog() {
  changePasswordErrorEl.classList.add("hidden");
  document.getElementById("oldPassword").value = "";
  document.getElementById("newPassword").value = "";
  changePasswordDialogEl.showModal();
}

document.getElementById("btnChangePassword").addEventListener("click", openChangePasswordDialog);
document.getElementById("btnChangePasswordCancel").addEventListener("click", () => changePasswordDialogEl.close());

changePasswordFormEl.addEventListener("submit", async (e) => {
  e.preventDefault();
  changePasswordErrorEl.classList.add("hidden");
  try {
    await api("/api/auth/change-password", {
      method: "POST",
      body: JSON.stringify({
        old_password: document.getElementById("oldPassword").value,
        new_password: document.getElementById("newPassword").value,
      }),
    });
    changePasswordDialogEl.close();
    alert("Đã đổi mật khẩu.");
  } catch (err) {
    const map = {
      wrong_password: "Mật khẩu hiện tại không đúng.",
      password_short: "Mật khẩu mới tối thiểu 6 ký tự.",
    };
    changePasswordErrorEl.textContent = map[err.message] || "Không đổi được mật khẩu.";
    changePasswordErrorEl.classList.remove("hidden");
  }
});

async function doLogout() {
  await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
  window.location.href = "/login";
}

document.getElementById("btnLogout").addEventListener("click", doLogout);

/* ── Tabs ── */
const tabPanels = {
  words: document.getElementById("tabWords"),
  history: document.getElementById("tabHistory"),
  notifications: document.getElementById("tabNotifications"),
};

const TOPBAR_META = {
  words: { title: "Từ khóa", sub: "Quản lý danh sách từ Gián điệp" },
  history: { title: "Lịch sử", sub: "Các lần tra cứu trên web" },
  notifications: { title: "Thông báo", sub: "Tin nhắn từ Admin" },
};

function updateTopbar(tab) {
  const meta = TOPBAR_META[tab] || TOPBAR_META.words;
  const titleEl = document.getElementById("topbarTitle");
  const subEl = document.getElementById("topbarSub");
  if (titleEl) titleEl.textContent = meta.title;
  if (subEl) subEl.textContent = meta.sub;
}

function switchTab(tab) {
  currentTab = tab;
  document.querySelectorAll(".app-nav-item, .mobile-bottom-nav-item[data-tab]").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.tab === tab);
  });
  for (const [key, el] of Object.entries(tabPanels)) {
    if (!el) continue;
    el.classList.toggle("hidden", key !== tab);
    el.classList.toggle("active", key === tab);
  }
  updateTopbar(tab);
  closeBellDropdown();
  if (tab === "history") loadHistory();
  if (tab === "notifications") loadNotifications();
  window.phaohnLayout?.();
}

document.getElementById("mainNav")?.addEventListener("click", (e) => {
  const btn = e.target.closest(".app-nav-item");
  if (btn?.dataset.tab) switchTab(btn.dataset.tab);
});

document.getElementById("mobileBottomNav")?.addEventListener("click", (e) => {
  const btn = e.target.closest(".mobile-bottom-nav-item");
  if (btn?.dataset.tab) switchTab(btn.dataset.tab);
});

/* ── Lookup (gộp trong tab Từ khóa) ── */
const lookupCardEl = document.getElementById("lookupCard");
const lookupMyWordEl = document.getElementById("lookupMyWord");
const lookupOthersEl = document.getElementById("lookupOthers");

function roleLabel(role) {
  return role === "spy" ? "Gián điệp" : "Dân thường";
}

function renderOtherLines(others) {
  return others
    .map(
      (o) =>
        `<div class="lookup-word-line role-${o.role}" title="${roleLabel(o.role)}">${escapeHtml(o.word)}</div>`
    )
    .join("");
}

function hideLookupCard() {
  lookupCardEl?.classList.add("hidden");
  window.phaohnLayout?.();
}

function showLookupCard() {
  lookupCardEl?.classList.remove("hidden");
  window.phaohnLayout?.();
}

async function doLookup() {
  const query = searchEl.value;
  if (!query) {
    hideLookupCard();
    return;
  }
  try {
    const result = await api("/api/lookup", {
      method: "POST",
      body: JSON.stringify({ my_word: query }),
    });
    if (result.status === "found") {
      lookupMyWordEl.innerHTML = `<span class="role-${result.my_role}">${escapeHtml(result.my_word)}</span>`;
      lookupOthersEl.innerHTML = result.others.length
        ? renderOtherLines(result.others)
        : '<span class="lookup-muted">______</span>';
      showLookupCard();
      await loadHistory();
    } else if (result.status === "not_found") {
      lookupMyWordEl.innerHTML = `<span class="lookup-word-plain">${escapeHtml(query)}</span>`;
      lookupOthersEl.innerHTML = '<span class="lookup-muted">Không có trong danh sách</span>';
      showLookupCard();
    } else {
      hideLookupCard();
    }
  } catch (err) {
    hideLookupCard();
    alert(`Không tra cứu được: ${err.message}`);
  }
}

document.getElementById("btnLookup")?.addEventListener("click", () => doLookup());
document.getElementById("btnCloseLookup")?.addEventListener("click", () => hideLookupCard());

/* ── History ── */
const historyRowsEl = document.getElementById("historyRows");
const historyEmptyEl = document.getElementById("historyEmpty");
const historyStatsCountEl = document.getElementById("historyStatsCount");
const historyStatsLatestEl = document.getElementById("historyStatsLatest");
const historyTableEl = historyRowsEl?.closest("table");

function formatTime(ms) {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function formatOthersPipe(pipe) {
  if (!pipe) return '<span class="lookup-muted">______</span>';
  return pipe
    .split("|")
    .filter(Boolean)
    .map((w) => `<div class="lookup-word-line lookup-word-plain">${escapeHtml(w)}</div>`)
    .join("");
}

function renderHistory(list) {
  const hasItems = list.length > 0;
  if (historyStatsCountEl) historyStatsCountEl.textContent = String(list.length);
  if (historyStatsLatestEl) {
    historyStatsLatestEl.textContent = hasItems
      ? `Gần nhất: ${formatTime(list[0].played_at)}`
      : "";
    historyStatsLatestEl.classList.toggle("hidden", !hasItems);
  }
  document.getElementById("btnExportHistory").disabled = !hasItems;
  document.getElementById("btnClearHistory").disabled = !hasItems;

  if (!hasItems) {
    historyEmptyEl?.classList.remove("hidden");
    historyTableEl?.classList.add("hidden");
    historyRowsEl?.replaceChildren();
    return;
  }

  historyEmptyEl?.classList.add("hidden");
  historyTableEl?.classList.remove("hidden");

  const frag = document.createDocumentFragment();
  list.forEach((entry, idx) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td class="td-stt">${sttBadge(idx + 1)}</td>
      <td class="td-time tbl-meta">${formatTime(entry.played_at)}</td>
      <td class="td-myword">${escapeHtml(entry.my_word)}</td>
      <td class="td-others">${formatOthersPipe(entry.other_words)}</td>
      <td class="td-actions">
        <button class="btn-sm btn-sm-danger" data-hist-del="${entry.id}"><i class="bi bi-trash"></i> Xóa</button>
      </td>`;
    frag.append(tr);
  });
  historyRowsEl.replaceChildren(frag);
  window.phaohnLayout?.();
}

async function loadHistory() {
  try {
    const list = await api("/api/history");
    renderHistory(list);
  } catch (err) {
    if (err.message !== "unauthorized") {
      if (historyStatsCountEl) historyStatsCountEl.textContent = "—";
      historyStatsLatestEl?.classList.add("hidden");
      historyEmptyEl?.classList.remove("hidden");
      const title = historyEmptyEl?.querySelector(".empty-title");
      const desc = historyEmptyEl?.querySelector(".empty-desc");
      if (title) title.textContent = "Không tải được";
      if (desc) desc.textContent = err.message;
    }
  }
}

historyRowsEl?.addEventListener("click", async (e) => {
  const btn = e.target.closest("[data-hist-del]");
  if (!btn) return;
  if (!confirm("Xóa mục lịch sử này?")) return;
  await api(`/api/history/${btn.dataset.histDel}`, { method: "DELETE" });
  await loadHistory();
});

document.getElementById("btnClearHistory")?.addEventListener("click", async () => {
  if (!confirm("Xóa toàn bộ lịch sử tra cứu?")) return;
  await api("/api/history", { method: "DELETE" });
  await loadHistory();
});

document.getElementById("btnExportHistory")?.addEventListener("click", () => {
  window.location.href = "/api/history/export/csv";
});

/* ── Notifications ── */
const notifListEl = document.getElementById("notifList");
const notifEmptyEl = document.getElementById("notifEmpty");
const bellBadgeEl = document.getElementById("bellBadge");
const bellDropdownEl = document.getElementById("bellDropdown");
const bellDropdownListEl = document.getElementById("bellDropdownList");
let inboxItems = [];

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

function countUnreadNotifications(list) {
  return list.filter((n) => !n.read).length;
}

function updateNotifBadge(count) {
  const label = count > 9 ? "9+" : String(count);
  if (bellBadgeEl) {
    bellBadgeEl.textContent = label;
    bellBadgeEl.classList.toggle("hidden", count === 0);
  }
  document.getElementById("btnBell")?.classList.toggle("bell-btn-active", count > 0);
  ["mobileNavNotifBadge", "sidebarNotifBadge"].forEach((id) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = label;
    el.classList.toggle("hidden", count === 0);
  });
}

function closeBellDropdown() {
  bellDropdownEl?.classList.add("hidden");
  bellDropdownEl?.classList.remove("bell-dropdown-mobile", "is-open");
}

function toggleBellDropdown() {
  if (!bellDropdownEl) return;
  const open = bellDropdownEl.classList.contains("hidden");
  if (open) {
    bellDropdownEl.classList.remove("hidden");
    bellDropdownEl.classList.add("is-open");
    if (window.innerWidth <= 900) bellDropdownEl.classList.add("bell-dropdown-mobile");
    renderBellDropdown(inboxItems);
  } else {
    closeBellDropdown();
  }
}

function renderBellDropdown(list) {
  if (!bellDropdownListEl) return;
  if (!list.length) {
    bellDropdownListEl.innerHTML = '<p class="bell-empty">Chưa có thông báo</p>';
    return;
  }
  const preview = list.slice(0, 5);
  bellDropdownListEl.innerHTML = preview.map((n) => {
    const readCls = n.read ? " bell-item-read" : " bell-item-unread";
    const ackAttr = n.read ? "" : ` data-bell-ack="${n.id}"`;
    return `
    <button type="button" class="bell-item${readCls}"${ackAttr}>
      <span class="bell-item-icon" aria-hidden="true"><i class="bi bi-megaphone-fill"></i></span>
      <span class="bell-item-main">
        <span class="bell-item-title">${escapeHtml(n.title)}</span>
        <span class="bell-item-body">${escapeHtml(n.body)}</span>
        <time class="bell-item-time">${escapeHtml(formatNotifTime(n.created_at))}</time>
      </span>
      ${n.read ? '<span class="bell-read-pill">Đã đọc</span>' : '<span class="bell-unread-dot" aria-hidden="true"></span>'}
    </button>`;
  }).join("");
}

function renderNotifications(list) {
  inboxItems = list;
  const hasItems = list.length > 0;
  const unread = countUnreadNotifications(list);
  updateNotifBadge(unread);
  document.getElementById("btnMarkAllRead").disabled = unread === 0;
  notifEmptyEl?.classList.toggle("hidden", hasItems);
  if (!bellDropdownEl?.classList.contains("hidden")) renderBellDropdown(list);
  if (!hasItems) {
    notifListEl?.replaceChildren();
    return;
  }
  const frag = document.createDocumentFragment();
  list.forEach((n) => {
    const card = document.createElement("article");
    card.className = n.read ? "notif-card notif-card-read" : "notif-card notif-card-unread";
    const action = n.read
      ? '<span class="notif-read-pill"><i class="bi bi-check2-circle"></i> Đã đọc</span>'
      : `<button type="button" class="notif-ack-btn" data-notif-ack="${n.id}"><i class="bi bi-check2"></i> Đánh dấu đã đọc</button>`;
    card.innerHTML = `
      <div class="notif-card-inner">
        <div class="notif-card-icon${n.read ? " notif-card-icon-read" : ""}" aria-hidden="true">
          <i class="bi bi-megaphone-fill"></i>
        </div>
        <div class="notif-card-content">
          <div class="notif-card-head">
            <h3 class="notif-card-title">${escapeHtml(n.title)}</h3>
            <time class="notif-card-time">${escapeHtml(formatNotifTime(n.created_at))}</time>
          </div>
          <p class="notif-card-body">${escapeHtml(n.body)}</p>
          <div class="notif-card-foot">${action}</div>
        </div>
        ${n.read ? "" : '<span class="notif-unread-dot" aria-hidden="true"></span>'}
      </div>`;
    frag.append(card);
  });
  notifListEl?.replaceChildren(frag);
  window.phaohnLayout?.();
}

async function loadNotifications() {
  try {
    const list = await api("/api/notifications/inbox");
    renderNotifications(list);
  } catch (err) {
    if (err.message !== "unauthorized") {
      notifEmptyEl?.classList.remove("hidden");
      const title = notifEmptyEl?.querySelector(".empty-title");
      const desc = notifEmptyEl?.querySelector(".empty-desc");
      if (title) title.textContent = "Không tải được";
      if (desc) desc.textContent = err.message;
    }
  }
}

notifListEl?.addEventListener("click", async (e) => {
  const btn = e.target.closest("[data-notif-ack]");
  if (!btn) return;
  const id = Number(btn.dataset.notifAck);
  await api("/api/notifications/ack", {
    method: "POST",
    body: JSON.stringify({ notification_ids: [id] }),
  });
  await loadNotifications();
});

document.getElementById("btnMarkAllRead")?.addEventListener("click", async () => {
  const unreadIds = inboxItems.filter((n) => !n.read).map((n) => n.id);
  if (!unreadIds.length) return;
  await api("/api/notifications/ack", {
    method: "POST",
    body: JSON.stringify({ notification_ids: unreadIds }),
  });
  await loadNotifications();
});

document.getElementById("btnBell")?.addEventListener("click", (e) => {
  e.stopPropagation();
  toggleBellDropdown();
});

document.getElementById("btnBellViewAll")?.addEventListener("click", () => {
  closeBellDropdown();
  switchTab("notifications");
});

document.getElementById("btnBellMarkAll")?.addEventListener("click", async () => {
  const unreadIds = inboxItems.filter((n) => !n.read).map((n) => n.id);
  if (!unreadIds.length) return;
  await api("/api/notifications/ack", {
    method: "POST",
    body: JSON.stringify({ notification_ids: unreadIds }),
  });
  await loadNotifications();
});

bellDropdownListEl?.addEventListener("click", async (e) => {
  const item = e.target.closest("[data-bell-ack]");
  if (!item || item.classList.contains("bell-item-read")) return;
  await api("/api/notifications/ack", {
    method: "POST",
    body: JSON.stringify({ notification_ids: [Number(item.dataset.bellAck)] }),
  });
  await loadNotifications();
});

let closeTopbarProfileMenu = () => {};

document.addEventListener("click", (e) => {
  if (!e.target.closest("#bellWrap")) closeBellDropdown();
  if (!e.target.closest("#topbarProfileWrap")) closeTopbarProfileMenu();
});

function setupTopbarProfileMenu() {
  const wrap = document.getElementById("topbarProfileWrap");
  const btn = document.getElementById("btnTopbarProfile");
  const dropdown = document.getElementById("topbarProfileDropdown");
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
    closeBellDropdown();
    closeAllActionMenus();
    if (open) {
      wrap.classList.add("open");
      dropdown.classList.remove("hidden");
      btn.setAttribute("aria-expanded", "true");
      positionProfileDropdown();
    } else {
      close();
    }
  };

  closeTopbarProfileMenu = close;
  btn.addEventListener("click", (e) => {
    e.stopPropagation();
    toggle();
  });
  document.getElementById("topbarBtnHelp")?.addEventListener("click", () => {
    close();
    helpDialogEl.showModal();
  });
  document.getElementById("topbarBtnPassword")?.addEventListener("click", () => {
    close();
    openChangePasswordDialog();
  });
  document.getElementById("topbarBtnLogout")?.addEventListener("click", async () => {
    close();
    await doLogout();
  });
}

async function initApp() {
  try {
    loadApkVersion();
    currentUser = await api("/api/auth/me");
    applyUserProfile(currentUser);
    promptNicknameIfNeeded(currentUser);
    updateTopbar("words");
    await Promise.all([loadPairs(), loadHistory(), loadNotifications()]);
    window.phaohnLayout?.();
  } catch (err) {
    if (err.message !== "unauthorized") {
      const title = emptyEl.querySelector(".empty-title");
      const desc = emptyEl.querySelector(".empty-desc");
      if (title) title.textContent = "Không tải được dữ liệu";
      if (desc) desc.textContent = err.message;
      emptyEl.classList.remove("hidden");
      if (tableEl) tableEl.classList.add("hidden");
    }
  }
}

setupTopbarProfileMenu();

initApp();