const API = ""; // aynı origin'de servis ediliyor
const POLL_SECONDS = 5;
const HISTORY_LENGTH = 24;
const DIRTY_WARN_THRESHOLD = 50;

let pollTimer = null;
let countdownTimer = null;
let clockTimer = null;
let secondsUntilRefresh = POLL_SECONDS;
let autoRefreshPaused = false;
let sessionStartedAt = null;

let lastStats = null;
let cachedHistory = [];
let dirtyHistory = [];
let lastDeltaCached = 0;
let lastDeltaDirty = 0;

let addonsData = [];
let sortKey = "id";
let sortDir = "asc";
let searchTerm = "";
let expandedAddonId = null;

let connState = "ok"; // "ok" | "fail"
let eventLogEntries = []; // { level, key, params, ts }

// ── i18n ──
const translations = {
  tr: {
    "doc.title": "Nexus Cache Orchestrator — Panel",
    "app.subtitle": "cache orchestrator / panel",
    "login.username": "Kullanıcı adı",
    "login.password": "Parola",
    "login.show": "GÖSTER",
    "login.hide": "GİZLE",
    "login.remember": "Kullanıcı adımı hatırla",
    "login.submit": "Giriş yap",
    "login.submitting": "Giriş yapılıyor…",
    "login.error.generic": "Giriş başarısız.",
    "login.error.network": "Sunucuya bağlanılamadı.",
    "topbar.panel": "/ panel",
    "topbar.connected": "Bağlı",
    "topbar.disconnected": "Bağlantı yok",
    "topbar.session": "Oturum",
    "topbar.logout": "Çıkış",
    "stats.cached": "Önbellekteki kayıt",
    "stats.addons": "Yüklü eklenti",
    "stats.addons.sub": "aktif modül sayısı",
    "stats.dirty": "Bekleyen yazım (dirty)",
    "stats.mongo": "MongoDB",
    "stats.mongo.ok": "Bağlı",
    "stats.mongo.fail": "Kopuk",
    "stats.mongo.sub.ok": "bağlantı sağlıklı",
    "stats.mongo.sub.fail": "bağlantı sağlanamıyor",
    "delta.none": "değişim yok",
    "delta.suffix": "/ son yenileme",
    "delta.unit.records": "kayıt",
    "delta.unit.keys": "anahtar",
    "addons.title": "Eklentiler",
    "addons.search.placeholder": "ara… ( / )",
    "addons.refresh": "Yenile",
    "addons.th.id": "ID",
    "addons.th.name": "Ad",
    "addons.th.class": "Sınıf",
    "addons.th.db": "Veritabanı",
    "addons.th.collection": "Koleksiyon",
    "addons.th.ttl": "Cache TTL",
    "addons.empty": "Yüklü eklenti yok.",
    "addons.noMatch": '"{term}" ile eşleşen eklenti yok.',
    "addons.loading": "Yükleniyor…",
    "detail.class": "Tam sınıf yolu",
    "detail.storage": "Depolama",
    "detail.ttl": "Cache TTL",
    "detail.id": "Eklenti ID",
    "unit.sec": "sn",
    "unit.min": "dk",
    "unit.hr": "sa",
    "log.title": "Olay günlüğü",
    "log.sessionStart": "Oturum açıldı, panel yüklendi.",
    "log.fetchFail": "Veriler alınamadı, sunucuya ulaşılamıyor.",
    "log.dirtyThreshold": "Bekleyen yazım sayısı eşiği aştı: {n} anahtar.",
    "log.mongoUp": "MongoDB bağlantısı yeniden kuruldu.",
    "log.mongoDown": "MongoDB bağlantısı koptu.",
    "toast.disconnected": "Sunucuyla bağlantı kesildi.",
    "footer.lastUpdate": "Son güncelleme:",
    "footer.nextRefresh": "Sonraki yenileme:",
    "footer.paused": "duraklatıldı",
    "footer.pause": "duraklat",
    "footer.resume": "devam et",
    "footer.shortcuts": "Kısayollar:",
    "footer.shortcut.search": "ara",
    "footer.shortcut.refresh": "yenile",
  },
  en: {
    "doc.title": "Nexus Cache Orchestrator — Dashboard",
    "app.subtitle": "cache orchestrator / dashboard",
    "login.username": "Username",
    "login.password": "Password",
    "login.show": "SHOW",
    "login.hide": "HIDE",
    "login.remember": "Remember my username",
    "login.submit": "Sign in",
    "login.submitting": "Signing in…",
    "login.error.generic": "Sign-in failed.",
    "login.error.network": "Could not reach the server.",
    "topbar.panel": "/ dashboard",
    "topbar.connected": "Connected",
    "topbar.disconnected": "Disconnected",
    "topbar.session": "Session",
    "topbar.logout": "Log out",
    "stats.cached": "Cached entries",
    "stats.addons": "Loaded add-ons",
    "stats.addons.sub": "active module count",
    "stats.dirty": "Pending writes (dirty)",
    "stats.mongo": "MongoDB",
    "stats.mongo.ok": "Connected",
    "stats.mongo.fail": "Disconnected",
    "stats.mongo.sub.ok": "connection healthy",
    "stats.mongo.sub.fail": "connection unavailable",
    "delta.none": "no change",
    "delta.suffix": "/ last refresh",
    "delta.unit.records": "records",
    "delta.unit.keys": "keys",
    "addons.title": "Add-ons",
    "addons.search.placeholder": "search… ( / )",
    "addons.refresh": "Refresh",
    "addons.th.id": "ID",
    "addons.th.name": "Name",
    "addons.th.class": "Class",
    "addons.th.db": "Database",
    "addons.th.collection": "Collection",
    "addons.th.ttl": "Cache TTL",
    "addons.empty": "No add-ons loaded.",
    "addons.noMatch": 'No add-ons match "{term}".',
    "addons.loading": "Loading…",
    "detail.class": "Full class path",
    "detail.storage": "Storage",
    "detail.ttl": "Cache TTL",
    "detail.id": "Add-on ID",
    "unit.sec": "sec",
    "unit.min": "min",
    "unit.hr": "hr",
    "log.title": "Event log",
    "log.sessionStart": "Session started, panel loaded.",
    "log.fetchFail": "Could not fetch data, server unreachable.",
    "log.dirtyThreshold": "Pending write count exceeded threshold: {n} keys.",
    "log.mongoUp": "MongoDB connection restored.",
    "log.mongoDown": "MongoDB connection lost.",
    "toast.disconnected": "Lost connection to the server.",
    "footer.lastUpdate": "Last update:",
    "footer.nextRefresh": "Next refresh:",
    "footer.paused": "paused",
    "footer.pause": "pause",
    "footer.resume": "resume",
    "footer.shortcuts": "Shortcuts:",
    "footer.shortcut.search": "search",
    "footer.shortcut.refresh": "refresh",
  },
};

let currentLang = localStorage.getItem("nexus_lang") || (navigator.language || "tr").slice(0, 2);
if (!translations[currentLang]) currentLang = "tr";

function t(key, params) {
  let str = (translations[currentLang] && translations[currentLang][key])
      || translations.tr[key] || key;
  if (params) {
    Object.keys(params).forEach((k) => {
      str = str.replace(`{${k}}`, params[k]);
    });
  }
  return str;
}

function setLanguage(lang) {
  if (!translations[lang] || lang === currentLang) return;
  currentLang = lang;
  localStorage.setItem("nexus_lang", lang);
  applyStaticTranslations();
  refreshDynamicTranslations();
}

function applyStaticTranslations() {
  document.documentElement.lang = currentLang;
  document.title = t("doc.title");

  document.querySelectorAll("[data-i18n]").forEach((el) => {
    el.textContent = t(el.dataset.i18n);
  });
  document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => {
    el.placeholder = t(el.dataset.i18nPlaceholder);
  });
  document.querySelectorAll(".lang-btn").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.lang === currentLang);
  });
}

// Re-render everything that mixes translated text with live data,
// without needing a fresh fetch from the server.
function refreshDynamicTranslations() {
  togglePasswordBtn.textContent = passwordInput.type === "password" ? t("login.show") : t("login.hide");

  connText.textContent = connState === "ok" ? t("topbar.connected") : t("topbar.disconnected");

  if (lastStats) {
    renderStatsText();
  }
  renderAddons();
  renderEventLog();
  updateCountdownLabel();
  autorefreshToggle.textContent = autoRefreshPaused ? t("footer.resume") : t("footer.pause");
}

// ── DOM referansları ──
const loginScreen = document.getElementById("login-screen");
const dashboardScreen = document.getElementById("dashboard-screen");
const loginForm = document.getElementById("login-form");
const loginError = document.getElementById("login-error");
const loginSubmit = document.getElementById("login-submit");
const logoutBtn = document.getElementById("logout-btn");
const togglePasswordBtn = document.getElementById("toggle-password");
const passwordInput = document.getElementById("password");
const usernameInput = document.getElementById("username");
const rememberCheckbox = document.getElementById("remember-username");
const refreshBtn = document.getElementById("refresh-btn");
const autorefreshToggle = document.getElementById("autorefresh-toggle");
const addonSearchInput = document.getElementById("addon-search");
const connLed = document.getElementById("conn-led");
const connText = document.getElementById("conn-text");

document.querySelectorAll(".lang-btn").forEach((btn) => {
  btn.addEventListener("click", () => setLanguage(btn.dataset.lang));
});

// ── Başlangıç ──
window.addEventListener("DOMContentLoaded", () => {
  applyStaticTranslations();
  restoreRememberedUsername();
  checkSession();
});

async function checkSession() {
  try {
    const res = await fetch(`${API}/api/stats`, { credentials: "same-origin" });
    if (res.ok) {
      showDashboard();
    } else {
      showLogin();
    }
  } catch (err) {
    showLogin();
  }
}

// ── Hatırlanan kullanıcı adı ──
function restoreRememberedUsername() {
  const saved = localStorage.getItem("nexus_remembered_username");
  if (saved) {
    usernameInput.value = saved;
    rememberCheckbox.checked = true;
    passwordInput.focus();
  }
}

togglePasswordBtn.addEventListener("click", () => {
  const isHidden = passwordInput.type === "password";
  passwordInput.type = isHidden ? "text" : "password";
  togglePasswordBtn.textContent = isHidden ? t("login.hide") : t("login.show");
});

// ── Login ──
loginForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  loginError.textContent = "";

  const username = usernameInput.value.trim();
  const password = passwordInput.value;

  loginSubmit.disabled = true;
  loginSubmit.textContent = t("login.submitting");

  try {
    const res = await fetch(`${API}/api/login`, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });

    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      loginError.textContent = body.error || t("login.error.generic");
      return;
    }

    if (rememberCheckbox.checked) {
      localStorage.setItem("nexus_remembered_username", username);
    } else {
      localStorage.removeItem("nexus_remembered_username");
    }

    showDashboard();
  } catch (err) {
    loginError.textContent = t("login.error.network");
  } finally {
    loginSubmit.disabled = false;
    loginSubmit.textContent = t("login.submit");
  }
});

// ── Logout ──
logoutBtn.addEventListener("click", async () => {
  try {
    await fetch(`${API}/api/logout`, {
      method: "POST",
      credentials: "same-origin",
    });
  } catch (_) { /* sunucuya ulaşamasak bile ekranı değiştir */ }

  stopPolling();
  showLogin();
});

// ── Ekran geçişleri ──
function showLogin() {
  loginScreen.classList.remove("hidden");
  dashboardScreen.classList.add("hidden");
  passwordInput.value = "";
  stopPolling();
  stopClock();
}

function showDashboard() {
  loginScreen.classList.add("hidden");
  dashboardScreen.classList.remove("hidden");
  sessionStartedAt = Date.now();
  cachedHistory = [];
  dirtyHistory = [];
  eventLogEntries = [];
  logEvent("ok", "log.sessionStart");
  fetchAll();
  startPolling();
  startClock();
}

function startPolling() {
  stopPolling();
  autoRefreshPaused = false;
  autorefreshToggle.textContent = t("footer.pause");
  secondsUntilRefresh = POLL_SECONDS;
  updateCountdownLabel();
  countdownTimer = setInterval(tickCountdown, 1000);
}

function stopPolling() {
  if (countdownTimer) clearInterval(countdownTimer);
  countdownTimer = null;
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = null;
}

function tickCountdown() {
  if (autoRefreshPaused) return;
  secondsUntilRefresh -= 1;
  if (secondsUntilRefresh <= 0) {
    fetchAll();
    secondsUntilRefresh = POLL_SECONDS;
  }
  updateCountdownLabel();
}

function updateCountdownLabel() {
  const el = document.getElementById("next-refresh");
  el.textContent = autoRefreshPaused ? t("footer.paused") : `${secondsUntilRefresh}s`;
}

autorefreshToggle.addEventListener("click", () => {
  autoRefreshPaused = !autoRefreshPaused;
  autorefreshToggle.textContent = autoRefreshPaused ? t("footer.resume") : t("footer.pause");
  if (!autoRefreshPaused) secondsUntilRefresh = POLL_SECONDS;
  updateCountdownLabel();
});

refreshBtn.addEventListener("click", () => {
  fetchAll();
  secondsUntilRefresh = POLL_SECONDS;
  updateCountdownLabel();
});

// ── Saat / oturum süresi ──
function startClock() {
  stopClock();
  tickClock();
  clockTimer = setInterval(tickClock, 1000);
}
function stopClock() {
  if (clockTimer) clearInterval(clockTimer);
  clockTimer = null;
}
function tickClock() {
  const now = new Date();
  document.getElementById("wall-clock").textContent = now.toLocaleTimeString(currentLang === "tr" ? "tr-TR" : "en-US");
  if (sessionStartedAt) {
    const elapsed = Math.floor((Date.now() - sessionStartedAt) / 1000);
    const m = String(Math.floor(elapsed / 60)).padStart(2, "0");
    const s = String(elapsed % 60).padStart(2, "0");
    document.getElementById("session-duration").textContent = `${m}:${s}`;
  }
}

// ── Veri çekme ──
async function authorizedFetch(path) {
  const res = await fetch(`${API}${path}`, { credentials: "same-origin" });

  if (res.status === 401) {
    showLogin();
    throw new Error("Unauthorized");
  }

  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

async function fetchAll() {
  setConnState("pending");
  try {
    const [stats, addons] = await Promise.all([
      authorizedFetch("/api/stats"),
      authorizedFetch("/api/addons"),
    ]);
    setConnState("ok");
    applyStats(stats);
    addonsData = addons;
    renderAddons();
  } catch (err) {
    if (err.message !== "Unauthorized") {
      setConnState("fail");
      logEvent("fail", "log.fetchFail");
      console.error("Veri çekilemedi:", err);
    }
  }
}

function setConnState(state) {
  if (state === "pending") {
    refreshBtn.classList.add("spinning");
    return;
  }
  refreshBtn.classList.remove("spinning");
  connState = state;
  if (state === "ok") {
    connLed.classList.remove("fail", "pulse");
    connText.textContent = t("topbar.connected");
  } else {
    connLed.classList.add("fail", "pulse");
    connText.textContent = t("topbar.disconnected");
    showToast("fail", t("toast.disconnected"));
  }
}

// ── İstatistikler + sparkline'lar ──
function applyStats(stats) {
  lastDeltaCached = lastStats ? stats.cachedEntries - lastStats.cachedEntries : 0;
  lastDeltaDirty = lastStats ? stats.dirtyKeys - lastStats.dirtyKeys : 0;

  cachedHistory.push(stats.cachedEntries);
  dirtyHistory.push(stats.dirtyKeys);
  if (cachedHistory.length > HISTORY_LENGTH) cachedHistory.shift();
  if (dirtyHistory.length > HISTORY_LENGTH) dirtyHistory.shift();
  drawSparkline("spark-cached", cachedHistory, "var(--amber)");
  drawSparkline("spark-dirty", dirtyHistory, "var(--red)");

  const dirtyCard = document.getElementById("stat-dirty-card");
  const wasWarn = dirtyCard.classList.contains("warn");
  const isWarn = stats.dirtyKeys > DIRTY_WARN_THRESHOLD;
  dirtyCard.classList.toggle("warn", isWarn);
  if (isWarn && !wasWarn) {
    logEvent("warn", "log.dirtyThreshold", { n: stats.dirtyKeys });
  }

  if (lastStats && lastStats.mongoConnected !== stats.mongoConnected) {
    logEvent(stats.mongoConnected ? "ok" : "fail", stats.mongoConnected ? "log.mongoUp" : "log.mongoDown");
  }

  lastStats = stats;
  renderStatsText();
}

// Renders everything derived from lastStats + saved deltas, in the current language.
function renderStatsText() {
  const stats = lastStats;
  document.getElementById("stat-cached").textContent = stats.cachedEntries;
  document.getElementById("stat-addons").textContent = stats.loadedAddons;
  document.getElementById("stat-dirty").textContent = stats.dirtyKeys;

  const mongoEl = document.getElementById("stat-mongo");
  mongoEl.textContent = stats.mongoConnected ? t("stats.mongo.ok") : t("stats.mongo.fail");
  mongoEl.className = "stat-value " + (stats.mongoConnected ? "ok" : "fail");
  document.getElementById("stat-mongo-sub").textContent = stats.mongoConnected
      ? t("stats.mongo.sub.ok")
      : t("stats.mongo.sub.fail");

  document.getElementById("last-updated").textContent =
      new Date(stats.timestamp).toLocaleTimeString(currentLang === "tr" ? "tr-TR" : "en-US");

  renderDelta("stat-cached-delta", lastDeltaCached, "delta.unit.records");
  renderDelta("stat-dirty-delta", lastDeltaDirty, "delta.unit.keys");
}

function renderDelta(elId, delta, unitKey) {
  const el = document.getElementById(elId);
  if (!delta) {
    el.textContent = t("delta.none");
    el.className = "stat-delta dim";
    return;
  }
  const sign = delta > 0 ? "+" : "";
  el.textContent = `${sign}${delta} ${t(unitKey)} ${t("delta.suffix")}`;
  el.className = "stat-delta " + (delta > 0 ? "up" : "down");
}

function drawSparkline(svgId, values, color) {
  const svg = document.getElementById(svgId);
  if (values.length < 2) { svg.innerHTML = ""; return; }

  const w = 160, h = 28, pad = 2;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;

  const points = values.map((v, i) => {
    const x = pad + (i / (values.length - 1)) * (w - pad * 2);
    const y = h - pad - ((v - min) / range) * (h - pad * 2);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });

  const lastX = points[points.length - 1].split(",")[0];
  const lastY = points[points.length - 1].split(",")[1];

  svg.innerHTML = `
    <polyline points="${points.join(" ")}" fill="none" stroke="${color}" stroke-width="1.5" />
    <circle cx="${lastX}" cy="${lastY}" r="2" fill="${color}" />
  `;
}

// ── Eklenti tablosu: arama, sıralama, satır detayı ──
addonSearchInput.addEventListener("input", (e) => {
  searchTerm = e.target.value.trim().toLocaleLowerCase(currentLang === "tr" ? "tr-TR" : "en-US");
  renderAddons();
});

document.querySelectorAll("#addons-table th[data-key]").forEach((th) => {
  th.addEventListener("click", () => {
    const key = th.dataset.key;
    if (sortKey === key) {
      sortDir = sortDir === "asc" ? "desc" : "asc";
    } else {
      sortKey = key;
      sortDir = "asc";
    }
    renderAddons();
  });
});

function getFilteredSortedAddons() {
  let list = addonsData;
  if (searchTerm) {
    list = list.filter((a) =>
        [a.id, a.name, a.className, a.database, a.collection]
            .some((f) => String(f).toLocaleLowerCase(currentLang === "tr" ? "tr-TR" : "en-US").includes(searchTerm))
    );
  }
  return [...list].sort((a, b) => {
    const av = a[sortKey], bv = b[sortKey];
    let cmp;
    if (typeof av === "number" && typeof bv === "number") {
      cmp = av - bv;
    } else {
      cmp = String(av).localeCompare(String(bv), currentLang === "tr" ? "tr-TR" : "en-US");
    }
    return sortDir === "asc" ? cmp : -cmp;
  });
}

function renderAddons() {
  const body = document.getElementById("addons-body");
  const list = getFilteredSortedAddons();

  document.getElementById("addons-count").textContent =
      addonsData.length ? `${list.length} / ${addonsData.length}` : "";

  document.querySelectorAll("#addons-table th[data-key]").forEach((th) => {
    th.querySelector(".arrow")?.remove();
    if (th.dataset.key === sortKey) {
      const arrow = document.createElement("span");
      arrow.className = "arrow";
      arrow.textContent = sortDir === "asc" ? "▲" : "▼";
      th.appendChild(arrow);
    }
  });

  if (!addonsData.length) {
    body.innerHTML = `<tr><td colspan="6" class="dim empty-cell">${escapeHtml(t("addons.empty"))}</td></tr>`;
    return;
  }
  if (!list.length) {
    body.innerHTML = `<tr><td colspan="6" class="dim empty-cell">${escapeHtml(t("addons.noMatch", { term: addonSearchInput.value }))}</td></tr>`;
    return;
  }

  body.innerHTML = list.map((a) => {
    const rows = [`
    <tr class="addon-row" data-id="${escapeHtml(a.id)}">
      <td>${escapeHtml(a.id)}</td>
      <td class="name-cell">${escapeHtml(a.name)}</td>
      <td class="dim">${escapeHtml(a.className)}</td>
      <td>${escapeHtml(a.database)}</td>
      <td>${escapeHtml(a.collection)}</td>
      <td>${formatTTL(a.cacheTTL)}</td>
    </tr>`];

    if (expandedAddonId === a.id) {
      rows.push(`
      <tr class="detail-row">
        <td colspan="6">
          <div class="detail-grid">
            <div class="detail-item"><div class="k">${escapeHtml(t("detail.class"))}</div><div class="v">${escapeHtml(a.className)}</div></div>
            <div class="detail-item"><div class="k">${escapeHtml(t("detail.storage"))}</div><div class="v">${escapeHtml(a.database)}.${escapeHtml(a.collection)}</div></div>
            <div class="detail-item"><div class="k">${escapeHtml(t("detail.ttl"))}</div><div class="v">${formatTTL(a.cacheTTL)} (${escapeHtml(a.cacheTTL)} ${escapeHtml(t("unit.sec"))})</div></div>
            <div class="detail-item"><div class="k">${escapeHtml(t("detail.id"))}</div><div class="v">${escapeHtml(a.id)}</div></div>
          </div>
        </td>
      </tr>`);
    }
    return rows.join("");
  }).join("");

  body.querySelectorAll("tr.addon-row").forEach((row) => {
    row.addEventListener("click", () => {
      const id = row.dataset.id;
      expandedAddonId = expandedAddonId === id ? null : id;
      renderAddons();
    });
  });
}

function formatTTL(seconds) {
  const n = Number(seconds);
  if (!Number.isFinite(n)) return escapeHtml(seconds);
  if (n < 60) return `${n} ${escapeHtml(t("unit.sec"))}`;
  if (n < 3600) return `${Math.round(n / 60)} ${escapeHtml(t("unit.min"))}`;
  return `${(n / 3600).toFixed(1)} ${escapeHtml(t("unit.hr"))}`;
}

// ── Olay günlüğü ──
function logEvent(level, key, params) {
  eventLogEntries.unshift({ level, key, params, ts: Date.now() });
  if (eventLogEntries.length > 40) eventLogEntries.pop();
  renderEventLog();
}

function renderEventLog() {
  const log = document.getElementById("event-log");
  log.innerHTML = "";
  eventLogEntries.forEach((entry) => {
    const row = document.createElement("div");
    row.className = `log-row ${entry.level}`;
    const ts = new Date(entry.ts).toLocaleTimeString(currentLang === "tr" ? "tr-TR" : "en-US");
    row.innerHTML = `<span class="ts"></span><span class="msg"></span>`;
    row.querySelector(".ts").textContent = ts;
    row.querySelector(".msg").textContent = t(entry.key, entry.params); // XSS'ten korunmak için textContent
    log.appendChild(row);
  });
}

// ── Toast bildirimleri ──
let lastToastAt = 0;
function showToast(level, message) {
  const now = Date.now();
  if (now - lastToastAt < 4000) return; // aynı anda toast yığılmasın
  lastToastAt = now;
  const stack = document.getElementById("toast-stack");
  const toast = document.createElement("div");
  toast.className = `toast ${level}`;
  toast.textContent = message;
  stack.appendChild(toast);
  setTimeout(() => toast.remove(), 4000);
}

// ── Klavye kısayolları ──
document.addEventListener("keydown", (e) => {
  if (dashboardScreen.classList.contains("hidden")) return;
  const tag = document.activeElement.tagName;
  const isTyping = tag === "INPUT" || tag === "TEXTAREA";

  if (e.key === "/" && !isTyping) {
    e.preventDefault();
    addonSearchInput.focus();
  } else if (e.key === "r" && !isTyping) {
    e.preventDefault();
    fetchAll();
    secondsUntilRefresh = POLL_SECONDS;
    updateCountdownLabel();
  } else if (e.key === "Escape" && document.activeElement === addonSearchInput) {
    addonSearchInput.value = "";
    searchTerm = "";
    renderAddons();
    addonSearchInput.blur();
  }
});

// Basit XSS koruması — innerHTML'e koymadan önce her değeri escape ediyoruz.
function escapeHtml(value) {
  const div = document.createElement("div");
  div.textContent = String(value);
  return div.innerHTML;
}