/* SAS admin shell — Alpine.store('sas') + CSRF header + HTMX auth forwarding.
 *
 * The server no longer accepts ?key= (it leaks a full-ADMIN credential into
 * access logs). Authenticate with the X-Sas-Admin-Key header, or log in at
 * /admin/login for a session cookie. A ?key= still present in the URL is
 * forwarded as the header for backwards compatibility.
 */
(function () {
  const THEME_KEY = 'sas-theme';
  const CSRF_COOKIE = 'sas_admin_csrf';

  function adminKeyFromUrl() {
    try {
      const v = new URLSearchParams(location.search).get('key');
      return (v && v.trim()) ? v.trim() : '';
    } catch (e) {
      return '';
    }
  }

  function csrfToken() {
    try {
      const hit = document.cookie.split(';')
        .map((c) => c.trim())
        .find((c) => c.startsWith(CSRF_COOKIE + '='));
      return hit ? decodeURIComponent(hit.substring(CSRF_COOKIE.length + 1)) : '';
    } catch (e) {
      return '';
    }
  }

  function authHeaders(extra) {
    const headers = extra ? Object.assign({}, extra) : {};
    const key = adminKeyFromUrl();
    if (key) {
      headers['X-Sas-Admin-Key'] = key;
    }
    const csrf = csrfToken();
    if (csrf) {
      headers['X-Sas-CSRF'] = csrf;
    }
    return headers;
  }

  function withKeyQuery(path) {
    return path;
  }

  document.addEventListener('htmx:configRequest', (ev) => {
    if (!ev.detail || !ev.detail.headers) return;
    const key = adminKeyFromUrl();
    if (key) {
      ev.detail.headers['X-Sas-Admin-Key'] = key;
    }
    const csrf = csrfToken();
    if (csrf) {
      ev.detail.headers['X-Sas-CSRF'] = csrf;
    }
  });

  document.addEventListener('sasToast', (ev) => {
    const d = ev && ev.detail;
    if (!d || !d.message) return;
    toast(String(d.message), d.kind || 'ok');
  });

  function applyTheme(theme) {
    const t = theme === 'light' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', t);
    try { localStorage.setItem(THEME_KEY, t); } catch (e) { /* ignore */ }
  }

  function toggleTheme() {
    const cur = document.documentElement.getAttribute('data-theme');
    applyTheme(cur === 'light' ? 'dark' : 'light');
  }

  const TOAST_BASE =
    'rounded-md border px-3 py-2 text-sm leading-snug shadow-lg font-[family-name:var(--font-body)]';
  const TOAST_KIND = {
    ok: 'border-amber-500/50 bg-slate-900 text-slate-100',
    error: 'border-rose-500/60 bg-rose-950 text-rose-100',
    info: 'border-sky-500/40 bg-slate-900 text-slate-100'
  };

  function ensureHost() {
    let host = document.getElementById('sas-toast-host');
    if (!host) {
      host = document.createElement('div');
      host.id = 'sas-toast-host';
      host.setAttribute('aria-live', 'polite');
      document.body.appendChild(host);
    }
    return host;
  }

  function toast(message, kind) {
    const host = ensureHost();
    const el = document.createElement('div');
    el.className = 'sas-toast ' + TOAST_BASE + ' ' + (TOAST_KIND[kind] || TOAST_KIND.info);
    el.textContent = message;
    host.appendChild(el);
    requestAnimationFrame(() => el.classList.add('show'));
    setTimeout(() => {
      el.classList.remove('show');
      setTimeout(() => el.remove(), 280);
    }, kind === 'error' ? 6500 : 3200);
  }

  function toastErrors(errors, title) {
    const list = Array.isArray(errors) ? errors : [String(errors || 'unknown error')];
    const head = title || 'Config invalid — not applied';
    toast(head + (list.length ? ': ' + list[0] : ''), 'error');
    if (list.length > 1) {
      toast('(+ ' + (list.length - 1) + ' more) ' + list.slice(1, 3).join(' · '), 'error');
    }
  }

  async function postJson(path, bodyText) {
    const url = withKeyQuery(path);
    const r = await fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: bodyText
    });
    let data = null;
    const text = await r.text();
    try { data = JSON.parse(text); } catch (_) { data = { ok: r.ok, message: text, errors: [text] }; }
    return { status: r.status, ok: r.ok && data && data.ok !== false, data: data, raw: text };
  }

  function refreshLinks(targetSelector, path) {
    const url = withKeyQuery(path);
    const headers = authHeaders();
    if (window.htmx) {
      htmx.ajax('GET', url, {
        target: targetSelector,
        swap: 'innerHTML',
        headers: headers
      });
      return;
    }
    fetch(url, {
      credentials: 'same-origin',
      headers: authHeaders({ 'HX-Request': 'true' })
    }).then(r => r.text()).then(html => {
      const el = document.querySelector(targetSelector);
      if (el) el.innerHTML = html;
    }).catch(() => {});
  }

  function copyText(text, okMessage) {
    if (!text) return;
    const done = () => toast(okMessage || 'Copied', 'ok');
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(() => {
        fallbackCopy(text);
        done();
      });
      return;
    }
    fallbackCopy(text);
    done();
  }

  function fallbackCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'absolute';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (e) { /* ignore */ }
    ta.remove();
  }

  document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.cdr-status').forEach((el) => {
      const t = (el.textContent || '').trim().toUpperCase();
      if (t.indexOf('TIMED_OUT') >= 0 || t.indexOf('TIMEOUT') >= 0 || t.indexOf('TIME_OUT') >= 0) {
        el.classList.remove('cdr-status--ok', 'cdr-status--failed');
        el.classList.add('cdr-status--timeout');
      }
    });
  });

  const api = {
    toast, toastErrors, postJson, refreshLinks, copyText,
    applyTheme, toggleTheme, adminKeyFromUrl
  };

  document.addEventListener('alpine:init', () => {
    Alpine.store('sas', api);
  });

  // Callable from server-rendered onclick before Alpine scopes the node.
  window.SasAdmin = api;
})();
