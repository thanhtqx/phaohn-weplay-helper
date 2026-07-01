const form = document.getElementById("loginForm");
const errorEl = document.getElementById("loginError");

async function checkAlreadyIn() {
  try {
    const res = await fetch("/api/auth/me", { credentials: "same-origin" });
    if (!res.ok) return;
    const user = await res.json();
    window.location.href = (user.role === "admin" || user.role === "superadmin") ? "/admin" : "/";
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
        err.lockMessage = detail.message || "Tài khoản đã bị Admin khóa.";
        throw err;
      }
      throw new Error(typeof detail === "string" ? detail : "invalid_credentials");
    }
    const data = await res.json();
    if (data.user?.role === "admin" || data.user?.role === "superadmin") {
      window.location.href = "/admin";
      return;
    }
    window.location.href = "/";
  } catch (err) {
    const map = {
      invalid_credentials: "Sai tài khoản hoặc mật khẩu.",
      account_locked: err.lockMessage || "Tài khoản đã bị Admin khóa.",
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