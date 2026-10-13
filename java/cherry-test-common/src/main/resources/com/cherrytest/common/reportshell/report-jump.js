(function() {
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
