(function () {
  const project = window.SITE_PROJECT;
  const releases = window.SITE_RELEASES;

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function applyProject() {
    if (!project) return;
    document.querySelectorAll("[data-project]").forEach(function (node) {
      const value = project[node.getAttribute("data-project")];
      if (typeof value === "string") node.textContent = value;
    });
    document.querySelectorAll("[data-project-href]").forEach(function (node) {
      node.setAttribute("href", "https://github.com/" + project.repository);
    });
  }

  function readStoredTheme() {
    try { return localStorage.getItem("site-theme"); } catch (error) { return null; }
  }

  function storeTheme(theme) {
    try { localStorage.setItem("site-theme", theme); } catch (error) { return; }
  }

  function currentTheme() {
    const explicit = document.documentElement.getAttribute("data-theme");
    if (explicit) return explicit;
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }

  function setupTheme() {
    const stored = readStoredTheme();
    if (stored === "light" || stored === "dark") document.documentElement.setAttribute("data-theme", stored);
    document.querySelectorAll("[data-theme-toggle]").forEach(function (button) {
      button.addEventListener("click", function () {
        const next = currentTheme() === "dark" ? "light" : "dark";
        document.documentElement.setAttribute("data-theme", next);
        storeTheme(next);
      });
    });
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    return new Promise(function (resolve, reject) {
      const area = element("textarea");
      area.value = text;
      area.setAttribute("readonly", "");
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      const copied = document.execCommand("copy");
      document.body.removeChild(area);
      if (copied) resolve(); else reject(new Error("copy rejected"));
    });
  }

  function setupCopy() {
    document.querySelectorAll("[data-copy]").forEach(function (button) {
      const label = button.querySelector("[data-copy-label]");
      button.addEventListener("click", function () {
        const source = document.getElementById(button.getAttribute("data-copy"));
        if (!source) return;
        const text = source.innerText.replace(/^\$ /gm, "").trim();
        copyText(text).then(function () {
          button.setAttribute("data-state", "copied");
          if (label) label.textContent = "Copied";
        }, function () {
          if (label) label.textContent = "Select to copy";
        }).then(function () {
          window.setTimeout(function () {
            button.removeAttribute("data-state");
            if (label) label.textContent = "Copy";
          }, 1600);
        });
      });
    });
  }

  function setupTabs() {
    document.querySelectorAll("[role=tablist]").forEach(function (list) {
      const tabs = Array.from(list.querySelectorAll("[role=tab]"));
      function select(tab) {
        tabs.forEach(function (candidate) {
          const selected = candidate === tab;
          candidate.setAttribute("aria-selected", String(selected));
          candidate.tabIndex = selected ? 0 : -1;
          const panel = document.getElementById(candidate.getAttribute("aria-controls"));
          if (panel) panel.hidden = !selected;
        });
      }
      tabs.forEach(function (tab, index) {
        tab.addEventListener("click", function () { select(tab); });
        tab.addEventListener("keydown", function (event) {
          if (event.key !== "ArrowRight" && event.key !== "ArrowLeft") return;
          const offset = event.key === "ArrowRight" ? 1 : tabs.length - 1;
          const next = tabs[(index + offset) % tabs.length];
          select(next);
          next.focus();
        });
      });
    });
  }

  function formatBytes(bytes) {
    if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + " MB";
    if (bytes >= 1024) return (bytes / 1024).toFixed(0) + " KB";
    return bytes + " B";
  }

  function renderBinaries(container) {
    const published = releases.versions.filter(function (version) {
      return version.release && version.release.assets.length > 0;
    });
    if (published.length === 0) {
      const empty = element("div", "empty-state");
      empty.appendChild(element("span", "badge badge-warn", "No binaries published"));
      empty.appendChild(element("p", "heading-3", "No prebuilt binaries have been published yet."));
      const detail = element("p", "muted small");
      detail.textContent = "Release assets for " + releases.repository + " are listed here when a tagged release ships them. Until then, install from source with the script in a clone or with cargo.";
      empty.appendChild(detail);
      container.appendChild(empty);
      return;
    }
    const wrap = element("div", "table-wrap");
    const table = element("table", "table");
    const head = element("thead");
    const headRow = element("tr");
    ["Version", "Asset", "Size", "Published"].forEach(function (label, index) {
      headRow.appendChild(element("th", index === 2 ? "numeric" : "", label));
    });
    head.appendChild(headRow);
    table.appendChild(head);
    const body = element("tbody");
    published.forEach(function (version) {
      version.release.assets.forEach(function (asset, index) {
        const row = element("tr");
        row.appendChild(element("td", "mono", index === 0 ? (version.tag || version.version) : ""));
        const assetCell = element("td");
        const link = element("a", "mono", asset.name);
        link.href = asset.url;
        assetCell.appendChild(link);
        row.appendChild(assetCell);
        row.appendChild(element("td", "numeric mono", formatBytes(asset.size)));
        row.appendChild(element("td", "mono muted", index === 0 ? (version.release.published || "").slice(0, 10) : ""));
        body.appendChild(row);
      });
    });
    table.appendChild(body);
    wrap.appendChild(table);
    container.appendChild(wrap);
  }

  function setupReleases() {
    if (!releases) return;
    const binaries = document.getElementById("binaries-list");
    if (binaries) renderBinaries(binaries);
    document.querySelectorAll("[data-release-version]").forEach(function (node) {
      node.textContent = "v" + releases.versions[0].version;
    });
    document.querySelectorAll("[data-release-generated]").forEach(function (node) {
      node.textContent = releases.generatedAt.slice(0, 10);
    });
  }

  function setupRunStage() {
    const stage = document.getElementById("run-stage");
    const mount = document.getElementById("run-player");
    const play = document.getElementById("run-play");
    if (!stage || !mount || !play) return;
    let player = null;
    function markUnavailable() {
      stage.dataset.state = "unavailable";
      play.disabled = true;
    }
    if (!window.AsciinemaPlayer) {
      markUnavailable();
      return;
    }
    try {
      player = window.AsciinemaPlayer.create("demo/shop.cast?v=20261009-summary", mount, {
        fit: "both",
        theme: "nord",
        controls: false,
        autoPlay: false,
        loop: false,
        preload: true,
        poster: "npt:1.5",
        terminalFontFamily: '"Geist Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'
      });
    } catch (error) {
      markUnavailable();
      return;
    }
    player.addEventListener("error", markUnavailable);
    player.addEventListener("playing", function () {
      stage.dataset.state = "player";
      play.textContent = "Restart demo";
    });
    player.addEventListener("ended", function () {
      play.textContent = "Restart demo";
    });
    play.addEventListener("click", function () {
      const start = function () { player.play(); };
      const seeking = player.seek(0);
      if (seeking && typeof seeking.then === "function") seeking.then(start, start);
      else start();
    });
  }

  applyProject();
  setupTheme();
  setupCopy();
  setupTabs();
  setupReleases();
  setupRunStage();
})();
