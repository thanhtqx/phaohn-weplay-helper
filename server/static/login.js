const form = document.getElementById("loginForm");
const errorEl = document.getElementById("loginError");

async function checkAlreadyIn() {
  try {
    const res = await fetch("/api/auth/me");
    if (res.ok) window.location.href = "/";
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
      body: JSON.stringify({ username, password }),
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.detail || "invalid_credentials");
    }
    window.location.href = "/";
  } catch (err) {
    const map = {
      invalid_credentials: "Sai tài khoản hoặc mật khẩu.",
    };
    errorEl.textContent = map[err.message] || "Không đăng nhập được.";
    errorEl.classList.remove("hidden");
  }
});

checkAlreadyIn();