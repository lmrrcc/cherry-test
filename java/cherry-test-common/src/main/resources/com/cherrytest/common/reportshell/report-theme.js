(function() {
  const key = 'cherry-test-theme';
  const root = document.documentElement;
  const saved = localStorage.getItem(key);
  if (saved) root.setAttribute('data-theme', saved);
  const themeToggle = document.getElementById('theme-toggle');
  function syncThemeIcon() {
    if (!themeToggle) return;
    themeToggle.textContent = root.getAttribute('data-theme') === 'dark' ? '🌙' : '☀';
  }
  syncThemeIcon();
  if (themeToggle) themeToggle.addEventListener('click', () => {
    const next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    root.setAttribute('data-theme', next);
    localStorage.setItem(key, next);
    syncThemeIcon();
  });
})();
