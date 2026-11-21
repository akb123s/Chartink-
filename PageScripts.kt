package chartink.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Small, self-contained snippets run with evaluateJavascript(). None of them touch cookies, storage,
 * forms, network APIs or authentication; they only (a) click Chartink's OWN visible widget refresh
 * buttons so the site refreshes its data itself, and (b) read visible text to find a timestamp.
 * No JavaScript interface is exposed to the page.
 */
object PageScripts {

    /** Same source as tools/test/refresh_js_test.js, which exercises it against sample DOMs. */
    private const val REFRESH_TEMPLATE = """
(function (FORCE) {
  try {
    var S = window.__ctkRefresh = { done: false, clicked: 0, attempted: 0, started: Date.now(), lastActivity: Date.now(), clickedAt: 0, net: 0, mut: 0 };
    function finish() { S.done = true; try { if (S.mo) S.mo.disconnect(); } catch (e) {} try { if (S.po) S.po.disconnect(); } catch (e) {} }

    var ae = document.activeElement;
    if (!FORCE && ae && ae !== document.body && ae !== document.documentElement) {
      var tg = (ae.tagName || '').toLowerCase();
      if (tg === 'input' || tg === 'textarea' || tg === 'select' || ae.isContentEditable) {
        finish(); return JSON.stringify({ status: 'BUSY', why: 'input-focus' });
      }
    }
    if (document.querySelector('.modal.show, .modal.in, [aria-modal="true"], .swal2-shown, .swal-overlay--show-modal')) {
      finish(); return JSON.stringify({ status: 'BUSY', why: 'dialog-open' });
    }

    var INC = /refresh|reload|fa-sync|fa-redo|fa-rotate-(left|right)|arrow-clockwise|arrow-repeat|arrow-counterclockwise|\bsync\b|autorenew/i;
    var EXC = /auto[-_ ]?refresh|refresh[-_ ]?(interval|rate|every|setting|option|toggle|switch)|refreshing|\bspin|loading|disabled|toggle|switch|dropdown|setting|checkbox|interval/i;
    var BAD_ANCESTOR = 'nav, footer, .navbar, [role="navigation"], .modal, .dropdown-menu, [role="menu"]';

    function cls(el) { var c = el.className; if (c && typeof c !== 'string') c = c.baseVal || ''; return c || ''; }
    function sig(el) {
      var s = [el.getAttribute('title'), el.getAttribute('aria-label'), el.getAttribute('data-original-title'),
               el.getAttribute('data-bs-original-title'), el.getAttribute('data-tooltip'), el.getAttribute('data-title'),
               el.id, el.getAttribute('name'), cls(el)].join(' ');
      var t = (el.tagName || '').toLowerCase();
      if (t === 'button' || t === 'a') { var tx = (el.textContent || '').trim(); if (tx.length <= 20) s += ' ' + tx; }
      return s;
    }
    function ok(el) {
      if (!el || el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
      var t = (el.tagName || '').toLowerCase();
      if (t === 'input' || t === 'select' || t === 'textarea') return false;
      if (t === 'button' && (el.getAttribute('type') || '').toLowerCase() === 'submit') return false;
      if (t === 'a') { var h = el.getAttribute('href'); if (h && h !== '#' && h.indexOf('javascript:') !== 0) return false; }
      if (el.closest && el.closest(BAD_ANCESTOR)) return false;
      if (EXC.test(sig(el))) return false;
      var r = el.getClientRects(); if (!r || r.length === 0) return false;
      var cs = window.getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || cs.pointerEvents === 'none') return false;
      return true;
    }

    var nodes = document.querySelectorAll('button, a, [role="button"], [onclick], i, span, svg, img, [title], [aria-label], [data-original-title]');
    var targets = [];
    for (var i = 0; i < nodes.length && i < 20000; i++) {
      var el = nodes[i], s = sig(el);
      if (!INC.test(s) || EXC.test(s)) continue;
      var tgt = el, t = (el.tagName || '').toLowerCase();
      if (t === 'i' || t === 'span' || t === 'svg' || t === 'img' || t === 'path') {
        var c = el.closest && el.closest('button, a, [role="button"], [onclick]'); if (c) tgt = c;
      }
      if (targets.indexOf(tgt) >= 0 || !ok(tgt)) continue;
      targets.push(tgt);
    }
    if (targets.length === 0) { finish(); return JSON.stringify({ status: 'NONE', why: 'no-refresh-controls' }); }
    if (targets.length > 40) { finish(); return JSON.stringify({ status: 'NONE', why: 'too-many-candidates' }); }

    try { S.mo = new MutationObserver(function () { S.lastActivity = Date.now(); S.mut++; });
          S.mo.observe(document.body, { childList: true, subtree: true, characterData: true }); } catch (e) {}
    try { S.po = new PerformanceObserver(function (l) {
            var es = l.getEntries();
            for (var k = 0; k < es.length; k++) { var it = es[k].initiatorType; if (it === 'xmlhttprequest' || it === 'fetch') { S.lastActivity = Date.now(); S.net++; } }
          }); S.po.observe({ entryTypes: ['resource'] }); } catch (e) {}

    targets.forEach(function (el, idx) {
      setTimeout(function () {
        S.attempted++;
        try { el.click(); S.clicked++; S.clickedAt = Date.now(); S.lastActivity = Date.now(); } catch (e) {}
      }, idx * 120);
    });
    var iv = setInterval(function () {
      var now = Date.now();
      var allAttempted = S.attempted >= targets.length;
      if (now - S.started > 8000 || (allAttempted && now - S.lastActivity > 1000)) { clearInterval(iv); finish(); }
    }, 250);
    return JSON.stringify({ status: 'CLICKED', n: targets.length });
  } catch (e) {
    try { window.__ctkRefresh.done = true; } catch (e2) {}
    return JSON.stringify({ status: 'ERROR', why: String(e) });
  }
})(__FORCE__)
"""

    private const val POLL = """
(function () { var s = window.__ctkRefresh; return JSON.stringify({ done: !s || s.done === true, clicked: s ? s.clicked : 0, net: s ? s.net : 0, mut: s ? s.mut : 0 }); })()
"""

    private const val TIMESTAMPS = """
(function () {
  try {
    var out = [], n = 0;
    var times = document.querySelectorAll('time[datetime]');
    for (var i = 0; i < times.length && n < 20; i++) { out.push(times[i].getAttribute('datetime')); n++; }
    var txt = (document.body && (document.body.innerText || document.body.textContent)) || '';
    if (txt.length > 400000) txt = txt.slice(0, 400000);
    var lines = txt.split(/\n+/);
    var re = /(last\s*(?:updated|refreshed|update)|updated|as\s*(?:of|on)|refreshed|data\s*(?:as|time)|timestamp)/i;
    for (var j = 0; j < lines.length && n < 40; j++) {
      var L = lines[j];
      if (L.length <= 160 && re.test(L) && /\d{1,2}:\d{2}/.test(L)) { out.push(L.trim()); n++; }
    }
    return JSON.stringify(out);
  } catch (e) { return '[]'; }
})()
"""

    fun refreshStart(force: Boolean): String = REFRESH_TEMPLATE.replace("__FORCE__", if (force) "true" else "false")
    fun poll(): String = POLL
    fun timestamps(): String = TIMESTAMPS

    /** evaluateJavascript hands back a JSON-encoded string (or "null"); undo that one layer. */
    private fun unwrap(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try { JSONTokener(raw).nextValue() as? String } catch (_: Exception) { null }
    }

    fun parseObject(raw: String?): JSONObject? =
        try { unwrap(raw)?.let { JSONObject(it) } } catch (_: Exception) { null }

    fun parseStringList(raw: String?): List<String> = try {
        val arr = JSONArray(unwrap(raw) ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) { emptyList() }
}
