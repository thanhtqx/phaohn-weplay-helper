/**
 * Cố định chiều cao vùng scroll theo viewport — gọi lại khi resize / đổi tab.
 */
(function () {
  const MOBILE_BP = 900;

  function isMobile() {
    return window.innerWidth <= MOBILE_BP;
  }

  function mobileBottomInset() {
    if (!isMobile()) return 0;
    const nav = document.querySelector(".mobile-bottom-nav");
    if (!nav || getComputedStyle(nav).display === "none") return 0;
    const rect = nav.getBoundingClientRect();
    return Math.ceil(rect.height) || 64;
  }

  function safeAreaBottom() {
    const probe = document.createElement("div");
    probe.style.cssText =
      "position:fixed;bottom:0;height:env(safe-area-inset-bottom,0px);pointer-events:none;visibility:hidden";
    document.body.appendChild(probe);
    const h = probe.offsetHeight;
    probe.remove();
    return h;
  }

  function fitScrollAreas() {
    const bottomInset = mobileBottomInset() + safeAreaBottom();
    const pad = isMobile() ? 6 : 10;

    document.querySelectorAll(".js-fit-viewport").forEach((el) => {
      const panel = el.closest(".panel");
      const tab = el.closest(".tab-panel");
      const section = el.closest(".admin-section");

      if (tab?.classList.contains("hidden")) return;
      if (section?.classList.contains("hidden")) return;
      if (!panel && !tab) return;

      const rect = el.getBoundingClientRect();
      if (rect.top <= 0 || rect.top > window.innerHeight) return;

      const foot = document.querySelector(".page-foot");
      const footH =
        foot && getComputedStyle(foot).display !== "none"
          ? Math.ceil(foot.getBoundingClientRect().height)
          : 0;
      const extra = document.querySelector(".admin-page") ? 8 : 0;
      const h = window.innerHeight - rect.top - bottomInset - pad - extra - footH;
      const height = `${Math.max(140, Math.floor(h))}px`;
      el.style.height = height;
      el.style.maxHeight = height;
      el.style.minHeight = "0";
    });
  }

  let raf = 0;
  function scheduleLayout() {
    cancelAnimationFrame(raf);
    raf = requestAnimationFrame(fitScrollAreas);
  }

  window.phaohnLayout = scheduleLayout;

  window.addEventListener("resize", scheduleLayout, { passive: true });
  window.addEventListener("orientationchange", scheduleLayout, { passive: true });
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", scheduleLayout);
  } else {
    scheduleLayout();
  }
  window.addEventListener("load", scheduleLayout);
})();