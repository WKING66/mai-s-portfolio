const root = document.documentElement;
let savedTheme;
try { savedTheme = window.localStorage?.getItem("portfolio-theme"); } catch {}
if (savedTheme) root.dataset.theme = savedTheme;

document.querySelector("[data-theme-toggle]")?.addEventListener("click", () => {
  const current = root.dataset.theme || (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  root.dataset.theme = current === "dark" ? "light" : "dark";
  try { window.localStorage?.setItem("portfolio-theme", root.dataset.theme); } catch {}
});

if (!matchMedia("(prefers-reduced-motion: reduce)").matches) {
  addEventListener("pointermove", ({ clientX, clientY }) => {
    root.style.setProperty("--mx", `${clientX}px`);
    root.style.setProperty("--my", `${clientY}px`);
  }, { passive: true });

  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => entry.target.classList.toggle("is-visible", entry.isIntersecting));
  }, { threshold: .12 });
  document.querySelectorAll(".reveal").forEach(element => observer.observe(element));
} else {
  document.querySelectorAll(".reveal").forEach(element => element.classList.add("is-visible"));
}
