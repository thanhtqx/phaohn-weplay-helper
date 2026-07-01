const form = document.getElementById("adminLoginForm");
const errorEl = document.getElementById("loginError");

async function checkAlreadyIn() {
  try {
    const res = await fetch("/api/auth/me", { credentials: "same-origin" });
    if (!res.ok) return;
    const user = await res.json();
    if (user.role === "admin" || user.role === "superadmin") {
      window.location.href = "/admin";
    } else {
      window.location.href = "/";
    }
  } catch (_) {}
}

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  errorEl.classList.add("hidden");
  const username = document.getElementById("username").value;
  const password = document.getElementById("password").value;
  try {
    const res = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({ username, password }),
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      const detail = data.detail;
      if (detail && typeof detail === "object" && detail.code === "account_locked") {
        const err = new Error("account_locked");
        err.lockMessage = detail.message || "Tài khoản đã bị khóa.";
        throw err;
      }
      throw new Error(typeof detail === "string" ? detail : "invalid_credentials");
    }
    const data = await res.json();
    if (data.user?.role !== "admin" && data.user?.role !== "superadmin") {
      await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
      throw new Error("not_admin");
    }
    window.location.href = "/admin";
  } catch (err) {
    const map = {
      invalid_credentials: "Sai tài khoản hoặc mật khẩu.",
      not_admin: "Tài khoản này không có quyền Admin. Dùng trang đăng nhập người dùng.",
      account_locked: err.lockMessage || "Tài khoản đã bị khóa.",
    };
    errorEl.textContent = map[err.message] || "Không đăng nhập được.";
    errorEl.classList.remove("hidden");
  }
});

const savedLock = sessionStorage.getItem("phaohn_lock_message");
if (savedLock) {
  sessionStorage.removeItem("phaohn_lock_message");
  errorEl.textContent = savedLock;
  errorEl.classList.remove("hidden");
}

checkAlreadyIn();