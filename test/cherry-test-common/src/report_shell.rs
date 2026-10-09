pub const REPORT_HTML_STYLES: &str = r#":root {
  color-scheme: light;
  --gray-1: #fcfcfd;
  --gray-2: #f8f9fa;
  --gray-3: #eff1f4;
  --gray-4: #e6e9ee;
  --gray-5: #dde1e7;
  --gray-6: #d2d7df;
  --gray-7: #bfc6d1;
  --gray-8: #9ea8b7;
  --gray-9: #6c7686;
  --gray-10: #5f6979;
  --gray-11: #464f5d;
  --gray-12: #171c24;
  --accent-1: #fbfcff;
  --accent-2: #f4f7ff;
  --accent-3: #e8efff;
  --accent-4: #d9e4ff;
  --accent-5: #c7d6ff;
  --accent-6: #b1c4fb;
  --accent-7: #93abf2;
  --accent-8: #6987e8;
  --accent-9: #2f5bea;
  --accent-10: #244fd8;
  --accent-11: #2348c2;
  --accent-12: #15275e;
  --pass-3: #e3f6ea;
  --pass-7: #8fd2a8;
  --pass-9: #1f9d55;
  --pass-11: #157341;
  --fail-3: #fde8e8;
  --fail-7: #f0a3a3;
  --fail-9: #d93636;
  --fail-11: #b42323;
  --warn-3: #fdf1dc;
  --warn-7: #e9c07a;
  --warn-9: #e59a1a;
  --warn-11: #94600a;
  --font-sans: ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
  --font-mono: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
  --text-sm: 0.875rem;
  --radius-2: 6px;
  --radius-3: 10px;
  --bg: var(--gray-1);
  --fg: var(--gray-12);
  --muted: var(--gray-10);
  --border: var(--gray-6);
  --ok: var(--pass-11);
  --skip: var(--warn-11);
  --fail: var(--fail-11);
  --low: var(--warn-11);
  --covered: #dbeafe;
  --uncovered: #fef9c3;
  --fail-line: #fecaca;
  --diff-bg: #ffffff;
  --diff-add-bg: #e6ffec;
  --diff-add-gutter: #ccffd8;
  --diff-del-bg: #ffebe9;
  --diff-del-gutter: #ffd7d5;
  --diff-sign-add: #1a7f37;
  --diff-sign-del: #cf222e;
  --diff-gutter-fg: #59636e;
  --diff-expander-bg: #eff6ff;
  --diff-expander-fg: #1d4ed8;
  --tok-kw: #cf222e;
  --tok-str: #0a3069;
  --tok-num: #0550ae;
  --tok-cmt: #6e7781;
  --tok-ty: #953800;
  --tok-fnc: #8250df;
  --tok-mac: #116329;
  --tok-attr: #116329;
  --rail-pass: #1a7f37;
  --rail-fail: #cf222e;
  --rail-covered: #0550ae;
  --rail-gap: #e59a1a;
}
html[data-theme="dark"] {
  color-scheme: dark;
  --gray-1: #0d1014;
  --gray-2: #12161b;
  --gray-3: #191e25;
  --gray-4: #1f252d;
  --gray-5: #262d36;
  --gray-6: #2f3741;
  --gray-7: #3c4551;
  --gray-8: #535e6d;
  --gray-9: #6b7686;
  --gray-10: #7d8898;
  --gray-11: #aab3c0;
  --gray-12: #eceff3;
  --accent-1: #0e1220;
  --accent-2: #121a30;
  --accent-3: #172447;
  --accent-4: #1b2c5c;
  --accent-5: #21366f;
  --accent-6: #294283;
  --accent-7: #33519c;
  --accent-8: #3e63c4;
  --accent-9: #4a74ff;
  --accent-10: #5e84ff;
  --accent-11: #9db5ff;
  --accent-12: #d8e2ff;
  --pass-3: #10291b;
  --pass-7: #22683f;
  --pass-9: #3cc477;
  --pass-11: #7fdca3;
  --fail-3: #2f1414;
  --fail-7: #7c2a2a;
  --fail-9: #f25454;
  --fail-11: #ff9d9d;
  --warn-3: #2c2110;
  --warn-7: #7a5718;
  --warn-9: #f0a83a;
  --warn-11: #f5c878;
  --covered: #1e3a5f;
  --uncovered: #422006;
  --fail-line: #7f1d1d;
  --diff-bg: #0d1117;
  --diff-add-bg: rgba(46, 160, 67, 0.15);
  --diff-add-gutter: rgba(63, 185, 80, 0.3);
  --diff-del-bg: rgba(248, 81, 73, 0.12);
  --diff-del-gutter: rgba(248, 81, 73, 0.3);
  --diff-sign-add: #3fb950;
  --diff-sign-del: #f85149;
  --diff-gutter-fg: #8d96a0;
  --diff-expander-bg: #161b22;
  --diff-expander-fg: #58a6ff;
  --tok-kw: #ff7b72;
  --tok-str: #a5d6ff;
  --tok-num: #79c0ff;
  --tok-cmt: #8b949e;
  --tok-ty: #ffa657;
  --tok-fnc: #d2a8ff;
  --tok-mac: #7ee787;
  --tok-attr: #7ee787;
  --rail-pass: #3fb950;
  --rail-fail: #f85149;
  --rail-covered: #79c0ff;
  --rail-gap: #f0a83a;
}
* { box-sizing: border-box; }
html, body { width: 100%; max-width: none; margin: 0; }
body { padding: 0; font: var(--text-sm)/1.5 var(--font-sans); background: var(--bg); color: var(--fg); -webkit-font-smoothing: antialiased; }
header { border-bottom: 1px solid var(--border); padding: 12px 0; background: var(--bg); }
.header-bar { display: flex; align-items: center; gap: 12px; }
.page { max-width: none; width: 100%; margin: 0; padding: 0 24px; box-sizing: border-box; }
main.page { padding-top: 16px; padding-bottom: 32px; }
.report-section { width: 100%; max-width: none; }
.table-wrap { width: 100%; overflow-x: auto; }
.table-scroll { max-width: 100%; }
.status { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-variant-numeric: tabular-nums; }
#theme-toggle { flex-shrink: 0; padding: 2px 8px; font: inherit; line-height: 1.4; }
button { background: transparent; color: var(--fg); border: 1px solid var(--border); border-radius: var(--radius-2); padding: 4px 8px; cursor: pointer; font: inherit; }
section { margin-bottom: 24px; }
h2 { font-size: inherit; margin: 0 0 8px; font-weight: 600; }
.finding-card h3 { font-size: inherit; margin: 8px 10px 4px; font-weight: 600; }
.finding-card p { margin: 4px 10px 8px; }
table { width: 100%; border-collapse: collapse; }
th, td { border-bottom: 1px solid var(--border); padding: 6px 8px; text-align: left; }
th { color: var(--muted); }
.num { text-align: right; }
.cell-detail { white-space: nowrap; font-variant-numeric: tabular-nums; }
.sub { color: var(--muted); }
.ok { color: var(--ok); }
.skip { color: var(--skip); }
.fail { color: var(--fail); }
.low { color: var(--low); }
.covered { color: var(--accent-11); }
.uncovered { color: var(--skip); }
.neutral { color: var(--fg); }
.muted { color: var(--muted); }
.fold-summary { display: flex; justify-content: space-between; align-items: baseline; gap: 16px; width: 100%; }
.fold-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fold-stats { flex-shrink: 0; text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; min-width: 280px; }
details.project, details.test-project { border: 1px solid var(--border); border-radius: var(--radius-3); margin-bottom: 8px; overflow: hidden; }
details.project > details.module,
details.module > details.file,
details.test-project > details.test-suite,
details.test-suite > details.test-case { border: none; border-top: 1px solid var(--border); margin-bottom: 0; border-radius: 0; }
details.project > summary,
details.module > summary,
details.file > summary,
details.test-project > summary,
details.test-suite > summary,
details.test-case > summary { padding: 8px 10px; cursor: pointer; list-style: none; }
details.module > summary { padding-left: 22px; }
details.file > summary { padding-left: 34px; }
details.test-suite > summary { padding-left: 22px; }
details.test-case > summary { padding-left: 34px; }
details.project > summary::-webkit-details-marker,
details.module > summary::-webkit-details-marker,
details.file > summary::-webkit-details-marker,
details.test-project > summary::-webkit-details-marker,
details.test-suite > summary::-webkit-details-marker,
details.test-case > summary::-webkit-details-marker { display: none; }
details.project > summary::before,
details.module > summary::before,
details.file > summary::before,
details.test-project > summary::before,
details.test-suite > summary::before,
details.test-case > summary::before { content: "▸ "; color: var(--muted); }
details[open].project > summary::before,
details[open].module > summary::before,
details[open].file > summary::before,
details[open].test-project > summary::before,
details[open].test-suite > summary::before,
details[open].test-case > summary::before { content: "▾ "; }
.diff { margin: 0; padding: 0; overflow-x: auto; font-family: var(--font-mono); font-size: 12px; line-height: 20px; background: var(--diff-bg); }
.diff::before { content: "run  green passed · red failed !    ±  ++ added · -- removed    cov  blue covered · amber gap"; display: block; padding: 3px 10px 4px; font-family: var(--font-sans); font-size: 11px; line-height: 16px; color: var(--muted); white-space: normal; }
.diff .row { display: flex; flex-wrap: nowrap; white-space: pre; align-items: stretch; width: 100%; min-width: 0; }
.diff .rail { width: 14px; flex: 0 0 14px; display: flex; align-items: center; justify-content: center; user-select: none; }
.diff .no { width: 52px; text-align: right; padding: 0 8px; color: var(--diff-gutter-fg); flex-shrink: 0; font-variant-numeric: tabular-nums; user-select: none; }
.diff .mark { width: 28px; flex-shrink: 0; text-align: center; user-select: none; font-weight: 600; }
.diff .code { flex: 1; padding: 0 10px; min-width: 0; }
.diff .row.covered:not(.fail-line) .rail.run { background: var(--rail-pass); }
.diff .row.fail-line .rail.run { background: var(--rail-fail); }
.diff .row.covered .rail.cov { background: var(--rail-covered); }
.diff .row.uncovered .rail.cov { background: var(--rail-gap); }
.diff .row .code { color: var(--fg); }
.diff .row.ctx { background: var(--diff-bg); }
.diff .row.add { background: var(--diff-add-bg); }
.diff .row.add .no { background: var(--diff-add-gutter); }
.diff .row.add .mark { color: var(--diff-sign-add); }
.diff .row.del { background: var(--diff-del-bg); }
.diff .row.del .no { background: var(--diff-del-gutter); }
.diff .row.del .mark { color: var(--diff-sign-del); }
.diff .row.covered .code { background: var(--covered); }
.diff .row.uncovered .code { background: var(--uncovered); }
.diff .row.fail-line .code { background: var(--fail-line); }
.diff .row.ellipsis { background: var(--diff-expander-bg); }
.diff .row.ellipsis .code { color: var(--diff-expander-fg); }
.diff .row.column-labels { background: var(--diff-bg); font-family: var(--font-sans); font-size: 11px; line-height: 20px; border-bottom: 1px solid var(--border); }
.diff .row.column-labels .rail, .diff .row.column-labels .no, .diff .row.column-labels .mark, .diff .row.column-labels .code { background: transparent; color: var(--diff-gutter-fg); font-weight: 600; }
.diff .row.column-labels .rail { overflow: hidden; font-size: 0; line-height: 0; }
.diff .row.column-labels .rail.run { background: linear-gradient(to right, var(--rail-pass) 50%, var(--rail-fail) 50%); }
.diff .row.column-labels .rail.cov { background: linear-gradient(to right, var(--rail-covered) 50%, var(--rail-gap) 50%); }
.diff .rail .flag, .why .flag { border: 0; background: transparent; padding: 0; line-height: 20px; font-weight: 700; cursor: pointer; }
.diff .rail .flag { color: #fff; min-width: 14px; }
.why { position: relative; display: inline-flex; align-items: center; gap: 2px; margin-right: 6px; }
.why .flag { color: var(--fail); font: inherit; }
.why-pop { display: none; position: absolute; z-index: 20; right: 0; top: calc(100% + 4px); width: max-content; max-width: 360px; padding: 8px 10px; background: var(--bg); color: var(--fg); border: 1px solid var(--fail-9); border-radius: var(--radius-2); box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12); white-space: pre-wrap; text-align: left; font-weight: 400; }
.why.open .why-pop, .why:hover .why-pop, .why:focus-within .why-pop { display: block; }
.test-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; align-items: center; max-width: 100%; }
.test-toolbar input, .test-toolbar select, #test-view-toggle { font: inherit; padding: 4px 8px; border: 1px solid var(--border); border-radius: var(--radius-2); background: var(--bg); color: var(--fg); max-width: 100%; min-width: 0; }
#tests[data-view="flat"] #test-list-grouped { display: none; }
#tests[data-view="grouped"] #test-list-flat { display: none; }
#test-list-flat details.test-case > summary { padding-left: 10px; }
#test-list-flat .test-path { color: var(--muted); }
#test-list-flat .test-detail { padding-left: 10px; }
.test-detail { padding: 0 10px 10px 34px; }
.test-reason { margin: 6px 0; white-space: pre-wrap; word-break: break-word; }
.test-meta { color: var(--muted); margin: 4px 0; }
.test-snippet { margin-top: 8px; }
.test-snippet .row.highlight .code { background: var(--covered); }
.test-snippet.fail .row.highlight .code { background: var(--fail-line); }
.crap-list { margin-top: 8px; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.crap-row { display: grid; grid-template-columns: 64px 92px 76px 76px minmax(120px, 1fr) minmax(160px, 2fr); gap: 8px; align-items: center; padding: 6px 10px 6px 12px; font-variant-numeric: tabular-nums; }
.crap-head { font-weight: 600; color: var(--muted); border-bottom: 1px solid var(--border); border-left: 4px solid transparent; }
details.crap-fn { border-left: 4px solid var(--gray-6); border-bottom: 1px solid var(--border); }
details.crap-fn > summary { cursor: pointer; list-style: none; }
details.crap-fn > summary::-webkit-details-marker { display: none; }
details.crap-fn > summary .crap-name::before { content: "▸ "; color: var(--muted); }
details[open].crap-fn > summary .crap-name::before { content: "▾ "; }
.crap-name, .crap-location { font-family: var(--font-mono); font-size: 12px; overflow-wrap: anywhere; }
.crap-location { color: var(--muted); }
.crap-critical { border-left-color: var(--fail-9); background: var(--fail-3); }
.crap-critical .crap-score { color: var(--fail-11); font-weight: 700; }
.crap-high { border-left-color: var(--warn-9); background: var(--warn-3); }
.crap-high .crap-score { color: var(--warn-11); font-weight: 700; }
.crap-moderate { border-left-color: var(--warn-7); }
.crap-moderate .crap-score { color: var(--warn-11); }
.crap-low .crap-score { color: var(--muted); }
details.crap-fn > .test-snippet, .crap-no-source { margin: 0 10px 10px; }
details.crap-more > summary { cursor: pointer; padding: 8px 12px; color: var(--muted); border-bottom: 1px solid var(--border); }
@media (max-width: 720px) {
  .crap-head { display: none; }
  .crap-row { grid-template-columns: 56px repeat(3, minmax(0, 1fr)); }
  .crap-name, .crap-location { grid-column: 1 / -1; }
}
.diff .tok-kw { color: var(--tok-kw); }
.diff .tok-str { color: var(--tok-str); }
.diff .tok-num { color: var(--tok-num); }
.diff .tok-cmt { color: var(--tok-cmt); font-style: italic; }
.diff .tok-ty { color: var(--tok-ty); }
.diff .tok-fnc { color: var(--tok-fnc); }
.diff .tok-mac { color: var(--tok-mac); }
.diff .tok-attr { color: var(--tok-attr); }
a.sym { color: inherit; text-decoration: underline dotted; text-underline-offset: 2px; }
a.sym:hover { text-decoration-thickness: 2px; }
.diff .row.jump-target .code { box-shadow: inset 0 0 0 2px var(--accent-8); }
.test-hidden { display: none !important; }
#tests .fold-stats { min-width: 120px; }
#changes .fold-stats { min-width: 320px; }
"#;

pub const REPORT_HTML_THEME_SCRIPT: &str = r#"(function() {
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
"#;

pub const REPORT_HTML_TEST_FILTER_SCRIPT: &str = r#"(function() {
  const filterInput = document.getElementById('test-filter');
  const tagSelect = document.getElementById('test-tag');
  const statusSelect = document.getElementById('test-status');
  const sortSelect = document.getElementById('test-sort');
  const viewToggle = document.getElementById('test-view-toggle');
  const testsSection = document.getElementById('tests');
  if (!filterInput) return;
  function testViewMode() {
    return testsSection && testsSection.getAttribute('data-view') === 'flat' ? 'flat' : 'grouped';
  }
  function syncViewToggle() {
    if (!viewToggle) return;
    const flat = testViewMode() === 'flat';
    viewToggle.textContent = flat ? 'flat' : 'grouped';
    viewToggle.setAttribute('aria-label', flat ? 'flat view' : 'grouped view');
  }
  function applyTestFilters() {
    const text = (filterInput.value || '').trim().toLowerCase();
    const tag = tagSelect ? tagSelect.value : '';
    const status = statusSelect ? statusSelect.value : 'all';
    const sort = sortSelect ? sortSelect.value : 'default';
    const flat = testViewMode() === 'flat';
    const caseNodes = flat
      ? [...document.querySelectorAll('#test-list-flat > details.test-case')]
      : [...document.querySelectorAll('#test-list-grouped details.test-case')];
    caseNodes.forEach((node) => {
      const tags = (node.getAttribute('data-tags') || '').toLowerCase();
      const label = (node.getAttribute('data-label') || '').toLowerCase();
      const st = node.getAttribute('data-status') || '';
      let visible = true;
      if (text && !tags.includes(text) && !label.includes(text)) visible = false;
      if (tag && !tags.split(/\s+/).includes(tag.toLowerCase())) visible = false;
      if (status === 'fail' && st !== 'FAIL') visible = false;
      if (status === 'skip' && st !== 'skipped') visible = false;
      node.classList.toggle('test-hidden', !visible);
    });
    if (flat) {
      const flatRoot = document.getElementById('test-list-flat');
      if (flatRoot) {
        const visibleCases = caseNodes.filter((c) => !c.classList.contains('test-hidden'));
        if (sort === 'fast') {
          visibleCases.sort((a, b) => Number(a.getAttribute('data-ms')) - Number(b.getAttribute('data-ms')));
        } else if (sort === 'slow') {
          visibleCases.sort((a, b) => Number(b.getAttribute('data-ms')) - Number(a.getAttribute('data-ms')));
        } else {
          visibleCases.sort((a, b) => Number(a.getAttribute('data-order')) - Number(b.getAttribute('data-order')));
        }
        visibleCases.forEach((c) => flatRoot.appendChild(c));
      }
      return;
    }
    document.querySelectorAll('#test-list-grouped details.test-suite').forEach((suite) => {
      const cases = [...suite.querySelectorAll(':scope > details.test-case')];
      const visibleCases = cases.filter((c) => !c.classList.contains('test-hidden'));
      if (sort === 'fast') {
        visibleCases.sort((a, b) => Number(a.getAttribute('data-ms')) - Number(b.getAttribute('data-ms')));
      } else if (sort === 'slow') {
        visibleCases.sort((a, b) => Number(b.getAttribute('data-ms')) - Number(a.getAttribute('data-ms')));
      } else {
        visibleCases.sort((a, b) => Number(a.getAttribute('data-order')) - Number(b.getAttribute('data-order')));
      }
      visibleCases.forEach((c) => suite.appendChild(c));
      suite.classList.toggle('test-hidden', visibleCases.length === 0);
    });
    document.querySelectorAll('#test-list-grouped details.test-project').forEach((project) => {
      const suites = [...project.querySelectorAll('details.test-suite')];
      const visibleSuites = suites.filter((s) => !s.classList.contains('test-hidden'));
      project.classList.toggle('test-hidden', visibleSuites.length === 0);
    });
  }
  if (viewToggle && testsSection) {
    syncViewToggle();
    viewToggle.addEventListener('click', () => {
      const next = testViewMode() === 'flat' ? 'grouped' : 'flat';
      testsSection.setAttribute('data-view', next);
      syncViewToggle();
      applyTestFilters();
    });
  }
  [filterInput, tagSelect, statusSelect, sortSelect].forEach((el) => {
    if (el) el.addEventListener('input', applyTestFilters);
    if (el && el.tagName === 'SELECT') el.addEventListener('change', applyTestFilters);
  });
  function placeWhy(why) {
    const pop = why.querySelector('.why-pop');
    const anchor = why.querySelector('.flag.ask') || why;
    if (!pop || !anchor) return;
    const margin = 8;
    pop.style.position = 'fixed';
    pop.style.right = 'auto';
    pop.style.width = 'max-content';
    pop.style.maxWidth = Math.min(360, window.innerWidth - margin * 2) + 'px';
    const previous = pop.style.display;
    pop.style.display = 'block';
    const rect = anchor.getBoundingClientRect();
    const width = pop.offsetWidth;
    const height = pop.offsetHeight;
    pop.style.display = previous;
    let left = rect.right - width;
    if (left < margin) left = margin;
    if (left + width > window.innerWidth - margin) left = Math.max(margin, window.innerWidth - margin - width);
    let top = rect.bottom + 4;
    if (top + height > window.innerHeight - margin) top = Math.max(margin, rect.top - height - 4);
    pop.style.left = left + 'px';
    pop.style.top = top + 'px';
  }
  document.addEventListener('pointerover', (event) => {
    const why = event.target.closest && event.target.closest('.why');
    if (why) placeWhy(why);
  }, true);
  document.addEventListener('click', (event) => {
    const flag = event.target.closest && event.target.closest('.why .flag');
    if (!flag) return;
    event.preventDefault();
    event.stopPropagation();
    const why = flag.closest('.why');
    if (!why) return;
    const open = !why.classList.contains('open');
    document.querySelectorAll('.why.open').forEach((node) => {
      node.classList.remove('open');
      const ask = node.querySelector('.flag.ask');
      if (ask) ask.setAttribute('aria-expanded', 'false');
    });
    if (!open) return;
    why.classList.add('open');
    const ask = why.querySelector('.flag.ask');
    if (ask) ask.setAttribute('aria-expanded', 'true');
    placeWhy(why);
  }, true);
  window.addEventListener('scroll', () => {
    document.querySelectorAll('.why.open').forEach(placeWhy);
  }, true);
})();
"#;

pub const REPORT_HTML_JUMP_SCRIPT: &str = r#"(function() {
  function reveal(id) {
    const target = document.getElementById(id);
    if (!target) return false;
    let node = target.parentElement;
    while (node) {
      if (node.tagName === 'DETAILS') node.open = true;
      node = node.parentElement;
    }
    target.scrollIntoView({ block: 'center' });
    document.querySelectorAll('.jump-target').forEach((el) => el.classList.remove('jump-target'));
    target.classList.add('jump-target');
    return true;
  }
  document.addEventListener('click', (event) => {
    const link = event.target.closest('a.sym');
    if (!link) return;
    if (!event.metaKey && !event.ctrlKey) { event.preventDefault(); return; }
    const href = link.getAttribute('href') || '';
    const hashAt = href.indexOf('#');
    const hash = hashAt >= 0 ? href.slice(hashAt + 1) : '';
    if (href.startsWith('#')) {
      event.preventDefault();
      if (reveal(hash)) history.pushState(null, '', '#' + hash);
      return;
    }
    event.preventDefault();
    location.href = href;
  });
  window.addEventListener('hashchange', () => { const id = location.hash.slice(1); if (id) reveal(id); });
  if (location.hash.length > 1) reveal(location.hash.slice(1));
})();
"#;

pub fn render_combined_report_document(
    title: &str,
    status_html: &str,
    main_body_html: &str,
) -> String {
    format!(
        "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>{title}</title>\n<style>\n{styles}\n</style>\n</head>\n<body>\n<header>\n<div class=\"page header-bar\"><div class=\"status\">{status}</div><button type=\"button\" id=\"theme-toggle\" aria-label=\"theme\">☀</button></div></header>\n<main class=\"page\">\n{body}\n</main>\n<script>\n{theme_script}\n{test_script}\n{jump_script}\n</script>\n</body>\n</html>\n",
        title = html_escape(title),
        styles = REPORT_HTML_STYLES,
        status = status_html,
        body = main_body_html,
        theme_script = REPORT_HTML_THEME_SCRIPT,
        test_script = REPORT_HTML_TEST_FILTER_SCRIPT,
        jump_script = REPORT_HTML_JUMP_SCRIPT,
    )
}

pub fn diff_row_with_id(
    id: &str,
    class: &str,
    old: &str,
    new: &str,
    mark: &str,
    code_html: &str,
) -> String {
    let id_attr = if id.is_empty() {
        String::new()
    } else {
        format!(" id=\"{id}\"")
    };
    let class_attr = if class.is_empty() {
        "row".to_string()
    } else {
        format!("row {class}")
    };
    let run = rail_run(class);
    let cov = rail_cov(class);
    format!(
        "<div class=\"{class_attr}\"{id_attr}><span class=\"rail run\">{run}</span><span class=\"no old\">{old}</span><span class=\"no new\">{new}</span><span class=\"mark\">{mark}</span><span class=\"code\">{code_html}</span><span class=\"rail cov\">{cov}</span></div>"
    )
}

fn rail_run(class: &str) -> &'static str {
    if class_has(class, "column-labels") {
        "run"
    } else if class_has(class, "fail-line") {
        "<button type=\"button\" class=\"flag bang\" aria-label=\"failed line\">!</button>"
    } else {
        ""
    }
}

fn rail_cov(class: &str) -> &'static str {
    if class_has(class, "column-labels") {
        "cov"
    } else {
        ""
    }
}

fn class_has(class: &str, name: &str) -> bool {
    class.split_whitespace().any(|part| part == name)
}

pub fn diff_row_html(class: &str, old: &str, new: &str, mark: &str, code_html: &str) -> String {
    diff_row_with_id("", class, old, new, mark, code_html)
}

pub fn html_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn styles_use_github_pull_request_gutters_and_a_blue_covered_line() {
        assert!(REPORT_HTML_STYLES.contains("--covered: #dbeafe;"));
        assert!(REPORT_HTML_STYLES.contains("--diff-add-bg: #e6ffec;"));
        assert!(REPORT_HTML_STYLES.contains("--diff-add-gutter: #ccffd8;"));
        assert!(REPORT_HTML_STYLES.contains("--diff-del-bg: #ffebe9;"));
        assert!(REPORT_HTML_STYLES
            .contains(".diff .row.add .no { background: var(--diff-add-gutter); }"));
        assert!(REPORT_HTML_STYLES
            .contains(".diff .row.del .no { background: var(--diff-del-gutter); }"));
        assert!(
            REPORT_HTML_STYLES.contains(".diff .row.covered .code { background: var(--covered); }")
        );
        assert!(REPORT_HTML_STYLES.contains("--rail-pass: #1a7f37;"));
        assert!(REPORT_HTML_STYLES.contains(".diff .row.fail-line .rail.run"));
        assert!(REPORT_HTML_STYLES.contains(".diff .row.uncovered .rail.cov"));
        assert!(REPORT_HTML_STYLES.contains("++ added · -- removed"));
        assert!(REPORT_HTML_STYLES.contains(".test-toolbar select"));
        assert!(REPORT_HTML_STYLES.contains("min-width: 0;"));
        assert!(REPORT_HTML_TEST_FILTER_SCRIPT.contains("function placeWhy"));
        assert!(REPORT_HTML_TEST_FILTER_SCRIPT.contains("closest('.why .flag')"));
        assert!(!REPORT_HTML_STYLES.contains(".diff .row.add .code { background"));
        assert!(!REPORT_HTML_STYLES.contains(".diff .row.del .code { background"));
    }
    #[test]
    fn diff_row_has_old_and_new_columns() {
        let row = diff_row_html("add covered", "", "12", "++", "let value = 1;");
        assert!(row.contains("<span class=\"rail run\"></span>"));
        assert!(row.contains("<span class=\"rail cov\"></span>"));
        assert!(row.contains("<span class=\"no old\"></span>"));
        assert!(row.contains("<span class=\"no new\">12</span>"));
        assert!(row.contains("<span class=\"mark\">++</span>"));
        assert!(row.contains("class=\"row add covered\""));
        let removed = diff_row_html("del", "3", "", "--", "old");
        assert!(removed.contains("<span class=\"mark\">--</span>"));
        let failed = diff_row_html("add uncovered fail-line", "", "4", "++", "gap");
        assert!(failed.contains("aria-label=\"failed line\">!</button>"));
        let labels = diff_row_html("column-labels", "old", "new", "±", "code");
        assert!(labels.contains("<span class=\"rail run\">run</span>"));
        assert!(labels.contains("<span class=\"rail cov\">cov</span>"));
    }
    #[test]
    fn combined_document_includes_theme_toggle() {
        let doc = render_combined_report_document("t", "ok", "<section></section>");
        assert!(doc.contains("theme-toggle"));
        assert!(doc.contains("cherry-test-theme"));
    }
    #[test]
    fn jump_script_requires_command_or_control_and_opens_details() {
        assert!(REPORT_HTML_STYLES.contains("--tok-kw: #cf222e;"));
        assert!(REPORT_HTML_STYLES.contains(".diff .tok-kw { color: var(--tok-kw); }"));
        assert!(REPORT_HTML_JUMP_SCRIPT.contains("event.metaKey"));
        assert!(REPORT_HTML_JUMP_SCRIPT.contains("event.ctrlKey"));
        assert!(REPORT_HTML_JUMP_SCRIPT.contains("DETAILS"));
        let doc = render_combined_report_document("t", "", "");
        assert!(doc.contains("event.metaKey"));
        let row = diff_row_with_id("cov-unit-a-rs-L1", "ctx covered", "1", "1", "", "fn");
        assert!(row.contains(" id=\"cov-unit-a-rs-L1\""));
        assert!(!diff_row_html("ctx", "1", "1", "", "fn").contains(" id="));
    }
    const DESIGN_SYSTEM_TOKENS: &str = include_str!("../../../site/design-system/tokens.css");
    const PRIMITIVE_SCALES: [&str; 6] = [
        "--gray-",
        "--accent-",
        "--pass-",
        "--fail-",
        "--warn-",
        "--radius-",
    ];
    fn declarations(stylesheet: &str, selector: &str) -> Vec<(String, String)> {
        let block_start = stylesheet
            .find(selector)
            .map(|index| index + selector.len())
            .unwrap_or_else(|| panic!("selector {selector} missing"));
        let block_end = block_start + stylesheet[block_start..].find('}').unwrap_or(0);
        stylesheet[block_start..block_end]
            .split(';')
            .filter_map(|declaration| declaration.split_once(':'))
            .map(|(name, value)| (name.trim().to_string(), value.trim().to_string()))
            .filter(|(name, _)| {
                PRIMITIVE_SCALES.iter().any(|scale| {
                    name.strip_prefix(scale).is_some_and(|step| {
                        !step.is_empty() && step.chars().all(|c| c.is_ascii_digit())
                    })
                })
            })
            .collect()
    }
    fn assert_primitives_match(report_selector: &str, tokens_selector: &str) {
        let report = declarations(REPORT_HTML_STYLES, report_selector);
        let tokens = declarations(DESIGN_SYSTEM_TOKENS, tokens_selector);
        assert!(!report.is_empty());
        for (name, value) in &report {
            let token = tokens.iter().find(|(token_name, _)| token_name == name);
            assert_eq!(
                token.map(|(_, token_value)| token_value),
                Some(value),
                "{name}"
            );
        }
        for (name, _) in tokens
            .iter()
            .filter(|(name, _)| !name.starts_with("--radius-"))
        {
            assert!(
                report.iter().any(|(report_name, _)| report_name == name),
                "{name} missing"
            );
        }
    }
    #[test]
    fn light_primitives_match_design_system_tokens() {
        assert_primitives_match(":root {", ":root {");
    }
    #[test]
    fn dark_primitives_match_design_system_tokens() {
        assert_primitives_match(
            "html[data-theme=\"dark\"] {",
            ":root[data-theme=\"dark\"] {",
        );
    }
}
