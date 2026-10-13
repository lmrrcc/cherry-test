(function() {
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
